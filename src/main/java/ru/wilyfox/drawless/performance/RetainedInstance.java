package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One streamed instance of a retained mesh in the {@link LiveGeometryEncoding} layout: pose, three
 * quantized normal bases, color, light, overlay and a texel offset into a shared texture atlas (128
 * bytes; only the retained armor shader reads the offset). Render thread only.
 */
class RetainedInstance {
  private static final Vector3f normal = new Vector3f();

  RetainedMesh mesh;
  final Matrix4f transform = new Matrix4f();
  final float[] normals = new float[9];
  int color, light, overlay;

  /** Atlas texel origin, x in the low and y in the high 16 bits; zero leaves UVs unchanged. */
  int uvOffset;

  void set(RetainedMesh mesh, PoseStack.Pose pose, int color, int light, int overlay) {
    this.mesh = mesh;
    transform.set(pose.pose());
    this.color = color;
    this.light = light;
    this.overlay = overlay;
    uvOffset = 0;
    // Retained normals are axis aligned: transform/normalize and quantize three bases exactly as
    // vanilla's packed signed-byte normal writer, once per instance rather than per face.
    for (int i = 0; i < 3; i++) {
      pose.transformNormal(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0, normal);
      normals[i * 3] = (byte) (int) (normal.x * 127) / 127f;
      normals[i * 3 + 1] = (byte) (int) (normal.y * 127) / 127f;
      normals[i * 3 + 2] = (byte) (int) (normal.z * 127) / 127f;
    }
  }

  void copy(RetainedInstance source, RetainedMesh mesh, int color, int light, int overlay) {
    this.mesh = mesh;
    transform.set(source.transform);
    System.arraycopy(source.normals, 0, normals, 0, normals.length);
    this.color = color;
    this.light = light;
    this.overlay = overlay;
    uvOffset = source.uvOffset;
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
    data.putInt(color).putInt(light).putInt(overlay).putInt(uvOffset);
  }
}
