package ru.wilyfox.drawless.performance;

/** A quad range in a retained geometry buffer: four consecutive vertices per quad. */
interface RetainedMesh {
  int firstVertex();

  int vertexCount();
}
