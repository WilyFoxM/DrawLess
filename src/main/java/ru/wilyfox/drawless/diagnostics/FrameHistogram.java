package ru.wilyfox.drawless.diagnostics;

/**
 * Frame intervals of a long recording in 0.1 ms buckets up to 200 ms, so its memory does not grow
 * with the session. Percentiles are the upper bound of their bucket; longer frames share the last
 * bucket and only the maximum stays exact. Pure logic, no game state.
 */
final class FrameHistogram {
  static final double BUCKET_MS = 0.1;
  private static final long BUCKET_NANOS = 100_000;
  private final int[] buckets = new int[2000];
  private long frames, nanos, maxNanos;

  void add(long intervalNanos) {
    frames++;
    nanos += intervalNanos;
    maxNanos = Math.max(maxNanos, intervalNanos);
    buckets[(int) Math.min(buckets.length - 1, intervalNanos / BUCKET_NANOS)]++;
  }

  long frames() {
    return frames;
  }

  double seconds() {
    return nanos / 1e9;
  }

  double meanFps() {
    return nanos > 0 ? frames * 1e9 / nanos : 0;
  }

  double quantileMs(double q) {
    if (frames == 0) return 0;
    long rank = Math.max(1, (long) Math.ceil(q * frames)), seen = 0;
    for (int i = 0; i < buckets.length; i++) {
      seen += buckets[i];
      if (seen >= rank) return Math.min((i + 1) * BUCKET_MS, maxNanos / 1e6);
    }
    return maxNanos / 1e6;
  }

  /** Frames of at least {@code ms}, within one bucket. */
  long atLeast(double ms) {
    long count = 0;
    for (int i = (int) Math.min(buckets.length - 1, Math.round(ms / BUCKET_MS));
        i < buckets.length;
        i++) count += buckets[i];
    return count;
  }

  FrameTail tail() {
    if (frames == 0) return FrameTail.EMPTY;
    double median = quantileMs(0.5), p99 = quantileMs(0.99);
    return new FrameTail(
        (int) Math.min(Integer.MAX_VALUE, frames),
        median,
        quantileMs(0.95),
        p99,
        quantileMs(0.999),
        maxNanos / 1e6,
        p99 > 0 ? 1000 / p99 : 0,
        (int) atLeast(2 * median),
        (int) atLeast(1000.0 / 30));
  }
}
