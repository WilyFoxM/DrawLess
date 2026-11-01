package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL31;
import ru.wilyfox.drawless.DrawLess;
import ru.wilyfox.drawless.compat.IrisShaders;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.mixin.ModelPartCubesAccessor;

/**
 * GPU-retained player armor geometry. Armor model parts keep resource-lifetime local meshes in a
 * texel buffer; each frame only part transforms, light and tint are streamed, and the indexed
 * vertex pulling encoding of Live geometry selects mesh vertex and instance. Animation setup and
 * the model-part traversal stay ordinary; only cube emission is replaced.
 *
 * <p>Base, glint and trim submits of one equipment piece share one decision: trims and glint use an
 * EQUAL depth test, so mixing GPU-transformed and CPU-transformed members of a piece is refused. A
 * failed piece is replayed through the ordinary submit operations before the piece returns, while
 * submission is still open.
 *
 * <p>Player bodies (an experimental, separately switched path) share the part meshes and the draw.
 * Vanilla draws a body as {@code entityTranslucent(skin)} in the distance-sorted translucent phase.
 * Skin downloads force the base layer opaque, and the entity shader discards alpha below 0.1; a
 * skin with no texel alpha between 26 and 254 therefore yields only discarded or opaque,
 * depth-writing fragments, whose result does not depend on blending order. Such skins are copied
 * into a skin atlas and every body draws once after the solid features with the same shader as
 * {@code entityCutout}. Any other skin, tint, outline or translucency keeps the vanilla submit.
 */
public final class PlayerArmorGeometry {
  private static final int MAX_MESH_VERTICES = 1 << 18, MAX_PARTS = 2048;
  private static final int ATLAS_SIZE = 1024, PIECE_HEADROOM = 128;
  private static final String LABEL = "DrawLess retained armor";

  /** Batch key of the shared base-armor atlas; every other batch is keyed by its RenderType. */
  private static final Object ATLAS = new Object();

  /** Batch key of player bodies; their UVs are moved into each skin's atlas cell per instance. */
  private static final Object SKINS = new Object();

  private static final int SKIN_SIZE = 64, SKIN_CELL = SKIN_SIZE + 2;
  private static final int SKIN_COLUMNS = ATLAS_SIZE / SKIN_CELL;

  /** UV mapping of body meshes: a 64x64 skin at the atlas origin; instances add their cell. */
  private static final AtlasSlot SKIN_UV = new AtlasSlot(0, 0, SKIN_SIZE, SKIN_SIZE);

  private static final Identifier ARMOR_VERTEX =
      Identifier.fromNamespaceAndPath("drawless", "core/live_armor");
  private static final Identifier GLINT_VERTEX =
      Identifier.fromNamespaceAndPath("drawless", "core/live_armor_glint");
  private static final Identifier ENTITY_SHADER = Identifier.withDefaultNamespace("core/entity");
  private static final Identifier GLINT_SHADER = Identifier.withDefaultNamespace("core/glint");
  private static final Set<String> ENTITY_FLAGS =
      Set.of(
          "NO_OVERLAY",
          "PER_FACE_LIGHTING",
          "NO_CARDINAL_LIGHTING",
          "EMISSIVE",
          "APPLY_TEXTURE_MATRIX");
  private static final VertexConsumer MARKER = new Marker();
  private static final IdentityHashMap<ModelPart, PartMeshes> parts = new IdentityHashMap<>();
  private static final RetainedPipelines pipelines = new RetainedPipelines("live_armor/");
  private static final RetainedGeometryBuffer geometry =
      new RetainedGeometryBuffer("DrawLess retained armor geometry");
  private static final IdentityHashMap<Object, ArrayList<Batch>> batches = new IdentityHashMap<>();
  private static final IdentityHashMap<RenderType, Identifier> armorTextures =
      new IdentityHashMap<>();
  private static final IdentityHashMap<RenderType, AtlasSlot> slots = new IdentityHashMap<>();
  private static final ArrayList<Batch> active = new ArrayList<>();
  private static final ArrayList<Pending> pending = new ArrayList<>();
  private static final ArrayList<Batch> touched = new ArrayList<>();
  private static final long[] reasons = new long[Reason.values().length];
  private static final long[] bodyReasons = new long[Reason.values().length];
  private static final IdentityHashMap<AbstractTexture, SkinSlot> skins = new IdentityHashMap<>();
  private static final SkinSlot[] skinCells = new SkinSlot[SKIN_COLUMNS * SKIN_COLUMNS];

  private static ClientLevel world;
  private static boolean registered, checked, enabled, levelSubmit, inPiece, pieceFailed;
  private static boolean raw, partFailed, drawn = true;
  private static String blocked = "off";
  private static int pendingCount, batchSequence;
  private static GpuTexture atlas;
  private static GpuTextureView atlasView;
  private static GpuSampler atlasSampler;
  private static int shelfX, shelfY, shelfHeight, atlasTextures;
  private static int frameInstances, frameSubmits, framePieces, frameFallbackPieces, frameDraws;
  private static Batch captureBatch, templateBatch;
  private static Model<?> templateModel;
  private static Object templateState;
  private static Object templateUv;
  private static final Matrix4f templatePose = new Matrix4f();
  private static int templateStart, templateEnd, frameReplicated;
  private static long replicatedTotal;
  private static PoseStack capturePose = new PoseStack();
  private static Object captureUv;
  private static long meshBuilds, indexRebuilds, instanceUploadedBytes;
  private static long framesTotal, piecesTotal, fallbackPiecesTotal, submitsTotal, instancesTotal;
  private static long drawsTotal, captureNanosTotal, uploadNanosTotal, drawNanosTotal;
  private static long captureNanos, uploadNanos, drawNanos;
  private static GpuTexture skinAtlas;
  private static GpuTextureView skinAtlasView;
  private static int captureOffset, frameBodies, frameBodyFallbacks;
  private static long frame, bodiesTotal, bodyFallbacksTotal, bodyNanos, bodyNanosTotal;
  private static long skinsAdmitted, skinsPartial, skinsEvicted;

  private PlayerArmorGeometry() {}

  public static void register() {
    if (registered) return;
    registered = true;
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
    LevelRenderEvents.AFTER_SOLID_FEATURES.register(PlayerArmorGeometry::draw);
  }

  public static boolean requested() {
    return armorRequested() || bodyRequested();
  }

  private static boolean armorRequested() {
    var config = DrawLessConfig.get();
    return config.enabled && config.playerArmorGeometry && !IrisShaders.packInUse();
  }

  private static boolean bodyRequested() {
    var config = DrawLessConfig.get();
    return config.enabled
        && config.playerBodyGeometry
        && !RenderModSupport.PLAYER_MODEL_MODS
        && !IrisShaders.packInUse();
  }

  /** Resource reload replaces model parts and atlas sprites; pipelines follow the device. */
  public static void reload() {
    clear();
    pipelines.clear();
    armorTextures.clear();
  }

  public static void clear() {
    for (var list : batches.values()) for (var batch : list) batch.close();
    batches.clear();
    active.clear();
    parts.clear();
    slots.clear();
    releasePiece();
    if (atlasView != null) atlasView.close();
    if (atlas != null) atlas.close();
    atlasView = null;
    atlas = null;
    atlasSampler = null;
    shelfX = shelfY = shelfHeight = atlasTextures = 0;
    skins.clear();
    Arrays.fill(skinCells, null);
    if (skinAtlasView != null) skinAtlasView.close();
    if (skinAtlas != null) skinAtlas.close();
    skinAtlasView = null;
    skinAtlas = null;
    frameBodies = frameBodyFallbacks = 0;
    bodiesTotal = bodyFallbacksTotal = bodyNanos = bodyNanosTotal = 0;
    skinsAdmitted = skinsPartial = skinsEvicted = 0;
    Arrays.fill(bodyReasons, 0);
    geometry.close();
    world = null;
    checked = enabled = levelSubmit = false;
    drawn = true;
    blocked = "off";
    batchSequence = 0;
    frameInstances = frameSubmits = framePieces = frameFallbackPieces = frameDraws = 0;
    meshBuilds = indexRebuilds = instanceUploadedBytes = 0;
    framesTotal = piecesTotal = fallbackPiecesTotal = submitsTotal = instancesTotal = 0;
    replicatedTotal = frameReplicated = 0;
    drawsTotal = captureNanosTotal = uploadNanosTotal = drawNanosTotal = 0;
    captureNanos = uploadNanos = drawNanos = 0;
    Arrays.fill(reasons, 0);
  }

  /** Called around {@code LevelRenderer.submitEntities}; GUI entity previews never capture. */
  public static void beginLevelSubmit(Minecraft client) {
    levelSubmit = false;
    if (!requested() || client.level == null) {
      if (world != null || geometry.allocated()) clear();
      return;
    }
    if (world != client.level) {
      clear();
      world = client.level;
    }
    if (!drawn && blocked.isEmpty()) block("draw-missed", null);
    for (var batch : active) batch.used = 0;
    active.clear();
    frameInstances = frameSubmits = framePieces = frameFallbackPieces = frameDraws = 0;
    frameReplicated = 0;
    captureNanos = 0;
    frameBodies = frameBodyFallbacks = 0;
    bodyNanos = 0;
    frame++;
    if (!checked) {
      checked = true;
      blocked = compatibility(client);
      if (blocked.isEmpty()) {
        geometry.limit(
            Math.min(MAX_MESH_VERTICES, GL31.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE) / 2));
        if (geometry.max() < 4096) blocked = "texel-buffer-limit";
      }
    }
    enabled = blocked.isEmpty();
    levelSubmit = enabled;
  }

  public static void endLevelSubmit() {
    levelSubmit = false;
    if (inPiece) abortPiece();
    for (var batch : active) if (batch.used > 0) drawn = false;
  }

  private static String compatibility(Minecraft client) {
    if (!RenderSystem.getDevice().getDeviceInfo().backendName().equals("OpenGL"))
      return "unsupported-backend";
    for (String file :
        List.of(
            "shaders/core/entity.vsh",
            "shaders/core/entity.fsh",
            "shaders/core/glint.vsh",
            "shaders/core/glint.fsh")) {
      var resource = client.getResourceManager().getResource(Identifier.withDefaultNamespace(file));
      if (resource.isEmpty() || !resource.get().source().equals(client.getVanillaPackResources()))
        return "custom-entity-shader";
    }
    return "";
  }

  public static void beginPiece() {
    if (!levelSubmit || inPiece || !armorRequested()) return;
    inPiece = true;
    pieceFailed = false;
    pendingCount = 0;
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

  /** An exception inside the piece: captured members must never be drawn. */
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
    templateBatch = null;
    templateModel = null;
    templateState = null;
    templateUv = null;
  }

  /** The base armor RenderType memoizes its texture; keep the mapping for atlas admission. */
  public static void armorTexture(RenderType type, Identifier texture) {
    if (armorTextures.size() < 1024) armorTextures.putIfAbsent(type, texture);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void submit(
      OrderedSubmitNodeCollector collector,
      Model model,
      Object state,
      PoseStack pose,
      RenderType type,
      int light,
      int overlay,
      int color,
      TextureAtlasSprite sprite,
      int outline,
      ModelFeatureRenderer.CrumblingOverlay crumbling,
      Operation<Void> original) {
    if (!inPiece || pieceFailed) {
      original.call(
          collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling);
      return;
    }
    long start = System.nanoTime();
    var reason = eligible(model, state, type, sprite, outline, crumbling);
    if (reason == Reason.READY)
      reason = capture(model, state, pose, type, light, overlay, color, sprite);
    captureNanos += System.nanoTime() - start;
    captureNanosTotal += System.nanoTime() - start;
    if (reason == Reason.READY) {
      if (pendingCount == pending.size()) pending.add(new Pending());
      pending
          .get(pendingCount++)
          .set(
              collector, model, state, pose, type, light, overlay, color, sprite, outline,
              crumbling, original);
      return;
    }
    reasons[reason.ordinal()]++;
    framePieces++;
    frameFallbackPieces++;
    fallbackPiecesTotal++;
    piecesTotal++;
    pieceFailed = true;
    rollback();
    // Replay already-withheld members first so the piece keeps its submission order.
    for (int i = 0; i < pendingCount; i++) pending.get(i).replay();
    for (int i = 0; i < pendingCount; i++) pending.get(i).release();
    pendingCount = 0;
    original.call(
        collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling);
  }

  private static Reason eligible(
      Model<?> model,
      Object state,
      RenderType type,
      TextureAtlasSprite sprite,
      int outline,
      ModelFeatureRenderer.CrumblingOverlay crumbling) {
    // Avatar armor sets bake PlayerModel; other subclasses may override part rendering.
    var modelType = model.getClass();
    if (!(state instanceof AvatarRenderState)
        || modelType != HumanoidModel.class && modelType != PlayerModel.class) return Reason.MODEL;
    if (outline != 0 || crumbling != null) return Reason.OUTLINE;
    if (sprite != null && sprite.isAnimated()) return Reason.SPRITE;
    return pipeline(type) == null ? Reason.PIPELINE : Reason.READY;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Reason capture(
      Model model,
      Object state,
      PoseStack pose,
      RenderType type,
      int light,
      int overlay,
      int color,
      TextureAtlasSprite sprite) {
    // Base and overlay layers share one atlas batch; trims keep their sprite sheet; blended glint
    // draws after every solid member, like vanilla's translucent model phase.
    Object uv;
    Batch batch;
    if (type.hasBlending()) {
      uv = null;
      batch = batch(type, type, 2);
    } else if (sprite != null) {
      uv = sprite;
      batch = batch(type, type, 1);
    } else {
      var slot = slot(type);
      if (slot == null) return Reason.TEXTURE;
      uv = slot;
      batch = batch(ATLAS, type, 0);
    }
    if (batch == null) return Reason.CAPACITY;
    if (!touched.contains(batch)) {
      batch.pieceStart = batch.used;
      touched.add(batch);
    }
    if (templateBatch != null
        && model == templateModel
        && state == templateState
        && templatePose.equals(pose.last().pose()))
      return replicate(batch, light, overlay, color, uv);
    int start = batch.used;
    captureBatch = batch;
    // Vanilla preparation would wrap the consumer with this sprite; meshes bake the same UVs.
    captureUv = uv;
    captureOffset = 0;
    raw = partFailed = false;
    try {
      model.setupAnim(state);
      capturePose.setIdentity();
      capturePose.last().set(pose.last());
      model.renderToBuffer(capturePose, MARKER, light, overlay, color);
    } catch (RuntimeException failure) {
      capturePose = new PoseStack();
      block("runtime-fallback", failure);
      return Reason.ERROR;
    } finally {
      captureBatch = null;
      captureUv = null;
    }
    if (raw) return Reason.RAW;
    if (partFailed) return Reason.MESH;
    if (templateBatch == null) {
      templateBatch = batch;
      templateStart = start;
      templateEnd = batch.used;
      templateModel = model;
      templateState = state;
      templateUv = uv;
      templatePose.set(pose.last().pose());
    }
    return Reason.READY;
  }

  /**
   * Base, glint, overlay and trim members of a piece prepare the same model and state at the same
   * pose; vanilla would repeat identical animation setup and traversal for each. Copy the captured
   * part transforms instead, keeping each member's own material, tint, light and sprite UVs.
   */
  private static Reason replicate(Batch batch, int light, int overlay, int color, Object uv) {
    for (int i = templateStart; i < templateEnd; i++) {
      var source = templateBatch.instances.get(i);
      var mesh =
          uv == templateUv
              ? (Mesh) source.mesh
              : mesh(
                  source.part,
                  ((ModelPartCubesAccessor) (Object) source.part).drawless$cubes(),
                  uv);
      if (mesh == null) return Reason.MESH;
      var instance = batch.next();
      if (instance == null) return Reason.CAPACITY;
      instance.copy(source, mesh, color, light, overlay);
    }
    frameReplicated++;
    replicatedTotal++;
    return Reason.READY;
  }

  public static boolean capturing(VertexConsumer consumer) {
    return consumer == MARKER;
  }

  public static void part(ModelPart part, PoseStack.Pose pose, int light, int overlay, int color) {
    var batch = captureBatch;
    if (batch == null) {
      partFailed = true;
      return;
    }
    var cubes = ((ModelPartCubesAccessor) (Object) part).drawless$cubes();
    if (cubes.isEmpty()) return;
    var mesh = mesh(part, cubes, captureUv);
    if (mesh == null) {
      partFailed = true;
      return;
    }
    var instance = batch.next();
    if (instance == null) {
      partFailed = true;
      return;
    }
    instance.set(part, mesh, pose, color, light, overlay);
    instance.uvOffset = captureOffset;
  }

  private static void rollback() {
    for (var batch : touched) batch.used = batch.pieceStart;
    touched.clear();
  }

  /** {@code uv}: null for raw texture UVs, a trim sprite, or a base-armor atlas slot. */
  private static Mesh mesh(ModelPart part, List<ModelPart.Cube> cubes, Object uv) {
    var meshes = parts.get(part);
    if (meshes != null && !meshes.matches(cubes)) {
      // Rebuilt cubes leave their old vertices unreachable until the next reset.
      parts.remove(part);
      meshes = null;
    }
    if (meshes == null) {
      if (parts.size() >= MAX_PARTS) return null;
      meshes = PartMeshes.capture(cubes);
      parts.put(part, meshes);
    }
    if (!meshes.supported) return null;
    var mesh = uv == null ? meshes.plain : meshes.mapped.get(uv);
    if (mesh != null) return mesh;
    mesh = build(meshes, uv);
    if (mesh == null) return null;
    if (uv == null) meshes.plain = mesh;
    else meshes.mapped.put(uv, mesh);
    return mesh;
  }

  private static Mesh build(PartMeshes meshes, Object uv) {
    int count = meshes.vertexCount;
    if (!geometry.fits(count)) return null;
    int first = geometry.begin(count);
    for (var polygons : meshes.polygons)
      for (var polygon : polygons) {
        var n = polygon.normal();
        for (var vertex : polygon.vertices()) {
          float u = vertex.u(), v = vertex.v();
          if (uv instanceof TextureAtlasSprite sprite) {
            u = sprite.getU(u);
            v = sprite.getV(v);
          } else if (uv instanceof AtlasSlot slot) {
            u = slot.u(u);
            v = slot.v(v);
          }
          geometry.vertex(
              vertex.worldX(), vertex.worldY(), vertex.worldZ(), u, n.x(), n.y(), n.z(), v);
        }
      }
    meshBuilds++;
    return new Mesh(first, count);
  }

  private static RenderPipeline pipeline(RenderType type) {
    var base = type.pipeline();
    var cached = pipelines.get(base);
    if (cached != null || pipelines.known(base)) return cached;
    Identifier vertex;
    if (base == RenderPipelines.ARMOR_CUTOUT_NO_CULL
        || base == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL
        || base == RenderPipelines.ENTITY_CUTOUT) {
      var defines = base.getShaderDefines();
      if (!base.getVertexShader().equals(ENTITY_SHADER)
          || !base.getFragmentShader().equals(ENTITY_SHADER)
          || !ENTITY_FLAGS.containsAll(defines.flags())
          || defines.values().keySet().stream().anyMatch(name -> !name.equals("ALPHA_CUTOUT")))
        return reject(base);
      vertex = ARMOR_VERTEX;
    } else if (base == RenderPipelines.GLINT) {
      if (!base.getVertexShader().equals(GLINT_SHADER)
          || !base.getFragmentShader().equals(GLINT_SHADER)
          || !base.getShaderDefines().flags().isEmpty()
          || !base.getShaderDefines().values().isEmpty()) return reject(base);
      vertex = GLINT_VERTEX;
    } else return reject(base);
    if (type.hasBlending() && base != RenderPipelines.GLINT || type.sortOnUpload())
      return reject(base);
    // Compile before the first piece is withheld; a shader error must not hide vanilla armor.
    var result = pipelines.compile(base, vertex);
    if (result == null) {
      block("shader-compile", null);
      return reject(base);
    }
    return result;
  }

  private static RenderPipeline reject(RenderPipeline base) {
    return pipelines.reject(base);
  }

  private static Batch batch(Object key, RenderType type, int rank) {
    var list = batches.computeIfAbsent(key, k -> new ArrayList<>(2));
    // Leave room for a whole piece so a nearly full chunk does not refuse every later piece.
    for (var batch : list)
      if (batch.used + PIECE_HEADROOM <= MAX_INSTANCES) {
        activate(batch);
        return batch;
      }
    if (batches.size() > 256 || list.size() > 64) return null;
    var batch = new Batch(type, rank, key == ATLAS, key == SKINS);
    list.add(batch);
    activate(batch);
    return batch;
  }

  private static void activate(Batch batch) {
    if (batch.used == 0 && !active.contains(batch)) {
      batch.sequence = batchSequence++;
      active.add(batch);
    }
  }

  private static void draw(LevelRenderContext context) {
    // Pieces withheld before a mid-frame block were never submitted to vanilla: still draw them.
    if (world == null || world != Minecraft.getInstance().level) return;
    frameInstances = 0;
    for (var batch : active) frameInstances += batch.used;
    drawn = true;
    if (frameInstances == 0) return;
    long start = System.nanoTime();
    var matrix = RenderSystem.getModelViewStack();
    matrix.pushMatrix();
    try {
      geometry.upload();
      // Within a piece vanilla submits base layers, then trims; blended glint goes to the
      // translucent model phase after every solid group. EQUAL-depth members only interact
      // inside their piece, so ranks base < trim < glint preserve each piece's order.
      active.sort(Comparator.comparingInt((Batch b) -> b.rank).thenComparingInt(b -> b.sequence));
      for (var batch : active)
        if (batch.used > 0) {
          if (batch.prepare()) indexRebuilds++;
          instanceUploadedBytes += (long) batch.used * INSTANCE_BYTES;
        }
      uploadNanos = System.nanoTime() - start;
      uploadNanosTotal += uploadNanos;
      long drawStart = System.nanoTime();
      matrix.set(context.levelState().cameraRenderState.viewRotationMatrix);
      for (var batch : active)
        batch.texture0 = batch.atlas ? atlasView : batch.skins ? skinAtlasView : null;
      frameDraws = RetainedPass.draw(LABEL, active, pipelines, geometry.buffer());
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

  private static AtlasSlot slot(RenderType type) {
    if (slots.containsKey(type)) return slots.get(type);
    AtlasSlot slot;
    try {
      slot = admit(type);
    } catch (IOException | RuntimeException failure) {
      slot = null;
    }
    if (slots.size() < 1024) slots.put(type, slot);
    return slot;
  }

  /**
   * Copies a resource-backed base armor texture into the shared atlas so every base and overlay
   * layer draws in one call. Vanilla textures lack COPY_SRC, so the atlas is filled from the same
   * resource contents SimpleTexture loads; the bound texture's size and sampler must match. A
   * one-texel gutter repeats or clamps the edges like the original sampler addressing.
   */
  private static AtlasSlot admit(RenderType type) throws IOException {
    var id = armorTextures.get(type);
    if (id == null || type.pipeline() != RenderPipelines.ARMOR_CUTOUT_NO_CULL) return null;
    var client = Minecraft.getInstance();
    var texture = client.getTextureManager().getTexture(id);
    if (texture.getClass() != SimpleTexture.class) return null;
    GpuSampler sampler = null;
    for (var binding : type.prepare().textures())
      if (binding.name().equals("Sampler0")
          && binding.textureView().texture() == texture.getTexture()) sampler = binding.sampler();
    if (sampler == null || atlasSampler != null && sampler != atlasSampler) return null;
    try (var contents = TextureContents.load(client.getResourceManager(), id)) {
      var image = contents.image();
      int w = image.getWidth(), h = image.getHeight();
      if (w != texture.getTexture().getWidth(0) || h != texture.getTexture().getHeight(0))
        return null;
      if (shelfX + w + 2 > ATLAS_SIZE) {
        shelfX = 0;
        shelfY += shelfHeight;
        shelfHeight = 0;
      }
      if (w + 2 > ATLAS_SIZE || shelfY + h + 2 > ATLAS_SIZE) return null;
      if (atlas == null) {
        atlas =
            RenderSystem.getDevice()
                .createTexture(
                    "DrawLess retained armor atlas",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,
                    ATLAS_SIZE,
                    ATLAS_SIZE,
                    1,
                    1);
        atlasView = RenderSystem.getDevice().createTextureView(atlas);
      }
      boolean clamp = contents.clamp();
      try (var padded = new NativeImage(w + 2, h + 2, false)) {
        for (int y = -1; y <= h; y++)
          for (int x = -1; x <= w; x++) {
            int sx = clamp ? Math.clamp(x, 0, w - 1) : Math.floorMod(x, w);
            int sy = clamp ? Math.clamp(y, 0, h - 1) : Math.floorMod(y, h);
            padded.setPixel(x + 1, y + 1, image.getPixel(sx, sy));
          }
        RenderSystem.getDevice()
            .createCommandEncoder()
            .writeToTexture(atlas, padded, 0, 0, shelfX, shelfY);
      }
      var slot = new AtlasSlot(shelfX + 1, shelfY + 1, w, h);
      shelfX += w + 2;
      shelfHeight = Math.max(shelfHeight, h + 2);
      atlasSampler = sampler;
      atlasTextures++;
      return slot;
    }
  }

  /**
   * Withholds a player body submit when its skin and state allow the opaque retained path; returns
   * false to leave the submit to vanilla. Called for every living-entity body submit.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static boolean body(
      SubmitNodeCollector collector,
      Model model,
      Object state,
      PoseStack pose,
      RenderType type,
      int light,
      int overlay,
      int color,
      TextureAtlasSprite sprite,
      int outline,
      ModelFeatureRenderer.CrumblingOverlay crumbling) {
    if (!levelSubmit || !(state instanceof AvatarRenderState avatar) || !bodyRequested())
      return false;
    long start = System.nanoTime();
    var reason = Reason.READY;
    RenderType cutout = null;
    SkinSlot slot = null;
    if (model.getClass() != PlayerModel.class) reason = Reason.MODEL;
    else if (outline != 0 || crumbling != null) reason = Reason.OUTLINE;
    else if (sprite != null) reason = Reason.SPRITE;
    else if (color != -1) reason = Reason.TINT;
    else {
      var skin = avatar.skin.body().texturePath();
      // Invisible-to-player and glowing-only bodies select other render types.
      if (type != RenderTypes.entityTranslucent(skin)) reason = Reason.PIPELINE;
      else {
        // The batch type only supplies pipeline and samplers; Sampler0 becomes the skin atlas.
        cutout = RenderTypes.entityCutout(DefaultPlayerSkin.getDefaultTexture());
        if (pipeline(cutout) == null) reason = Reason.PIPELINE;
        else {
          slot = skinSlot(skin);
          if (slot == null) reason = Reason.SKIN;
          else if (slot == SkinSlot.PARTIAL) reason = Reason.SKIN_PARTIAL;
          else if (slot.cell < 0) reason = Reason.SKIN;
        }
      }
    }
    if (reason == Reason.READY)
      reason = captureBody(model, state, pose, cutout, slot, light, overlay);
    long elapsed = System.nanoTime() - start;
    bodyNanos += elapsed;
    bodyNanosTotal += elapsed;
    if (reason == Reason.READY) {
      frameBodies++;
      bodiesTotal++;
      return true;
    }
    bodyReasons[reason.ordinal()]++;
    frameBodyFallbacks++;
    bodyFallbacksTotal++;
    return false;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Reason captureBody(
      Model model,
      Object state,
      PoseStack pose,
      RenderType cutout,
      SkinSlot slot,
      int light,
      int overlay) {
    var batch = batch(SKINS, cutout, 0);
    if (batch == null || batch.used + PIECE_HEADROOM > MAX_INSTANCES) return Reason.CAPACITY;
    int start = batch.used;
    captureBatch = batch;
    captureUv = SKIN_UV;
    captureOffset = slot.x | slot.y << 16;
    raw = partFailed = false;
    try {
      model.setupAnim(state);
      capturePose.setIdentity();
      capturePose.last().set(pose.last());
      model.renderToBuffer(capturePose, MARKER, light, overlay, -1);
    } catch (RuntimeException failure) {
      capturePose = new PoseStack();
      batch.used = start;
      block("runtime-fallback", failure);
      return Reason.ERROR;
    } finally {
      captureBatch = null;
      captureUv = null;
      captureOffset = 0;
    }
    if (raw || partFailed) {
      batch.used = start;
      return raw ? Reason.RAW : Reason.MESH;
    }
    slot.frame = frame;
    return Reason.READY;
  }

  /** The skin's atlas cell, {@link SkinSlot#PARTIAL}/{@link SkinSlot#REFUSED}, or null to retry. */
  private static SkinSlot skinSlot(Identifier id) {
    var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
    var slot = skins.get(texture);
    if (slot != null) return slot;
    try {
      slot = admitSkin(id, texture);
    } catch (IOException | RuntimeException failure) {
      slot = SkinSlot.REFUSED;
    }
    if (slot != null && skins.size() < 1024) skins.put(texture, slot);
    // A cell admitted or used this frame must not be evicted by a later body of the same frame.
    if (slot != null && slot.cell >= 0) slot.frame = frame;
    return slot;
  }

  /**
   * Classifies a 64x64 skin by its texels and copies an eligible one into a free atlas cell with a
   * clamped one-texel gutter. Downloaded skins keep their processed pixels in a DynamicTexture;
   * default skins are resource-backed and reloaded from the same contents vanilla uploads.
   */
  private static SkinSlot admitSkin(Identifier id, AbstractTexture texture) throws IOException {
    TextureContents contents = null;
    NativeImage image;
    if (texture instanceof DynamicTexture dynamic) image = dynamic.getPixels();
    else if (texture.getClass() == SimpleTexture.class) {
      contents = TextureContents.load(Minecraft.getInstance().getResourceManager(), id);
      image = contents.image();
    } else return SkinSlot.REFUSED;
    try {
      if (image == null
          || image.getWidth() != SKIN_SIZE
          || image.getHeight() != SKIN_SIZE
          || texture.getTexture().getWidth(0) != SKIN_SIZE
          || texture.getTexture().getHeight(0) != SKIN_SIZE) return SkinSlot.REFUSED;
      for (int y = 0; y < SKIN_SIZE; y++)
        for (int x = 0; x < SKIN_SIZE; x++) {
          int alpha = image.getPixel(x, y) >>> 24;
          if (alpha >= 26 && alpha <= 254) {
            skinsPartial++;
            return SkinSlot.PARTIAL;
          }
        }
      int cell = freeCell();
      if (cell < 0) return null;
      if (skinAtlas == null) {
        skinAtlas =
            RenderSystem.getDevice()
                .createTexture(
                    "DrawLess retained skin atlas",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,
                    ATLAS_SIZE,
                    ATLAS_SIZE,
                    1,
                    1);
        skinAtlasView = RenderSystem.getDevice().createTextureView(skinAtlas);
      }
      int cx = cell % SKIN_COLUMNS * SKIN_CELL, cy = cell / SKIN_COLUMNS * SKIN_CELL;
      try (var padded = new NativeImage(SKIN_CELL, SKIN_CELL, false)) {
        for (int y = -1; y <= SKIN_SIZE; y++)
          for (int x = -1; x <= SKIN_SIZE; x++)
            padded.setPixel(
                x + 1,
                y + 1,
                image.getPixel(Math.clamp(x, 0, SKIN_SIZE - 1), Math.clamp(y, 0, SKIN_SIZE - 1)));
        RenderSystem.getDevice()
            .createCommandEncoder()
            .writeToTexture(skinAtlas, padded, 0, 0, cx, cy);
      }
      var slot = new SkinSlot(cell, cx + 1, cy + 1);
      skinCells[cell] = slot;
      skinsAdmitted++;
      return slot;
    } finally {
      if (contents != null) contents.close();
    }
  }

  /** A free cell, else the least recently drawn one not used this frame; -1 when none. */
  private static int freeCell() {
    int oldest = -1;
    for (int cell = 0; cell < skinCells.length; cell++) {
      var slot = skinCells[cell];
      if (slot == null) return cell;
      if (slot.frame != frame && (oldest < 0 || slot.frame < skinCells[oldest].frame))
        oldest = cell;
    }
    if (oldest < 0) return -1;
    skins.values().remove(skinCells[oldest]);
    skinCells[oldest] = null;
    skinsEvicted++;
    return oldest;
  }

  private static void block(String reason, RuntimeException error) {
    if (!blocked.equals(reason))
      DrawLess.LOGGER.warn(
          "Retained player armor geometry disabled ({}); using vanilla rendering", reason, error);
    blocked = reason;
    enabled = levelSubmit = false;
  }

  public static String diagnostic() {
    if (!requested()) return IrisShaders.packInUse() ? "status=shader-pack" : "status=off";
    int meshes = 0;
    for (var part : parts.values())
      if (part.supported) meshes += (part.plain == null ? 0 : 1) + part.mapped.size();
    var reasonText = new StringBuilder();
    for (var reason : Reason.values())
      if (reason != Reason.READY && reasons[reason.ordinal()] > 0) {
        if (!reasonText.isEmpty()) reasonText.append(',');
        reasonText.append(reason.label).append(':').append(reasons[reason.ordinal()]);
      }
    return String.format(
        Locale.ROOT,
        "status=%s; pieces=%d; fallbackPieces=%d; submits=%d; replicatedSubmits=%d; "
            + "instances=%d; draws=%d; "
            + "framesTotal=%d; piecesTotal=%d; fallbackPiecesTotal=%d; submitsTotal=%d; "
            + "replicatedSubmitsTotal=%d; "
            + "instancesTotal=%d; drawsTotal=%d; parts=%d; meshes=%d; meshVertices=%d; "
            + "meshBuilds=%d; indexRebuilds=%d; geometryUploadedBytes=%d; "
            + "instanceUploadedBytes=%d; atlasTextures=%d; capture=%.4fms; upload=%.4fms; "
            + "drawCpu=%.4fms; "
            + "captureTotalMs=%.4f; uploadTotalMs=%.4f; drawTotalMs=%.4f; fallback=%s; "
            + "bodies=%d; bodyFallbacks=%d; bodiesTotal=%d; bodyFallbacksTotal=%d; skins=%d; "
            + "skinsAdmitted=%d; skinsPartial=%d; skinsEvicted=%d; bodyCapture=%.4fms; "
            + "bodyCaptureTotalMs=%.4f; bodyFallback=%s",
        enabled ? "on" : blocked.isEmpty() ? "idle" : blocked,
        framePieces,
        frameFallbackPieces,
        frameSubmits,
        frameReplicated,
        frameInstances,
        frameDraws,
        framesTotal,
        piecesTotal,
        fallbackPiecesTotal,
        submitsTotal,
        replicatedTotal,
        instancesTotal,
        drawsTotal,
        parts.size(),
        meshes,
        geometry.vertices(),
        meshBuilds,
        indexRebuilds,
        geometry.uploadedBytes(),
        instanceUploadedBytes,
        atlasTextures,
        captureNanos / 1e6,
        uploadNanos / 1e6,
        drawNanos / 1e6,
        captureNanosTotal / 1e6,
        uploadNanosTotal / 1e6,
        drawNanosTotal / 1e6,
        reasonText.isEmpty() ? "none" : reasonText,
        frameBodies,
        frameBodyFallbacks,
        bodiesTotal,
        bodyFallbacksTotal,
        (int) Arrays.stream(skinCells).filter(Objects::nonNull).count(),
        skinsAdmitted,
        skinsPartial,
        skinsEvicted,
        bodyNanos / 1e6,
        bodyNanosTotal / 1e6,
        reasons(bodyReasons));
  }

  private static String reasons(long[] counts) {
    var text = new StringBuilder();
    for (var reason : Reason.values())
      if (reason != Reason.READY && counts[reason.ordinal()] > 0) {
        if (!text.isEmpty()) text.append(',');
        text.append(reason.label).append(':').append(counts[reason.ordinal()]);
      }
    return text.isEmpty() ? "none" : text.toString();
  }

  private enum Reason {
    READY("ready"),
    MODEL("model"),
    OUTLINE("outline-or-crumbling"),
    SPRITE("animated-sprite"),
    PIPELINE("pipeline"),
    TEXTURE("texture"),
    CAPACITY("capacity"),
    MESH("mesh"),
    RAW("raw-vertices"),
    TINT("tint"),
    SKIN("skin-texture"),
    SKIN_PARTIAL("skin-semi-transparent"),
    ERROR("error");
    final String label;

    Reason(String label) {
      this.label = label;
    }
  }

  private record Mesh(int firstVertex, int vertexCount) implements RetainedMesh {}

  /** A skin's atlas cell (texel origin inside the gutter) and the last frame that drew it. */
  private static final class SkinSlot {
    static final SkinSlot REFUSED = new SkinSlot(-1, 0, 0), PARTIAL = new SkinSlot(-1, 0, 0);
    final int cell, x, y;
    long frame;

    SkinSlot(int cell, int x, int y) {
      this.cell = cell;
      this.x = x;
      this.y = y;
    }
  }

  /** Base-armor texture placement inside the atlas; exact for power-of-two sizes. */
  private record AtlasSlot(int x, int y, int width, int height) {
    float u(float u) {
      return (x + u * width) / ATLAS_SIZE;
    }

    float v(float v) {
      return (y + v * height) / ATLAS_SIZE;
    }
  }

  /** Resource-lifetime topology snapshot of one model part; identity-validated on every use. */
  private static final class PartMeshes {
    final ModelPart.Cube[] cubes;
    final ModelPart.Polygon[][] polygons;
    final boolean supported;
    final int vertexCount;
    final IdentityHashMap<Object, Mesh> mapped = new IdentityHashMap<>(4);
    Mesh plain;

    private PartMeshes(ModelPart.Cube[] cubes, ModelPart.Polygon[][] polygons, int vertexCount) {
      this.cubes = cubes;
      this.polygons = polygons;
      this.vertexCount = vertexCount;
      supported = vertexCount > 0;
    }

    static PartMeshes capture(List<ModelPart.Cube> list) {
      var cubes = list.toArray(new ModelPart.Cube[0]);
      var polygons = new ModelPart.Polygon[cubes.length][];
      int count = 0;
      for (int c = 0; c < cubes.length; c++) {
        polygons[c] = cubes[c].polygons.clone();
        for (var polygon : polygons[c]) {
          // Instances carry quantized axis bases; only axis-aligned quads stay exact.
          if (polygon == null || polygon.vertices().length != 4 || !axis(polygon.normal()))
            return new PartMeshes(cubes, polygons, 0);
          count += 4;
        }
      }
      return new PartMeshes(cubes, polygons, count);
    }

    private static boolean axis(org.joml.Vector3fc n) {
      int nonzero = 0;
      for (float value : new float[] {n.x(), n.y(), n.z()}) {
        if (value == 0) continue;
        if (value != 1 && value != -1) return false;
        nonzero++;
      }
      return nonzero == 1;
    }

    boolean matches(List<ModelPart.Cube> current) {
      if (current.size() != cubes.length) return false;
      for (int c = 0; c < cubes.length; c++) {
        var cube = current.get(c);
        if (cube != cubes[c] || cube.polygons.length != polygons[c].length) return false;
        for (int p = 0; p < polygons[c].length; p++)
          if (cube.polygons[p] != polygons[c][p]) return false;
      }
      return true;
    }
  }

  private static final class Instance extends RetainedInstance {
    ModelPart part;

    void set(ModelPart part, Mesh mesh, PoseStack.Pose pose, int color, int light, int overlay) {
      this.part = part;
      set(mesh, pose, color, light, overlay);
    }

    void copy(Instance source, Mesh mesh, int color, int light, int overlay) {
      part = source.part;
      copy((RetainedInstance) source, mesh, color, light, overlay);
    }
  }

  private static final class Batch extends RetainedBatch<Instance> {
    final int rank;
    final boolean atlas, skins;
    int pieceStart, sequence;

    Batch(RenderType type, int rank, boolean atlas, boolean skins) {
      super(type, LABEL, 4096);
      this.rank = rank;
      this.atlas = atlas;
      this.skins = skins;
    }

    Instance next() {
      return next(Instance::new);
    }
  }

  /** A withheld vanilla submit; references are released when its piece finishes. */
  private static final class Pending {
    OrderedSubmitNodeCollector collector;
    Model<?> model;
    Object state;
    PoseStack pose;
    RenderType type;
    int light, overlay, color, outline;
    TextureAtlasSprite sprite;
    ModelFeatureRenderer.CrumblingOverlay crumbling;
    Operation<Void> original;

    void set(
        OrderedSubmitNodeCollector collector,
        Model<?> model,
        Object state,
        PoseStack pose,
        RenderType type,
        int light,
        int overlay,
        int color,
        TextureAtlasSprite sprite,
        int outline,
        ModelFeatureRenderer.CrumblingOverlay crumbling,
        Operation<Void> original) {
      this.collector = collector;
      this.model = model;
      this.state = state;
      this.pose = pose;
      this.type = type;
      this.light = light;
      this.overlay = overlay;
      this.color = color;
      this.sprite = sprite;
      this.outline = outline;
      this.crumbling = crumbling;
      this.original = original;
    }

    void replay() {
      original.call(
          collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling);
    }

    void release() {
      collector = null;
      model = null;
      state = null;
      pose = null;
      type = null;
      sprite = null;
      crumbling = null;
      original = null;
    }
  }

  /** Receives only part transforms; any raw vertex means an unknown writer and a fallback. */
  private static final class Marker implements VertexConsumer {
    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      raw = true;
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      raw = true;
      return this;
    }

    @Override
    public void addVertex(
        float x,
        float y,
        float z,
        int color,
        float u,
        float v,
        int overlay,
        int light,
        float nx,
        float ny,
        float nz) {
      raw = true;
    }
  }
}
