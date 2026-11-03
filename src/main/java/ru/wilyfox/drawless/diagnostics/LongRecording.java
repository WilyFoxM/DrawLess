package ru.wilyfox.drawless.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.chat.Component;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.performance.FrameWork;
import ru.wilyfox.drawless.util.AtomicFileWriter;

/**
 * A passive recording during normal play: how often slow frames happen and what was in them. Memory
 * stays bounded (a histogram, one row per minute, a capped slow-frame log), so it can run for a
 * whole session. It answers "are there hitches and where do they come from", not "how much faster":
 * it has no OFF controls and is not a performance comparison.
 */
public final class LongRecording {
  private static final long MINUTE = 60_000_000_000L;
  private static final long LIMIT = 60 * MINUTE;
  private static final int MAX_SLOW = 5000;
  private static final int REPORT_SLOWEST = 50;
  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
  private static Run active;
  private static volatile boolean saving;
  private static volatile Path lastSaved;

  private LongRecording() {}

  record SlowFrame(
      double seconds,
      double ms,
      int clientTicks,
      int gcPauses,
      double gcPauseMs,
      boolean concurrentGc,
      double allocKb,
      int avatars,
      int avatarDelta,
      double drawlessWorkMs,
      String drawlessWork,
      boolean worldChange) {}

  record Minute(
      int minute,
      long frames,
      double fps,
      double maxMs,
      long over33Ms,
      double avatarsMean,
      int loadedPlayers,
      String particles) {}

  record Collector(String name, boolean pause, int count, double totalMs, double maxMs) {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          Run run = active;
          if (run == null) return;
          run.pendingTicks++;
          if (client.level == null) finish(client, "disconnect", false);
          else if (System.nanoTime() - run.started >= LIMIT) finish(client, "time_limit", false);
        });
    // Leaving a world drops queued client tasks, so saving cannot wait in that queue.
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          if (client.isSameThread()) finish(client, "disconnect", false);
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> finish(client, "game_closing", true));
  }

  public static boolean busy() {
    return active != null || saving;
  }

  public static Component start(Minecraft client, String label) {
    if (busy()
        || DisplayProbe.busy()
        || BenchmarkSeries.running()
        || PlayerProfile.busy()
        || RenderMetrics.getInstance().isEnabled())
      return Component.translatable("drawless.player_profile.busy");
    if (client.level == null || client.player == null)
      return Component.translatable("drawless.benchmark.enter_world");
    if (!label.matches("[A-Za-z0-9_-]{1,24}") || label.equals("stop") || label.equals("status"))
      return Component.translatable("drawless.benchmark.invalid_label");
    active = new Run(client, label);
    return Component.translatable("drawless.record.started", label, LIMIT / MINUTE);
  }

  public static Component stop(Minecraft client) {
    if (active == null) return Component.translatable("drawless.record.not_running");
    finish(client, "stopped", false);
    return Component.translatable("drawless.record.stopping");
  }

  public static Component status() {
    Run run = active;
    if (run != null)
      return Component.translatable(
          "drawless.record.running",
          run.label,
          (System.nanoTime() - run.started) / MINUTE,
          run.total.atLeast(1000.0 / 30));
    return lastSaved != null
        ? Component.translatable("drawless.record.last", lastSaved.toString())
        : Component.translatable("drawless.record.idle");
  }

  public static void onFrame(Minecraft client, LevelRenderState state) {
    Run run = active;
    if (run != null) run.frame(client, state);
  }

  private static void finish(Minecraft client, String reason, boolean synchronous) {
    Run run = active;
    if (run == null) return;
    active = null;
    saving = true;
    GcEvents.unsubscribe(run.gc);
    run.closeMinute(client);
    String json = run.json(reason);
    String markdown = run.markdown(reason);
    Path directory =
        client
            .gameDirectory
            .toPath()
            .resolve("drawless-reports")
            .resolve("record-" + FILE_TIME.format(Instant.now()) + "-" + run.label);
    Runnable write =
        () -> {
          Component outcome;
          try {
            AtomicFileWriter.write(directory.resolve("record.json"), w -> w.write(json));
            AtomicFileWriter.write(directory.resolve("README.md"), w -> w.write(markdown));
            lastSaved = directory;
            outcome =
                Component.translatable(
                    "drawless.record.saved",
                    run.label,
                    reason,
                    String.format(Locale.ROOT, "%.1f", run.total.seconds() / 60),
                    run.total.atLeast(1000.0 / 30),
                    directory.toString());
          } catch (Exception error) {
            ru.wilyfox.drawless.DrawLess.LOGGER.warn("Could not save frame recording", error);
            outcome = Component.translatable("drawless.record.failed", String.valueOf(error));
          } finally {
            saving = false;
          }
          if (synchronous) return;
          Component message = outcome;
          client.execute(
              () -> {
                if (client.gui != null) client.gui.hud.getChat().addClientSystemMessage(message);
              });
        };
    if (synchronous) write.run();
    else CompletableFuture.runAsync(write);
  }

  private static final class Run {
    final String label, environment, hardware, settings;
    final long started = System.nanoTime();
    final long uptimeOffset =
        started - ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L;
    final String startedAt = Instant.now().toString();
    final Queue<GcEvents.Event> gc = GcEvents.subscribe();
    final FrameHistogram total = new FrameHistogram();
    final FrameHistogram tickFrames = new FrameHistogram();
    final FrameHistogram otherFrames = new FrameHistogram();
    final List<Minute> minutes = new ArrayList<>();
    final List<SlowFrame> slow = new ArrayList<>();
    final List<long[]> slowSpans = new ArrayList<>();
    net.minecraft.client.multiplayer.ClientLevel level;
    long lastFrameAt, lastAllocated, excludedFrames, allocatedBytes, allocatedFrames, slowDropped;
    long workFrames, workNanos, slowWithTick, slowWithWork, slowWithAvatarChange;
    int pendingTicks, lastAvatars = -1, worldChanges;
    boolean worldChanged;
    double logThresholdMs = 1000.0 / 60;
    long nextThresholdAt;
    long minuteStart, minuteFrames, minuteNanos, minuteMax, minuteOver33, minuteAvatars;

    Run(Minecraft client, String label) {
      this.label = label;
      level = client.level;
      minuteStart = started;
      lastAllocated = FrameRecorder.allocated();
      FrameWork.reset();
      hardware = Hardware.describe();
      settings = DrawLessConfig.snapshot();
      environment =
          "framebuffer="
              + client.getWindow().getWidth()
              + "x"
              + client.getWindow().getHeight()
              + "; fpsLimit="
              + client.options.framerateLimit().get()
              + "; vsync="
              + client.options.enableVsync().get()
              + "; fov="
              + client.options.fov().get()
              + "; renderDistance="
              + client.options.getEffectiveRenderDistance()
              + "; resourcePacks="
              + client.options.resourcePacks
              + ru.wilyfox.drawless.compat.IrisShaders.environment();
    }

    void frame(Minecraft client, LevelRenderState state) {
      long now = System.nanoTime();
      long bytes = FrameRecorder.allocated();
      long frameAllocated = lastAllocated >= 0 && bytes >= 0 ? bytes - lastAllocated : -1;
      lastAllocated = bytes;
      long work = FrameWork.pendingNanos();
      String workText = work > 0 ? FrameWork.drain() : "";
      int ticks = pendingTicks;
      pendingTicks = 0;
      if (client.level != level) {
        level = client.level;
        worldChanges++;
        worldChanged = true;
      }
      if (client.gui.screen() != null || client.isPaused() || !client.getWindow().isFocused()) {
        lastFrameAt = 0;
        excludedFrames++;
        return;
      }
      long interval = lastFrameAt != 0 ? now - lastFrameAt : 0;
      lastFrameAt = now;
      int avatars = 0;
      for (var entityState : state.entityRenderStates)
        if (entityState instanceof AvatarRenderState) avatars++;
      int delta = lastAvatars >= 0 ? avatars - lastAvatars : 0;
      lastAvatars = avatars;
      if (interval <= 0) return;
      total.add(interval);
      (ticks > 0 ? tickFrames : otherFrames).add(interval);
      if (frameAllocated >= 0) {
        allocatedBytes += frameAllocated;
        allocatedFrames++;
      }
      if (work > 0) {
        workFrames++;
        workNanos += work;
      }
      minuteFrames++;
      minuteNanos += interval;
      minuteMax = Math.max(minuteMax, interval);
      minuteAvatars += avatars;
      double ms = interval / 1e6;
      if (ms >= 1000.0 / 30) minuteOver33++;
      if (ms >= logThresholdMs)
        logSlow(now, interval, ticks, frameAllocated, avatars, delta, work, workText);
      worldChanged = false;
      if (now >= nextThresholdAt) {
        nextThresholdAt = now + 1_000_000_000L;
        logThresholdMs = Math.max(1000.0 / 60, 2.5 * total.quantileMs(0.5));
      }
      if (now - minuteStart >= MINUTE) closeMinute(client);
    }

    private void logSlow(
        long now,
        long interval,
        int ticks,
        long frameAllocated,
        int avatars,
        int delta,
        long work,
        String workText) {
      if (ticks > 0) slowWithTick++;
      if (work > 0) slowWithWork++;
      if (delta != 0) slowWithAvatarChange++;
      if (slow.size() >= MAX_SLOW) {
        slowDropped++;
        return;
      }
      slow.add(
          new SlowFrame(
              (now - started) / 1e9,
              interval / 1e6,
              ticks,
              0,
              0,
              false,
              frameAllocated >= 0 ? frameAllocated / 1024.0 : -1,
              avatars,
              delta,
              work / 1e6,
              workText,
              worldChanged));
      slowSpans.add(new long[] {now - interval, now});
    }

    void closeMinute(Minecraft client) {
      if (minuteFrames == 0) return;
      minutes.add(
          new Minute(
              minutes.size() + 1,
              minuteFrames,
              minuteNanos > 0 ? minuteFrames * 1e9 / minuteNanos : 0,
              minuteMax / 1e6,
              minuteOver33,
              (double) minuteAvatars / minuteFrames,
              client.level != null ? client.level.players().size() : 0,
              client.particleEngine.countParticles()));
      minuteStart = System.nanoTime();
      minuteFrames = minuteNanos = minuteMax = minuteOver33 = minuteAvatars = 0;
    }

    /**
     * GC pauses and cycles are matched to logged frames, one millisecond of JVM rounding around.
     */
    private List<SlowFrame> slowWithGc() {
      var events = List.copyOf(gc);
      var out = new ArrayList<SlowFrame>(slow.size());
      for (int i = 0; i < slow.size(); i++) {
        var f = slow.get(i);
        long from = slowSpans.get(i)[0], to = slowSpans.get(i)[1];
        int pauses = 0;
        double pauseMs = 0;
        boolean concurrent = false;
        for (var e : events) {
          long start = uptimeOffset + (e.startMs() - 1) * 1_000_000L;
          long end = uptimeOffset + (e.endMs() + 1) * 1_000_000L;
          if (start >= to || end <= from) continue;
          if (e.pause()) {
            pauses++;
            pauseMs += e.durationMs();
          } else concurrent = true;
        }
        out.add(
            new SlowFrame(
                f.seconds,
                f.ms,
                f.clientTicks,
                pauses,
                pauseMs,
                concurrent,
                f.allocKb,
                f.avatars,
                f.avatarDelta,
                f.drawlessWorkMs,
                f.drawlessWork,
                f.worldChange));
      }
      return out;
    }

    private List<Collector> collectors() {
      var totals = new LinkedHashMap<String, double[]>();
      var pauses = new LinkedHashMap<String, Boolean>();
      for (var e : gc) {
        var t = totals.computeIfAbsent(e.name(), k -> new double[3]);
        t[0]++;
        t[1] += e.durationMs();
        t[2] = Math.max(t[2], e.durationMs());
        pauses.put(e.name(), e.pause());
      }
      var out = new ArrayList<Collector>();
      totals.forEach(
          (name, t) -> out.add(new Collector(name, pauses.get(name), (int) t[0], t[1], t[2])));
      return out;
    }

    String json(String reason) {
      var gson = new GsonBuilder().setPrettyPrinting().create();
      var json = new JsonObject();
      json.addProperty("schema_version", 1);
      json.addProperty("label", label);
      json.addProperty("result", reason);
      json.addProperty("started", startedAt);
      json.addProperty("environment", environment);
      json.addProperty("hardware", hardware);
      json.addProperty("java", System.getProperty("java.runtime.version"));
      json.add("jvm_collectors", gson.toJsonTree(GcEvents.collectors()));
      json.add("settings", JsonParser.parseString(settings));
      var mods = new JsonObject();
      FabricLoader.getInstance().getAllMods().stream()
          .sorted(Comparator.comparing(m -> m.getMetadata().getId()))
          .forEach(
              m ->
                  mods.addProperty(
                      m.getMetadata().getId(), m.getMetadata().getVersion().getFriendlyString()));
      json.add("mods", mods);
      json.addProperty("measured_seconds", total.seconds());
      json.addProperty("mean_fps", total.meanFps());
      json.addProperty("excluded_frames", excludedFrames);
      json.addProperty("world_changes", worldChanges);
      json.add("tail", gson.toJsonTree(total.tail()));
      json.add("tick_frames", gson.toJsonTree(tickFrames.tail()));
      json.add("other_frames", gson.toJsonTree(otherFrames.tail()));
      json.addProperty("frames_at_least_16_7_ms", total.atLeast(1000.0 / 60));
      json.addProperty("frames_at_least_33_3_ms", total.atLeast(1000.0 / 30));
      json.addProperty("frames_at_least_50_ms", total.atLeast(50));
      json.addProperty("frames_at_least_100_ms", total.atLeast(100));
      json.addProperty(
          "render_alloc_kib_per_frame",
          allocatedFrames > 0 ? allocatedBytes / 1024.0 / allocatedFrames : -1);
      json.addProperty(
          "render_alloc_mib_per_second",
          total.seconds() > 0 ? allocatedBytes / 1048576.0 / total.seconds() : -1);
      json.addProperty("frames_with_drawless_work", workFrames);
      json.addProperty("drawless_work_ms", workNanos / 1e6);
      json.add("gc", gson.toJsonTree(collectors()));
      json.add("minutes", gson.toJsonTree(minutes));
      json.addProperty("slow_with_tick", slowWithTick);
      json.addProperty("slow_with_drawless_work", slowWithWork);
      json.addProperty("slow_with_avatar_change", slowWithAvatarChange);
      json.addProperty("slow_dropped", slowDropped);
      json.add("slow_frames", gson.toJsonTree(slowWithGc()));
      return gson.toJson(json) + "\n";
    }

    String markdown(String reason) {
      var t = total.tail();
      double minutesMeasured = total.seconds() / 60;
      var out = new StringBuilder("# DrawLess frame recording: ").append(label).append("\n\n");
      out.append("Result: **")
          .append(reason)
          .append("**. Started ")
          .append(startedAt)
          .append(".\n\n");
      out.append(
          "A passive recording during normal play: no OFF controls, so it says whether slow frames happen and what was in them, not how much faster anything is. Frame intervals are CPU-side wall time between level renders on the render thread. Frames in menus, chat, the pause screen or an unfocused window are excluded. Percentiles have 0.1 ms resolution. GC pauses and cycles are matched to logged frames with one millisecond of tolerance, the JVM's resolution. DrawLess first-time work is its own mesh building, skin admission and mesh compaction; chunk building, networking and other mods are not attributed.\n\n");
      out.append("Environment: `").append(environment).append("`\n\n");
      out.append("Hardware: ").append(hardware).append("; Java ");
      out.append(System.getProperty("java.runtime.version")).append("; collectors ");
      out.append(GcEvents.collectors()).append("\n\n");
      out.append(
          String.format(
              Locale.ROOT,
              "Measured %.1f minutes, %d frames, mean %.1f FPS; %d frames excluded; %d world changes.\n\n",
              minutesMeasured,
              total.frames(),
              total.meanFps(),
              excludedFrames,
              worldChanges));
      out.append(
          "| Median ms | p95 ms | p99 ms | p99.9 ms | Max ms | 1% low FPS |\n| ---: | ---: | ---: | ---: | ---: | ---: |\n");
      out.append(
          String.format(
              Locale.ROOT,
              "| %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |\n\n",
              t.medianMs(),
              t.p95Ms(),
              t.p99Ms(),
              t.p999Ms(),
              t.maxMs(),
              t.low1Fps()));
      out.append(
          String.format(
              Locale.ROOT,
              "Frames containing a client tick: %d, median %.1f ms, p99 %.1f ms. Other frames: %d, median %.1f ms, p99 %.1f ms.\n\n",
              tickFrames.frames(),
              tickFrames.tail().medianMs(),
              tickFrames.tail().p99Ms(),
              otherFrames.frames(),
              otherFrames.tail().medianMs(),
              otherFrames.tail().p99Ms()));
      out.append("| Frame at least | Frames | Per minute |\n| ---: | ---: | ---: |\n");
      for (double ms : new double[] {1000.0 / 60, 1000.0 / 30, 50, 100})
        out.append(
            String.format(
                Locale.ROOT,
                "| %.1f ms | %d | %.2f |\n",
                ms,
                total.atLeast(ms),
                minutesMeasured > 0 ? total.atLeast(ms) / minutesMeasured : 0));
      out.append(
          String.format(
              Locale.ROOT,
              "\nRender-thread allocation: %.1f KiB per frame, %.1f MiB/s. DrawLess first-time work ran in %d frames (%.2f ms in total).\n\n",
              allocatedFrames > 0 ? allocatedBytes / 1024.0 / allocatedFrames : 0,
              total.seconds() > 0 ? allocatedBytes / 1048576.0 / total.seconds() : 0,
              workFrames,
              workNanos / 1e6));
      var collectors = collectors();
      if (collectors.isEmpty()) out.append("No garbage collection was reported.\n\n");
      else {
        out.append(
            "| Collector | Kind | Collections | Total ms | Max ms |\n| --- | --- | ---: | ---: | ---: |\n");
        for (var c : collectors)
          out.append(
              String.format(
                  Locale.ROOT,
                  "| %s | %s | %d | %.0f | %.0f |\n",
                  c.name(),
                  c.pause() ? "pause" : "concurrent",
                  c.count(),
                  c.totalMs(),
                  c.maxMs()));
        out.append('\n');
      }
      var logged = slowWithGc();
      long withPause = logged.stream().filter(f -> f.gcPauses > 0).count();
      long withCycle = logged.stream().filter(f -> f.concurrentGc).count();
      out.append(
          String.format(
              Locale.ROOT,
              "## Slow frames\n\nA frame is logged when it takes at least 16.7 ms and at least 2.5× the median so far. Logged %d (%d more only counted). With a client tick %d, with DrawLess first-time work %d, with a changed avatar count %d; of the logged ones, near a GC pause %d, during a concurrent GC cycle %d.\n\n",
              logged.size(),
              slowDropped,
              slowWithTick,
              slowWithWork,
              slowWithAvatarChange,
              withPause,
              withCycle));
      out.append(
          "| s | Frame ms | Client ticks | GC pauses | Concurrent GC | Render alloc KiB | Avatars (Δ) | DrawLess first-time work |\n| ---: | ---: | ---: | ---: | --- | ---: | --- | --- |\n");
      logged.stream()
          .sorted(Comparator.comparingDouble((SlowFrame f) -> f.ms).reversed())
          .limit(REPORT_SLOWEST)
          .forEach(
              f ->
                  out.append(
                      String.format(
                          Locale.ROOT,
                          "| %.2f | %.2f | %d | %d | %s | %.0f | %d (%+d) | %s |\n",
                          f.seconds,
                          f.ms,
                          f.clientTicks,
                          f.gcPauses,
                          f.concurrentGc,
                          f.allocKb,
                          f.avatars,
                          f.avatarDelta,
                          f.drawlessWork.isEmpty() ? "–" : f.drawlessWork)));
      out.append(
          "\n## By minute\n\n| Minute | Frames | FPS | Max ms | Frames ≥33.3 ms | Avatars (mean) | Loaded players | Particles |\n| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
      for (var m : minutes)
        out.append(
            String.format(
                Locale.ROOT,
                "| %d | %d | %.1f | %.1f | %d | %.1f | %d | %s |\n",
                m.minute,
                m.frames,
                m.fps,
                m.maxMs,
                m.over33Ms,
                m.avatarsMean,
                m.loadedPlayers,
                m.particles));
      out.append("\nAll logged frames, settings and mods: [record.json](record.json).\n");
      return out.toString();
    }
  }
}
