package ru.wilyfox.drawless.diagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Distribution of the measured frame intervals of one phase. Percentiles use the nearest rank; 1%
 * low is the FPS equivalent of the 99th percentile frame time, as frame-time tools report it. Pure
 * logic, no game state.
 */
record FrameTail(
    int frames,
    double medianMs,
    double p95Ms,
    double p99Ms,
    double p999Ms,
    double maxMs,
    double low1Fps,
    int slowFrames,
    int over33Ms) {
  static final FrameTail EMPTY = new FrameTail(0, 0, 0, 0, 0, 0, 0, 0, 0);

  /** A frame is slow when it takes at least twice the phase median. */
  static FrameTail of(long[] intervalNanos, int count) {
    if (count <= 0) return EMPTY;
    long[] sorted = Arrays.copyOf(intervalNanos, count);
    Arrays.sort(sorted);
    double median = rank(sorted, 0.5) / 1e6;
    int slow = 0, over33 = 0;
    for (int i = 0; i < count; i++) {
      double ms = intervalNanos[i] / 1e6;
      if (ms >= 2 * median) slow++;
      if (ms >= 1000.0 / 30) over33++;
    }
    double p99 = rank(sorted, 0.99) / 1e6;
    return new FrameTail(
        count,
        median,
        rank(sorted, 0.95) / 1e6,
        p99,
        rank(sorted, 0.999) / 1e6,
        sorted[count - 1] / 1e6,
        p99 > 0 ? 1000 / p99 : 0,
        slow,
        over33);
  }

  private static long rank(long[] sorted, double q) {
    int index = (int) Math.ceil(q * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
  }

  /**
   * Bracketed change of one tail metric: each ON against the mean of its neighboring OFF phases.
   */
  record Pair(int onPhase, double baseline, double on, double changePercent) {}

  static List<Pair> pairs(List<Double> values) {
    if (values.size() != 3 && values.size() != 7)
      throw new IllegalArgumentException("Incomplete series");
    var pairs = new ArrayList<Pair>();
    for (int i = 1; i + 1 < values.size(); i += 2) {
      double baseline = (values.get(i - 1) + values.get(i + 1)) / 2;
      pairs.add(
          new Pair(
              i + 1,
              baseline,
              values.get(i),
              baseline > 0 ? (values.get(i) / baseline - 1) * 100 : 0));
    }
    return List.copyOf(pairs);
  }
}
