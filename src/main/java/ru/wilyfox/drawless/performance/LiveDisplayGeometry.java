package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.culling.Frustum;
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
import net.minecraft.world.phys.AABB;
import ru.wilyfox.drawless.DrawLess;
import ru.wilyfox.drawless.compat.IrisShaders;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;
import ru.wilyfox.drawless.observe.Observation;
import ru.wilyfox.drawless.util.ClientDisconnect;

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
  private static int frame, generation, storeGeneration;
  private static long indexRebuilds, instanceUploadedBytes;
  private static long instances, quads, draws, fallbacks, indexBytes;
  private static long captureNanos, uploadNanos, drawNanos;
  private static long framesTotal, captureTotal, uploadTotal, drawTotal;
  private static long frameNow;
  private static long visibleVertices, interpolatingParts;
  private static int visibleMeshCount;
  private static long poseReused, poseSubmitted, poseReusedTotal, poseSubmittedTotal;
  private static long entityLoopStarted, entityLoopNanos, entityLoopTotal, entityLoopFrames;
  private static final long MOVING_REFRESH = 50_000_000L, STABLE_REFRESH = 1_000_000_000L;
  private static final double MOVING_MARGIN = 1, STABLE_MARGIN = 0.5;
  private static int culled;
  private static long culledTotal;

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
    ClientDisconnect.subscribe(client -> clear());
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
    culled = 0;
    culledTotal = 0;
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
    // Cached parts hold instances of meshes that a compaction or clear has replaced.
    if (storeGeneration != ItemMeshes.generation()) {
      storeGeneration = ItemMeshes.generation();
      parts.clear();
    }
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
    instances = quads = fallbacks = indexBytes = captureNanos = uploadNanos = culled = 0;
    frame++;
    frameNow = System.nanoTime();
    enabled = false;
    if (!DrawLessConfig.get().displayModelBudget) return;
    if (!checked) {
      checked = true;
      blocked = ItemMeshes.prepare(client);
    }
    enabled = blocked.isEmpty() && !IrisShaders.packInUse();
    // Entity-local records must not accumulate across hidden/unloaded actors.
    if ((frame & 63) == 0)
      parts.entrySet().removeIf(e -> e.getKey().isRemoved() || frame - e.getValue().seen > 120);
  }

  /**
   * True when a part's last pose lies outside this frame's view, so nothing of it is needed now.
   * Only parts already on the GPU qualify; a changed display, or bounds not refreshed for a while,
   * take the ordinary path. Ticks and animation are not affected.
   */
  public static boolean outsideView(Entity entity, Frustum frustum) {
    if (!enabled
        || !DrawLessConfig.get().liveViewCulling
        || !(entity instanceof Display.ItemDisplay item)) return false;
    var part = parts.get(item);
    if (part == null
        || part.reason != Reason.READY
        || part.used == 0
        || part.render != item.renderState()
        || frameNow - part.boundsChecked > (part.moving ? MOVING_REFRESH : STABLE_REFRESH))
      return false;
    double margin = part.moving ? MOVING_MARGIN : STABLE_MARGIN;
    double x = item.getX(), y = item.getY(), z = item.getZ();
    double mx = margin + Math.abs(x - item.xo);
    double my = margin + Math.abs(y - item.yo);
    double mz = margin + Math.abs(z - item.zo);
    if (frustum.isVisible(
        new AABB(
            x + part.minX - mx,
            y + part.minY - my,
            z + part.minZ - mz,
            x + part.maxX + mx,
            y + part.maxY + my,
            z + part.maxZ + mz))) return false;
    part.seen = frame;
    culled++;
    culledTotal++;
    return true;
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
    Observation.get().global("Vanilla entity extraction loop (capture excluded)", entityLoopNanos);
  }

  public static void finishFrame(Minecraft client, LevelRenderState state) {
    if (!enabled) return;
    long start = System.nanoTime();
    boolean sample = Observation.get().sampling();
    long eligibilityNanos = 0, poseNanos = 0, groupNanos = 0;
    try (var ignored = Observation.get().scope("render/liveDisplay/capture")) {
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
            part.boundsChecked = frameNow;
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
        if (sample)
          Observation.get().admission(Observation.get().source(renderState), reason.label);
      }
    } catch (RuntimeException failure) {
      fail(failure);
    } finally {
      captureNanos = System.nanoTime() - start;
      captureTotal += captureNanos;
      Observation.get().global("Live geometry capture", captureNanos);
      if (sample) {
        Observation.get().global("Live eligibility (capture component)", eligibilityNanos);
        Observation.get().global("Live local pose (capture component)", poseNanos);
        Observation.get().global("Live positioning/grouping (capture component)", groupNanos);
      }
      capture.reset(null);
    }
    if (!enabled) {
      extracted.clear();
      return;
    }
    start = System.nanoTime();
    try (var ignored = Observation.get().scope("render/liveDisplay/upload")) {
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
      Observation.get().global("Live geometry upload", uploadNanos);
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
    try (var ignored = Observation.get().scope("render/liveDisplay/draw")) {
      matrix.set(context.levelState().cameraRenderState.viewRotationMatrix);
      if (SODIUM) for (var sprite : animated) SodiumSprites.mark(sprite);
      draws = RetainedPass.draw(LABEL, activeBatches, ItemMeshes.pipelines(), ItemMeshes.buffer());
    } catch (RuntimeException failure) {
      fail(failure);
    } finally {
      matrix.popMatrix();
      drawNanos = System.nanoTime() - start;
      drawTotal += drawNanos;
      Observation.get().global("Live geometry draw CPU", drawNanos);
    }
    var profiler = Observation.get();
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
            "%s; status=%s; meshes=%d; quadSets=%d; aliases=%d; parts=%d; interpolatingParts=%d; visibleMeshes=%d; visibleVertices=%d; instances=%d; culled=%d; culledTotal=%d; quads=%d; draws=%d; fallback=%d; localBytes=%d; indexBytes=%d; meshBuilds=%d; indexRebuilds=%d; geometryUploads=%d; geometryUploadedBytes=%d; instanceUploadedBytes=%d; animatedSprites=%d; poseReused=%d; poseSubmitted=%d; poseReusedTotal=%d; poseSubmittedTotal=%d; capture=%.3fms; upload=%.3fms; drawCpu=%.3fms; framesTotal=%d; captureTotalMs=%.3f; uploadTotalMs=%.3f; drawTotalMs=%.3f; admission=%s",
            liveMode == null ? DrawLessConfig.get().extremeDisplayMode : liveMode,
            enabled
                ? "on"
                : !DrawLessConfig.get().displayModelBudget
                    ? "layer-cache-off"
                    : blocked.isEmpty() && IrisShaders.packInUse() ? "shader-pack" : blocked,
            ItemMeshes.meshCount(),
            ItemMeshes.keys(),
            ItemMeshes.aliases(),
            accepted.size(),
            interpolatingParts,
            visibleMeshCount,
            visibleVertices,
            instances,
            culled,
            culledTotal,
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
        + ItemMeshes.diagnostic()
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

    /** Bounds around the entity origin at the last pose, and when they were last confirmed. */
    float minX, minY, minZ, maxX, maxY, maxZ;

    long boundsChecked;
    boolean moving;

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
      if (reason == Reason.READY) {
        for (int i = 0; i < used; i++)
          indexBytes += (long) instances.get(i).item.vertexCount / 4 * 6 * 4;
        measure(state);
      }
    }

    private void measure(ItemDisplayEntityRenderState state) {
      moving = state.interpolationProgress < 1;
      minX = minY = minZ = Float.MAX_VALUE;
      maxX = maxY = maxZ = -Float.MAX_VALUE;
      for (int i = 0; i < used; i++) {
        var instance = instances.get(i);
        var bounds = instance.item.bounds;
        var m = instance.transform;
        for (int corner = 0; corner < 8; corner++) {
          float cx = bounds[(corner & 1) == 0 ? 0 : 3];
          float cy = bounds[(corner & 2) == 0 ? 1 : 4];
          float cz = bounds[(corner & 4) == 0 ? 2 : 5];
          float x = m.m00() * cx + m.m10() * cy + m.m20() * cz + instance.localX;
          float y = m.m01() * cx + m.m11() * cy + m.m21() * cz + instance.localY;
          float z = m.m02() * cx + m.m12() * cy + m.m22() * cz + instance.localZ;
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          minZ = Math.min(minZ, z);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
          maxZ = Math.max(maxZ, z);
        }
      }
      // A billboard turns with the camera, which may move while the part is culled.
      if (state.renderState.billboardConstraints() != Display.BillboardConstraints.FIXED) {
        float radius =
            Math.max(
                Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(
                    Math.max(Math.abs(minY), Math.abs(maxY)),
                    Math.max(Math.abs(minZ), Math.abs(maxZ))));
        radius *= (float) Math.sqrt(3);
        minX = minY = minZ = -radius;
        maxX = maxY = maxZ = radius;
      }
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
