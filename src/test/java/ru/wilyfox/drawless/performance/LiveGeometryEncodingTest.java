package ru.wilyfox.drawless.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LiveGeometryEncodingTest {
  @Test
  void referencesSelectTheSameSharedVertexWithIndependentInstances() {
    for (int vertex : new int[] {0, 1, 65536, LiveGeometryEncoding.MAX_VERTICES - 1})
      for (int instance : new int[] {0, 1, 127, LiveGeometryEncoding.MAX_INSTANCES - 1}) {
        int reference = LiveGeometryEncoding.index(vertex, instance);
        assertTrue(reference >= 0, "gl_VertexID must fit a signed positive integer");
        assertEquals(vertex, LiveGeometryEncoding.vertex(reference));
        assertEquals(instance, LiveGeometryEncoding.instance(reference));
      }
    assertEquals(32, LiveGeometryEncoding.VERTEX_BYTES);
    assertEquals(128, LiveGeometryEncoding.INSTANCE_BYTES);
  }

  @Test
  void invalidReferencesCannotOverflowIntoOtherGeometry() {
    assertThrows(IllegalArgumentException.class, () -> LiveGeometryEncoding.index(-1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> LiveGeometryEncoding.index(LiveGeometryEncoding.MAX_VERTICES, 0));
    assertThrows(IllegalArgumentException.class, () -> LiveGeometryEncoding.index(0, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> LiveGeometryEncoding.index(0, LiveGeometryEncoding.MAX_INSTANCES));
  }
}
