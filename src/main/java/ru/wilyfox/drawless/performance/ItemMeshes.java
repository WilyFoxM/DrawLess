package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL31;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;

/**
 * Shared owner of retained local item meshes.
 *
 * <p>Key: the identity sequence of a {@code List<BakedQuad>}. Value: one local mesh per material
 * (item RenderType, tint index, light emission) in one shared texel buffer, or a remembered refusal
 * (transparency, unsupported pipeline). Lifetime: until resource reload, world change, disconnect,
 * client stop, or the frame no consumer requests meshes. Render thread only. Limits: vertex and key
 * counts are bounded; a full store refuses (capacity) rather than evicts. Consumers keep their own
 * frame instance lists and turn off independently; a consumer's runtime failure does not clear
 * meshes.
 */
final class ItemMeshes {
  private static final int MAX_MESHES = 4096, MAX_ALIASES = 8192;
  private static final Identifier ITEM_SHADER = Identifier.withDefaultNamespace("core/item");
  private static final Identifier VERTEX_SHADER =
      Identifier.fromNamespaceAndPath("drawless", "core/live_item");
  private static final IdentityHashMap<List<BakedQuad>, Geometry> aliases = new IdentityHashMap<>();
  private static final HashMap<QuadKey, Geometry> geometries = new HashMap<>();
  private static final RetainedGeometryBuffer buffer =
      new RetainedGeometryBuffer("DrawLess shared local Item Display geometry");
  private static final RetainedPipelines pipelines = new RetainedPipelines("live/");
  private static ClientLevel level;
  private static boolean registered, checked;
  private static String blocked = "";
  private static int meshCount, buildsThisFrame, keyQuads;
  private static long meshBuilds, buildNanos;

  private ItemMeshes() {}

  static void register() {
    if (registered) return;
    registered = true;
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
  }

  /** True while at least one consumer may draw item meshes. */
  static boolean requested() {
    return LiveDisplayGeometry.requested();
  }

  /**
   * Once per frame before any consumer captures: releases everything when no consumer requests
   * meshes and resets the shared per-frame build budget.
   */
  static void beginFrame(Minecraft client) {
    if (!requested() || client.level == null) {
      if (checked || buffer.allocated() || !geometries.isEmpty()) clear();
      return;
    }
    if (level != client.level) {
      clear();
      level = client.level;
    }
    buildsThisFrame = 0;
    buildNanos = 0;
  }

  /** Empty when meshes can be built; otherwise a stable fallback reason. Checked once per store. */
  static String prepare(Minecraft client) {
    if (!checked) {
      checked = true;
      blocked = compatibility(client);
      if (blocked.isEmpty()) {
        buffer.limit(
            Math.min(MAX_VERTICES, GL31.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE) / 2));
        if (buffer.max() < 4096) blocked = "texel-buffer-limit";
      }
    }
    return blocked;
  }

  private static String compatibility(Minecraft client) {
    if (!RenderSystem.getDevice().getDeviceInfo().backendName().equals("OpenGL"))
      return "unsupported-backend";
    if (RenderModSupport.IRIS) return "iris-vanilla-fallback";
    for (String file : List.of("shaders/core/item.vsh", "shaders/core/item.fsh")) {
      var resource = client.getResourceManager().getResource(Identifier.withDefaultNamespace(file));
      if (resource.isEmpty() || !resource.get().source().equals(client.getVanillaPackResources()))
        return "custom-item-shader";
    }
    return "";
  }

  static void clear() {
    aliases.clear();
    geometries.clear();
    buffer.close();
    level = null;
    checked = false;
    blocked = "";
    meshCount = buildsThisFrame = keyQuads = 0;
    meshBuilds = buildNanos = 0;
  }

  /** Resource reload replaces baked quads and sprites; pipelines follow the device. */
  static void reload() {
    clear();
    pipelines.clear();
  }

  /**
   * Meshes for a quad list whose contents never change while the list is reachable, such as layers
   * published by {@link DisplayModelBudget}: its identity is remembered as an alias of the key.
   */
  static Geometry forImmutable(List<BakedQuad> quads) {
    var cached = aliases.get(quads);
    if (cached != null) return cached;
    if (aliases.size() >= MAX_ALIASES
        || quads.size() > 16384
        || keyQuads + (long) quads.size() > 1_048_576) return Geometry.CAPACITY;
    var key = new QuadKey(quads);
    cached = geometries.get(key);
    if (cached != null) {
      aliases.put(quads, cached);
      return cached;
    }
    if (buildsThisFrame >= 4 || buildsThisFrame > 0 && buildNanos > 2_000_000)
      return Geometry.WARMING;
    long start = System.nanoTime();
    buildsThisFrame++;
    try (var ignored = RenderMetrics.getInstance().scope("render/liveDisplay/buildGeometry")) {
      var grouped = new LinkedHashMap<Material, List<BakedQuad>>();
      for (var quad : quads) {
        var info = quad.materialInfo();
        var type = info.itemRenderType();
        if (type.hasBlending() || type.sortOnUpload())
          return remember(key, quads, Geometry.TRANSPARENT);
        var pipeline = type.pipeline();
        if (!pipeline.getVertexShader().equals(ITEM_SHADER)
            || !pipeline.getFragmentShader().equals(ITEM_SHADER)
            || !pipeline.getShaderDefines().flags().isEmpty()
            || pipeline.getShaderDefines().values().keySet().stream()
                .anyMatch(name -> !name.equals("ALPHA_CUTOUT")))
          return remember(key, quads, Geometry.UNSUPPORTED);
        if (!pipelines.known(pipeline) && pipelines.size() >= 32)
          return remember(key, quads, Geometry.UNSUPPORTED);
        grouped
            .computeIfAbsent(
                new Material(type, info.isTinted() ? info.tintIndex() : -1, info.lightEmission()),
                k -> new ArrayList<>())
            .add(quad);
      }
      if (meshCount + grouped.size() > MAX_MESHES || !buffer.fits((long) quads.size() * 4))
        return remember(key, quads, Geometry.CAPACITY);
      // Compile before accepting a single part; shader errors cannot make the vanilla model vanish.
      for (var material : grouped.keySet()) pipeline(material.type.pipeline());
      buffer.begin(quads.size() * 4);
      var meshes = new ArrayList<Mesh>(grouped.size());
      for (var entry : grouped.entrySet()) {
        int offset = buffer.vertices();
        Set<TextureAtlasSprite> sprites = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var quad : entry.getValue()) {
          var n = quad.direction().getUnitVec3f();
          if (quad.materialInfo().sprite().isAnimated()) sprites.add(quad.materialInfo().sprite());
          for (int v = 0; v < 4; v++) {
            var p = quad.position(v);
            long uv = quad.packedUV(v);
            buffer.vertex(
                p.x(), p.y(), p.z(), UVPair.unpackU(uv), n.x(), n.y(), n.z(), UVPair.unpackV(uv));
          }
        }
        meshes.add(
            new Mesh(
                entry.getKey().type,
                offset,
                buffer.vertices() - offset,
                entry.getKey().tint,
                entry.getKey().emission,
                List.copyOf(sprites)));
        meshCount++;
        meshBuilds++;
      }
      return remember(key, quads, new Geometry(Status.READY, List.copyOf(meshes)));
    } finally {
      buildNanos += System.nanoTime() - start;
    }
  }

  private static Geometry remember(QuadKey key, List<BakedQuad> quads, Geometry geometry) {
    if (geometries.size() >= MAX_MESHES) return Geometry.CAPACITY;
    geometries.put(key, geometry);
    keyQuads += key.quads.length;
    aliases.put(quads, geometry);
    return geometry;
  }

  /** Derived pipeline for an item RenderType already admitted by {@link #forImmutable}. */
  static RenderPipeline pipeline(RenderPipeline base) {
    var cached = pipelines.get(base);
    if (cached != null) return cached;
    var result = pipelines.compile(base, VERTEX_SHADER);
    if (result == null)
      throw new IllegalStateException("Live Item Display shader could not compile");
    return result;
  }

  static RetainedPipelines pipelines() {
    return pipelines;
  }

  /** Uploads meshes built this frame; idempotent, so every consumer may call it. */
  static void upload() {
    buffer.upload();
  }

  static GpuBuffer buffer() {
    return buffer.buffer();
  }

  static int meshCount() {
    return meshCount;
  }

  static int keys() {
    return geometries.size();
  }

  static int aliases() {
    return aliases.size();
  }

  static long localBytes() {
    return (long) buffer.vertices() * VERTEX_BYTES;
  }

  static long meshBuilds() {
    return meshBuilds;
  }

  static long geometryUploads() {
    return buffer.uploads();
  }

  static long geometryUploadedBytes() {
    return buffer.uploadedBytes();
  }

  enum Status {
    READY,
    TRANSPARENT,
    UNSUPPORTED,
    WARMING,
    CAPACITY
  }

  private record Material(RenderType type, int tint, int emission) {}

  /** One retained material of an item model; immutable for the store's lifetime. */
  static final class Mesh implements RetainedMesh {
    final RenderType type;
    final int firstVertex, vertexCount, tint, emission;
    final List<TextureAtlasSprite> sprites;

    /**
     * Per-mesh state owned by the Live Item Display consumer; it validates the value against its
     * own generation before use, since consumers clear independently of the store.
     */
    Object live;

    Mesh(
        RenderType type,
        int firstVertex,
        int vertexCount,
        int tint,
        int emission,
        List<TextureAtlasSprite> sprites) {
      this.type = type;
      this.firstVertex = firstVertex;
      this.vertexCount = vertexCount;
      this.tint = tint;
      this.emission = emission;
      this.sprites = sprites;
    }

    @Override
    public int firstVertex() {
      return firstVertex;
    }

    @Override
    public int vertexCount() {
      return vertexCount;
    }
  }

  record Geometry(Status status, List<Mesh> meshes) {
    static final Geometry TRANSPARENT = new Geometry(Status.TRANSPARENT, List.of()),
        UNSUPPORTED = new Geometry(Status.UNSUPPORTED, List.of()),
        WARMING = new Geometry(Status.WARMING, List.of()),
        CAPACITY = new Geometry(Status.CAPACITY, List.of());
  }

  /** Full sequence equality is only needed on a new list identity, never on a warm frame. */
  private static final class QuadKey {
    final BakedQuad[] quads;
    final int hash;

    QuadKey(List<BakedQuad> source) {
      quads = source.toArray(BakedQuad[]::new);
      int h = 1;
      for (var q : quads) h = 31 * h + System.identityHashCode(q);
      hash = h;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof QuadKey key) || hash != key.hash || quads.length != key.quads.length)
        return false;
      for (int i = 0; i < quads.length; i++) if (quads[i] != key.quads[i]) return false;
      return true;
    }
  }
}
