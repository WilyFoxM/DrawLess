package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

/**
 * Resource-local topology with unique positions; transformed positions/normals belong to a frame.
 */
final class CubeTopology {
  private static final Vector3f TEMP = new Vector3f();
  private final ModelPart.Polygon[] polygons;
  private final ModelPart.Vertex[][] vertices;
  private final float[] normals, positions, uv;
  private final int[] positionOffsets, normalOffsets;
  final int count, floats, uniquePositions;
  final int lookupHash = 31 * System.identityHashCode(this);

  private CubeTopology(ModelPart.Cube cube, int count) {
    polygons = cube.polygons.clone();
    vertices = new ModelPart.Vertex[polygons.length][];
    normals = new float[polygons.length * 3];
    uv = new float[count * 2];
    positionOffsets = new int[count];
    normalOffsets = new int[count];
    float[] corners = new float[count * 3];
    int size = 0, vertexIndex = 0;
    for (int i = 0; i < polygons.length; i++) {
      vertices[i] = polygons[i].vertices().clone();
      var n = polygons[i].normal();
      normals[i * 3] = n.x();
      normals[i * 3 + 1] = n.y();
      normals[i * 3 + 2] = n.z();
      for (var vertex : vertices[i]) {
        float x = vertex.worldX(), y = vertex.worldY(), z = vertex.worldZ();
        int p = 0;
        while (p < size
            && !(same(corners[p], x) && same(corners[p + 1], y) && same(corners[p + 2], z))) p += 3;
        if (p == size) {
          corners[size++] = x;
          corners[size++] = y;
          corners[size++] = z;
        }
        positionOffsets[vertexIndex] = p;
        normalOffsets[vertexIndex] = i * 3;
        uv[vertexIndex * 2] = vertex.u();
        uv[vertexIndex * 2 + 1] = vertex.v();
        vertexIndex++;
      }
    }
    positions = Arrays.copyOf(corners, size);
    for (int i = 0; i < normalOffsets.length; i++) normalOffsets[i] += size;
    uniquePositions = size / 3;
    floats = size + normals.length;
    this.count = count;
  }

  private static boolean same(float a, float b) {
    return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
  }

  static CubeTopology capture(ModelPart.Cube cube) {
    if (cube.polygons.length > 32) return null;
    int count = 0;
    for (var p : cube.polygons) {
      if (p == null || p.vertices() == null || p.normal() == null) return null;
      count += p.vertices().length;
      if (count > 128) return null;
      for (var v : p.vertices()) if (v == null) return null;
    }
    return count == 0 ? null : new CubeTopology(cube, count);
  }

  boolean matches(ModelPart.Cube cube) {
    if (cube.polygons.length != polygons.length) return false;
    for (int i = 0; i < polygons.length; i++) {
      var p = cube.polygons[i];
      if (p != polygons[i] || p.vertices().length != vertices[i].length) return false;
      var n = p.normal();
      if (!same(n.x(), normals[i * 3])
          || !same(n.y(), normals[i * 3 + 1])
          || !same(n.z(), normals[i * 3 + 2])) return false;
      for (int j = 0; j < vertices[i].length; j++)
        if (p.vertices()[j] != vertices[i][j]) return false;
    }
    return true;
  }

  void transform(FrameGeometryCache.Entry entry, PoseStack.Pose pose) {
    float[] output = entry.vertices;
    for (int p = 0; p < positions.length; p += 3) {
      pose.pose().transformPosition(positions[p], positions[p + 1], positions[p + 2], TEMP);
      output[p] = TEMP.x;
      output[p + 1] = TEMP.y;
      output[p + 2] = TEMP.z;
    }
    for (int p = 0; p < normals.length; p += 3) {
      pose.transformNormal(normals[p], normals[p + 1], normals[p + 2], TEMP);
      int q = positions.length + p;
      output[q] = TEMP.x;
      output[q + 1] = TEMP.y;
      output[q + 2] = TEMP.z;
    }
    entry.ready = true;
  }

  void emit(
      FrameGeometryCache.Entry entry,
      VertexConsumer destination,
      int light,
      int overlay,
      int tint) {
    float[] a = entry.vertices;
    for (int i = 0; i < count; i++) {
      int p = positionOffsets[i], n = normalOffsets[i];
      destination.addVertex(
          a[p],
          a[p + 1],
          a[p + 2],
          tint,
          uv[i * 2],
          uv[i * 2 + 1],
          overlay,
          light,
          a[n],
          a[n + 1],
          a[n + 2]);
    }
  }
}
