package ru.wilyfox.drawless.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/** Descriptive summaries only: screening flags never erase recorded phases. */
final class BenchmarkReport {
  record Output(String markdown, String json) {}

  private BenchmarkReport() {}

  static Output create(
      String label,
      int brackets,
      String target,
      String reason,
      String originalSettings,
      String environment,
      List<String> environmentWarnings,
      List<DisplayProbe.Result> runs) {
    var gson = new GsonBuilder().setPrettyPrinting().create();
    var json = new JsonObject();
    json.addProperty("schema_version", 1);
    json.addProperty("label", label);
    json.addProperty("result", reason);
    json.addProperty("target", target);
    json.addProperty("requested_brackets", brackets);
    json.addProperty("measurement_seconds_per_phase", 20);
    json.addProperty("warmup_seconds_per_phase", 2);
    json.addProperty("environment", environment);
    json.addProperty("java", System.getProperty("java.runtime.version"));
    json.add("original_settings", JsonParser.parseString(originalSettings));
    var mods = new JsonObject();
    FabricLoader.getInstance().getAllMods().stream()
        .sorted(java.util.Comparator.comparing(m -> m.getMetadata().getId()))
        .forEach(
            m ->
                mods.addProperty(
                    m.getMetadata().getId(), m.getMetadata().getVersion().getFriendlyString()));
    json.add("mods", mods);
    var phases = new JsonArray();
    for (var run : runs) {
      var phase = new JsonObject();
      phase.addProperty("label", run.label());
      phase.addProperty("result", run.reason());
      phase.addProperty("report", run.path().getFileName().toString());
      phase.addProperty("mean_observed_fps", run.fps());
      phase.addProperty("frame_intervals", run.intervals());
      phase.addProperty("measured_interval_seconds", run.seconds());
      phase.addProperty("menu_frames", run.menuFrames());
      phase.addProperty("unfocused_frames", run.unfocusedFrames());
      phase.add("samples", gson.toJsonTree(run.samples()));
      phases.add(phase);
    }
    json.add("phases", phases);
    var warnings = new ArrayList<>(environmentWarnings);
    List<BracketedComparison.Pair> pairs = List.of();
    boolean complete =
        reason.equals("complete") && runs.size() == BracketedComparison.phases(brackets);
    if (complete) {
      pairs = BracketedComparison.pairs(runs.stream().map(DisplayProbe.Result::fps).toList());
      List<Double> off = new ArrayList<>();
      for (int i = 0; i < runs.size(); i += 2) off.add(runs.get(i).fps());
      double mean = off.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
      double spread =
          (java.util.Collections.max(off) - java.util.Collections.min(off)) / mean * 100;
      json.addProperty("off_range_over_mean_percent", spread);
      if (spread > 5)
        warnings.add(
            String.format(
                Locale.ROOT,
                "OFF controls varied by %.2f%% (range/mean; screening threshold 5%%).",
                spread));
      if (brackets == 1)
        warnings.add("One bracketed ON run is exploratory; repeat independent sessions.");
      populationWarning(runs, warnings, true);
      populationWarning(runs, warnings, false);
      particlePopulationWarning(runs, warnings, target.startsWith("particle"));
      if (target.equals("player_geometry")) playerGeometryWarning(runs, warnings);
      json.addProperty(
          "mean_paired_change_percent",
          pairs.stream()
              .mapToDouble(BracketedComparison.Pair::changePercent)
              .average()
              .orElseThrow());
    } else
      warnings.add("Incomplete or aborted series: no aggregate performance claim is calculated.");
    warnings.add(
        "One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.");
    warnings.add(
        "Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.");
    json.add("pairs", gson.toJsonTree(pairs));
    json.add("review_flags", gson.toJsonTree(warnings));
    json.addProperty("publication_status", "manual_review_required");
    var out =
        new StringBuilder("# DrawLess benchmark: ")
            .append(label)
            .append("\n\n")
            .append("Result: **")
            .append(reason)
            .append("**. Target: `")
            .append(target)
            .append("`.\n\n")
            .append(
                "**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.\n\n")
            .append(
                "Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. ")
            .append(
                "Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.\n\n");
    if (complete)
      out.append(
          String.format(
              Locale.ROOT,
              "Mean paired FPS change: **%+.2f%%**.\n\n",
              pairs.stream()
                  .mapToDouble(BracketedComparison.Pair::changePercent)
                  .average()
                  .orElseThrow()));
    out.append(
            "## Recorded phases\n\n| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |\n")
        .append("| --- | --- | ---: | ---: | ---: | --- |\n");
    for (var run : runs)
      out.append(
          String.format(
              Locale.ROOT,
              "| %s | %s | %.2f | %d | %.3f | [Report](%s) |\n",
              run.label(),
              run.reason(),
              run.fps(),
              run.intervals(),
              run.seconds(),
              run.path().getFileName()));
    if (!pairs.isEmpty()) {
      out.append(
          "\n## Bracketed comparisons\n\n| ON phase | Neighboring OFF mean FPS | ON FPS | Change |\n| ---: | ---: | ---: | ---: |\n");
      for (var pair : pairs)
        out.append(
            String.format(
                Locale.ROOT,
                "| %d | %.2f | %.2f | %+.2f%% |\n",
                pair.onPhase(),
                pair.baselineFps(),
                pair.onFps(),
                pair.changePercent()));
    }
    out.append("\n## Review flags\n\n");
    warnings.forEach(w -> out.append("- ").append(w).append('\n'));
    out.append("\n## Environment\n\n")
        .append(environment)
        .append(
            "\n\nBuilds, original settings, all samples and unrounded results: [summary.json](summary.json).\n")
        .append(
            "Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.\n");
    return new Output(out.toString(), gson.toJson(json) + "\n");
  }

  private static void populationWarning(
      List<DisplayProbe.Result> runs, List<String> warnings, boolean items) {
    var values =
        runs.stream()
            .flatMap(r -> r.samples().stream())
            .mapToInt(s -> items ? s.counts().items() : s.players())
            .summaryStatistics();
    if (values.getCount() == 0) {
      warnings.add("No population samples were recorded.");
      return;
    }
    if ((items
            && values.getMax() > 0
            && (values.getMax() - values.getMin()) / (double) values.getMax() > 0.02)
        || (!items && values.getMax() != values.getMin()))
      warnings.add(
          (items ? "Item Display" : "Player")
              + " population changed across samples: "
              + values.getMin()
              + "–"
              + values.getMax()
              + ". Review scene comparability.");
  }

  static void particlePopulationWarning(
      List<DisplayProbe.Result> runs, List<String> warnings, boolean particleTarget) {
    var totals = new java.util.IntSummaryStatistics();
    boolean unknown = false;
    var pattern = java.util.regex.Pattern.compile("(?:^|\\s)T\\s+(\\d+)\\s*$");
    for (var run : runs)
      for (var sample : run.samples()) {
        var match = pattern.matcher(sample.particlePopulation());
        if (match.find()) {
          try {
            totals.accept(Integer.parseInt(match.group(1)));
          } catch (NumberFormatException ignored) {
            unknown = true;
          }
        } else unknown = true;
      }
    if (particleTarget && (unknown || totals.getCount() == 0))
      warnings.add("Particle population could not be fully parsed; inspect the raw group counts.");
    if (totals.getCount() == 0) return;
    if (particleTarget && totals.getMax() == 0)
      warnings.add(
          "No loaded particles were sampled: this scene cannot demonstrate the particle optimization benefit.");
    if (totals.getMax() > 0
        && (totals.getMax() - totals.getMin()) / (double) totals.getMax() > 0.02)
      warnings.add(
          "Loaded particle population varied by more than 2%: "
              + totals.getMin()
              + "–"
              + totals.getMax()
              + ". Review particle workload comparability.");
  }

  static void playerGeometryWarning(List<DisplayProbe.Result> runs, List<String> warnings) {
    for (int i = 1; i < runs.size(); i += 2) {
      var samples = runs.get(i).samples();
      long models = geometryDelta(samples, "modelPassesTotal");
      long hits = geometryDelta(samples, "hitsTotal");
      if (models < 0 || hits < 0) {
        warnings.add(
            "Player geometry counters could not be compared for "
                + runs.get(i).label()
                + ". Inspect the raw samples.");
      } else if (models == 0) {
        warnings.add(
            "No player armor model passes were sampled in "
                + runs.get(i).label()
                + ". This scene cannot demonstrate armor reuse.");
      } else if (hits == 0) {
        warnings.add(
            "No armor geometry reuse hits were sampled in "
                + runs.get(i).label()
                + ". Review equipment, transforms and fallback counters before attributing an FPS change to reuse.");
      }
    }
  }

  private static long geometryDelta(List<DisplayProbe.Sample> samples, String key) {
    if (samples.size() < 2) return -1;
    var pattern = java.util.regex.Pattern.compile("(?:^|; )" + key + "=(\\d+)(?:;|$)");
    var first = pattern.matcher(samples.getFirst().playerGeometry());
    var last = pattern.matcher(samples.getLast().playerGeometry());
    if (!first.find() || !last.find()) return -1;
    try {
      long a = Long.parseLong(first.group(1)), b = Long.parseLong(last.group(1));
      return b >= a ? b - a : -1;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }
}
