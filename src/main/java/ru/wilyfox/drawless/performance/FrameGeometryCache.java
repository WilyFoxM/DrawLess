package ru.wilyfox.drawless.performance;

import java.util.ArrayList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Bounded frame-local transformed geometry. Entries own payloads, never render consumers. */
public final class FrameGeometryCache {
  private final Entry[] table;
  private final ArrayList<Entry> pool = new ArrayList<>();
  private final int limit, byteLimit;
  private int used, payloadBytes;

  public FrameGeometryCache() {
    this(4096, 8 * 1024 * 1024);
  }

  FrameGeometryCache(int limit, int byteLimit) {
    this.limit = limit;
    this.byteLimit = byteLimit;
    int size = 2;
    while (size < limit * 2) size <<= 1;
    table = new Entry[size];
  }

  public Entry acquire(
      Object shape, Matrix4f pose, Matrix3f normal, boolean trusted, int vertices) {
    return acquire(shape, shape, pose, normal, trusted, vertices);
  }

  public Entry acquire(
      Object shape, Object context, Matrix4f pose, Matrix3f normal, boolean trusted, int vertices) {
    return acquireWithHash(
        shape,
        context,
        31 * System.identityHashCode(shape) + System.identityHashCode(context),
        pose,
        normal,
        trusted,
        vertices);
  }

  public Entry acquireWithHash(
      Object shape,
      Object context,
      int hash,
      Matrix4f pose,
      Matrix3f normal,
      boolean trusted,
      int vertices) {
    if (shape == null || vertices < 1 || vertices > 1024) return null;
    // State identity narrows the search; complete matrices still prove transform compatibility.
    int slot = (hash ^ (hash >>> 16)) & (table.length - 1);
    while (table[slot] != null) {
      Entry e = table[slot];
      if (e.shape == shape
          && e.context == context
          && e.trusted == trusted
          && e.pose.properties() == pose.properties()
          && e.pose.equals(pose)
          && e.normal.equals(normal)) return e;
      slot = (slot + 1) & (table.length - 1);
    }
    if (used == limit) return null;
    int floats = vertices;
    if (used == pool.size() && payloadBytes + floats * Float.BYTES > byteLimit) return null;
    Entry e = used < pool.size() ? pool.get(used) : new Entry();
    if (e.vertices.length < floats) {
      int growth = (floats - e.vertices.length) * Float.BYTES;
      if (payloadBytes + growth > byteLimit) return null;
      e.vertices = new float[floats];
      payloadBytes += growth;
    }
    if (used == pool.size()) pool.add(e);
    used++;
    e.shape = shape;
    e.context = context;
    e.pose.set(pose);
    e.normal.set(normal);
    e.trusted = trusted;
    e.slot = slot;
    e.count = vertices;
    e.ready = false;
    table[slot] = e;
    return e;
  }

  public int used() {
    return used;
  }

  public int payloadBytes() {
    return payloadBytes;
  }

  public void clearFrame() {
    for (int i = 0; i < used; i++) {
      Entry e = pool.get(i);
      table[e.slot] = null;
      e.shape = null;
      e.context = null;
      e.ready = false;
    }
    used = 0;
  }

  public void release() {
    clearFrame();
    pool.clear();
    payloadBytes = 0;
  }

  public static final class Entry {
    private Object shape, context;
    private final Matrix4f pose = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private boolean trusted;
    private int slot;
    public float[] vertices = new float[0];
    public int count;
    public boolean ready;
  }
}
