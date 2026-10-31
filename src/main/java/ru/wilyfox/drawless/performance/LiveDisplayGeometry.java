package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import ru.wilyfox.drawless.DrawLess;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;

/**
 * Experimental indexed vertex pulling. Local meshes are shared ({@link ItemMeshes}); only current
 * vanilla poses/light/tints are streamed. Encoded indices select a mesh vertex and an instance, so
 * a material needs one draw, without a draw call per bone, compute shaders, or decimating
 * animation/ticks.
 */
public final class LiveDisplayGeometry {
  private static final int MAX_PARTS = 4096;
  private static final long MAX_INDEX_BYTES = 16L * 1024 * 1024;
  private static final String LABEL = "DrawLess live Item Displays";
  private static final boolean SODIUM = RenderModSupport.SODIUM;
  private static final IdentityHashMap<EntityRenderState, Display.ItemDisplay> extracted =
      new IdentityHashMap<>();
  private static final IdentityHashMap<Display.ItemDisplay, Part> parts = new IdentityHashMap<>();
  private static final IdentityHashMap<RenderType, TypeBatches> batches = new IdentityHashMap<>();
  private static final ArrayList<RetainedBatch<Instance>> activeBatches = new ArrayList<>();
  private static final Set<EntityRenderState> accepted =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private static final Set<TextureAtlasSprite> animated =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private static final Capture capture = new Capture();
  private static PoseStack pose = new PoseStack();
  private static final int[] reasons = new int[Reason.values().length];
  private static ClientLevel world;
  private static boolean registered, enabled, checked, all;
  private static ExtremeDisplayMode liveMode;
  private static String blocked = "off";
  private static int frame, generation;
  private static long indexRebuilds, instanceUploadedBytes;
  private static long instances, quads, draws, fallbacks, indexBytes;
  private static long captureNanos, uploadNanos, drawNanos;
  private static long framesTotal, captureTotal, uploadTotal, drawTotal;
  private static long frameNow;
  private static long visibleVertices, interpolatingParts;
  private static int visibleMeshCount;
  private static long poseReused, poseSubmitted, poseReusedTotal, poseSubmittedTotal;
  private static long entityLoopStarted, entityLoopNanos, entityLoopTotal, entityLoopFrames;

  private LiveDisplayGeometry() {}

  public static void register() {
    if (registered) return;
    registered = true;
    ItemMeshes.register();
    ClientEntityEvents.ENTITY_UNLOAD.register(
        (entity, level) -> {
          parts.remove(entity);
          DisplayExtractionCache.unload(entity);
        });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
    LevelRenderEvents.AFTER_SOLID_FEATURES.register(LiveDisplayGeometry::draw);
  }

  public static boolean requested() {
    return DrawLessConfig.get().enabled && DrawLessConfig.get().extremeDisplayMode.liveCache();
  }

  public static boolean ready() {
    return enabled;
  }

  public static void clear() {
    for (var type : batches.values()) for (var batch : type.chunks) batch.close();
    batches.clear();
    activeBatches.clear();
    accepted.clear();
    extracted.clear();
    parts.clear();
    animated.clear();
    capture.reset(null);
    world = null;
    enabled = checked = false;
    blocked = "off";
    liveMode = null;
    DisplayExtractionCache.clear();
    frame = 0;
    // Per-mesh consumer state of shared meshes refers to the batches closed above.
    generation++;
    indexRebuilds = instanceUploadedBytes = 0;
    instances = quads = draws = fallbacks = indexBytes = captureNanos = uploadNanos = drawNanos = 0;
    framesTotal = captureTotal = uploadTotal = drawTotal = 0;
    Arrays.fill(reasons, 0);
    visibleVertices = interpolatingParts = 0;
    visibleMeshCount = 0;
    poseReused = poseSubmitted = poseReusedTotal = poseSubmittedTotal = 0;
    entityLoopStarted = entityLoopNanos = entityLoopTotal = entityLoopFrames = 0;
    pose = new PoseStack();
  }

  /** GPU pipeline objects are device-owned: retain bounded variants across mode/world changes. */
  public static void reload() {
    clear();
    ItemMeshes.reload();
  }

  public static void beginFrame(Minecraft client) {
    // Called every level frame, so it is also the shared item store's frame hook for all consumers.
    ItemMeshes.beginFrame(client);
    if (!requested() || client.level == null) {
      if (world != null) clear();
      return;
    }
    if (world != client.level
        || all != DrawLessConfig.get().extremeAllItemDisplays
        || liveMode != DrawLessConfig.get().extremeDisplayMode) {
      clear();
      world = client.level;
      all = DrawLessConfig.get().extremeAllItemDisplays;
      liveMode = DrawLessConfig.get().extremeDisplayMode;
    }
    extracted.clear();
    accepted.clear();
    animated.clear();
    activeBatches.clear();
    visibleVertices = interpolatingParts = 0;
    visibleMeshCount = 0;
    poseReused = poseSubmitted = 0;
    for (var type : batches.values()) {
      type.count = 0;
      for (var batch : type.chunks) batch.used = 0;
    }
    Arrays.fill(reasons, 0);
    instances = quads = fallbacks = indexBytes = captureNanos = uploadNanos = 0;
    frame++;
    frameNow = System.nanoTime();
    enabled = false;
    if (!DrawLessConfig.get().displayModelBudget) return;
    if (!checked) {
      checked = true;
      blocked = ItemMeshes.prepare(client);
    }
    enabled = blocked.isEmpty();
    // Entity-local records must not accumulate across hidden/unloaded actors.
    if ((frame & 63) == 0)
      parts.entrySet().removeIf(e -> e.getKey().isRemoved() || frame - e.getValue().seen > 120);
  }

  public static void recordState(Entity entity, EntityRenderState state) {
    if (enabled
        && entity instanceof Display.ItemDisplay item
        && ExtremeDisplayPerformance.target(item)) extracted.put(state, item);
  }

  /**
   * One timing pair per frame, including vanilla visibility and state extraction, before capture.
   */
  public static void startEntityLoop() {
    if (enabled) entityLoopStarted = System.nanoTime();
  }

  public static void finishEntityLoop() {
    if (entityLoopStarted == 0) return;
    entityLoopNanos = System.nanoTime() - entityLoopStarted;
    entityLoopStarted = 0;
    entityLoopTotal += entityLoopNanos;
    entityLoopFrames++;
    DisplayCostProbe.global("Vanilla entity extraction loop (capture excluded)", entityLoopNanos);
  }

  public static void finishFrame(Minecraft client, LevelRenderState state) {
    if (!enabled) return;
    long start = System.nanoTime();
    boolean sample = DisplayCostProbe.frame();
    long eligibilityNanos = 0, poseNanos = 0, groupNanos = 0;
    try (var ignored = RenderMetrics.getInstance().scope("render/liveDisplay/capture")) {
      for (var renderState : state.entityRenderStates) {
        var entity = extracted.get(renderState);
        if (entity == null || !(renderState instanceof ItemDisplayEntityRenderState item)) continue;
        long stage = sample ? System.nanoTime() : 0;
        var reason = eligible(entity, item);
        if (sample) eligibilityNanos += System.nanoTime() - stage;
        if (reason == Reason.READY) {
          var part = parts.get(entity);
          if (part == null && parts.size() < MAX_PARTS) {
            part = new Part();
            parts.put(entity, part);
          }
          if (part == null) reason = Reason.CAPACITY;
          else {
            stage = sample ? System.nanoTime() : 0;
            part.seen = frame;
            if (part.matches(item)) {
              reason = part.reason;
              poseReused++;
              poseReusedTotal++;
            } else {
              capture.reset(part);
              // Capture around the entity origin. Camera/actor/passenger translation is
              // applied below from current doubles; it must not invalidate a local pose.
              var offset = item.passengerOffset;
              client
                  .getEntityRenderDispatcher()
                  .submit(
                      item,
                      state.cameraRenderState,
                      offset == null ? 0 : -offset.x,
                      offset == null ? 0 : -offset.y,
                      offset == null ? 0 : -offset.z,
                      pose,
                      capture);
              reason = capture.reason;
              if (reason == Reason.READY && !capture.getSubmitsPerOrder().isEmpty())
                reason = Reason.EXTRA;
              if (reason == Reason.READY && part.used == 0) reason = Reason.EMPTY;
              part.remember(
                  item,
                  reason,
                  client.getEntityRenderDispatcher().getRenderer(item).getClass()
                      == DisplayRenderer.ItemDisplayRenderer.class);
              poseSubmitted++;
              poseSubmittedTotal++;
            }
            if (sample) poseNanos += System.nanoTime() - stage;
            if (reason == Reason.READY) {
              stage = sample ? System.nanoTime() : 0;
              if (indexBytes + part.indexBytes > MAX_INDEX_BYTES || instances + part.used > 8192)
                reason = Reason.CAPACITY;
              else {
                indexBytes += part.indexBytes;
                instances += part.used;
                var offset = item.passengerOffset;
                var camera = state.cameraRenderState.pos;
                float x = (float) (item.x - camera.x + (offset == null ? 0 : offset.x));
                float y = (float) (item.y - camera.y + (offset == null ? 0 : offset.y));
                float z = (float) (item.z - camera.z + (offset == null ? 0 : offset.z));
                for (int i = 0; i < part.used; i++) {
                  var instance = part.instances.get(i);
                  instance.position(x, y, z, item.lightCoords);
                  add(instance);
                }
                accepted.add(renderState);
                if (item.interpolationProgress < 1) interpolatingParts++;
              }
              if (sample) groupNanos += System.nanoTime() - stage;
            }
          }
        }
        reasons[reason.ordinal()]++;
        if (reason != Reason.READY) fallbacks++;
        if (sample) DisplayCostProbe.admission(DisplayCostProbe.source(renderState), reason.label);
      }
    } catch (RuntimeException failure) {
      fail(failure);
    } finally {
      captureNanos = System.nanoTime() - start;
      captureTotal += captureNanos;
      DisplayCostProbe.global("Live geometry capture", captureNanos);
      if (sample) {
        DisplayCostProbe.global("Live eligibility (capture component)", eligibilityNanos);
        DisplayCostProbe.global("Live local pose (capture component)", poseNanos);
        DisplayCostProbe.global("Live positioning/grouping (capture component)", groupNanos);
      }
      capture.reset(null);
    }
    if (!enabled) {
      extracted.clear();
      return;
    }
    start = System.nanoTime();
    try (var ignored = RenderMetrics.getInstance().scope("render/liveDisplay/upload")) {
      ItemMeshes.upload();
      for (var batch : activeBatches) {
        if (batch.prepare()) indexRebuilds++;
        instanceUploadedBytes += (long) batch.used * INSTANCE_BYTES;
      }
      // Commit only after every pipeline and buffer is ready. Failure keeps ALL vanilla states.
      if (!accepted.isEmpty()) {
        state.entityRenderStates.removeIf(accepted::contains);
        if (DisplayExtractionCache.active())
          for (var acceptedState : accepted)
            DisplayExtractionCache.accept(
                extracted.get(acceptedState), (ItemDisplayEntityRenderState) acceptedState);
      }
    } catch (RuntimeException failure) {
      fail(failure);
    } finally {
      uploadNanos = System.nanoTime() - start;
      uploadTotal += uploadNanos;
      framesTotal++;
      DisplayCostProbe.global("Live geometry upload", uploadNanos);
      extracted.clear();
    }
  }

  private static Reason eligible(Display.ItemDisplay entity, ItemDisplayEntityRenderState state) {
    if (state.renderState == null || state.item.isEmpty()) return Reason.EMPTY;
    if (state.appearsGlowing()
        || state.displayFireAnimation
        || state.nameTag != null
        || state.scoreText != null
        || !state.shadowPieces.isEmpty()
        || state.leashStates != null && !state.leashStates.isEmpty()) return Reason.EXTRA;
    if (state.outlineColor != 0) return Reason.FOIL;
    return DisplayModelBudget.hasSafeLayers(entity, state, frameNow)
        ? Reason.READY
        : Reason.DYNAMIC;
  }

  private static void add(Instance instance) {
    var mesh = instance.item;
    var state = MeshState.of(mesh);
    var type = state.batches;
    int chunk = type.count++ / MAX_INSTANCES;
    if (chunk == type.chunks.size())
      type.chunks.add(new RetainedBatch<>(type.type, "DrawLess live Item Display", 1024));
    var batch = type.chunks.get(chunk);
    if (batch.used == 0) activeBatches.add(batch);
    batch.add(instance);
    quads += mesh.vertexCount / 4;
    if (state.visibleFrame != frame) {
      state.visibleFrame = frame;
      visibleMeshCount++;
      visibleVertices += mesh.vertexCount;
      for (var sprite : mesh.sprites) animated.add(sprite);
    }
  }

  private static Reason reason(ItemMeshes.Status status) {
    return switch (status) {
      case READY -> Reason.READY;
      case TRANSPARENT -> Reason.TRANSPARENT;
      case UNSUPPORTED -> Reason.UNSUPPORTED;
      case WARMING -> Reason.WARMING;
      case CAPACITY -> Reason.CAPACITY;
    };
  }

  private static void draw(LevelRenderContext context) {
    if (!enabled || !requested() || world != Minecraft.getInstance().level) return;
    long start = System.nanoTime();
    draws = 0;
    var matrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
    matrix.pushMatrix();
    try (var ignored = RenderMetrics.getInstance().scope("render/liveDisplay/draw")) {
      matrix.set(context.levelState().cameraRenderState.viewRotationMatrix);
      if (SODIUM) for (var sprite : animated) SodiumSprites.mark(sprite);
      draws = RetainedPass.draw(LABEL, activeBatches, ItemMeshes.pipelines(), ItemMeshes.buffer());
    } catch (RuntimeException failure) {
      fail(failure);
    } finally {
      matrix.popMatrix();
      drawNanos = System.nanoTime() - start;
      drawTotal += drawNanos;
      DisplayCostProbe.global("Live geometry draw CPU", drawNanos);
    }
    var profiler = RenderMetrics.getInstance();
    if (profiler.isEnabled()) {
      profiler.incrementCounter("liveDisplay/instances", instances);
      profiler.incrementCounter("liveDisplay/draws", draws);
      profiler.incrementCounter("liveDisplay/vanillaFallback", fallbacks);
      profiler.incrementCounter("liveDisplay/poseReused", poseReused);
      profiler.incrementCounter("liveDisplay/poseSubmitted", poseSubmitted);
    }
  }

  private static void fail(RuntimeException error) {
    if (!blocked.equals("runtime-fallback"))
      DrawLess.LOGGER.warn("Live Item Display geometry disabled; using vanilla rendering", error);
    enabled = false;
    blocked = "runtime-fallback";
    activeBatches.clear();
  }

  public static String diagnostic() {
    if (!requested()) return "off";
    var reasonsText = new StringJoiner(",");
    for (var r : Reason.values())
      if (reasons[r.ordinal()] > 0) reasonsText.add(r.label + ":" + reasons[r.ordinal()]);
    return String.format(
            Locale.ROOT,
            "%s; status=%s; meshes=%d; quadSets=%d; aliases=%d; parts=%d; interpolatingParts=%d; visibleMeshes=%d; visibleVertices=%d; instances=%d; quads=%d; draws=%d; fallback=%d; localBytes=%d; indexBytes=%d; meshBuilds=%d; indexRebuilds=%d; geometryUploads=%d; geometryUploadedBytes=%d; instanceUploadedBytes=%d; animatedSprites=%d; pausedTicksTotal=0; poseReused=%d; poseSubmitted=%d; poseReusedTotal=%d; poseSubmittedTotal=%d; capture=%.3fms; upload=%.3fms; drawCpu=%.3fms; framesTotal=%d; captureTotalMs=%.3f; uploadTotalMs=%.3f; drawTotalMs=%.3f; admission=%s",
            liveMode == null ? DrawLessConfig.get().extremeDisplayMode : liveMode,
            enabled ? "on" : !DrawLessConfig.get().displayModelBudget ? "layer-cache-off" : blocked,
            ItemMeshes.meshCount(),
            ItemMeshes.keys(),
            ItemMeshes.aliases(),
            accepted.size(),
            interpolatingParts,
            visibleMeshCount,
            visibleVertices,
            instances,
            quads,
            draws,
            fallbacks,
            ItemMeshes.localBytes(),
            indexBytes,
            ItemMeshes.meshBuilds(),
            indexRebuilds,
            ItemMeshes.geometryUploads(),
            ItemMeshes.geometryUploadedBytes(),
            instanceUploadedBytes,
            animated.size(),
            poseReused,
            poseSubmitted,
            poseReusedTotal,
            poseSubmittedTotal,
            captureNanos / 1e6,
            uploadNanos / 1e6,
            drawNanos / 1e6,
            framesTotal,
            captureTotal / 1e6,
            uploadTotal / 1e6,
            drawTotal / 1e6,
            reasonsText)
        + String.format(
            Locale.ROOT,
            "; entityLoop=%.3fms; entityLoopTotalMs=%.3f; entityLoopFramesTotal=%d",
            entityLoopNanos / 1e6,
            entityLoopTotal / 1e6,
            entityLoopFrames)
        + "; "
        + DisplayExtractionCache.diagnostic();
  }

  private enum Reason {
    READY("live-cached"),
    EMPTY("live-empty"),
    DYNAMIC("live-unsafe-model"),
    EXTRA("live-extra-visual"),
    FOIL("live-foil-outline"),
    TRANSPARENT("live-transparency"),
    UNSUPPORTED("live-pipeline"),
    WARMING("live-warming"),
    CAPACITY("live-capacity");
    final String label;

    Reason(String label) {
      this.label = label;
    }
  }

  /** This consumer's state for one shared mesh; stale after {@link #clear()}. */
  private static final class MeshState {
    final int generation = LiveDisplayGeometry.generation;
    final TypeBatches batches;
    int visibleFrame = -1;

    private MeshState(ItemMeshes.Mesh mesh) {
      batches = LiveDisplayGeometry.batches.computeIfAbsent(mesh.type, TypeBatches::new);
    }

    static MeshState of(ItemMeshes.Mesh mesh) {
      if (mesh.live instanceof MeshState state
          && state.generation == LiveDisplayGeometry.generation) return state;
      var state = new MeshState(mesh);
      mesh.live = state;
      return state;
    }
  }

  private static final class Part {
    final ArrayList<Instance> instances = new ArrayList<>();
    int used, seen;
    long indexBytes;
    ItemStackRenderState layers;
    Display.RenderState render;
    float interpolation, yRot, xRot, cameraXRot, cameraYRot;
    Reason reason;
    boolean replayable;

    boolean matches(ItemDisplayEntityRenderState state) {
      if (!replayable || layers != state.item) return false;
      // These rejections depend only on immutable item layers, not on the display pose.
      if (reason == Reason.TRANSPARENT
          || reason == Reason.UNSUPPORTED
          || reason == Reason.FOIL
          || reason == Reason.EMPTY) return true;
      if (reason != Reason.READY
          || render != state.renderState
          || interpolation != state.interpolationProgress
          || yRot != state.entityYRot
          || xRot != state.entityXRot) return false;
      return switch (render.billboardConstraints()) {
        case FIXED -> true;
        case HORIZONTAL -> cameraXRot == state.cameraXRot;
        case VERTICAL -> cameraYRot == state.cameraYRot;
        case CENTER -> cameraXRot == state.cameraXRot && cameraYRot == state.cameraYRot;
      };
    }

    void remember(ItemDisplayEntityRenderState state, Reason reason, boolean replayable) {
      layers = state.item;
      render = state.renderState;
      interpolation = state.interpolationProgress;
      yRot = state.entityYRot;
      xRot = state.entityXRot;
      cameraXRot = state.cameraXRot;
      cameraYRot = state.cameraYRot;
      this.reason = reason;
      this.replayable = replayable;
      indexBytes = 0;
      if (reason == Reason.READY)
        for (int i = 0; i < used; i++)
          indexBytes += (long) instances.get(i).item.vertexCount / 4 * 6 * 4;
    }
  }

  private static final class Instance extends RetainedInstance {
    ItemMeshes.Mesh item;
    float localX, localY, localZ;

    void set(ItemMeshes.Mesh mesh, PoseStack.Pose pose, int color, int light, int overlay) {
      item = mesh;
      super.set(
          mesh,
          pose,
          color,
          LightCoordsUtil.lightCoordsWithEmission(light, mesh.emission),
          overlay);
      localX = transform.m30();
      localY = transform.m31();
      localZ = transform.m32();
    }

    void position(float x, float y, float z, int light) {
      // Always derive from the original local translation; never accumulate camera deltas.
      transform.m30(localX + x).m31(localY + y).m32(localZ + z);
      this.light = LightCoordsUtil.lightCoordsWithEmission(light, item.emission);
    }
  }

  private static final class Capture extends SubmitNodeStorage {
    Part part;
    Reason reason = Reason.READY;

    void reset(Part part) {
      this.part = part;
      reason = Reason.READY;
      getSubmitsPerOrder().clear();
      if (part != null) part.used = 0;
    }

    @Override
    public void submitItem(
        PoseStack pose,
        ItemDisplayContext context,
        int light,
        int overlay,
        int outline,
        int[] tints,
        List<BakedQuad> quads,
        ItemStackRenderState.FoilType foil) {
      if (reason != Reason.READY) return;
      if (foil != ItemStackRenderState.FoilType.NONE || outline != 0) {
        reason = Reason.FOIL;
        return;
      }
      if (!pose.last().pose().isFinite() || !pose.last().normal().isFinite()) {
        reason = Reason.EXTRA;
        return;
      }
      // Accepted displays carry immutable layers published by DisplayModelBudget.
      var geometry = ItemMeshes.forImmutable(quads);
      if (geometry.status() != ItemMeshes.Status.READY) {
        reason = reason(geometry.status());
        return;
      }
      if (part.used + geometry.meshes().size() > 128) {
        reason = Reason.CAPACITY;
        return;
      }
      for (var mesh : geometry.meshes()) {
        if (part.used == part.instances.size()) part.instances.add(new Instance());
        int color = mesh.tint >= 0 && mesh.tint < tints.length ? tints[mesh.tint] : -1;
        part.instances.get(part.used++).set(mesh, pose.last(), color, light, overlay);
      }
    }
  }

  private static final class TypeBatches {
    final RenderType type;
    final ArrayList<RetainedBatch<Instance>> chunks = new ArrayList<>();
    int count;

    TypeBatches(RenderType type) {
      this.type = type;
    }
  }

  private static final class SodiumSprites {
    static void mark(TextureAtlasSprite sprite) {
      net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(sprite);
    }
  }
}
