package ru.wilyfox.drawless.performance;

import ru.wilyfox.drawless.diagnostics.DisplayProbe;

/**
 * Two coarse timestamps per pass only during the explicit short diagnostic; no per-particle timers.
 */
public final class ParticleWorkProbe {
  private static long ticks, frames, tickNanos, frameNanos;
  private static long points, visible, pointsTotal, visibleTotal;
  private static boolean frameCollecting;

  private ParticleWorkProbe() {}

  public static long begin() {
    return DisplayProbe.running() ? System.nanoTime() : 0;
  }

  public static long frameBegin() {
    frameCollecting = DisplayProbe.running();
    points = visible = 0;
    return frameCollecting ? System.nanoTime() : 0;
  }

  public static boolean point(boolean result) {
    if (frameCollecting) {
      points++;
      if (result) visible++;
    }
    return result;
  }

  public static void tickEnd(long start) {
    if (start != 0) {
      tickNanos += System.nanoTime() - start;
      ticks++;
    }
  }

  public static void frameEnd(long start) {
    if (start != 0) {
      frameNanos += System.nanoTime() - start;
      frames++;
      pointsTotal += points;
      visibleTotal += visible;
    }
    frameCollecting = false;
  }

  public static String diagnostic() {
    return "ticksTotal="
        + ticks
        + "; tickTotalMs="
        + tickNanos / 1e6
        + "; framesTotal="
        + frames
        + "; frameTotalMs="
        + frameNanos / 1e6
        + "; pointQueries="
        + points
        + "; visible="
        + visible
        + "; pointQueriesTotal="
        + pointsTotal
        + "; visibleTotal="
        + visibleTotal;
  }
}
