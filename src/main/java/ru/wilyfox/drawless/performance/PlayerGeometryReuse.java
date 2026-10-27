package ru.wilyfox.drawless.performance;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.IdentityHashMap;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.DisplayProbe;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;
import ru.wilyfox.drawless.mixin.PlayerGeometryPoseAccessor;

/**
 * Single owner for armor provenance, resource topology and bounded frame-local transformed
 * vertices.
 */
public final class PlayerGeometryReuse {
  private static final FrameGeometryCache CACHE = new FrameGeometryCache();
  private static final IdentityHashMap<ModelPart.Cube, CubeTopology> SHAPES =
      new IdentityHashMap<>();
  private static Object world;
  private static Object prepareState;
  private static int prepareHash;
  private static Thread owner;
  private static boolean active, enabled, measuring, timing, encoding;
  private static int armorDepth, geometryDepth, lastEntries;
  private static long frames,
      enabledFrames,
      models,
      cubes,
      requests,
      hits,
      misses,
      transformedPositions,
      replayedVertices,
      timedFrames,
      timedModels,
      timedCubes,
      timedHits;
  private static long unsupported, capacityFallbacks, topologyChanges, invalidations;
  private static long lookupNanos, transformNanos, emitNanos, ordinaryNanos, maintenanceNanos;

  private PlayerGeometryReuse() {}

  public static void register() {
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          invalidate();
          world = null;
        });
  }

  public static void beginFrame(Minecraft client) {
    boolean previousEnabled = enabled;
    if (world != client.level) {
      invalidate();
      world = client.level;
    }
    var config = DrawLessConfig.get();
    enabled = config.enabled && config.playerGeometryReuse;
    if (!enabled && previousEnabled) invalidate();
    measuring = DisplayProbe.running() || RenderMetrics.getInstance().isEnabled();
    active = client.level != null && (enabled || measuring);
    owner = active ? Thread.currentThread() : null;
    armorDepth = geometryDepth = 0;
    prepareState = null;
    if (active) {
      frames++;
      if (enabled) enabledFrames++;
      timing = measuring && frames % 20 == 1;
      if (timing) timedFrames++;
    }
  }

  public static void endFrame() {
    if (!active) return;
    long started = timing ? System.nanoTime() : 0;
    lastEntries = CACHE.used();
    CACHE.clearFrame();
    active = false;
    armorDepth = geometryDepth = 0;
    prepareState = null;
    owner = null;
    if (started != 0) maintenanceNanos += System.nanoTime() - started;
  }

  public static void invalidate() {
    CACHE.release();
    SHAPES.clear();
    active = encoding = timing = false;
    armorDepth = geometryDepth = lastEntries = 0;
    prepareState = null;
    owner = null;
    invalidations++;
  }

  private static boolean owned() {
    return active && Thread.currentThread() == owner;
  }

  public static int armor(boolean player) {
    if (!owned()) return -1;
    int previous = armorDepth;
    armorDepth = player ? armorDepth + 1 : 0;
    return previous;
  }

  public static void restoreArmor(int depth) {
    if (depth >= 0 && owned()) armorDepth = depth;
  }

  public static boolean tagArmor() {
    return owned() && armorDepth != 0;
  }

  public static Object context() {
    return owned() ? prepareState : null;
  }

  public static int prepare(boolean playerArmor, Object state) {
    if (!owned()) return -1;
    int previous = geometryDepth;
    geometryDepth = playerArmor ? geometryDepth + 1 : 0;
    prepareState = playerArmor ? state : null;
    prepareHash = System.identityHashCode(prepareState);
    if (playerArmor) {
      models++;
      if (timing) timedModels++;
    }
    return previous;
  }

  public static void restorePrepare(int depth, Object state) {
    if (depth >= 0 && owned()) {
      geometryDepth = depth;
      prepareState = state;
      prepareHash = System.identityHashCode(state);
    }
  }

  public static boolean geometry() {
    return owned() && geometryDepth != 0;
  }

  public static void compile(
      ModelPart.Cube cube,
      PoseStack.Pose pose,
      VertexConsumer destination,
      int light,
      int overlay,
      int tint,
      Operation<Void> original) {
    cubes++;
    if (timing) timedCubes++;
    if (!enabled || encoding) {
      ordinary(pose, destination, light, overlay, tint, original);
      return;
    }
    requests++;
    long started = timing ? System.nanoTime() : 0;
    CubeTopology shape = SHAPES.get(cube);
    if (shape == null || !shape.matches(cube)) {
      if (shape != null) topologyChanges++;
      if (shape == null && SHAPES.size() >= 512) shape = null;
      else {
        shape = CubeTopology.capture(cube);
        if (shape != null) SHAPES.put(cube, shape);
      }
    }
    var entry =
        shape == null
            ? null
            : CACHE.acquireWithHash(
                shape,
                prepareState,
                shape.lookupHash + prepareHash,
                pose.pose(),
                pose.normal(),
                ((PlayerGeometryPoseAccessor) (Object) pose).drawless$trustedNormals(),
                shape.floats);
    if (started != 0) lookupNanos += System.nanoTime() - started;
    if (entry == null) {
      if (shape == null) unsupported++;
      else capacityFallbacks++;
      ordinary(pose, destination, light, overlay, tint, original);
    } else {
      encoding = true;
      try {
        if (entry.ready) {
          hits++;
          if (timing) timedHits++;
          replayedVertices += shape.count;
        } else {
          misses++;
          started = timing ? System.nanoTime() : 0;
          shape.transform(entry, pose);
          transformedPositions += shape.uniquePositions;
          if (started != 0) transformNanos += System.nanoTime() - started;
        }
        started = timing ? System.nanoTime() : 0;
        shape.emit(entry, destination, light, overlay, tint);
        if (started != 0) emitNanos += System.nanoTime() - started;
      } finally {
        encoding = false;
      }
    }
  }

  private static void ordinary(
      PoseStack.Pose pose,
      VertexConsumer destination,
      int light,
      int overlay,
      int tint,
      Operation<Void> original) {
    long started = timing ? System.nanoTime() : 0;
    try {
      original.call(pose, destination, light, overlay, tint);
    } finally {
      if (started != 0) ordinaryNanos += System.nanoTime() - started;
    }
  }

  public static String diagnostic() {
    var c = DrawLessConfig.get();
    return String.format(
        Locale.ROOT,
        "status=%s; framesTotal=%d; timedFramesTotal=%d; timedModelPassesTotal=%d; timedCubeCallsTotal=%d; timedHitsTotal=%d; enabledFramesTotal=%d; modelPassesTotal=%d; cubeCallsTotal=%d; requestsTotal=%d; hitsTotal=%d; missesTotal=%d; transformedPositionsTotal=%d; replayedVerticesTotal=%d; unsupportedTotal=%d; capacityFallbacksTotal=%d; topologyChangesTotal=%d; invalidationsTotal=%d; lookupTotalMs=%.5f; transformTotalMs=%.5f; emitTotalMs=%.5f; ordinaryTotalMs=%.5f; maintenanceTotalMs=%.5f; vertexPayloadBytes=%d; entriesLastFrame=%d; topologyEntries=%d",
        c.enabled && c.playerGeometryReuse ? "on" : "off",
        frames,
        timedFrames,
        timedModels,
        timedCubes,
        timedHits,
        enabledFrames,
        models,
        cubes,
        requests,
        hits,
        misses,
        transformedPositions,
        replayedVertices,
        unsupported,
        capacityFallbacks,
        topologyChanges,
        invalidations,
        lookupNanos / 1e6,
        transformNanos / 1e6,
        emitNanos / 1e6,
        ordinaryNanos / 1e6,
        maintenanceNanos / 1e6,
        CACHE.payloadBytes(),
        lastEntries,
        SHAPES.size());
  }
}
