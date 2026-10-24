package ru.wilyfox.drawless.performance;

import ru.wilyfox.drawless.diagnostics.DisplayProbe;

/**
 * Two coarse timestamps per pass only during the explicit short diagnostic; no per-particle timers.
 */
public final class ParticleWorkProbe {
  private static long ticks, frames, tickNanos, frameNanos;
  private static long points, visible, pointsTotal, visibleTotal;
  private static boolean frameCollecting;
  private static long vertexPasses,
      vertexNanos,
      preparePasses,
      prepareNanos,
      executePasses,
      executeNanos;

  private ParticleWorkProbe() {}

  public static long begin() {
    return ParticleSpatialContext.clientThread() && DisplayProbe.running() ? System.nanoTime() : 0;
  }

  public static long frameBegin() {
    if (!ParticleSpatialContext.clientThread()) return 0;
    frameCollecting = DisplayProbe.running();
    points = visible = 0;
    return frameCollecting ? System.nanoTime() : 0;
  }

  public static boolean point(boolean result) {
    if (frameCollecting && ParticleSpatialContext.clientThread()) {
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
    if (!ParticleSpatialContext.clientThread()) return;
    if (start != 0) {
      frameNanos += System.nanoTime() - start;
      frames++;
      pointsTotal += points;
      visibleTotal += visible;
    }
    frameCollecting = false;
  }

  public static void vertexEnd(long start) {
    if (start != 0) {
      vertexPasses++;
      vertexNanos += System.nanoTime() - start;
    }
  }

  public static void prepareEnd(long start) {
    if (start != 0) {
      preparePasses++;
      prepareNanos += System.nanoTime() - start;
    }
  }

  public static void executeEnd(long start) {
    if (start != 0) {
      executePasses++;
      executeNanos += System.nanoTime() - start;
    }
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
        + visibleTotal
        + "; vertexPassesTotal="
        + vertexPasses
        + "; vertexTotalMs="
        + vertexNanos / 1e6
        + "; preparePassesTotal="
        + preparePasses
        + "; prepareTotalMs="
        + prepareNanos / 1e6
        + "; executePassesTotal="
        + executePasses
        + "; executeTotalMs="
        + executeNanos / 1e6;
  }
}
