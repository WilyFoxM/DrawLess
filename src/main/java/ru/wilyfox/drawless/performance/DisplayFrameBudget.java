package ru.wilyfox.drawless.performance;

/** Soft CPU budget: one indivisible model/group may finish after the limit. */
final class DisplayFrameBudget {
  private long spent, limit;
  private long previousFrame;
  private boolean previousLongGap;
  private double averageInterval = 16_666_667, scale = 1;

  void begin(long now, int microseconds, boolean adaptive) {
    if (previousFrame != 0) {
      long interval = now - previousFrame;
      boolean longGap = interval >= 250_000_000L;
      // Ignore a single loading/pause gap, but still respond to sustained <4 FPS.
      if (interval > 0 && (!longGap || previousLongGap))
        averageInterval += .1 * (Math.min(interval, 250_000_000L) - averageInterval);
      previousLongGap = longGap;
    }
    previousFrame = now;
    double desired = adaptive ? Math.clamp(16_666_667d / averageInterval, .25, 1) : 1;
    scale = adaptive ? scale + .05 * (desired - scale) : 1;
    limit = Math.round(Math.clamp(microseconds, 100, 5000) * 1000d * scale);
    spent = 0;
  }

  boolean admit(long waitingNanos, boolean near) {
    return spent < limit || near || waitingNanos >= 250_000_000L;
  }

  void charge(long nanos) {
    spent += Math.max(0, nanos);
  }

  long limit() {
    return limit;
  }

  long spent() {
    return spent;
  }

  void reset() {
    spent = limit = previousFrame = 0;
    previousLongGap = false;
    averageInterval = 16_666_667;
    scale = 1;
  }
}
