package ru.wilyfox.drawless.diagnostics;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.util.AtomicFileWriter;

/** Explicit, short diagnostic phases. Idle code never scans the world or writes files. */
public final class DisplayProbe {
  private static final long SECOND = 1_000_000_000L;
  private static final long WARMUP = 2 * SECOND;
  private static final long DURATION = 20 * SECOND;
  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
  private static Session active;
  private static boolean registered;
  private static volatile Path lastSaved;
  private static volatile boolean saving;

  private DisplayProbe() {}

  public static void register() {
    if (registered) return;
    registered = true;
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          Session session = active;
          if (session == null) return;
          long now = System.nanoTime();
          if (client.level != session.level) finish(client, "world_changed");
          else if (session.measurementStart != 0 && now - session.measurementStart >= DURATION)
            finish(client, "complete");
          else if (now - session.requestedAt >= 60 * SECOND) finish(client, "timeout");
        });
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> finish(client, "disconnect"));
  }

  public static String start(Minecraft client, String label) {
    if (PlayerProfile.busy())
      return Component.translatable("drawless.player_profile.busy").getString();
    if (BenchmarkSeries.running())
      return Component.translatable("drawless.benchmark.busy").getString();
    if (saving)
      return "Previous DrawLess report is still saving. Wait before starting another probe.";
    if (active != null)
      return "Display probe already running: " + active.label + ". Wait for the saved report.";
    if (client.level == null || client.player == null)
      return "Enter a world before starting a display probe.";
    if (label == null || !label.matches("[A-Za-z0-9_-]{1,32}"))
      return "Usage: /drawless probe <label> (1-32 letters, digits, _ or -).";
    active = new Session(label, client);
    DisplayCostProbe.start();
    RenderMetrics.getInstance().recordClientEvent("display-probe/start", label);
    return "Display probe "
        + label
        + ": close chat and keep the camera still. 2s warmup + 20s recording; report saves automatically.";
  }

  static boolean busy() {
    return active != null || saving;
  }

  static boolean startAutomated(
      Minecraft client,
      String label,
      Path directory,
      String metadata,
      Consumer<Result> completion) {
    if (busy() || PlayerProfile.busy() || client.level == null || client.player == null)
      return false;
    active = new Session(label, client);
    active.directory = directory;
    active.metadata = metadata;
    active.completion = completion;
    DisplayCostProbe.start();
    return true;
  }

  static void cancelAutomated(Minecraft client) {
    if (active != null && active.completion != null) finish(client, "cancelled");
  }

  record Result(
      String label,
      String reason,
      Path path,
      double fps,
      long intervals,
      double seconds,
      int menuFrames,
      int unfocusedFrames,
      List<Sample> samples) {}

  public static String status() {
    return active != null
        ? "Display probe running: " + active.label
        : lastSaved != null ? "Last display report: " + lastSaved : "No display probe running.";
  }

  /** Cheap gate for coarse particle timing; fine-grained full profiling is not required. */
  public static boolean running() {
    return active != null;
  }

  public static boolean measuring(Minecraft client) {
    var session = active;
    return session != null
        && client.level == session.level
        && session.measurementStart != 0
        && System.nanoTime() >= session.measurementStart
        && client.gui.screen() == null
        && !client.isPaused();
  }

  public static void onFrame(
      Minecraft client, Camera camera, Frustum frustum, LevelRenderState state) {
    Session session = active;
    if (session == null) return;
    if (client.level != session.level || client.player == null) {
      finish(client, "world_changed");
      return;
    }
    if (client.gui.screen() != null || client.isPaused()) {
      session.lastFrameAt = 0;
      session.menuFrames++;
      return;
    }
    long now = System.nanoTime();
    if (session.measurementStart == 0) {
      session.measurementStart = now + WARMUP;
      session.nextSampleAt = session.measurementStart;
    }
    if (now < session.measurementStart) return;
    if (session.lastFrameAt != 0) {
      session.frameIntervals++;
      session.frameIntervalNanos += now - session.lastFrameAt;
      session.windowIntervals++;
      session.windowNanos += now - session.lastFrameAt;
    }
    session.lastFrameAt = now;
    // Every measured frame: a window is comparable only if its rendered population held still.
    int avatars = 0;
    for (var entityState : state.entityRenderStates)
      if (entityState instanceof AvatarRenderState) avatars++;
    session.windowMinAvatars = Math.min(session.windowMinAvatars, avatars);
    session.windowMaxAvatars = Math.max(session.windowMaxAvatars, avatars);
    session.windowAvatarSum += avatars;
    session.windowAvatarFrames++;
    if (!client.getWindow().isFocused()) session.unfocusedFrames++;
    if (now >= session.nextSampleAt && session.samples.size() < 24) {
      session.nextSampleAt = now + SECOND;
      try (var ignored = RenderMetrics.getInstance().scope("diagnostic/displayProbe/sample")) {
        long started = System.nanoTime();
        Counts counts = count(client.level.entitiesForRendering(), state, frustum);
        Population population = population(client, state, session);
        session.windowIntervals = session.windowNanos = 0;
        session.windowMinAvatars = Integer.MAX_VALUE;
        session.windowMaxAvatars = -1;
        session.windowAvatarSum = session.windowAvatarFrames = 0;
        session.samples.add(
            new Sample(
                (now - session.measurementStart) / 1e9,
                client.getFps(),
                client.level.players().size(),
                counts,
                camera.position().x,
                camera.position().y,
                camera.position().z,
                camera.yRot(),
                camera.xRot(),
                camera.getFov(),
                client.getWindow().isFocused(),
                RenderMetrics.getInstance().isEnabled(),
                System.nanoTime() - started,
                ru.wilyfox.drawless.performance.DisplayModelBudget.diagnostic(),
                ru.wilyfox.drawless.performance.NpcFrameCache.diagnostic(),
                ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.diagnostic(),
                ru.wilyfox.drawless.performance.NpcFrameCache.groupDiagnostic(),
                ru.wilyfox.drawless.performance.ParticleLightCache.diagnostic(),
                ru.wilyfox.drawless.performance.ParticleCollisionCache.diagnostic(),
                ru.wilyfox.drawless.performance.ParticleWorkProbe.diagnostic(),
                client.particleEngine.countParticles(),
                ru.wilyfox.drawless.performance.PlayerArmorGeometry.diagnostic(),
                ru.wilyfox.drawless.performance.PlayerItemGeometry.diagnostic(),
                population));
      }
    }
    if (now - session.measurementStart >= DURATION) finish(client, "complete");
  }

  /**
   * Rendered players and mannequins of the sampled frame, items they carry, and how many stand in
   * sections the vanilla occlusion graph did not reach this frame (-1 when another renderer owns
   * section visibility). The window fields cover frames since the previous sample.
   */
  private static Population population(Minecraft client, LevelRenderState state, Session session) {
    int avatars = 0, held = 0, head = 0, outside = 0;
    ViewArea area =
        RenderModSupport.SODIUM
            ? null
            : ((ru.wilyfox.drawless.mixin.DisplayProbeViewAreaAccessor) client.levelRenderer)
                .drawless$viewArea();
    var graph = client.levelRenderer.sectionOcclusionGraph();
    var position = new BlockPos.MutableBlockPos();
    for (var entityState : state.entityRenderStates) {
      if (!(entityState instanceof AvatarRenderState avatar)) continue;
      avatars++;
      if (!avatar.rightHandItemState.isEmpty()) held++;
      if (!avatar.leftHandItemState.isEmpty()) held++;
      if (!avatar.headItem.isEmpty() || avatar.wornHeadType != null) head++;
      if (area != null) {
        var section = area.getRenderSectionAt(position.set(avatar.x, avatar.y, avatar.z));
        if (section == null || graph.getNode(section) == null) outside++;
      } else if (SodiumVisibility.available && !SodiumVisibility.visible(avatar)) outside++;
    }
    return new Population(
        session.windowIntervals,
        session.windowNanos,
        session.windowMinAvatars == Integer.MAX_VALUE ? avatars : session.windowMinAvatars,
        Math.max(session.windowMaxAvatars, avatars),
        session.windowAvatarFrames == 0
            ? avatars
            : (double) session.windowAvatarSum / session.windowAvatarFrames,
        avatars,
        held,
        head,
        area != null || SodiumVisibility.available ? outside : -1);
  }

  /**
   * Sodium's own section visibility for an avatar-sized box around the render state, from the graph
   * of the frame being extracted. Isolated so Sodium classes load only with Sodium; a Sodium build
   * without this method turns the counter off instead of failing the probe.
   */
  private static final class SodiumVisibility {
    static boolean available = RenderModSupport.SODIUM;

    static boolean visible(AvatarRenderState avatar) {
      try {
        var renderer =
            net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
        return renderer == null
            || renderer.isBoxVisible(
                avatar.x - 0.3,
                avatar.y,
                avatar.z - 0.3,
                avatar.x + 0.3,
                avatar.y + 1.8,
                avatar.z + 0.3);
      } catch (NoSuchMethodError | NoClassDefFoundError incompatible) {
        available = false;
        return true;
      }
    }
  }

  static Counts count(Iterable<Entity> entities, LevelRenderState state, Frustum frustum) {
    int displays = 0,
        items = 0,
        noCull = 0,
        nominalOutside = 0,
        bone = 0,
        custom = 0,
        passengers = 0;
    Map<String, Integer> models = new HashMap<>();
    for (Entity entity : entities) {
      if (entity.isRemoved() || !(entity instanceof Display display)) continue;
      displays++;
      if (!(display instanceof Display.ItemDisplay item)) continue;
      items++;
      if (!display.affectedByCulling()) noCull++;
      if (frustum != null && !frustum.isVisible(display.getBoundingBoxForCulling()))
        nominalOutside++;
      if (entity.isPassenger()) passengers++;
      var stack = item.getItemStack();
      if (stack.is(Items.BONE)) bone++;
      if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) custom++;
      var model = stack.get(DataComponents.ITEM_MODEL);
      String key =
          model != null
              ? model.toString()
              : String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
      models.merge(key, 1, Integer::sum);
    }
    int prepared = 0, empty = 0;
    for (var entityState : state.entityRenderStates) {
      if (entityState instanceof ItemDisplayEntityRenderState itemState) {
        prepared++;
        if (itemState.item.isEmpty()) empty++;
      }
    }
    var top =
        models.entrySet().stream()
            .sorted(
                Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                    .thenComparing(Map.Entry.comparingByKey()))
            .limit(16)
            .map(entry -> new ModelCount(entry.getKey(), entry.getValue()))
            .toList();
    return new Counts(
        displays, items, noCull, nominalOutside, bone, custom, passengers, prepared, empty, top);
  }

  private static void finish(Minecraft client, String reason) {
    Session session = active;
    if (session == null) return;
    saving = true;
    active = null;
    RenderMetrics.getInstance()
        .recordClientEvent("display-probe/end", session.label + ": " + reason);
    double fps =
        session.frameIntervalNanos > 0
            ? session.frameIntervals * 1e9 / session.frameIntervalNanos
            : 0;
    String markdown =
        report(
                session.label,
                reason,
                session.context + session.metadata,
                fps,
                session.menuFrames,
                session.unfocusedFrames,
                List.copyOf(session.samples))
            + DisplayCostProbe.report();
    DisplayCostProbe.stop();
    Path output =
        session.directory.resolve(
            "drawless-" + FILE_TIME.format(Instant.now()) + "-" + session.label + ".md");
    Result result =
        new Result(
            session.label,
            reason,
            output,
            fps,
            session.frameIntervals,
            session.frameIntervalNanos / 1e9,
            session.menuFrames,
            session.unfocusedFrames,
            List.copyOf(session.samples));
    CompletableFuture.runAsync(
        () -> {
          Result completed = result;
          String failure = null;
          try {
            AtomicFileWriter.write(output, writer -> writer.write(markdown));
            lastSaved = output;
          } catch (Exception error) {
            ru.wilyfox.drawless.DrawLess.LOGGER.warn("Could not save display probe", error);
            failure = error.getMessage();
            completed =
                new Result(
                    session.label,
                    "save_failed",
                    output,
                    fps,
                    result.intervals,
                    result.seconds,
                    result.menuFrames,
                    result.unfocusedFrames,
                    result.samples);
          } finally {
            saving = false;
          }
          Result outcome = completed;
          String errorText = failure;
          client.execute(
              () -> {
                if (session.completion != null) session.completion.accept(outcome);
                else
                  message(
                      client,
                      outcome.reason.equals("save_failed")
                          ? "Could not save display probe: " + errorText
                          : String.format(
                              Locale.ROOT,
                              "%s finished (%s): %.1f FPS. Saved: %s",
                              session.label,
                              reason,
                              fps,
                              output));
              });
        });
  }

  static String report(
      String label,
      String reason,
      String context,
      double fps,
      int menuFrames,
      int unfocusedFrames,
      List<Sample> samples) {
    var out = new StringBuilder("# Item Display probe\n\n");
    out.append("Phase: ")
        .append(label)
        .append("; result: ")
        .append(reason)
        .append("\n\n")
        .append(context)
        .append("\n\n");
    out.append(String.format(Locale.ROOT, "Mean observed FPS: %.2f\n\n", fps));
    out.append("Samples: ")
        .append(samples.size())
        .append("; target measurement: 20 seconds after 2 seconds of warmup.\n\n");
    out.append("Menu frames excluded: ")
        .append(menuFrames)
        .append("; unfocused measured frames: ")
        .append(unfocusedFrames)
        .append("\n\n");
    out.append(
        "Counts are sampled once per second. Prepared states are not a count of visible pixels.\n\n");
    out.append(
        "**Outside nominal box** tests the server's culling AABB, which may be zero-sized or omit model translation/rotation/scale. It is a diagnostic indicator, not a safe decision to hide the model. Occlusion behind blocks is not measured.\n\n");
    out.append(
        "| s | FPS | Players | Display loaded | Item loaded | Item no frustum culling | Item outside nominal box | Bone carriers | Custom model data | Passengers | Item prepared | Prepared empty | Camera XYZ | Yaw / pitch / FOV | Focused | Detailed profiler | Scan ms |\n");
    out.append(
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- | ---: |\n");
    for (Sample sample : samples) {
      Counts c = sample.counts;
      out.append(
          String.format(
              Locale.ROOT,
              "| %.2f | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %.2f / %.2f / %.2f | %.2f / %.2f / %.2f | %s | %s | %.3f |\n",
              sample.seconds,
              sample.fps,
              sample.players,
              c.displays,
              c.items,
              c.noCull,
              c.nominalOutside,
              c.bone,
              c.custom,
              c.passengers,
              c.prepared,
              c.preparedEmpty,
              sample.x,
              sample.y,
              sample.z,
              sample.yaw,
              sample.pitch,
              sample.fov,
              sample.focused,
              sample.profiling,
              sample.scanNanos / 1e6));
    }
    out.append(
        "\n## Rendered avatar population by sample\n\nAvatars are extracted player and mannequin render states (after frustum and any renderer-side culling), unlike the loaded Players column. Window FPS, min–max and mean cover measured frames since the previous sample; the other columns describe the sampled frame. Held items count non-empty hand states; head items include worn skulls. Outside graph counts avatars in sections the occlusion graph did not reach this frame: the vanilla section graph, or Sodium's section visibility for an avatar-sized box when Sodium renders terrain — candidates for occlusion work, not proof of invisibility. The vanilla graph only extends after block edits until it is rebuilt (for example when the camera enters another section), so newly enclosed sections may still count as reached. n/a when neither source is available.\n\n| s | Window frames | Window FPS | Avatars min–max | Window mean | Avatars | Held items | Head items | Outside graph |\n| ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |\n");
    for (Sample sample : samples) {
      var p = sample.population;
      out.append(
          String.format(
              Locale.ROOT,
              "| %.2f | %d | %s | %d–%d | %.2f | %d | %d | %d | %s |\n",
              sample.seconds,
              p.windowIntervals,
              p.windowNanos > 0
                  ? String.format(Locale.ROOT, "%.2f", p.windowIntervals * 1e9 / p.windowNanos)
                  : "–",
              p.avatarsMin,
              p.avatarsMax,
              p.avatarsMean,
              p.avatars,
              p.heldItems,
              p.headItems,
              p.outsideGraph < 0 ? "n/a" : Integer.toString(p.outsideGraph)));
    }
    out.append("\n## Model identifiers by sample (top 16)\n\n");
    out.append(
        "An identifier may select several models via CustomModelData; bone carriers alone do not identify decorative NPCs.\n\n");
    out.append("| s | Item model identifier | Count |\n| ---: | --- | ---: |\n");
    for (Sample sample : samples)
      for (ModelCount model : sample.counts.models)
        out.append(
            String.format(
                Locale.ROOT,
                "| %.2f | %s | %d |\n",
                sample.seconds,
                model.id.replace("|", "\\|"),
                model.count));
    out.append(
        "\n## Display model budget by sample\n\nCounters describe the sampled frame, not the whole second. First appearances, close models and changes waiting 250ms can exceed the soft preparation budget. Dynamic/unknown models use vanilla preparation.\n\n| s | Layer cache / preparation |\n| ---: | --- |\n");
    for (Sample sample : samples)
      out.append(
          String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.displayBudget));
    out.append(
        "\n## ModelEngine NPC pose budget by sample\n\nFull render states are reused between group updates. Cached geometry is still submitted every frame. Grouping uses root UUID, or approximate block cell and model family for unattached parts.\n\n| s | NPC pose budget |\n| ---: | --- |\n");
    for (Sample sample : samples)
      out.append(String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.npcBudget));
    out.append(
        "\n## Extreme Item Display performance by sample\n\nFreeze retains static vertices on the GPU, batching by material. Draw calls cover this cached scene only (previous rendered frame). Prepared counts above exclude these GPU-only parts. Unrender omits extraction and drawing. Tick pausing depends on pauseTicks; TICKS_ONLY pauses Display ticks while preserving ordinary geometry. STATIC_CACHE reuses only stable opaque geometry, with live ticks, immediate pose/light invalidation and vanilla visibility; unsupported visuals stay on the ordinary path. LIVE_CACHE shares local opaque/cutout geometry while streaming current vanilla matrices/light/tint every frame. LIVE_EXTRACT additionally recycles only successfully GPU-owned states, with full vanilla extraction/finalization every frame, and bypasses light probing only for server brightness overrides. stateFresh includes ordinary fallback; stateReused counts state allocations avoided, not frozen poses. Mesh/index rebuild and upload counters are cumulative; instances/quads/fallback describe a frame. Incoming packets still apply; GPU elapsed time is not measured.\n\n| s | Extreme scene |\n| ---: | --- |\n");
    for (Sample sample : samples)
      out.append(String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.extreme));
    out.append(
        "\n## Particle light cache by sample\n\nBase light is shared within the client tick while world and published-light data remain valid; unsupported temporal integrations use pass-local reuse. Cross-frame hits reuse entries captured in an earlier extraction pass. Shared chunk knowledge is bounded and cleared by lifecycle changes. Counts describe the previous completed particle pass; Total counters are cumulative enabled passes, not per-second rates. Motion, collisions, count, lifetime and subclass light overrides are unchanged.\n\n| s | Particle lighting |\n| ---: | --- |\n");
    for (Sample sample : samples)
      out.append(String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.particles));
    out.append(
        "\n## Particle collision fast path by sample\n\nEmpty volumes skip collision collection. Up to eight whole-air sections need no block reads; small nonempty sections are checked with the current collision context. Only context-independent empty scan proofs are retained; empty non-air shapes are rechecked for each query. Tick-local block states are shared between overlapping volumes. blockReadsTotal counts underlying state reads made by the proof scanner, excluding vanilla fallback and reads inside shape implementations; blockQueriesTotal and blockHitsTotal describe the state table. shapeChecksTotal counts non-air shape queries and shapeSkipsTotal counts successful context-checked bypasses. Unsupported volumes and nonempty shapes use vanilla. Proof hits include blocked proofs; emptySkips counts actual bypasses. Tick counters and Total counters are separate from frame counters.\n\n| s | Particle collisions |\n| ---: | --- |\n");
    for (Sample sample : samples)
      out.append(
          String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.particleCollision));
    out.append(
        "\n## Particle workload by sample\n\nCoarse timing is collected only during this explicit probe, even with detailed profiling OFF. Difference cumulative times/counts between samples; tick time is per tick, extraction time per rendered frame. Vertex encoding (buildLayer), preparation (inclusive of encoding) and execute-group CPU submission are measured separately. Their counts are stage invocations, not rendered frames; do not add nested scopes. contextTotalMs/contextTicksTotal measure shared start-of-tick maintenance outside the particle tick/extract scopes. Shared staging uploads, invalidation event time and GPU execution are not isolated by these timers. Population counts cover engine groups, including particles outside the camera; pointQueries/visible count unmodified vanilla quad-particle visibility decisions, without per-particle timers. The experimental frustum cell cache has been removed.\n\n| s | Loaded particle groups | Cumulative timings and visibility |\n| ---: | --- | --- |\n");
    for (Sample sample : samples)
      out.append(
          String.format(
              Locale.ROOT,
              "| %.2f | %s | %s |\n",
              sample.seconds,
              sample.particlePopulation,
              sample.particleWork));
    out.append(
        "\n## Retained player armor geometry by sample\n\nArmor model-part meshes are retained on the GPU for their resource lifetime; each frame streams one 128-byte instance per captured part (pose, normal bases, tint, light, overlay). Per-frame fields describe the last level render before the sample; *Total fields are cumulative, so difference them between samples. A piece is one equipment renderLayers call; base, glint and trim submits of a piece are captured together or all replayed through the ordinary path (fallback reasons count pieces). capture includes animation setup and the part traversal at submit time; upload includes new mesh vertices, index rebuilds and instance writes; drawCpu is CPU time to record the retained draws. These are CPU-thread elapsed times measured every active frame, not GPU time. Vanilla armor preparation and execution that no longer occur are not counted here; compare player profiles or bracketed FPS for net effect.\n\n| s | Player armor |\n| ---: | --- |\n");
    for (var sample : samples)
      out.append(String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.playerArmor));
    out.append(
        "\n## Retained player item geometry by sample\n\nItems held and worn in the head item layer by avatars reuse the shared item meshes of Item Displays, looked up by quad content every submit; each frame streams one 128-byte instance per material of a captured layer. A piece is one item stack: all its layers are captured together or all replayed through the ordinary path in order (fallback reasons count pieces). capture covers the content lookup and instance setup at submit time; upload covers new shared mesh vertices, index rebuilds and instance writes; drawCpu is CPU time to record the retained draws. CPU-thread elapsed times, not GPU time; vanilla item preparation that no longer occurs is not counted here.\n\n| s | Player items |\n| ---: | --- |\n");
    for (var sample : samples)
      out.append(String.format(Locale.ROOT, "| %.2f | %s |\n", sample.seconds, sample.playerItems));
    if (!samples.isEmpty())
      out.append("\n## NPC group membership (last sample, top 16)\n\n")
          .append(samples.getLast().npcGroups);
    return out.toString();
  }

  private static void message(Minecraft client, String text) {
    if (client.gui != null)
      client.gui.hud.getChat().addClientSystemMessage(Component.literal("[DrawLess] " + text));
  }

  private static final class Session {
    final String label, context;
    final net.minecraft.client.multiplayer.ClientLevel level;
    final long requestedAt = System.nanoTime();
    final List<Sample> samples = new ArrayList<>(24);
    long measurementStart, nextSampleAt, lastFrameAt, frameIntervals, frameIntervalNanos;
    long windowIntervals, windowNanos, windowAvatarSum;
    int windowMinAvatars = Integer.MAX_VALUE, windowMaxAvatars = -1, windowAvatarFrames;
    int menuFrames, unfocusedFrames;
    Path directory;
    String metadata = "";
    Consumer<Result> completion;

    Session(String label, Minecraft client) {
      this.label = label;
      this.level = client.level;
      directory = client.gameDirectory.toPath().resolve("drawless-reports");
      var config = ru.wilyfox.drawless.config.DrawLessConfig.get();
      context =
          "Minecraft "
              + net.minecraft.SharedConstants.getCurrentVersion().name()
              + "; DrawLess "
              + net.fabricmc.loader.api.FabricLoader.getInstance()
                  .getModContainer("drawless")
                  .orElseThrow()
                  .getMetadata()
                  .getVersion()
                  .getFriendlyString()
              + "; dimension "
              + level.dimension().identifier()
              + "; render distance "
              + client.options.getEffectiveRenderDistance()
              + "; GUI scale "
              + client.getWindow().getGuiScale()
              + "; detailed profiler "
              + RenderMetrics.getInstance().isEnabled()
              + "; DrawLess enabled "
              + config.enabled
              + "; text buffer reuse "
              + config.textBufferReuse
              + "; text buffer reuse effective "
              + (config.enabled
                  && config.textBufferReuse
                  && !ru.wilyfox.drawless.compat.RenderModSupport.IMMEDIATELY_FAST)
              + "; player armor geometry "
              + config.playerArmorGeometry
              + "; player item geometry "
              + config.playerItemGeometry
              + "; feature batching "
              + config.featureBatching
              + "; feature batching effective "
              + ru.wilyfox.drawless.performance.FeatureBatching.enabled()
              + "; feature batching mode "
              + ru.wilyfox.drawless.performance.FeatureBatching.requestedMode()
              + "; feature batching effective mode "
              + ru.wilyfox.drawless.performance.FeatureBatching.effectiveMode()
              + "; display model budget "
              + config.displayModelBudget
              + "; display budget us "
              + config.displayModelBudgetMicros
              + "; adaptive display budget "
              + config.adaptiveDisplayModelBudget
              + "; particle light cache "
              + config.particleLightCache
              + "; particle light cache effective "
              + (config.enabled
                  && config.particleLightCache
                  && ru.wilyfox.drawless.compat.RenderModSupport.PARTICLE_CACHES)
              + "; temporal particle light available "
              + ru.wilyfox.drawless.compat.RenderModSupport.TEMPORAL_PARTICLE_LIGHT
              + "; particle empty collision "
              + config.particleEmptyCollision
              + "; particle empty collision effective "
              + (config.enabled
                  && config.particleEmptyCollision
                  && ru.wilyfox.drawless.compat.RenderModSupport.PARTICLE_CACHES)
              + "; Particle Core "
              + ru.wilyfox.drawless.compat.RenderModSupport.PARTICLE_CORE
              + "; AsyncParticles "
              + ru.wilyfox.drawless.compat.RenderModSupport.ASYNC_PARTICLES
              + "; particle visibility vanilla"
              + "; NPC pose budget "
              + config.modelEngineNpcBudget
              + "; NPC groups/frame "
              + config.npcGroupsPerFrame
              + "; adaptive NPC budget "
              + config.adaptiveNpcBudget
              + "; extreme display mode "
              + config.extremeDisplayMode
              + "; entire item scene "
              + config.extremeAllItemDisplays
              + "; pause display ticks "
              + (config.extremeDisplayMode
                      == ru.wilyfox.drawless.config.ExtremeDisplayMode.TICKS_ONLY
                  || (config.extremeDisplayMode
                              == ru.wilyfox.drawless.config.ExtremeDisplayMode.FREEZE
                          || config.extremeDisplayMode
                              == ru.wilyfox.drawless.config.ExtremeDisplayMode.UNRENDER)
                      && config.extremePauseDisplayTicks)
              + "; Sodium "
              + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")
              + "; ImmediatelyFast "
              + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("immediatelyfast");
    }
  }

  record ModelCount(String id, int count) {}

  record Counts(
      int displays,
      int items,
      int noCull,
      int nominalOutside,
      int bone,
      int custom,
      int passengers,
      int prepared,
      int preparedEmpty,
      List<ModelCount> models) {}

  record Sample(
      double seconds,
      int fps,
      int players,
      Counts counts,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float fov,
      boolean focused,
      boolean profiling,
      long scanNanos,
      String displayBudget,
      String npcBudget,
      String extreme,
      String npcGroups,
      String particles,
      String particleCollision,
      String particleWork,
      String particlePopulation,
      String playerArmor,
      String playerItems,
      Population population) {}

  /** Frame intervals of the window ending at this sample and the rendered avatar population. */
  record Population(
      long windowIntervals,
      long windowNanos,
      int avatarsMin,
      int avatarsMax,
      double avatarsMean,
      int avatars,
      int heldItems,
      int headItems,
      int outsideGraph) {}
}
