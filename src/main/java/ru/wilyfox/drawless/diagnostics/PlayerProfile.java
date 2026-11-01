package ru.wilyfox.drawless.diagnostics;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.network.chat.Component;
import ru.wilyfox.drawless.config.DrawLessConfig;

/** Explicit sampled player profiling. All retained live render data is cleared every frame. */
public final class PlayerProfile {
  public enum Mode {
    CONTROL,
    STAGES,
    DEEP
  }

  private static final int STRIDE = 20, MAX_FRAMES = 512, MAX_TAGS = 8192;
  private static Session session;
  private static boolean saving;
  private static Path lastSaved;
  private static Tag source;
  private static int executing;
  private static boolean collecting;

  private PlayerProfile() {}

  public record Tag(int actor, String layer) {}

  public static void register() {
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> finish(client, "disconnect"));
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, access) -> {
          var players =
              ClientCommands.literal("players")
                  .then(
                      ClientCommands.literal("cancel")
                          .executes(
                              c -> {
                                boolean active = session != null;
                                finish(c.getSource().getClient(), "cancelled");
                                c.getSource()
                                    .sendFeedback(
                                        active
                                            ? Component.translatable(
                                                "drawless.player_profile.cancelled")
                                            : status());
                                return 1;
                              }))
                  .then(
                      ClientCommands.literal("status")
                          .executes(
                              c -> {
                                c.getSource().sendFeedback(status());
                                return 1;
                              }));
          var label =
              ClientCommands.argument("label", StringArgumentType.word())
                  .executes(
                      c ->
                          startCommand(
                              c.getSource().getClient(),
                              c.getSource()::sendFeedback,
                              StringArgumentType.getString(c, "label"),
                              Mode.STAGES));
          for (Mode mode : Mode.values())
            label.then(
                ClientCommands.literal(mode.name().toLowerCase(Locale.ROOT))
                    .executes(
                        c ->
                            startCommand(
                                c.getSource().getClient(),
                                c.getSource()::sendFeedback,
                                StringArgumentType.getString(c, "label"),
                                mode)));
          players.then(label);
          dispatcher.register(
              ClientCommands.literal("drawless")
                  .then(ClientCommands.literal("profile").then(players)));
        });
  }

  private static int startCommand(
      Minecraft client, java.util.function.Consumer<Component> feedback, String label, Mode mode) {
    feedback.accept(start(client, label, mode));
    return 1;
  }

  public static Component start(Minecraft client, String label, Mode mode) {
    if (busy()
        || DisplayProbe.busy()
        || BenchmarkSeries.running()
        || RenderMetrics.getInstance().isEnabled())
      return Component.translatable("drawless.player_profile.busy");
    if (client.level == null || client.player == null)
      return Component.translatable("drawless.benchmark.enter_world");
    if (!label.matches("[A-Za-z0-9_-]{1,24}") || label.equals("cancel") || label.equals("status"))
      return Component.translatable("drawless.benchmark.invalid_label");
    session = new Session(client, label, mode);
    return Component.translatable(
        "drawless.player_profile.armed", mode.name().toLowerCase(Locale.ROOT));
  }

  public static boolean busy() {
    return session != null || saving;
  }

  public static Path lastSaved() {
    return lastSaved;
  }

  public static boolean stages() {
    return collecting() && session.mode != Mode.CONTROL;
  }

  public static boolean collecting() {
    return collecting && session != null && Thread.currentThread() == session.owner;
  }

  public static boolean attributed() {
    return collecting() && source != null;
  }

  public static boolean deep() {
    return attributed() && session.mode == Mode.DEEP;
  }

  public static Tag source() {
    return attributed() ? source : null;
  }

  public static Tag bind(Tag tag) {
    if (!collecting()) return null;
    Tag old = source;
    source = tag;
    return old;
  }

  public static long begin(String name) {
    return collecting() ? session.timings.begin(name) : 0;
  }

  public static void end(long token) {
    if (collecting()) session.timings.end(token);
  }

  public static void count(String name, long amount) {
    if (collecting() && (session.counters.containsKey(name) || session.counters.size() < 256))
      session.counters.merge(name, amount, Long::sum);
  }

  /** Called once per actual renderFrame, before extract, prepare, uploads and execution. */
  public static long frameBegin(Minecraft client) {
    Session s = session;
    if (s == null || Thread.currentThread() != s.owner) return 0;
    long now = System.nanoTime();
    if (s.pending != null) {
      s.pending.put("interval_ns", now - s.lastFrameAt);
      s.pending = null;
    }
    s.lastFrameAt = now;
    if (client.level != s.world || client.player == null) {
      finish(client, "world_changed");
      return 0;
    }
    if (s.measurementStart == 0) {
      if (now - s.requested > 60_000_000_000L) finish(client, "start_timeout");
      else if (client.gui.screen() == null
          && !client.isPaused()
          && client.getWindow().isFocused()) {
        s.originalInactivity = client.options.inactivityFpsLimit().get();
        client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        s.measurementStart = now + 2_000_000_000L;
      }
      return 0;
    }
    if (client.gui.screen() != null || client.isPaused() || !client.getWindow().isFocused()) {
      finish(client, "screen_or_focus_changed");
      return 0;
    }
    if (now < s.measurementStart) return 0;
    if (now - s.measurementStart >= 20_000_000_000L || s.frames.size() == MAX_FRAMES) {
      finish(client, s.frames.size() == MAX_FRAMES ? "sample_limit" : "complete");
      return 0;
    }
    if (s.observed++ % STRIDE != 0) return 0;
    clearFrame(s);
    collecting = true;
    s.timings.beginFrame();
    long before = s.timings.rootNanos();
    s.frameRootBefore = before;
    s.frameActors = 0;
    s.loadedPlayers = client.level.players().size();
    return s.timings.begin("frame");
  }

  public static void frameEnd(long token, boolean success) {
    Session s = session;
    if (s == null || token == 0 || Thread.currentThread() != s.owner) return;
    s.timings.end(token);
    boolean valid = s.timings.endFrame();
    var row = new LinkedHashMap<String, Object>();
    row.put("render_thread_ns", s.timings.rootNanos() - s.frameRootBefore);
    row.put("loaded_players", s.loadedPlayers);
    row.put("extracted_players", s.frameActors);
    row.put("submitted_players", s.submitted.cardinality());
    row.put("interval_ns", 0L);
    row.put("valid", valid && success);
    s.frames.add(row);
    s.pending = row;
    collecting = false;
    clearFrame(s);
    if (!valid || !success) finish(Minecraft.getInstance(), "interrupted_frame");
  }

  private static void clearFrame(Session s) {
    source = null;
    executing = 0;
    s.states.clear();
    s.actors.clear();
    s.submitted.clear();
    s.submits.clear();
    s.groups.clear();
    s.models.clear();
  }

  public static Tag extracted(Object entity, EntityRenderState state) {
    if (!collecting() || session.mode == Mode.CONTROL) return null;
    Tag tag = session.actors.get(entity);
    if (tag == null) {
      if (session.actors.size() == MAX_TAGS) {
        count("actor_tag_limit", 1);
        return null;
      }
      tag = new Tag(++session.frameActors, "body");
      session.actors.put(entity, tag);
    }
    if (session.states.size() < MAX_TAGS) session.states.put(state, tag);
    else count("state_tag_limit", 1);
    count("player_extractions", 1);
    return tag;
  }

  public static Tag state(EntityRenderState state) {
    return collecting() && session.mode != Mode.CONTROL ? session.states.get(state) : null;
  }

  public static void submitted(Tag tag) {
    if (attributed()) {
      session.submitted.set(tag.actor());
      count("player_submissions", 1);
    }
  }

  public static Tag layer(Object layer) {
    return attributed() ? new Tag(source.actor, layer.getClass().getSimpleName()) : null;
  }

  public static void tag(Object submit) {
    if (!attributed() || session.mode == Mode.CONTROL) return;
    if (session.submits.size() < MAX_TAGS) {
      session.submits.put(submit, source);
      count("tagged_submits", 1);
    } else count("submit_tag_limit", 1);
  }

  public static Tag submit(Object submit) {
    return collecting() && session.mode != Mode.CONTROL ? session.submits.get(submit) : null;
  }

  public static void model(ModelFeatureRenderer.Submit<?> submit) {
    if (!attributed()) return;
    var states = session.models.get(submit.model());
    if (states == null && session.models.size() < 512) {
      states = new IdentityHashMap<>();
      session.models.put(submit.model(), states);
    }
    if (states != null) {
      if (states.containsKey(submit.state())) count("repeated_model_state_preparations", 1);
      else if (states.size() < MAX_TAGS) states.put(submit.state(), Boolean.TRUE);
    }
    count("model_preparations", 1);
  }

  public static void groupUse(Object group) {
    if (!collecting() || session.mode == Mode.CONTROL) return;
    groupOrigins(group, attributed() ? 1 : 2);
  }

  /** Cached text consumers may emit glyphs without asking the group for a buffer again. */
  public static void nameTagGroup(Object group, List<?> submits) {
    if (!stages()) return;
    int origins = 0;
    for (Object submit : submits) origins |= session.submits.containsKey(submit) ? 1 : 2;
    if (origins != 0) groupOrigins(group, origins);
  }

  private static void groupOrigins(Object group, int origins) {
    if (session.groups.size() < MAX_TAGS || session.groups.containsKey(group))
      session.groups.merge(group, origins, (a, b) -> a | b);
    else count("group_tag_limit", 1);
  }

  public static int execute(Object group) {
    if (!collecting()) return -1;
    int previous = executing;
    executing = session.groups.getOrDefault(group, 0);
    return previous;
  }

  public static void restoreExecute(int value) {
    if (value >= 0 && collecting()) executing = value;
  }

  public static String executionKind() {
    return executing == 1 ? "players_only" : executing == 3 ? "mixed" : "unattributed";
  }

  public static String material(Object type) {
    String value = String.valueOf(type).toLowerCase(Locale.ROOT);
    return value.contains("glint") ? "glint" : "other_material";
  }

  public static Component status() {
    if (session != null)
      return Component.translatable("drawless.player_profile.active", session.label);
    if (saving) return Component.translatable("drawless.player_profile.saving");
    return lastSaved != null
        ? Component.translatable("drawless.player_profile.last", lastSaved.toString())
        : Component.translatable("drawless.player_profile.idle");
  }

  public static void finish(Minecraft client, String reason) {
    Session s = session;
    if (s == null || Thread.currentThread() != s.owner) return;
    session = null;
    collecting = false;
    clearFrame(s);
    if (s.originalInactivity != null) client.options.inactivityFpsLimit().set(s.originalInactivity);
    var data = new LinkedHashMap<String, Object>();
    double intervals =
        s.frames.stream().mapToDouble(f -> ((Number) f.get("interval_ns")).doubleValue()).sum();
    boolean matched =
        !s.frames.isEmpty()
            && s.frames.stream().allMatch(f -> ((Number) f.get("interval_ns")).longValue() > 0);
    data.put("schema_version", 2);
    data.put("coverage_revision", "scene-stages-and-player-name-tags");
    data.put("result", reason);
    data.put("label", s.label);
    data.put("mode", s.mode.name().toLowerCase(Locale.ROOT));
    data.put("sample_stride", STRIDE);
    data.put("requested_seconds", 20);
    data.put("warmup_seconds", 2);
    data.put("observed_frames", s.observed);
    data.put("sampled_frames", s.frames.size());
    data.put("java", System.getProperty("java.runtime.version"));
    data.put("gpu_timing", "unavailable");
    data.put(
        "views", "Combined renderFrame scope; separate shadow/GUI/mirror views are not labeled.");
    data.put("stages", s.timings.snapshot(s.frames.size(), matched ? intervals : 0));
    data.put("frames", List.copyOf(s.frames));
    data.put("counters", Map.copyOf(s.counters));
    data.put("measured_bookkeeping_ms", s.timings.bookkeepingNanos() / 1e6);
    data.put("timing_node_overflow", s.timings.overflow());
    data.put("settings_start", s.settings);
    data.put("settings_end", DrawLessConfig.snapshot());
    data.put("environment_start", s.environment);
    data.put("environment_end", environment(client));
    data.put(
        "afk_policy_original",
        s.originalInactivity == null ? null : s.originalInactivity.getSerializedName());
    data.put(
        "mods",
        FabricLoader.getInstance().getAllMods().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    m -> m.getMetadata().getId(),
                    m -> m.getMetadata().getVersion().getFriendlyString())));
    saving = true;
    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
    Path folder =
        client
            .gameDirectory
            .toPath()
            .resolve("drawless-reports/player-profile-" + stamp + "-" + s.label);
    CompletableFuture.runAsync(
            () -> {
              try {
                PlayerProfileReport.write(folder, data);
              } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
              }
            })
        .whenComplete(
            (unused, error) ->
                client.execute(
                    () -> {
                      saving = false;
                      if (error != null)
                        ru.wilyfox.drawless.DrawLess.LOGGER.warn(
                            "Could not save player profile {}", folder, error);
                      if (error == null) lastSaved = folder;
                      client
                          .gui
                          .hud
                          .getChat()
                          .addClientSystemMessage(
                              Component.translatable(
                                  error == null
                                      ? "drawless.player_profile.saved"
                                      : "drawless.player_profile.save_failed",
                                  folder.toString()));
                    }));
  }

  private static String environment(Minecraft c) {
    return "framebuffer="
        + c.getWindow().getWidth()
        + "x"
        + c.getWindow().getHeight()
        + "; vsync="
        + c.options.enableVsync().get()
        + "; fpsLimit="
        + c.options.framerateLimit().get()
        + "; camera="
        + c.options.getCameraType()
        + "; renderDistance="
        + c.options.getEffectiveRenderDistance()
        + "; fov="
        + c.options.fov().get()
        + "; resourcePacks="
        + c.options.resourcePacks
        + ru.wilyfox.drawless.compat.IrisShaders.environment();
  }

  private static final class Session {
    final ClientLevel world;
    final Thread owner = Thread.currentThread();
    final String label, settings, environment;
    final Mode mode;
    final long requested = System.nanoTime();
    final ProfileTimings timings = new ProfileTimings();
    final IdentityHashMap<EntityRenderState, Tag> states = new IdentityHashMap<>();
    final IdentityHashMap<Object, Tag> actors = new IdentityHashMap<>();
    final java.util.BitSet submitted = new java.util.BitSet();
    final IdentityHashMap<Object, Tag> submits = new IdentityHashMap<>();
    final IdentityHashMap<Object, Integer> groups = new IdentityHashMap<>();
    final IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> models =
        new IdentityHashMap<>();
    final Map<String, Long> counters = new LinkedHashMap<>();
    final List<Map<String, Object>> frames = new ArrayList<>();
    InactivityFpsLimit originalInactivity;
    Map<String, Object> pending;
    long measurementStart, observed, lastFrameAt, frameRootBefore;
    int frameActors, loadedPlayers;

    Session(Minecraft c, String label, Mode mode) {
      world = c.level;
      this.label = label;
      this.mode = mode;
      settings = DrawLessConfig.snapshot();
      environment = environment(c);
    }
  }
}
