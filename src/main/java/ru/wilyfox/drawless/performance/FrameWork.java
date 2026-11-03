package ru.wilyfox.drawless.performance;

import java.util.Locale;

/**
 * First-time work the retained owners do inside a frame: mesh builds, skin admission and mesh
 * compaction. Render-thread counters only; a diagnostic session drains them once per frame to tell
 * what a slow frame spent on DrawLess's own setup. Outside a session they just accumulate.
 */
public final class FrameWork {
  public enum Kind {
    ITEM_MESH("itemMesh"),
    PART_MESH("partMesh"),
    SKIN("skin"),
    COMPACTION("compaction");

    final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  private static final long[] COUNTS = new long[Kind.values().length];
  private static final long[] NANOS = new long[Kind.values().length];
  private static long total;

  private FrameWork() {}

  public static void add(Kind kind, long nanos) {
    COUNTS[kind.ordinal()]++;
    NANOS[kind.ordinal()] += nanos;
    total += nanos;
  }

  /** Nanoseconds of first-time work since the last drain. */
  public static long pendingNanos() {
    return total;
  }

  /** "skin:1 0.312ms, itemMesh:3 0.120ms" since the last drain, then resets; "" when none. */
  public static String drain() {
    var out = new StringBuilder();
    for (var kind : Kind.values()) {
      int i = kind.ordinal();
      if (COUNTS[i] == 0) continue;
      if (!out.isEmpty()) out.append(", ");
      out.append(String.format(Locale.ROOT, "%s:%d %.3fms", kind.label, COUNTS[i], NANOS[i] / 1e6));
      COUNTS[i] = NANOS[i] = 0;
    }
    total = 0;
    return out.toString();
  }

  public static void reset() {
    drain();
  }
}
