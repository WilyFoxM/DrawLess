package ru.wilyfox.drawless.diagnostics;

import java.util.ArrayList;
import java.util.List;

/** The OFF controls on either side of an ON phase form its local baseline. */
final class BracketedComparison {
  record Pair(int onPhase, double baselineFps, double onFps, double changePercent) {}

  private BracketedComparison() {}

  static int phases(int brackets) {
    if (brackets != 1 && brackets != 3)
      throw new IllegalArgumentException("Expected 1 or 3 brackets");
    return brackets * 2 + 1;
  }

  static String label(int phase) {
    return (phase % 2 == 0 ? "off" : "on") + (phase / 2 + 1);
  }

  static List<Pair> pairs(List<Double> fps) {
    if (fps.size() != 3 && fps.size() != 7) throw new IllegalArgumentException("Incomplete series");
    for (double value : fps)
      if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid FPS");
    var pairs = new ArrayList<Pair>();
    for (int i = 1; i + 1 < fps.size(); i += 2) {
      double baseline = (fps.get(i - 1) + fps.get(i + 1)) / 2;
      pairs.add(new Pair(i + 1, baseline, fps.get(i), (fps.get(i) / baseline - 1) * 100));
    }
    return List.copyOf(pairs);
  }
}
