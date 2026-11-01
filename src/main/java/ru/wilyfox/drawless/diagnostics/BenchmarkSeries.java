package ru.wilyfox.drawless.diagnostics;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.util.AtomicFileWriter;

/** Opt-in, client-thread orchestration. Temporary settings are never saved to the config file. */
public final class BenchmarkSeries {
  public enum Target {
    FULL,
    PARTICLES,
    PARTICLE_LIGHT,
    PARTICLE_COLLISION,
    TEXT,
    PLAYER_ARMOR,
    PLAYER_ITEMS,
    PLAYER_BODY,
    BATCHING;

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    void apply(boolean on) {
      var c = DrawLessConfig.get();
      c.enabled = this == FULL ? on : true;
      switch (this) {
        case FULL -> {}
        case PARTICLES -> {
          c.particleLightCache = on;
          c.particleEmptyCollision = on;
        }
        case PARTICLE_LIGHT -> c.particleLightCache = on;
        case PARTICLE_COLLISION -> c.particleEmptyCollision = on;
        case TEXT -> c.textBufferReuse = on;
        case PLAYER_ARMOR -> c.playerArmorGeometry = on;
        case PLAYER_ITEMS -> c.playerItemGeometry = on;
        case PLAYER_BODY -> c.playerBodyGeometry = on;
        case BATCHING -> c.featureBatching = on;
      }
    }

    boolean unavailable() {
      return (this == TEXT || this == BATCHING) && RenderModSupport.IMMEDIATELY_FAST
          || (this == BATCHING
                  || this == PLAYER_ARMOR
                  || this == PLAYER_ITEMS
                  || this == PLAYER_BODY)
              && RenderModSupport.IRIS
          || this == PLAYER_BODY && RenderModSupport.PLAYER_MODEL_MODS
          || (this == PARTICLES || this == PARTICLE_LIGHT || this == PARTICLE_COLLISION)
              && !RenderModSupport.PARTICLE_CACHES;
    }
  }

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
  private static Run active;
  private static boolean savingSummary;
  private static Path lastSummary;

  private BenchmarkSeries() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(BenchmarkSeries::tick);
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> abort(client, "disconnect"));
    ClientCommandRegistration.register();
  }

  public static boolean running() {
    return active != null || savingSummary;
  }

  public static Component start(Minecraft client, int brackets, String label, Target target) {
    BracketedComparison.phases(brackets);
    if (PlayerProfile.busy()) return Component.translatable("drawless.player_profile.busy");
    if (running() || DisplayProbe.busy()) return text("busy");
    if (client.level == null || client.player == null) return text("enter_world");
    if (!label.matches("[A-Za-z0-9_-]{1,24}")) return text("invalid_label");
    if (target.unavailable()) return text("unavailable", target.id());
    active = new Run(client, brackets, label, target);
    return text("armed", label, brackets);
  }

  public static Component cancel(Minecraft client) {
    if (active == null) return text(savingSummary ? "saving" : "idle");
    abort(client, "cancelled");
    return text("cancelled");
  }

  public static Component status() {
    if (active != null)
      return text(
          "status", active.label, active.phase + 1, BracketedComparison.phases(active.brackets));
    if (savingSummary) return text("saving");
    return lastSummary == null ? text("idle") : text("last", lastSummary.toString());
  }

  // Called before any settings screen can save the temporarily applied configuration.
  public static void beforeSettings(Minecraft client) {
    if (active != null) abort(client, "settings_opened");
  }

  private static void tick(Minecraft client) {
    var run = active;
    if (run == null || run.reason != null) return;
    if (client.level != run.level || client.player == null) {
      abort(client, "world_changed");
      return;
    }
    if (run.anchor == null) {
      if (System.nanoTime() - run.requestedAt > 60_000_000_000L) {
        abort(client, "start_timeout");
        return;
      }
      if (client.gui.screen() != null || client.isPaused() || !client.getWindow().isFocused())
        return;
      if (!DrawLessConfig.snapshot().equals(run.original)) {
        abort(client, "settings_changed");
        return;
      }
      run.originalInactivity = client.options.inactivityFpsLimit().get();
      client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
      run.environmentWarnings.add(
          "AFK frame throttling was temporarily disabled during this series; original inactivity policy: "
              + run.originalInactivity.getSerializedName()
              + ". The original policy was restored afterwards.");
      run.anchor = Anchor.capture(client);
      next(client, run);
      return;
    }
    String failure = guard(client, run);
    if (failure != null) abort(client, failure);
  }

  private static String guard(Minecraft client, Run run) {
    if (client.level != run.level || client.player == null) return "world_changed";
    if (client.gui.screen() != null || client.isPaused()) return "screen_opened";
    if (!client.getWindow().isFocused()) return "focus_lost";
    if (client.getFramerateLimitTracker().getThrottleReason()
        != com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason.NONE)
      return "frame_throttled";
    if (!run.expected.equals(DrawLessConfig.snapshot())) return "settings_changed";
    Anchor now = Anchor.capture(client);
    if (Math.abs(now.x - run.anchor.x) > 0.01
        || Math.abs(now.y - run.anchor.y) > 0.01
        || Math.abs(now.z - run.anchor.z) > 0.01
        || Math.abs(net.minecraft.util.Mth.wrapDegrees(now.yaw - run.anchor.yaw)) > 0.05
        || Math.abs(now.pitch - run.anchor.pitch) > 0.05) return "camera_moved";
    return now.options.equals(run.anchor.options) ? null : "environment_changed";
  }

  private static void next(Minecraft client, Run run) {
    if (active != run || run.reason != null) return;
    if (run.phase > 0) {
      String failure = guard(client, run);
      if (failure != null) {
        abort(client, failure);
        return;
      }
    }
    DrawLessConfig.restoreSnapshot(run.original);
    run.target.apply(run.phase % 2 == 1);
    run.expected = DrawLessConfig.snapshot();
    String label = run.label + "_" + BracketedComparison.label(run.phase);
    String metadata =
        "\n\nBenchmark series: "
            + run.directory.getFileName()
            + "; target: "
            + run.target.id()
            + "; phase: "
            + (run.phase + 1)
            + "/"
            + BracketedComparison.phases(run.brackets)
            + "; settings snapshot: "
            + run.expected
            + "; environment: "
            + run.anchor.options;
    run.pending =
        DisplayProbe.startAutomated(
            client, label, run.directory, metadata, result -> completed(client, run, result));
    if (!run.pending) {
      abort(client, "probe_busy");
      return;
    }
    client.gui.hud.setOverlayMessage(
        text(
            "phase",
            BracketedComparison.label(run.phase).toUpperCase(Locale.ROOT),
            run.phase + 1,
            BracketedComparison.phases(run.brackets)),
        false);
  }

  private static void completed(Minecraft client, Run run, DisplayProbe.Result result) {
    if (active != run) return;
    run.pending = false;
    run.results.add(result);
    if (run.reason != null) {
      finish(client, run);
      return;
    }
    if (!result.reason().equals("complete")
        || !Double.isFinite(result.fps())
        || result.fps() <= 0
        || result.intervals() == 0
        || result.samples().size() < 18
        || result.menuFrames() != 0
        || result.unfocusedFrames() != 0) {
      abort(client, result.reason().equals("complete") ? "incomplete_phase" : result.reason());
      return;
    }
    String failure = guard(client, run);
    if (failure != null) {
      abort(client, failure);
      return;
    }
    run.phase++;
    if (run.phase == BracketedComparison.phases(run.brackets)) {
      run.reason = "complete";
      finish(client, run);
    } else next(client, run);
  }

  private static void abort(Minecraft client, String reason) {
    var run = active;
    if (run == null || run.reason != null) return;
    run.reason = reason;
    DrawLessConfig.restoreSnapshot(run.original);
    restoreInactivity(client, run);
    client.gui.hud.setOverlayMessage(text("aborted", text("reason." + reason)), false);
    if (run.pending) DisplayProbe.cancelAutomated(client);
    else finish(client, run);
  }

  private static void finish(Minecraft client, Run run) {
    DrawLessConfig.restoreSnapshot(run.original);
    restoreInactivity(client, run);
    active = null;
    savingSummary = true;
    // Build immutable output on the client thread, then perform only file I/O in the worker.
    var report =
        BenchmarkReport.create(
            run.label,
            run.brackets,
            run.target.id(),
            run.reason,
            run.original,
            run.anchor == null ? "not_started" : run.anchor.options,
            run.environmentWarnings,
            run.results);
    CompletableFuture.runAsync(
        () -> {
          try {
            AtomicFileWriter.write(
                run.directory.resolve("summary.json"), w -> w.write(report.json()));
            AtomicFileWriter.write(
                run.directory.resolve("README.md"), w -> w.write(report.markdown()));
            client.execute(
                () -> {
                  lastSummary = run.directory.resolve("README.md");
                  savingSummary = false;
                  message(
                      client,
                      text(
                          run.reason.equals("complete") ? "finished" : "partial",
                          lastSummary.toString()));
                });
          } catch (Exception error) {
            ru.wilyfox.drawless.DrawLess.LOGGER.warn("Could not save benchmark summary", error);
            client.execute(
                () -> {
                  savingSummary = false;
                  message(client, text("save_failed", run.directory.toString()));
                });
          }
        });
  }

  private static void restoreInactivity(Minecraft client, Run run) {
    if (run.originalInactivity != null) {
      client.options.inactivityFpsLimit().set(run.originalInactivity);
      run.originalInactivity = null;
    }
  }

  private static Component text(String id, Object... args) {
    return Component.translatable("drawless.benchmark." + id, args);
  }

  private static void message(Minecraft client, Component message) {
    client
        .gui
        .hud
        .getChat()
        .addClientSystemMessage(Component.literal("[DrawLess] ").append(message));
  }

  private static final class Run {
    final String label, original;
    final int brackets;
    final Target target;
    final ClientLevel level;
    final Path directory;
    final long requestedAt = System.nanoTime();
    final List<DisplayProbe.Result> results = new ArrayList<>();
    final List<String> environmentWarnings = new ArrayList<>();
    InactivityFpsLimit originalInactivity;
    int phase;
    boolean pending;
    String reason, expected;
    Anchor anchor;

    Run(Minecraft client, int brackets, String label, Target target) {
      this.label = label;
      this.brackets = brackets;
      this.target = target;
      original = expected = DrawLessConfig.snapshot();
      level = client.level;
      if (client.options.enableVsync().get())
        environmentWarnings.add(
            "VSync is enabled; synchronization can mask or quantize performance differences.");
      if (client.options.framerateLimit().get() < 260)
        environmentWarnings.add(
            "A Minecraft FPS limit is enabled: " + client.options.framerateLimit().get() + ".");
      if (RenderMetrics.getInstance().isEnabled())
        environmentWarnings.add(
            "Detailed CPU profiling is enabled; its overhead is included in this comparison.");
      directory =
          client
              .gameDirectory
              .toPath()
              .resolve("drawless-reports")
              .resolve("benchmark-" + TIME.format(Instant.now()) + "-" + label);
    }
  }

  private record Anchor(double x, double y, double z, float yaw, float pitch, String options) {
    static Anchor capture(Minecraft client) {
      return new Anchor(
          client.player.getX(),
          client.player.getY(),
          client.player.getZ(),
          client.player.getYRot(),
          client.player.getXRot(),
          "framebuffer="
              + client.getWindow().getWidth()
              + "x"
              + client.getWindow().getHeight()
              + "; fpsLimit="
              + client.options.framerateLimit().get()
              + "; vsync="
              + client.options.enableVsync().get()
              + "; inactivityFpsLimit="
              + client.options.inactivityFpsLimit().get().getSerializedName()
              + "; fov="
              + client.options.fov().get()
              + "; renderDistance="
              + client.options.getEffectiveRenderDistance()
              + "; camera="
              + client.options.getCameraType()
              + "; resourcePacks="
              + client.options.resourcePacks
              + "; profiler="
              + RenderMetrics.getInstance().isEnabled());
    }
  }

  private static final class ClientCommandRegistration {
    static void register() {
      net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
          (dispatcher, access) -> {
            var root = ClientCommands.literal("drawless");
            var command =
                ClientCommands.literal("benchmark")
                    .then(
                        ClientCommands.literal("cancel")
                            .executes(
                                c -> {
                                  c.getSource().sendFeedback(cancel(c.getSource().getClient()));
                                  return 1;
                                }))
                    .then(
                        ClientCommands.literal("status")
                            .executes(
                                c -> {
                                  c.getSource().sendFeedback(status());
                                  return 1;
                                }));
            for (int brackets : new int[] {1, 3}) {
              var label =
                  ClientCommands.argument("label", StringArgumentType.word())
                      .executes(
                          c -> {
                            c.getSource()
                                .sendFeedback(
                                    start(
                                        c.getSource().getClient(),
                                        brackets,
                                        StringArgumentType.getString(c, "label"),
                                        Target.FULL));
                            return 1;
                          });
              for (Target target : Target.values())
                label.then(
                    ClientCommands.literal(target.id())
                        .executes(
                            c -> {
                              c.getSource()
                                  .sendFeedback(
                                      start(
                                          c.getSource().getClient(),
                                          brackets,
                                          StringArgumentType.getString(c, "label"),
                                          target));
                              return 1;
                            }));
              command.then(ClientCommands.literal(Integer.toString(brackets)).then(label));
            }
            dispatcher.register(root.then(command));
          });
    }
  }
}
