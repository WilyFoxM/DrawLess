package ru.wilyfox.drawless.diagnostics;

import com.google.gson.GsonBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.wilyfox.drawless.util.AtomicFileWriter;

/** Stable technical report fields; no live game objects cross to the writer. */
public final class PlayerProfileReport {
  private PlayerProfileReport() {}

  @SuppressWarnings("unchecked")
  static void write(Path directory, Map<String, Object> data) throws Exception {
    var limits =
        List.of(
            "CPU-thread elapsed time, including possible driver waits; GPU execution time is unavailable.",
            "One instrumented frame every 20 rendered frames. This is diagnostic sampling, not an uninstrumented FPS benchmark or a complete frametime trace.",
            "Inclusive parents contain their children. Self time removes measured child spans only; unknown work and observer overhead remain.",
            "Frame-interval percentages use the sum of matched start-to-next-start intervals for sampled frames; render-frame percentages use the renderFrame method's measured elapsed time.",
            "Shared uploads cover all staged features. Mixed groups are not apportioned to players. Actual draw submissions are CPU calls, not GPU elapsed time.",
            "Player attribution follows tagged model/item/name-tag submits. Name-tag layout and glyph emission are measured separately; shared or mixed name-tag draws are not apportioned. Other text, shadow, leash and custom bypass paths are not individually attributed.",
            "Name-tag groups combine submit provenance with observed buffer use, including text-cache hits. Unknown-origin submits prevent a player-only classification even if they emit no glyphs.",
            "World/GUI extraction, terrain preparation, frame-graph passes and feature phases are CPU scopes for the whole scene. Missing or bypassed hooks remain in parent self time; pass labels do not identify separate views or GPU execution.",
            "Glint classification uses render-type naming, not shader inspection. Repeated model/state identity is an opportunity counter, not proof that mutable poses or geometry can be reused.",
            "Deep cube/part hooks describe the vanilla ModelPart path; custom mesh/vertex formats may bypass it. Cube vertex counts are topology counts, not uploaded bytes or visible pixels.",
            "Measured bookkeeping is a partial observer-cost indicator; clock, call-chain, JIT and allocation effects are not fully isolated or subtracted. Compare control, stages and deep under the same scene.",
            "No player names/UUIDs are stored. Per-frame attribution maps are bounded and cleared; limits/overflows are disclosed by counters.");
    data.put("limits", limits);
    var warnings = new java.util.ArrayList<String>();
    if (!data.get("settings_start").equals(data.get("settings_end")))
      warnings.add("Optimization settings changed: this recording mixes configurations.");
    if (!data.get("environment_start").equals(data.get("environment_end")))
      warnings.add("Environment changed during the recording.");
    if (((Number) data.get("timing_node_overflow")).longValue() > 0)
      warnings.add("Timing-node limit reached: some work remains in parent self time.");
    if (data.get("environment_start").toString().contains("vsync=true"))
      warnings.add("VSync is enabled. Presentation may include frame pacing waits.");
    data.put("review_flags", warnings);
    String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(data);
    AtomicFileWriter.write(directory.resolve("profile.json"), w -> w.write(json));
    var text = new StringBuilder("# DrawLess player CPU profile\n\n");
    text.append("Result: **")
        .append(data.get("result"))
        .append("**; mode: **")
        .append(data.get("mode"))
        .append("**; sampled frames: ")
        .append(data.get("sampled_frames"))
        .append(" / observed: ")
        .append(data.get("observed_frames"))
        .append(".\n\n");
    text.append("Environment: `").append(data.get("environment_start")).append("`\n\n");
    text.append(
        "CPU timing includes profiling overhead. Percentages describe measured scopes, not a predicted FPS gain.\n\n");
    text.append("Coverage: `")
        .append(data.get("coverage_revision"))
        .append("`; schema: ")
        .append(data.get("schema_version"))
        .append(
            ". World, terrain, frame-pass and GUI scopes cover the whole scene. Player name-tag layout/glyph scopes are attributed separately; mixed draws remain shared.\n\n");
    for (String warning : warnings) text.append("- Review: ").append(warning).append('\n');
    var stages = (List<Map<String, Object>>) data.get("stages");
    text.append(
        "\n## Largest self-time scopes\n\n| Stage | Self ms / sampled frame |\n| --- | ---: |\n");
    stages.stream()
        .sorted(
            java.util.Comparator.comparingDouble(
                    (Map<String, Object> r) -> ((Number) r.get("self_ms")).doubleValue())
                .reversed())
        .limit(15)
        .forEach(
            row ->
                text.append("| ")
                    .append(row.get("path"))
                    .append(" | ")
                    .append(
                        String.format(
                            Locale.ROOT,
                            "%.5f",
                            ((Number) row.get("self_ms")).doubleValue()
                                / Math.max(1, ((Number) data.get("sampled_frames")).intValue())))
                    .append(" |\n"));
    text.append("\n## Complete timing tree\n\n");
    text.append(
            "| Stage (nested path) | Calls | Inclusive ms | Self ms | ms / sampled frame | % renderFrame | % frame interval |\n")
        .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
    for (Map<String, Object> row : (List<Map<String, Object>>) data.get("stages")) {
      text.append("| ")
          .append(row.get("path").toString().replace('|', '/'))
          .append(" | ")
          .append(row.get("calls"));
      for (String key :
          List.of(
              "inclusive_ms",
              "self_ms",
              "ms_per_sampled_frame",
              "percent_sampled_render_frame",
              "percent_sampled_frame_interval"))
        text.append(" | ")
            .append(
                row.get(key) == null
                    ? "unavailable"
                    : String.format(Locale.ROOT, "%.5f", ((Number) row.get(key)).doubleValue()));
      text.append(" |\n");
    }
    text.append("\n## Sampled counters\n\n");
    ((Map<String, Long>) data.get("counters"))
        .forEach((k, v) -> text.append("- ").append(k).append(": ").append(v).append('\n'));
    text.append("\nMeasured recorder bookkeeping: ")
        .append(data.get("measured_bookkeeping_ms"))
        .append(" ms. Timing-node overflow: ")
        .append(data.get("timing_node_overflow"))
        .append(".\n\n## Coverage and limitations\n\n");
    limits.forEach(line -> text.append("- ").append(line).append('\n'));
    text.append(
        "\nSettings, per-frame denominators, mods and all unrounded values: [profile.json](profile.json).\n");
    AtomicFileWriter.write(directory.resolve("README.md"), w -> w.write(text.toString()));
  }
}
