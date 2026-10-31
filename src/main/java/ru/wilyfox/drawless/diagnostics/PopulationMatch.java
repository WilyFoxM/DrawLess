package ru.wilyfox.drawless.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Population-standardized FPS for a bracketed series. A window is comparable when its rendered
 * avatar count held one value for every measured frame. Only values observed in every phase are
 * kept, and each phase's mean frame time is averaged over those values with weights shared by all
 * phases (direct standardization), so a phase that spent more time at a heavier population is not
 * credited or penalized for it. Other windows stay in the raw result; nothing is discarded there.
 */
final class PopulationMatch {
  static final int MIN_WINDOWS = 5;

  record Phase(
      int windows,
      int stableWindows,
      int matchedWindows,
      double matchedSeconds,
      double meanMatchedAvatars,
      double standardizedFps) {}

  record Result(List<Integer> commonAvatarCounts, List<Phase> phases, boolean comparable) {
    List<Double> fps() {
      return phases.stream().map(Phase::standardizedFps).toList();
    }
  }

  private PopulationMatch() {}

  static Result match(List<List<DisplayProbe.Population>> phases) {
    // Per phase: avatar count -> {frame intervals, nanos, windows}.
    var strata = new ArrayList<Map<Integer, long[]>>();
    var stable = new ArrayList<Integer>();
    for (var windows : phases) {
      var byCount = new TreeMap<Integer, long[]>();
      int count = 0;
      for (var w : windows) {
        if (w.windowIntervals() <= 0 || w.windowNanos() <= 0 || w.avatarsMin() != w.avatarsMax())
          continue;
        count++;
        var sum = byCount.computeIfAbsent(w.avatarsMin(), k -> new long[3]);
        sum[0] += w.windowIntervals();
        sum[1] += w.windowNanos();
        sum[2]++;
      }
      strata.add(byCount);
      stable.add(count);
    }
    var common = new TreeSet<Integer>();
    if (!strata.isEmpty()) common.addAll(strata.getFirst().keySet());
    for (var byCount : strata) common.retainAll(byCount.keySet());
    var weights = new TreeMap<Integer, Double>();
    for (int value : common)
      weights.put(value, strata.stream().mapToDouble(s -> s.get(value)[1]).sum());
    double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
    var results = new ArrayList<Phase>();
    boolean comparable = !common.isEmpty();
    for (int i = 0; i < phases.size(); i++) {
      var byCount = strata.get(i);
      int matched = 0;
      double seconds = 0, avatarSeconds = 0, frameNanos = 0;
      for (int value : common) {
        long[] sum = byCount.get(value);
        matched += (int) sum[2];
        seconds += sum[1] / 1e9;
        avatarSeconds += value * sum[1] / 1e9;
        frameNanos += weights.get(value) / totalWeight * ((double) sum[1] / sum[0]);
      }
      if (matched < MIN_WINDOWS) comparable = false;
      results.add(
          new Phase(
              phases.get(i).size(),
              stable.get(i),
              matched,
              seconds,
              seconds > 0 ? avatarSeconds / seconds : 0,
              frameNanos > 0 ? 1e9 / frameNanos : 0));
    }
    return new Result(List.copyOf(common), List.copyOf(results), comparable);
  }
}
