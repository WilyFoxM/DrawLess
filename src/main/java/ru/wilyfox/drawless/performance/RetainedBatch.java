package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.function.Supplier;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.lwjgl.system.MemoryUtil;

/**
 * Instances of one material for one draw. Indices depend only on each slot's mesh, so they are
 * rebuilt only when the slot-to-mesh sequence changes; instance data is streamed every frame
 * through a ring buffer. The owner decides lifetime and must {@link #close()} it on clear.
 */
class RetainedBatch<I extends RetainedInstance> implements AutoCloseable {
  final RenderType type;
  final ArrayList<I> instances = new ArrayList<>();
  int used;

  /** Replaces the {@code Sampler0} binding of the type, or null for the type's own texture. */
  GpuTextureView texture0;

  private final String label;
  private final int initialRingBytes;
  private RetainedMesh[] previous = new RetainedMesh[0];
  private int previousCount, indexCount;
  private GpuBuffer indices;
  private MappableRingBuffer live;

  RetainedBatch(RenderType type, String label, int initialRingBytes) {
    this.type = type;
    this.label = label;
    this.initialRingBytes = initialRingBytes;
  }

  /** Appends an instance owned elsewhere; the caller bounds the count by MAX_INSTANCES. */
  void add(I instance) {
    if (used == instances.size()) instances.add(instance);
    else instances.set(used, instance);
    used++;
  }

  /** Next pooled instance owned by this batch, or null when the batch is full. */
  I next(Supplier<I> factory) {
    if (used >= MAX_INSTANCES) return null;
    if (used == instances.size()) instances.add(factory.get());
    return instances.get(used++);
  }

  /** Uploads this frame's instances; returns true when the index buffer had to be rebuilt. */
  boolean prepare() {
    boolean same = previousCount == used;
    for (int i = 0; same && i < used; i++) same = previous[i] == instances.get(i).mesh;
    if (!same) {
      int count = 0;
      for (int i = 0; i < used; i++) count += instances.get(i).mesh.vertexCount() / 4 * 6;
      var data = MemoryUtil.memAlloc(count * 4);
      try {
        for (int i = 0; i < used; i++) {
          var mesh = instances.get(i).mesh;
          for (int v = 0; v < mesh.vertexCount(); v += 4) {
            int base = mesh.firstVertex() + v;
            data.putInt(index(base, i)).putInt(index(base + 1, i)).putInt(index(base + 2, i));
            data.putInt(index(base + 2, i)).putInt(index(base + 3, i)).putInt(index(base, i));
          }
        }
        data.flip();
        var next =
            RenderSystem.getDevice()
                .createBuffer(() -> label + " references", GpuBuffer.USAGE_INDEX, data);
        if (indices != null) indices.close();
        indices = next;
        indexCount = count;
        if (previous.length < used)
          previous = new RetainedMesh[Math.max(used, previous.length * 2)];
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
              () -> label + " transforms",
              GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
              Math.max(size, live == null ? initialRingBytes : live.size() * 2));
    }
    try (var mapping = live.currentBuffer().slice(0, size).map(false, true)) {
      var data = mapping.data();
      for (int i = 0; i < used; i++) instances.get(i).write(data);
    }
    return !same;
  }

  GpuBuffer indices() {
    return indices;
  }

  int indexCount() {
    return indexCount;
  }

  GpuBuffer instanceBuffer() {
    return live.currentBuffer();
  }

  /** Called once after this frame's draw recorded, so the next frame writes another slice. */
  void rotate() {
    if (live != null) live.rotate();
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
