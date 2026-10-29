package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
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
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;
import ru.wilyfox.drawless.DrawLess;
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
 */
public final class PlayerArmorGeometry {
  private static final int MAX_MESH_VERTICES = 1 << 18, MAX_PARTS = 2048;
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
  private static final BindGroupLayout MESH_LAYOUT =
      BindGroupLayout.builder()
          .withUniform("FHGeometry", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
          .withUniform("FHInstances", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
          .build();
  private static final VertexConsumer MARKER = new Marker();
  private static final IdentityHashMap<ModelPart, PartMeshes> parts = new IdentityHashMap<>();
  private static final IdentityHashMap<RenderPipeline, RenderPipeline> pipelines =
      new IdentityHashMap<>();
  private static final IdentityHashMap<RenderType, ArrayList<Batch>> batches =
      new IdentityHashMap<>();
  private static final ArrayList<Batch> active = new ArrayList<>();
  private static final ArrayList<Pending> pending = new ArrayList<>();
  private static final ArrayList<Batch> touched = new ArrayList<>();
  private static final Vector3f normal = new Vector3f();
  private static final long[] reasons = new long[Reason.values().length];

  private static ClientLevel world;
  private static boolean registered, checked, enabled, levelSubmit, inPiece, pieceFailed;
  private static boolean raw, partFailed, drawn = true;
  private static String blocked = "off";
  private static ByteBuffer geometryData;
  private static GpuBuffer geometryBuffer;
  private static int vertices, uploadedVertices, maxVertices, pendingCount, order, batchSequence;
  private static int frameInstances, frameSubmits, framePieces, frameFallbackPieces, frameDraws;
  private static Batch captureBatch, templateBatch;
  private static Model<?> templateModel;
  private static Object templateState;
  private static TextureAtlasSprite templateSprite;
  private static final Matrix4f templatePose = new Matrix4f();
  private static int templateStart, templateEnd, frameReplicated;
  private static long replicatedTotal;
  private static PoseStack capturePose = new PoseStack();
  private static TextureAtlasSprite captureSprite;
  private static long meshBuilds, indexRebuilds, geometryUploadedBytes, instanceUploadedBytes;
  private static long framesTotal, piecesTotal, fallbackPiecesTotal, submitsTotal, instancesTotal;
  private static long drawsTotal, captureNanosTotal, uploadNanosTotal, drawNanosTotal;
  private static long captureNanos, uploadNanos, drawNanos;

  private PlayerArmorGeometry() {}

  public static void register() {
    if (registered) return;
    registered = true;
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
    LevelRenderEvents.AFTER_SOLID_FEATURES.register(PlayerArmorGeometry::draw);
  }

  public static boolean requested() {
    var config = DrawLessConfig.get();
    return config.enabled && config.playerArmorGeometry && !RenderModSupport.IRIS;
  }

  /** Resource reload replaces model parts and atlas sprites; pipelines follow the device. */
  public static void reload() {
    clear();
    pipelines.clear();
  }

  public static void clear() {
    for (var list : batches.values()) for (var batch : list) batch.close();
    batches.clear();
    active.clear();
    parts.clear();
    releasePiece();
    if (geometryBuffer != null) {
      geometryBuffer.close();
      geometryBuffer = null;
    }
    if (geometryData != null) {
      MemoryUtil.memFree(geometryData);
      geometryData = null;
    }
    world = null;
    checked = enabled = levelSubmit = false;
    drawn = true;
    blocked = "off";
    vertices = uploadedVertices = maxVertices = batchSequence = 0;
    frameInstances = frameSubmits = framePieces = frameFallbackPieces = frameDraws = 0;
    meshBuilds = indexRebuilds = geometryUploadedBytes = instanceUploadedBytes = 0;
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
      if (world != null || geometryBuffer != null) clear();
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
    if (!checked) {
      checked = true;
      blocked = compatibility(client);
      if (blocked.isEmpty()) {
        maxVertices =
            Math.min(MAX_MESH_VERTICES, GL31.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE) / 2);
        if (maxVertices < 4096) blocked = "texel-buffer-limit";
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
    if (!levelSubmit || inPiece) return;
    inPiece = true;
    pieceFailed = false;
    order = 0;
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
    templateSprite = null;
  }

  public static void order(int value) {
    if (inPiece) order = value;
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
    var batch = batch(type, order);
    if (batch == null) return Reason.CAPACITY;
    if (!touched.contains(batch)) {
      batch.pieceStart = batch.used;
      touched.add(batch);
    }
    if (templateBatch != null
        && model == templateModel
        && state == templateState
        && templatePose.equals(pose.last().pose()))
      return replicate(batch, light, overlay, color, sprite);
    int start = batch.used;
    captureBatch = batch;
    // Vanilla preparation would wrap the consumer with this sprite; meshes bake the same UVs.
    captureSprite = sprite;
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
      captureSprite = null;
    }
    if (raw) return Reason.RAW;
    if (partFailed) return Reason.MESH;
    if (templateBatch == null) {
      templateBatch = batch;
      templateStart = start;
      templateEnd = batch.used;
      templateModel = model;
      templateState = state;
      templateSprite = sprite;
      templatePose.set(pose.last().pose());
    }
    return Reason.READY;
  }

  /**
   * Base, glint, overlay and trim members of a piece prepare the same model and state at the same
   * pose; vanilla would repeat identical animation setup and traversal for each. Copy the captured
   * part transforms instead, keeping each member's own material, tint, light and sprite UVs.
   */
  private static Reason replicate(
      Batch batch, int light, int overlay, int color, TextureAtlasSprite sprite) {
    for (int i = templateStart; i < templateEnd; i++) {
      var source = templateBatch.instances.get(i);
      var mesh =
          sprite == templateSprite
              ? source.mesh
              : mesh(
                  source.part,
                  ((ModelPartCubesAccessor) (Object) source.part).drawless$cubes(),
                  sprite);
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
    var mesh = mesh(part, cubes, captureSprite);
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
  }

  private static void rollback() {
    for (var batch : touched) batch.used = batch.pieceStart;
    touched.clear();
  }

  private static Mesh mesh(ModelPart part, List<ModelPart.Cube> cubes, TextureAtlasSprite sprite) {
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
    var mesh = sprite == null ? meshes.plain : meshes.sprites.get(sprite);
    if (mesh != null) return mesh;
    mesh = build(meshes, sprite);
    if (mesh == null) return null;
    if (sprite == null) meshes.plain = mesh;
    else meshes.sprites.put(sprite, mesh);
    return mesh;
  }

  private static Mesh build(PartMeshes meshes, TextureAtlasSprite sprite) {
    int count = meshes.vertexCount;
    if (vertices + count > maxVertices) return null;
    ensureCpuCapacity((vertices + count) * VERTEX_BYTES);
    int first = vertices;
    for (var polygons : meshes.polygons)
      for (var polygon : polygons) {
        var n = polygon.normal();
        for (var vertex : polygon.vertices()) {
          float u = sprite == null ? vertex.u() : sprite.getU(vertex.u());
          float v = sprite == null ? vertex.v() : sprite.getV(vertex.v());
          geometryData.putFloat(vertex.worldX()).putFloat(vertex.worldY());
          geometryData.putFloat(vertex.worldZ()).putFloat(u);
          geometryData.putFloat(n.x()).putFloat(n.y()).putFloat(n.z()).putFloat(v);
          vertices++;
        }
      }
    meshBuilds++;
    return new Mesh(first, count);
  }

  private static void ensureCpuCapacity(int size) {
    if (geometryData == null)
      geometryData =
          MemoryUtil.memAlloc(Math.min(maxVertices * VERTEX_BYTES, Math.max(65536, size)));
    else if (size > geometryData.capacity())
      geometryData =
          MemoryUtil.memRealloc(
              geometryData,
              Math.min(maxVertices * VERTEX_BYTES, Math.max(size, geometryData.capacity() * 2)));
    geometryData.position(vertices * VERTEX_BYTES);
  }

  private static RenderPipeline pipeline(RenderType type) {
    var base = type.pipeline();
    var cached = pipelines.get(base);
    if (cached != null) return cached;
    if (pipelines.containsKey(base)) return null;
    Identifier vertex;
    if (base == RenderPipelines.ARMOR_CUTOUT_NO_CULL
        || base == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
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
    var snippet =
        new RenderPipeline.Snippet(
            Optional.of(vertex),
            Optional.of(base.getFragmentShader()),
            Optional.of(base.getShaderDefines()),
            Optional.of(base.getBindGroupLayouts()),
            base.getColorTargetStates(),
            base.getColorTargetStates().length,
            Optional.ofNullable(base.getDepthStencilState()),
            Optional.of(base.getPolygonMode()),
            Optional.of(base.isCull()),
            new VertexFormat[com.mojang.blaze3d.systems.RenderPass.MAX_VERTEX_BUFFERS],
            Optional.of(base.getPrimitiveTopology()));
    var result =
        RenderPipeline.builder(snippet)
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "drawless", "live_armor/" + base.getLocation().getPath()))
            .withBindGroupLayout(MESH_LAYOUT)
            .build();
    // Compile before the first piece is withheld; a shader error must not hide vanilla armor.
    if (!RenderSystem.getDevice().precompilePipeline(result).isValid()) {
      block("shader-compile", null);
      return reject(base);
    }
    pipelines.put(base, result);
    return result;
  }

  private static RenderPipeline reject(RenderPipeline base) {
    pipelines.put(base, null);
    return null;
  }

  private static Batch batch(RenderType type, int order) {
    var list = batches.computeIfAbsent(type, key -> new ArrayList<>(2));
    for (var batch : list)
      if (batch.order == order && batch.used < MAX_INSTANCES) {
        activate(batch);
        return batch;
      }
    if (batches.size() > 256 || list.size() > 64) return null;
    var batch = new Batch(type, order);
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
      uploadGeometry();
      // Vanilla routes blended model submits (glint) to the translucent model phase, after every
      // solid group, so glint also covers later overlay and trim layers. Solid groups follow
      // ascending submit order; within an order keep first submission.
      active.sort(
          Comparator.comparingInt((Batch b) -> b.type.hasBlending() ? 1 : 0)
              .thenComparingInt(b -> b.order)
              .thenComparingInt(b -> b.sequence));
      for (var batch : active) if (batch.used > 0) batch.prepare();
      uploadNanos = System.nanoTime() - start;
      uploadNanosTotal += uploadNanos;
      long drawStart = System.nanoTime();
      matrix.set(context.levelState().cameraRenderState.viewRotationMatrix);
      frameDraws = 0;
      drawBatches();
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

  /** One render pass per output target; vanilla would open one per group draw. */
  private static void drawBatches() {
    RenderPass pass = null;
    GpuTextureView passColor = null, passDepth = null;
    boolean scissored = false;
    try {
      for (var batch : active) {
        if (batch.used == 0) continue;
        var prepared = batch.type.prepare();
        var target = prepared.outputTarget().getRenderTarget();
        var color =
            RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth =
            target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride
                    : target.getDepthTextureView())
                : null;
        if (pass == null || color != passColor || depth != passDepth) {
          if (pass != null) pass.close();
          pass =
              RenderSystem.getDevice()
                  .createCommandEncoder()
                  .createRenderPass(
                      () -> "DrawLess retained armor",
                      color,
                      Optional.empty(),
                      depth,
                      OptionalDouble.empty());
          passColor = color;
          passDepth = depth;
          scissored = false;
        }
        pass.setPipeline(pipelines.get(batch.type.pipeline()));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
        pass.setUniform("FHGeometry", geometryBuffer);
        pass.setUniform("FHInstances", batch.live.currentBuffer());
        var scissor = prepared.scissorState();
        if (scissor.enabled()) {
          pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
          scissored = true;
        } else if (scissored) {
          pass.disableScissor();
          scissored = false;
        }
        for (var texture : prepared.textures())
          pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
        pass.setIndexBuffer(batch.indices, IndexType.INT);
        pass.drawIndexed(batch.indexCount, 1, 0, 0, 0);
        frameDraws++;
      }
    } finally {
      if (pass != null) pass.close();
      for (var batch : active) if (batch.used > 0 && batch.live != null) batch.live.rotate();
    }
  }

  private static void uploadGeometry() {
    if (vertices == uploadedVertices) return;
    int size = vertices * VERTEX_BYTES;
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    if (geometryBuffer == null || geometryBuffer.size() < size) {
      var next =
          RenderSystem.getDevice()
              .createBuffer(
                  () -> "DrawLess retained armor geometry",
                  GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
                      | GpuBuffer.USAGE_COPY_DST
                      | GpuBuffer.USAGE_COPY_SRC,
                  geometryData.capacity());
      if (geometryBuffer != null) {
        encoder.copyToBuffer(
            geometryBuffer.slice(0, (long) uploadedVertices * VERTEX_BYTES),
            next.slice(0, (long) uploadedVertices * VERTEX_BYTES));
        geometryBuffer.close();
      }
      geometryBuffer = next;
    }
    int offset = uploadedVertices * VERTEX_BYTES;
    encoder.writeToBuffer(
        geometryBuffer.slice(offset, size - offset), geometryData.slice(offset, size - offset));
    geometryUploadedBytes += size - offset;
    uploadedVertices = vertices;
  }

  private static void block(String reason, RuntimeException error) {
    if (!blocked.equals(reason))
      DrawLess.LOGGER.warn(
          "Retained player armor geometry disabled ({}); using vanilla rendering", reason, error);
    blocked = reason;
    enabled = levelSubmit = false;
  }

  public static String diagnostic() {
    if (!requested()) return "status=off";
    int meshes = 0;
    for (var part : parts.values())
      if (part.supported) meshes += (part.plain == null ? 0 : 1) + part.sprites.size();
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
            + "instanceUploadedBytes=%d; capture=%.4fms; upload=%.4fms; drawCpu=%.4fms; "
            + "captureTotalMs=%.4f; uploadTotalMs=%.4f; drawTotalMs=%.4f; fallback=%s",
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
        vertices,
        meshBuilds,
        indexRebuilds,
        geometryUploadedBytes,
        instanceUploadedBytes,
        captureNanos / 1e6,
        uploadNanos / 1e6,
        drawNanos / 1e6,
        captureNanosTotal / 1e6,
        uploadNanosTotal / 1e6,
        drawNanosTotal / 1e6,
        reasonText.isEmpty() ? "none" : reasonText);
  }

  private enum Reason {
    READY("ready"),
    MODEL("model"),
    OUTLINE("outline-or-crumbling"),
    SPRITE("animated-sprite"),
    PIPELINE("pipeline"),
    CAPACITY("capacity"),
    MESH("mesh"),
    RAW("raw-vertices"),
    ERROR("error");
    final String label;

    Reason(String label) {
      this.label = label;
    }
  }

  private record Mesh(int firstVertex, int vertexCount) {}

  /** Resource-lifetime topology snapshot of one model part; identity-validated on every use. */
  private static final class PartMeshes {
    final ModelPart.Cube[] cubes;
    final ModelPart.Polygon[][] polygons;
    final boolean supported;
    final int vertexCount;
    final IdentityHashMap<TextureAtlasSprite, Mesh> sprites = new IdentityHashMap<>(2);
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

  private static final class Instance {
    ModelPart part;
    Mesh mesh;
    final Matrix4f transform = new Matrix4f();
    final float[] normals = new float[9];
    int color, light, overlay;

    void set(ModelPart part, Mesh mesh, PoseStack.Pose pose, int color, int light, int overlay) {
      this.part = part;
      this.mesh = mesh;
      transform.set(pose.pose());
      this.color = color;
      this.light = light;
      this.overlay = overlay;
      // Cube normals are axis aligned: transform and quantize three bases exactly as the
      // packed signed-byte normal writer would for each face.
      for (int i = 0; i < 3; i++) {
        pose.transformNormal(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0, normal);
        normals[i * 3] = (byte) (int) (normal.x * 127) / 127f;
        normals[i * 3 + 1] = (byte) (int) (normal.y * 127) / 127f;
        normals[i * 3 + 2] = (byte) (int) (normal.z * 127) / 127f;
      }
    }

    void copy(Instance source, Mesh mesh, int color, int light, int overlay) {
      part = source.part;
      this.mesh = mesh;
      transform.set(source.transform);
      System.arraycopy(source.normals, 0, normals, 0, normals.length);
      this.color = color;
      this.light = light;
      this.overlay = overlay;
    }

    void write(ByteBuffer data) {
      int offset = data.position();
      transform.get(offset, data);
      data.position(offset + 64);
      for (int i = 0; i < 3; i++)
        data.putFloat(normals[i * 3])
            .putFloat(normals[i * 3 + 1])
            .putFloat(normals[i * 3 + 2])
            .putFloat(0);
      data.putInt(color).putInt(light).putInt(overlay).putInt(0);
    }
  }

  private static final class Batch implements AutoCloseable {
    final RenderType type;
    final int order;
    final ArrayList<Instance> instances = new ArrayList<>();
    Mesh[] previous = new Mesh[0];
    int used, pieceStart, sequence, indexCount, previousCount;
    GpuBuffer indices;
    MappableRingBuffer live;

    Batch(RenderType type, int order) {
      this.type = type;
      this.order = order;
    }

    Instance next() {
      if (used >= MAX_INSTANCES) return null;
      if (used == instances.size()) instances.add(new Instance());
      return instances.get(used++);
    }

    void prepare() {
      boolean same = previousCount == used;
      for (int i = 0; same && i < used; i++) same = previous[i] == instances.get(i).mesh;
      if (!same) {
        int count = 0;
        for (int i = 0; i < used; i++) count += instances.get(i).mesh.vertexCount / 4 * 6;
        var data = MemoryUtil.memAlloc(count * 4);
        try {
          for (int i = 0; i < used; i++) {
            var mesh = instances.get(i).mesh;
            for (int v = 0; v < mesh.vertexCount; v += 4) {
              int base = mesh.firstVertex + v;
              data.putInt(index(base, i)).putInt(index(base + 1, i)).putInt(index(base + 2, i));
              data.putInt(index(base + 2, i)).putInt(index(base + 3, i)).putInt(index(base, i));
            }
          }
          data.flip();
          var next =
              RenderSystem.getDevice()
                  .createBuffer(
                      () -> "DrawLess retained armor references", GpuBuffer.USAGE_INDEX, data);
          if (indices != null) indices.close();
          indices = next;
          indexCount = count;
          indexRebuilds++;
          if (previous.length < used) previous = new Mesh[Math.max(used, previous.length * 2)];
          for (int i = 0; i < used; i++) previous[i] = instances.get(i).mesh;
          previousCount = used;
        } finally {
          MemoryUtil.memFree(data);
        }
      }
      int size = used * INSTANCE_BYTES;
      if (live == null || live.size() < size) {
        if (live != null) live.close();
        live =
            new MappableRingBuffer(
                () -> "DrawLess retained armor transforms",
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                Math.max(size, live == null ? 4096 : live.size() * 2));
      }
      try (var mapping = live.currentBuffer().slice(0, size).map(false, true)) {
        var data = mapping.data();
        for (int i = 0; i < used; i++) instances.get(i).write(data);
      }
      instanceUploadedBytes += size;
    }

    @Override
    public void close() {
      if (indices != null) indices.close();
      if (live != null) live.close();
      indices = null;
      live = null;
      previousCount = 0;
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
