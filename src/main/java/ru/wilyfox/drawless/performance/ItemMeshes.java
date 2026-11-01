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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL31;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;

/**
 * Shared owner of retained local item meshes.
 *
 * <p>Key: the identity sequence of a {@code List<BakedQuad>}; immutable lists may also be aliased
 * by identity ({@link #forImmutable}), refilled lists are looked up by content ({@link
 * #forContent}). Value: one local mesh per material (item RenderType, tint index, light emission)
 * in one shared texel buffer, or a remembered refusal (transparency, unsupported pipeline).
 * Lifetime: until resource reload, world change, disconnect, client stop, or the frame no consumer
 * requests meshes. Render thread only. Limits: vertex and key counts are bounded. A lookup that
 * hits a limit is refused (capacity) for that frame and requests eviction; at the start of the next
 * frame, before any consumer captures, keys unused for {@link #evictAfterNanos} are dropped and the
 * surviving meshes are compacted into new Mesh objects. Every compaction advances {@link
 * #generation()}: a consumer holding meshes across frames must drop them when it changes. Consumers
 * keep their own frame instance lists and turn off independently; a consumer's runtime failure does
 * not clear meshes.
 */
final class ItemMeshes {
  private static final int MAX_MESHES = 4096, MAX_ALIASES = 8192;
  private static final long RETRY_NANOS = 5_000_000_000L;

  /** Age after which an unused key may be evicted under pressure; adjustable by local tests. */
  static long evictAfterNanos = 30_000_000_000L;

  private static final Identifier ITEM_SHADER = Identifier.withDefaultNamespace("core/item");
  private static final Identifier VERTEX_SHADER =
      Identifier.fromNamespaceAndPath("drawless", "core/live_item");
  private static final Identifier GLINT_SHADER = Identifier.withDefaultNamespace("core/glint");
  // Decodes the shared mesh/instance layout; its position expression matches live_item.vsh.
  private static final Identifier GLINT_VERTEX =
      Identifier.fromNamespaceAndPath("drawless", "core/live_armor_glint");
  private static final IdentityHashMap<List<BakedQuad>, Geometry> aliases = new IdentityHashMap<>();
  private static final HashMap<Object, Geometry> geometries = new HashMap<>();
  private static final Probe probe = new Probe();
  private static final RetainedGeometryBuffer buffer =
      new RetainedGeometryBuffer("DrawLess shared local Item Display geometry");
  private static final RetainedPipelines pipelines = new RetainedPipelines("live/");
  private static ClientLevel level;
  private static boolean registered, checked;
  private static String blocked = "";
  private static int meshCount, buildsThisFrame, keyQuads, generation;
  private static long meshBuilds, buildNanos, now, nextCompaction;
  private static long compactions, evictedKeys;
  private static boolean pressure;

  private ItemMeshes() {}

  static void register() {
    if (registered) return;
    registered = true;
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
  }

  /** True while at least one consumer may draw item meshes. */
  static boolean requested() {
    return LiveDisplayGeometry.requested() || PlayerItemGeometry.requested();
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
    now = System.nanoTime();
    if (pressure && now >= nextCompaction) compact();
  }

  /**
   * Drops keys unused for {@link #evictAfterNanos} and moves the surviving vertices to the front.
   * Surviving keys get new Geometry and Mesh objects, so retained index buffers rebuild themselves
   * and no consumer can keep a stale vertex range.
   */
  private static void compact() {
    pressure = false;
    long cutoff = now - evictAfterNanos;
    var survivors = new ArrayList<Map.Entry<Object, Geometry>>();
    for (var entry : geometries.entrySet())
      if (entry.getValue().lastUsed >= cutoff) survivors.add(entry);
    if (survivors.size() == geometries.size()) {
      nextCompaction = now + RETRY_NANOS;
      return;
    }
    long start = System.nanoTime();
    var meshes = new ArrayList<Mesh>();
    for (var entry : survivors) meshes.addAll(entry.getValue().meshes);
    meshes.sort(Comparator.comparingInt((Mesh mesh) -> mesh.firstVertex));
    var firsts = new int[meshes.size()];
    var counts = new int[meshes.size()];
    for (int i = 0; i < firsts.length; i++) {
      firsts[i] = meshes.get(i).firstVertex;
      counts[i] = meshes.get(i).vertexCount;
    }
    var moved = buffer.compact(firsts, counts);
    var relocated = new IdentityHashMap<Mesh, Mesh>(meshes.size());
    for (int i = 0; i < firsts.length; i++)
      relocated.put(meshes.get(i), meshes.get(i).at(moved[i]));
    var replaced = new IdentityHashMap<Geometry, Geometry>(survivors.size());
    evictedKeys += geometries.size() - survivors.size();
    geometries.clear();
    keyQuads = 0;
    for (var entry : survivors) {
      var old = entry.getValue();
      var next = new ArrayList<Mesh>(old.meshes.size());
      for (var mesh : old.meshes) next.add(relocated.get(mesh));
      var geometry = new Geometry(old.status, List.copyOf(next));
      geometry.lastUsed = old.lastUsed;
      replaced.put(old, geometry);
      geometries.put(entry.getKey(), geometry);
      keyQuads += ((QuadKey) entry.getKey()).quads.length;
    }
    aliases.entrySet().removeIf(alias -> !replaced.containsKey(alias.getValue()));
    aliases.replaceAll((list, geometry) -> replaced.get(geometry));
    meshCount = meshes.size();
    generation++;
    compactions++;
    nextCompaction = now + RETRY_NANOS;
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
    if (!vanilla(client, "shaders/core/item.vsh", "shaders/core/item.fsh"))
      return "custom-item-shader";
    return "";
  }

  private static boolean vanilla(Minecraft client, String... files) {
    for (String file : files) {
      var resource = client.getResourceManager().getResource(Identifier.withDefaultNamespace(file));
      if (resource.isEmpty() || !resource.get().source().equals(client.getVanillaPackResources()))
        return false;
    }
    return true;
  }

  static void clear() {
    aliases.clear();
    geometries.clear();
    buffer.close();
    level = null;
    checked = pressure = false;
    blocked = "";
    meshCount = buildsThisFrame = keyQuads = 0;
    meshBuilds = buildNanos = nextCompaction = compactions = evictedKeys = 0;
    // Every mesh object is gone: holders from before the clear must not match a later store.
    generation++;
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
    if (cached != null) return used(cached);
    if (quads.size() > 16384) return Geometry.CAPACITY;
    if (aliases.size() >= MAX_ALIASES || keyQuads + (long) quads.size() > 1_048_576) return full();
    var key = new QuadKey(quads);
    cached = geometries.get(key);
    if (cached != null) {
      aliases.put(quads, cached);
      return used(cached);
    }
    return build(key, quads, quads);
  }

  /**
   * Meshes for a quad list that a caller refills between frames (an extracted layer of a living
   * entity): looked up by content every call, never remembered by identity.
   */
  static Geometry forContent(List<BakedQuad> quads) {
    if (quads.size() > 16384) return Geometry.CAPACITY;
    probe.quads = quads;
    try {
      var cached = geometries.get(probe);
      if (cached != null) return used(cached);
    } finally {
      probe.quads = null;
    }
    if (keyQuads + (long) quads.size() > 1_048_576) return full();
    return build(new QuadKey(quads), quads, null);
  }

  /** {@code alias}: the immutable list to remember by identity, or null. */
  private static Geometry build(QuadKey key, List<BakedQuad> quads, List<BakedQuad> alias) {
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
          return remember(key, alias, Geometry.TRANSPARENT);
        var pipeline = type.pipeline();
        if (!pipeline.getVertexShader().equals(ITEM_SHADER)
            || !pipeline.getFragmentShader().equals(ITEM_SHADER)
            || !pipeline.getShaderDefines().flags().isEmpty()
            || pipeline.getShaderDefines().values().keySet().stream()
                .anyMatch(name -> !name.equals("ALPHA_CUTOUT")))
          return remember(key, alias, Geometry.UNSUPPORTED);
        if (!pipelines.known(pipeline) && pipelines.size() >= 32)
          return remember(key, alias, Geometry.UNSUPPORTED);
        grouped
            .computeIfAbsent(
                new Material(type, info.isTinted() ? info.tintIndex() : -1, info.lightEmission()),
                k -> new ArrayList<>())
            .add(quad);
      }
      // A full store is not remembered as a refusal: eviction may make room next frame.
      if (meshCount + grouped.size() > MAX_MESHES || !buffer.fits((long) quads.size() * 4))
        return full();
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
      return remember(key, alias, new Geometry(Status.READY, List.copyOf(meshes)));
    } finally {
      buildNanos += System.nanoTime() - start;
    }
  }

  private static Geometry remember(QuadKey key, List<BakedQuad> alias, Geometry geometry) {
    if (geometries.size() >= MAX_MESHES) return full();
    // Refusals are per key too, so they age and can be evicted like meshes.
    if (geometry.status != Status.READY) geometry = new Geometry(geometry.status, List.of());
    used(geometry);
    geometries.put(key, geometry);
    keyQuads += key.quads.length;
    if (alias != null) aliases.put(alias, geometry);
    return geometry;
  }

  private static Geometry used(Geometry geometry) {
    geometry.lastUsed = now;
    return geometry;
  }

  private static Geometry full() {
    pressure = true;
    return Geometry.CAPACITY;
  }

  /** Advances whenever existing Mesh objects stop being valid (compaction or clear). */
  static int generation() {
    return generation;
  }

  static String diagnostic() {
    return String.format(
        Locale.ROOT,
        "itemStoreKeys=%d; itemStoreMeshes=%d; itemStoreVertices=%d/%d; itemStoreGeneration=%d; "
            + "itemStoreCompactions=%d; itemStoreEvictedKeys=%d",
        geometries.size(),
        meshCount,
        buffer.vertices(),
        buffer.max(),
        generation,
        compactions,
        evictedKeys);
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

  /**
   * Glint variant over retained item meshes, shared by every glint RenderType of items (they differ
   * only in texture, texturing and output target). Null when glint must stay ordinary: a resource
   * pack replaced the glint shader, or the pipeline was refused or failed to compile.
   */
  static RenderPipeline glintPipeline(Minecraft client) {
    var base = RenderPipelines.GLINT;
    if (pipelines.known(base)) return pipelines.get(base);
    if (!vanilla(client, "shaders/core/glint.vsh", "shaders/core/glint.fsh")
        || !base.getVertexShader().equals(GLINT_SHADER)
        || !base.getFragmentShader().equals(GLINT_SHADER)
        || !base.getShaderDefines().flags().isEmpty()
        || !base.getShaderDefines().values().isEmpty()) return pipelines.reject(base);
    var result = pipelines.compile(base, GLINT_VERTEX);
    return result == null ? pipelines.reject(base) : result;
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

    /** The same material at another place in the buffer, as a new object. */
    Mesh at(int first) {
      return new Mesh(type, first, vertexCount, tint, emission, sprites);
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

  static final class Geometry {
    static final Geometry TRANSPARENT = new Geometry(Status.TRANSPARENT, List.of()),
        UNSUPPORTED = new Geometry(Status.UNSUPPORTED, List.of()),
        WARMING = new Geometry(Status.WARMING, List.of()),
        CAPACITY = new Geometry(Status.CAPACITY, List.of());

    final Status status;
    final List<Mesh> meshes;

    /** {@link System#nanoTime()} of the frame that last looked this key up. */
    long lastUsed;

    Geometry(Status status, List<Mesh> meshes) {
      this.status = status;
      this.meshes = meshes;
    }

    Status status() {
      return status;
    }

    List<Mesh> meshes() {
      return meshes;
    }
  }

  /** Full sequence equality is only needed on a new list identity, never on a warm frame. */
  private static final class QuadKey {
    final BakedQuad[] quads;
    final int hash;

    QuadKey(List<BakedQuad> source) {
      quads = source.toArray(BakedQuad[]::new);
      hash = hash(source);
    }

    static int hash(List<BakedQuad> quads) {
      int h = 1;
      for (int i = 0, n = quads.size(); i < n; i++)
        h = 31 * h + System.identityHashCode(quads.get(i));
      return h;
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

  /**
   * Allocation-free lookup view of a live list; HashMap calls {@code probe.equals(storedKey)}. Held
   * only for the duration of one lookup.
   */
  private static final class Probe {
    List<BakedQuad> quads;

    @Override
    public int hashCode() {
      return QuadKey.hash(quads);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof QuadKey key) || key.quads.length != quads.size()) return false;
      for (int i = 0; i < key.quads.length; i++) if (key.quads[i] != quads.get(i)) return false;
      return true;
    }
  }
}
