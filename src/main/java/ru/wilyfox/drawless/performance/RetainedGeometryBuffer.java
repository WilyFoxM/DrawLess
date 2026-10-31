package ru.wilyfox.drawless.performance;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Append-only local vertices of one owner in the {@link LiveGeometryEncoding} layout (position+u,
 * normal+v), mirrored to a texel buffer. Only vertices appended since the last upload are written;
 * a larger GPU buffer copies the uploaded prefix. Render thread only; {@link #close()} frees both.
 */
final class RetainedGeometryBuffer implements AutoCloseable {
  private final String label;
  private ByteBuffer data;
  private GpuBuffer buffer;
  private int vertices, uploaded, max;
  private long uploads, uploadedBytes;

  RetainedGeometryBuffer(String label) {
    this.label = label;
  }

  void limit(int maxVertices) {
    max = maxVertices;
  }

  int max() {
    return max;
  }

  int vertices() {
    return vertices;
  }

  boolean fits(long count) {
    return vertices + count <= max;
  }

  /** Reserves CPU room for {@code count} more vertices; returns the first new vertex. */
  int begin(int count) {
    int size = (vertices + count) * VERTEX_BYTES;
    if (data == null)
      data = MemoryUtil.memAlloc(Math.min(max * VERTEX_BYTES, Math.max(65536, size)));
    else if (size > data.capacity())
      data =
          MemoryUtil.memRealloc(
              data, Math.min(max * VERTEX_BYTES, Math.max(size, data.capacity() * 2)));
    data.position(vertices * VERTEX_BYTES);
    return vertices;
  }

  void vertex(float x, float y, float z, float u, float nx, float ny, float nz, float v) {
    data.putFloat(x).putFloat(y).putFloat(z).putFloat(u);
    data.putFloat(nx).putFloat(ny).putFloat(nz).putFloat(v);
    vertices++;
  }

  /**
   * Moves the given vertex ranges to the front, in ascending source order, and drops every other
   * vertex. {@code firsts} must be sorted ascending and ranges must not overlap; returns the new
   * first vertex of each range. The whole buffer is uploaded again on the next {@link #upload()}.
   */
  int[] compact(int[] firsts, int[] counts) {
    var moved = new int[firsts.length];
    int next = 0;
    for (int i = 0; i < firsts.length; i++) {
      // Same-buffer absolute bulk put behaves as if copied through a temporary: overlap is safe.
      if (firsts[i] != next)
        data.put(next * VERTEX_BYTES, data, firsts[i] * VERTEX_BYTES, counts[i] * VERTEX_BYTES);
      moved[i] = next;
      next += counts[i];
    }
    vertices = next;
    uploaded = 0;
    if (data != null) data.position(vertices * VERTEX_BYTES);
    return moved;
  }

  void upload() {
    if (vertices == uploaded) return;
    int size = vertices * VERTEX_BYTES;
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    if (buffer == null || buffer.size() < size) {
      var next =
          RenderSystem.getDevice()
              .createBuffer(
                  () -> label,
                  GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
                      | GpuBuffer.USAGE_COPY_DST
                      | GpuBuffer.USAGE_COPY_SRC,
                  data.capacity());
      if (buffer != null) {
        encoder.copyToBuffer(
            buffer.slice(0, (long) uploaded * VERTEX_BYTES),
            next.slice(0, (long) uploaded * VERTEX_BYTES));
        buffer.close();
      }
      buffer = next;
    }
    int offset = uploaded * VERTEX_BYTES;
    encoder.writeToBuffer(buffer.slice(offset, size - offset), data.slice(offset, size - offset));
    uploadedBytes += size - offset;
    uploads++;
    uploaded = vertices;
  }

  GpuBuffer buffer() {
    return buffer;
  }

  boolean allocated() {
    return buffer != null || data != null;
  }

  long uploads() {
    return uploads;
  }

  long uploadedBytes() {
    return uploadedBytes;
  }

  @Override
  public void close() {
    if (buffer != null) buffer.close();
    if (data != null) MemoryUtil.memFree(data);
    buffer = null;
    data = null;
    // The vertex limit is a device property set by the owner; it survives clears.
    vertices = uploaded = 0;
    uploads = uploadedBytes = 0;
  }
}
