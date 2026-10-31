package ru.wilyfox.drawless.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * Population-adjusted FPS for a bracketed series in a live scene where the rendered avatar count
 * drifts. Each one-second window gives a mean frame time and a mean avatar count. A common slope
 * (milliseconds per avatar) is estimated from variation within phases only, so the ON/OFF
 * difference itself does not enter it; every phase is then moved to the series' mean avatar count.
 * A second fit with separate OFF and ON slopes is reported as a sensitivity check. Raw results are
 * never replaced.
 */
final class PopulationRegression {
  static final int MIN_WINDOWS = 5;

  /** Separate-slope and common-slope mean changes may differ by this much (percentage points). */
  static final double SENSITIVITY_LIMIT = 1.0;

  record Phase(
      int windows,
      double meanAvatars,
      double meanFrameMs,
      double shiftMs,
      double shiftStdErrMs,
      double adjustedFrameMs,
      double adjustedFps) {}

  record Result(
      boolean comparable,
      String reason,
      double slopeMsPerAvatar,
      double slopeStdErr,
      double referenceAvatars,
      List<Phase> phases,
      double offSlopeMsPerAvatar,
      double onSlopeMsPerAvatar,
      List<Double> separateSlopeFps) {
    List<Double> fps() {
      return phases.stream().map(Phase::adjustedFps).toList();
    }
  }

  private PopulationRegression() {}

  static Result fit(List<List<DisplayProbe.Population>> series) {
    var data = new ArrayList<double[][]>();
    for (var phase : series) {
      var windows = new ArrayList<double[]>();
      for (var w : phase)
        if (w.windowIntervals() > 0 && w.windowNanos() > 0)
          windows.add(new double[] {w.avatarsMean(), w.windowNanos() / 1e6 / w.windowIntervals()});
      data.add(windows.toArray(double[][]::new));
    }
    for (var windows : data)
      if (windows.length < MIN_WINDOWS)
        return refused("Fewer than " + MIN_WINDOWS + " measured windows in a phase.");
    int n = 0;
    double total = 0;
    for (var windows : data) {
      n += windows.length;
      for (var w : windows) total += w[0];
    }
    double reference = total / n;
    var all = new ArrayList<Integer>();
    for (int i = 0; i < data.size(); i++) all.add(i);
    double[] common = slope(data, all);
    double slope = common[0], sxx = common[1];
    boolean constant = sxx < 1e-12;
    if (constant) {
      for (var windows : data)
        if (Math.abs(mean(windows, 0) - reference) > 1e-9)
          return refused(
              "Avatar counts differ between phases but never within one: the slope cannot be estimated.");
      slope = 0;
    }
    double residual = 0;
    for (var windows : data) {
      double mx = mean(windows, 0), my = mean(windows, 1);
      for (var w : windows) {
        double r = w[1] - my - slope * (w[0] - mx);
        residual += r * r;
      }
    }
    int dof = n - data.size() - 1;
    double stdErr = constant || dof <= 0 ? 0 : Math.sqrt(residual / dof / sxx);
    var phases = new ArrayList<Phase>();
    for (var windows : data) {
      double mx = mean(windows, 0), my = mean(windows, 1);
      double shift = slope * (mx - reference);
      phases.add(
          new Phase(
              windows.length,
              mx,
              my,
              shift,
              stdErr * Math.abs(mx - reference),
              my - shift,
              1000 / (my - shift)));
    }
    var off = new ArrayList<Integer>();
    var on = new ArrayList<Integer>();
    for (int i = 0; i < data.size(); i++) (i % 2 == 0 ? off : on).add(i);
    double offSlope = constant ? 0 : slope(data, off)[0];
    double onSlope = constant ? 0 : slope(data, on)[0];
    if (Double.isNaN(offSlope)) offSlope = slope;
    if (Double.isNaN(onSlope)) onSlope = slope;
    var separate = new ArrayList<Double>();
    for (int i = 0; i < data.size(); i++) {
      double mx = mean(data.get(i), 0), my = mean(data.get(i), 1);
      separate.add(1000 / (my - (i % 2 == 0 ? offSlope : onSlope) * (mx - reference)));
    }
    return new Result(
        true,
        constant ? "Constant avatar count: no adjustment needed." : "",
        slope,
        stdErr,
        reference,
        List.copyOf(phases),
        offSlope,
        onSlope,
        List.copyOf(separate));
  }

  /** {slope, within-phase sum of squares of avatars}; NaN slope without within-phase variation. */
  private static double[] slope(List<double[][]> data, List<Integer> indices) {
    double sxx = 0, sxy = 0;
    for (int i : indices) {
      var windows = data.get(i);
      double mx = mean(windows, 0), my = mean(windows, 1);
      for (var w : windows) {
        sxx += (w[0] - mx) * (w[0] - mx);
        sxy += (w[0] - mx) * (w[1] - my);
      }
    }
    return new double[] {sxx < 1e-12 ? Double.NaN : sxy / sxx, sxx};
  }

  private static double mean(double[][] windows, int column) {
    double sum = 0;
    for (var w : windows) sum += w[column];
    return sum / windows.length;
  }

  private static Result refused(String reason) {
    // Zeros instead of NaN: JSON output rejects NaN, and `comparable` already says no result.
    return new Result(false, reason, 0, 0, 0, List.of(), 0, 0, List.of());
  }
}
