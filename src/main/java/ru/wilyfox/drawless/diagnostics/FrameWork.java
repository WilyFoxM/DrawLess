package ru.wilyfox.drawless.diagnostics;

import java.util.Locale;
import ru.wilyfox.drawless.observe.Observer;

/**
 * First-time work the retained owners do inside a frame: mesh builds, skin admission and mesh
 * compaction. Render-thread counters only; a diagnostic session drains them once per frame to tell
 * what a slow frame spent on DrawLess's own setup. Outside a session they just accumulate.
 */
public final class FrameWork {
  private static final long[] COUNTS = new long[Observer.Work.values().length];
  private static final long[] NANOS = new long[Observer.Work.values().length];
  private static long total;

  private FrameWork() {}

  public static void add(Observer.Work kind, long nanos) {
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
    for (var kind : Observer.Work.values()) {
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
