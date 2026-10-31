package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import ru.wilyfox.drawless.DrawLess;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;

/**
 * GPU-retained items held and worn (head item layer) by avatars in the level. Item meshes come from
 * the shared {@link ItemMeshes} store, looked up by quad content because extracted layers are
 * refilled every frame; each frame only item poses, light and tint are streamed.
 *
 * <p>One item stack is one piece: all of its quad layers are retained, or every layer is replayed
 * through the ordinary submit operations in its original order while submission is still open.
 * Coplanar overlay layers therefore never split between paths. Standard glint is retained with its
 * layer: a second instance of the same mesh draws with the glint pipeline after every base, at
 * EQUAL depth. Special foil, outlines, special renderers and blended or unsupported materials keep
 * the whole stack ordinary. GUI previews and first-person hands are outside level entity submission
 * and never captured.
 */
public final class PlayerItemGeometry {
  private static final String LABEL = "DrawLess retained player items";
  private static final IdentityHashMap<RenderType, ArrayList<Batch>> batches =
      new IdentityHashMap<>();
  private static final ArrayList<Batch> active = new ArrayList<>();
  private static final ArrayList<Batch> touched = new ArrayList<>();
  private static final ArrayList<Pending> pending = new ArrayList<>();
  private static final Set<TextureAtlasSprite> animated =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private static final long[] reasons = new long[Reason.values().length];
  private static final boolean SODIUM = RenderModSupport.SODIUM;

  private static ClientLevel world;
  private static boolean registered, checked, enabled, levelSubmit, inPiece, pieceFailed;
  private static boolean drawn = true;
  private static String blocked = "off";
  private static int layerDepth, pendingCount;
  private static int frameInstances, frameSubmits, framePieces, frameFallbackPieces, frameDraws;
  private static int frameGlintInstances;
  private static long indexRebuilds, instanceUploadedBytes;
  private static long framesTotal, piecesTotal, fallbackPiecesTotal, submitsTotal, instancesTotal;
  private static long drawsTotal, captureNanosTotal, uploadNanosTotal, drawNanosTotal;
  private static long captureNanos, uploadNanos, drawNanos;

  private PlayerItemGeometry() {}

  public static void register() {
    if (registered) return;
    registered = true;
    ItemMeshes.register();
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
    LevelRenderEvents.AFTER_SOLID_FEATURES.register(PlayerItemGeometry::draw);
  }

  public static boolean requested() {
    var config = DrawLessConfig.get();
    return config.enabled && config.playerItemGeometry && !RenderModSupport.IRIS;
  }

  /** Mesh and pipeline lifetime belongs to {@link ItemMeshes}; only frame state is dropped. */
  public static void reload() {
    clear();
  }

  public static void clear() {
    for (var list : batches.values()) for (var batch : list) batch.close();
    batches.clear();
    active.clear();
    animated.clear();
    releasePiece();
    world = null;
    checked = enabled = levelSubmit = false;
    drawn = true;
    blocked = "off";
    layerDepth = 0;
    frameInstances = frameSubmits = framePieces = frameFallbackPieces = frameDraws = 0;
    indexRebuilds = instanceUploadedBytes = 0;
    framesTotal = piecesTotal = fallbackPiecesTotal = submitsTotal = instancesTotal = 0;
    drawsTotal = captureNanosTotal = uploadNanosTotal = drawNanosTotal = 0;
    captureNanos = uploadNanos = drawNanos = 0;
    Arrays.fill(reasons, 0);
  }

  /** Called around {@code LevelRenderer.submitEntities}; GUI entity previews never capture. */
  public static void beginLevelSubmit(Minecraft client) {
    levelSubmit = false;
    if (!requested() || client.level == null) {
      if (world != null) clear();
      return;
    }
    if (world != client.level) {
      clear();
      world = client.level;
    }
    if (!drawn && blocked.isEmpty()) block("draw-missed", null);
    for (var batch : active) batch.used = 0;
    active.clear();
    animated.clear();
    layerDepth = 0;
    frameInstances = frameSubmits = framePieces = frameFallbackPieces = frameDraws = 0;
    captureNanos = 0;
    if (!checked) {
      checked = true;
      blocked = ItemMeshes.prepare(client);
    }
    enabled = blocked.isEmpty();
    levelSubmit = enabled;
  }

  public static void endLevelSubmit() {
    levelSubmit = false;
    layerDepth = 0;
    if (inPiece) abortPiece();
    for (var batch : active) if (batch.used > 0) drawn = false;
  }

  /**
   * The item layers whose stacks are captured. Kept narrow on purpose: avatars (players and
   * mannequins) only; other armed entities stay ordinary until validated separately.
   */
  public static boolean enterLayer(EntityRenderState state) {
    if (!levelSubmit || !(state instanceof AvatarRenderState)) return false;
    layerDepth++;
    return true;
  }

  public static void exitLayer() {
    if (layerDepth > 0) layerDepth--;
  }

  public static boolean beginPiece() {
    if (layerDepth == 0 || inPiece) return false;
    inPiece = true;
    pieceFailed = false;
    pendingCount = 0;
    return true;
  }

  public static void endPiece() {
    if (!inPiece) return;
    if (!pieceFailed && pendingCount > 0) {
      framePieces++;
      piecesTotal++;
      frameSubmits += pendingCount;
      submitsTotal += pendingCount;
    }
    releasePiece();
  }

  /** An exception inside the piece: captured layers must never be drawn. */
  public static void abortPiece() {
    if (!inPiece) return;
    rollback();
    releasePiece();
  }

  private static void releasePiece() {
    for (int i = 0; i < pendingCount; i++) pending.get(i).release();
    pendingCount = 0;
    touched.clear();
    inPiece = pieceFailed = false;
  }

  /**
   * Before a special-renderer layer (shield, banner, head...) submits: the whole stack goes
   * ordinary, withheld layers first.
   */
  public static void special() {
    if (inPiece && !pieceFailed) fail(Reason.SPECIAL);
  }

  public static void submitItem(
      SubmitNodeCollector collector,
      PoseStack pose,
      ItemDisplayContext context,
      int light,
      int overlay,
      int outline,
      int[] tints,
      List<BakedQuad> quads,
      ItemStackRenderState.FoilType foil,
      Operation<Void> original) {
    if (!inPiece || pieceFailed) {
      original.call(collector, pose, context, light, overlay, outline, tints, quads, foil);
      return;
    }
    long start = System.nanoTime();
    var reason = capture(pose, light, overlay, outline, tints, quads, foil);
    long elapsed = System.nanoTime() - start;
    captureNanos += elapsed;
    captureNanosTotal += elapsed;
    if (reason == Reason.READY) {
      if (pendingCount == pending.size()) pending.add(new Pending());
      pending
          .get(pendingCount++)
          .set(collector, pose, context, light, overlay, outline, tints, quads, foil, original);
      return;
    }
    fail(reason);
    original.call(collector, pose, context, light, overlay, outline, tints, quads, foil);
  }

  /** Withheld layers are replayed first, so the stack keeps its submission order. */
  private static void fail(Reason reason) {
    reasons[reason.ordinal()]++;
    framePieces++;
    frameFallbackPieces++;
    fallbackPiecesTotal++;
    piecesTotal++;
    pieceFailed = true;
    rollback();
    for (int i = 0; i < pendingCount; i++) pending.get(i).replay();
    for (int i = 0; i < pendingCount; i++) pending.get(i).release();
    pendingCount = 0;
  }

  private static Reason capture(
      PoseStack pose,
      int light,
      int overlay,
      int outline,
      int[] tints,
      List<BakedQuad> quads,
      ItemStackRenderState.FoilType foil) {
    // Special foil derives decal UVs from the pose on the CPU; standard glint reuses quad UVs.
    if (foil == ItemStackRenderState.FoilType.SPECIAL) return Reason.FOIL_SPECIAL;
    boolean glint = foil == ItemStackRenderState.FoilType.STANDARD;
    if (glint && ItemMeshes.glintPipeline(Minecraft.getInstance()) == null) return Reason.GLINT;
    if (outline != 0) return Reason.OUTLINE;
    var last = pose.last();
    if (!last.pose().isFinite() || !last.normal().isFinite()) return Reason.POSE;
    var geometry = ItemMeshes.forContent(quads);
    switch (geometry.status()) {
      case READY -> {}
      case TRANSPARENT -> {
        return Reason.TRANSPARENT;
      }
      case UNSUPPORTED -> {
        return Reason.PIPELINE;
      }
      case WARMING -> {
        return Reason.WARMING;
      }
      case CAPACITY -> {
        return Reason.CAPACITY;
      }
    }
    for (var mesh : geometry.meshes()) {
      var batch = batch(mesh.type, 0);
      var instance = batch == null ? null : batch.next(RetainedInstance::new);
      if (instance == null) return Reason.CAPACITY;
      int color = mesh.tint >= 0 && mesh.tint < tints.length ? tints[mesh.tint] : -1;
      instance.set(
          mesh,
          last,
          color,
          LightCoordsUtil.lightCoordsWithEmission(light, mesh.emission),
          overlay);
      if (SODIUM) animated.addAll(mesh.sprites);
      if (glint) {
        // Same mesh and transform: the glint pass tests EQUAL depth against this instance.
        var glintBatch = batch(glintType(mesh.type), 1);
        var copy = glintBatch == null ? null : glintBatch.next(RetainedInstance::new);
        if (copy == null) return Reason.CAPACITY;
        copy.copy(instance, mesh, -1, instance.light, overlay);
      }
    }
    return Reason.READY;
  }

  /** Vanilla's choice: translucent glint follows bases drawn into the item-entity target. */
  private static RenderType glintType(RenderType base) {
    return base.outputTarget() == OutputTarget.ITEM_ENTITY_TARGET
            && Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()
        ? RenderTypes.glintTranslucent()
        : RenderTypes.glint();
  }

  /** {@code rank}: 0 for base materials, 1 for glint, which draws after every base. */
  private static Batch batch(RenderType type, int rank) {
    var list = batches.computeIfAbsent(type, k -> new ArrayList<>(1));
    Batch found = null;
    for (var batch : list)
      if (batch.used < MAX_INSTANCES) {
        found = batch;
        break;
      }
    if (found == null) {
      if (list.size() >= 8) return null;
      found = new Batch(type, rank);
      list.add(found);
    }
    if (found.used == 0 && !active.contains(found)) active.add(found);
    if (!touched.contains(found)) {
      found.pieceStart = found.used;
      touched.add(found);
    }
    return found;
  }

  private static void rollback() {
    for (var batch : touched) batch.used = batch.pieceStart;
    touched.clear();
  }

  private static void draw(LevelRenderContext context) {
    // Stacks withheld before a mid-frame block were never submitted to vanilla: still draw them.
    if (world == null || world != Minecraft.getInstance().level) return;
    frameInstances = frameGlintInstances = 0;
    for (var batch : active) {
      frameInstances += batch.used;
      if (batch.rank > 0) frameGlintInstances += batch.used;
    }
    drawn = true;
    if (frameInstances == 0) return;
    long start = System.nanoTime();
    var matrix = RenderSystem.getModelViewStack();
    matrix.pushMatrix();
    try {
      // Vanilla prepares every main layer of a group before any foil layer; the sort is stable.
      active.sort(Comparator.comparingInt((Batch batch) -> batch.rank));
      ItemMeshes.upload();
      for (var batch : active)
        if (batch.used > 0) {
          if (batch.prepare()) indexRebuilds++;
          instanceUploadedBytes += (long) batch.used * INSTANCE_BYTES;
        }
      uploadNanos = System.nanoTime() - start;
      uploadNanosTotal += uploadNanos;
      long drawStart = System.nanoTime();
      matrix.set(context.levelState().cameraRenderState.viewRotationMatrix);
      if (SODIUM) for (var sprite : animated) SodiumSprites.mark(sprite);
      frameDraws = RetainedPass.draw(LABEL, active, ItemMeshes.pipelines(), ItemMeshes.buffer());
      drawNanos = System.nanoTime() - drawStart;
      drawNanosTotal += drawNanos;
      drawsTotal += frameDraws;
      instancesTotal += frameInstances;
      framesTotal++;
    } catch (RuntimeException failure) {
      block("runtime-fallback", failure);
    } finally {
      matrix.popMatrix();
      for (var batch : active) batch.used = 0;
      active.clear();
    }
  }

  private static void block(String reason, RuntimeException error) {
    if (!blocked.equals(reason))
      DrawLess.LOGGER.warn(
          "Retained player item geometry disabled ({}); using vanilla rendering", reason, error);
    blocked = reason;
    enabled = levelSubmit = false;
  }

  public static String diagnostic() {
    if (!requested()) return "status=off";
    var reasonText = new StringBuilder();
    for (var reason : Reason.values())
      if (reason != Reason.READY && reasons[reason.ordinal()] > 0) {
        if (!reasonText.isEmpty()) reasonText.append(',');
        reasonText.append(reason.label).append(':').append(reasons[reason.ordinal()]);
      }
    return String.format(
        Locale.ROOT,
        "status=%s; pieces=%d; fallbackPieces=%d; submits=%d; instances=%d; "
            + "glintInstances=%d; draws=%d; "
            + "framesTotal=%d; piecesTotal=%d; fallbackPiecesTotal=%d; submitsTotal=%d; "
            + "instancesTotal=%d; drawsTotal=%d; itemMeshes=%d; itemMeshBuilds=%d; "
            + "indexRebuilds=%d; instanceUploadedBytes=%d; capture=%.4fms; upload=%.4fms; "
            + "drawCpu=%.4fms; captureTotalMs=%.4f; uploadTotalMs=%.4f; drawTotalMs=%.4f; "
            + "%s; fallback=%s",
        enabled ? "on" : blocked.isEmpty() ? "idle" : blocked,
        framePieces,
        frameFallbackPieces,
        frameSubmits,
        frameInstances,
        frameGlintInstances,
        frameDraws,
        framesTotal,
        piecesTotal,
        fallbackPiecesTotal,
        submitsTotal,
        instancesTotal,
        drawsTotal,
        ItemMeshes.meshCount(),
        ItemMeshes.meshBuilds(),
        indexRebuilds,
        instanceUploadedBytes,
        captureNanos / 1e6,
        uploadNanos / 1e6,
        drawNanos / 1e6,
        captureNanosTotal / 1e6,
        uploadNanosTotal / 1e6,
        drawNanosTotal / 1e6,
        ItemMeshes.diagnostic(),
        reasonText.isEmpty() ? "none" : reasonText);
  }

  private enum Reason {
    READY("ready"),
    FOIL_SPECIAL("foil-special"),
    GLINT("glint-pipeline"),
    OUTLINE("outline"),
    SPECIAL("special-renderer"),
    POSE("pose"),
    TRANSPARENT("transparency"),
    PIPELINE("pipeline"),
    WARMING("warming"),
    CAPACITY("capacity");
    final String label;

    Reason(String label) {
      this.label = label;
    }
  }

  private static final class Batch extends RetainedBatch<RetainedInstance> {
    final int rank;
    int pieceStart;

    Batch(RenderType type, int rank) {
      super(type, LABEL, 4096);
      this.rank = rank;
    }
  }

  /** A withheld item layer; references are released when its stack finishes. */
  private static final class Pending {
    final PoseStack pose = new PoseStack();
    SubmitNodeCollector collector;
    ItemDisplayContext context;
    int light, overlay, outline;
    int[] tints;
    List<BakedQuad> quads;
    ItemStackRenderState.FoilType foil;
    Operation<Void> original;

    void set(
        SubmitNodeCollector collector,
        PoseStack pose,
        ItemDisplayContext context,
        int light,
        int overlay,
        int outline,
        int[] tints,
        List<BakedQuad> quads,
        ItemStackRenderState.FoilType foil,
        Operation<Void> original) {
      this.collector = collector;
      // The layer pops its transform after submitting: replay from a copy of the submitted pose.
      this.pose.last().set(pose.last());
      this.context = context;
      this.light = light;
      this.overlay = overlay;
      this.outline = outline;
      this.tints = tints;
      this.quads = quads;
      this.foil = foil;
      this.original = original;
    }

    void replay() {
      original.call(collector, pose, context, light, overlay, outline, tints, quads, foil);
    }

    void release() {
      collector = null;
      context = null;
      tints = null;
      quads = null;
      foil = null;
      original = null;
    }
  }

  private static final class SodiumSprites {
    static void mark(TextureAtlasSprite sprite) {
      net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(sprite);
    }
  }
}
