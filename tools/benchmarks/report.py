#!/usr/bin/env python3
"""Turn existing DrawLess probe Markdown into benchmark evidence (draft by default).

No game control, uploads, invented frame percentiles or automatic publication.
Only --inspect uses the standard library; plotting requires requirements.txt.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import re
import statistics
import sys
from pathlib import Path


ENVIRONMENT_KEYS = (
    "artifact", "artifact_sha256", "source_commit", "cpu", "gpu", "ram", "java",
    "driver", "resolution", "vsync_and_fps_limit", "graphics", "resource_pack",
    "mods_with_versions", "jvm_heap_and_arguments", "power_mode", "scene_and_mirror",
    "date_and_timezone",
)
NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"


def number(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError("Non-finite numeric value")
    return parsed


def required_match(pattern: str, text: str) -> re.Match:
    match = re.search(pattern, text, re.MULTILINE)
    if match is None:
        raise ValueError(f"Missing report field: {pattern}")
    return match


def tables(text: str) -> list[list[list[str]]]:
    result, current = [], []
    for line in text.splitlines() + [""]:
        if line.startswith("|") and line.rstrip().endswith("|"):
            current.append([cell.strip().replace(r"\|", "|")
                            for cell in re.split(r"(?<!\\)\|", line.strip())[1:-1]])
        elif current:
            result.append(current)
            current = []
    return result


def parse_probe(path: Path) -> dict:
    raw = path.read_bytes()
    text = raw.decode("utf-8-sig").replace("\r\n", "\n")
    # Historical reports must never silently acquire standalone DrawLess identity.
    context = required_match(r"^(Minecraft [^;\n]+; DrawLess [0-9][^;\n]*;[^\n]*)$", text).group(1)
    context_fields = set(context.split("; "))
    phase = required_match(r"^Phase: ([A-Za-z0-9_-]+); result: ([a-z_]+)$", text)
    fps = number(required_match(rf"^Mean observed FPS: ({NUMBER})$", text).group(1))
    if fps <= 0:
        raise ValueError(f"{path.name}: nonpositive mean observed FPS")
    status = required_match(r"^Menu frames excluded: (\d+); unfocused measured frames: (\d+)$", text)
    declared_samples = int(required_match(r"^Samples: (\d+);", text).group(1))
    enabled = required_match(r"; DrawLess enabled (true|false)(?:;|$)", context).group(1) == "true"
    scene_tables = [t for t in tables(text) if t[0][:3] == ["s", "FPS", "Players"]]
    if len(scene_tables) != 1:
        raise ValueError(f"{path.name}: expected one scene table")
    table = scene_tables[0]
    required = ("s", "FPS", "Players", "Display loaded", "Item loaded", "Bone carriers",
                "Camera XYZ", "Yaw / pitch / FOV", "Focused", "Detailed profiler", "Scan ms")
    if any(key not in table[0] for key in required):
        raise ValueError(f"{path.name}: unsupported scene-table columns")
    samples = []
    for cells in table[2:]:
        if len(cells) != len(table[0]):
            raise ValueError(f"{path.name}: malformed scene-table row")
        row = dict(zip(table[0], cells))
        for key in ("s", "FPS", "Players", "Display loaded", "Item loaded", "Bone carriers", "Scan ms"):
            row[key] = number(row[key])
            if row[key] < 0:
                raise ValueError(f"{path.name}: negative {key}")
        if row["Focused"] not in ("true", "false") or row["Detailed profiler"] not in ("true", "false"):
            raise ValueError(f"{path.name}: invalid sample boolean")
        if samples and row["s"] <= samples[-1]["s"]:
            raise ValueError(f"{path.name}: sample times are not increasing")
        samples.append(row)
    if len(samples) != declared_samples or not samples:
        raise ValueError(f"{path.name}: missing or inconsistent sample count")
    costs = {}
    for t in tables(text):
        if t[0] == ["Global stage", "ms/sampled frame"]:
            costs = {r[0]: number(r[1]) for r in t[2:] if len(r) == 2}
    live = []
    for t in tables(text):
        if t[0] == ["s", "Extreme scene"]:
            for row in t[2:]:
                if len(row) != 2:
                    raise ValueError(f"{path.name}: malformed cache row")
                metrics = {}
                for key, value in re.findall(rf"(?:^|;\s*)([A-Za-z][A-Za-z0-9]*)=({NUMBER})(?:ms)?(?=;|$)", row[1]):
                    metrics[key] = number(value)
                state = re.search(r"(?:^|;\s*)status=([^;]+)", row[1])
                metrics["status"] = state.group(1) if state else "unknown"
                metrics["seconds"] = number(row[0])
                live.append(metrics)
    particles = []
    for t in tables(text):
        if t[0] == ["s", "Loaded particle groups", "Cumulative timings and visibility"]:
            for row in t[2:]:
                if len(row) != 3:
                    raise ValueError(f"{path.name}: malformed particle workload row")
                metrics = {key: number(value) for key, value in re.findall(
                    rf"(?:^|;\s*)([A-Za-z][A-Za-z0-9]*)=({NUMBER})(?=;|$)", row[2])}
                metrics["seconds"] = number(row[0])
                population = re.search(r"(?:^|\s)T\s+(\d+)(?:\s|$)", row[1])
                if population:
                    metrics["loaded"] = int(population.group(1))
                particles.append(metrics)
    warnings = []
    if phase.group(2) != "complete":
        warnings.append("Probe did not complete normally")
    if int(status.group(1)) or int(status.group(2)):
        warnings.append("Menu or unfocused frames were recorded")
    if len(samples) < 18 or samples[-1]["s"] - samples[0]["s"] < 17:
        warnings.append("Too little sampled coverage for the declared 20-second probe")
    if any(s["Focused"] != "true" or s["Detailed profiler"] != "false" for s in samples):
        warnings.append("Sample has focus loss or detailed profiling enabled")
    if "detailed profiler false" not in context_fields:
        warnings.append("Context does not confirm detailed profiling OFF")
    if len({(s["Camera XYZ"], s["Yaw / pitch / FOV"]) for s in samples}) != 1:
        warnings.append("Sampled camera changed")
    if re.search(r"extreme display mode (FREEZE|UNRENDER|TICKS_ONLY)", context):
        raise ValueError(f"{path.name}: diagnostic fidelity-changing mode cannot be a throughput claim")
    if enabled and not {"extreme display mode LIVE_CACHE", "NPC pose budget false", "pause display ticks false"} <= context_fields:
        warnings.append("ON does not confirm the ordinary LIVE_CACHE fidelity-preserving preset")
    if not {"particle light cache false", "particle empty collision false"} <= context_fields:
        warnings.append("Particle experiments are enabled: not the planned default-preset comparison")
    if enabled and (not live or any(r["status"] != "on" for r in live)):
        warnings.append("Live GPU cache activation is not confirmed in every sample; inspect fallbacks")
    return dict(file=path.name, sha256=hashlib.sha256(raw).hexdigest(), phase=phase.group(1),
                result=phase.group(2), context=context, enabled=enabled, mean_observed_fps=fps,
                samples=samples, sampled_cpu_ms=costs, live_samples=live,
                particle_samples=particles, warnings=warnings)


def spread(values: list[float]) -> float:
    mean = statistics.mean(values)
    return (max(values) - min(values)) / mean * 100 if mean else 0.0


def cache_summary(run: dict) -> dict:
    rows = run["live_samples"]
    if not rows:
        return {}
    result = {}
    for key in ("localBytes", "indexBytes", "meshes", "instances", "fallback", "draws"):
        values = [r[key] for r in rows if key in r]
        if values:
            result[key + "_sampled_range"] = [min(values), max(values)]
    # Cumulative counter differences describe only this sampled subinterval.
    if len(rows) > 1:
        first, last = rows[0], rows[-1]
        if "framesTotal" in first and "framesTotal" in last:
            frames = last["framesTotal"] - first["framesTotal"]
            if frames > 0:
                result["counter_interval_frames"] = frames
                result["counter_interval_seconds"] = last["seconds"] - first["seconds"]
                for key in ("captureTotalMs", "uploadTotalMs", "drawTotalMs", "instanceUploadedBytes"):
                    if key in first and key in last:
                        delta = last[key] - first[key]
                        if delta >= 0:
                            result[key + "_per_frame"] = delta / frames
                        else:
                            run["warnings"].append(f"Counter reset: {key}; interval value unavailable")
            elif frames < 0:
                run["warnings"].append("Live frame counter reset; interval values unavailable")
    return result


def load_series(manifest_path: Path) -> tuple[dict, list[dict], list[dict], list[str]]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported manifest schema")
    if not isinstance(manifest.get("title"), str) or not manifest["title"].strip():
        raise ValueError("Manifest title is required")
    if not isinstance(manifest.get("notes", []), list) or any(not isinstance(n, str) for n in manifest.get("notes", [])):
        raise ValueError("Manifest notes must be an array of strings")
    environment = manifest.get("environment", {})
    for key in ENVIRONMENT_KEYS:
        value = environment.get(key)
        if not isinstance(value, str) or not value.strip() or value in ("REQUIRED", "UNKNOWN") or "REPLACE_" in value:
            raise ValueError(f"Fill environment.{key} before generating a benchmark")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", environment["artifact_sha256"]):
        raise ValueError("Artifact SHA-256 must contain 64 hex characters")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", environment["source_commit"]):
        raise ValueError("Use the full measured source commit")
    entries = manifest.get("runs", [])
    if not isinstance(entries, list) or any(not isinstance(e, dict) for e in entries):
        raise ValueError("Manifest runs must be an array of objects")
    roles = [e.get("role") for e in entries]
    if len(entries) < 3 or len(entries) % 2 == 0 or roles != ["off" if i % 2 == 0 else "on" for i in range(len(entries))]:
        raise ValueError("Runs must be ordered OFF, ON, OFF, ... ON, OFF")
    runs = []
    for entry in entries:
        path = (manifest_path.parent / entry["path"]).resolve()
        run = parse_probe(path)
        run["role"] = entry["role"]
        if run["enabled"] != (entry["role"] == "on"):
            raise ValueError(f"{path.name}: role does not match DrawLess enabled in the report")
        run["cache_summary"] = cache_summary(run)
        runs.append(run)
    if len({r["sha256"] for r in runs}) != len(runs):
        raise ValueError("Duplicate report bytes; each run must have its own recorded input")
    if len({r["phase"] for r in runs}) != len(runs):
        raise ValueError("Run phase labels must be unique")
    warnings = [f"{r['phase']}: {w}" for r in runs for w in r["warnings"]]
    normalized = {re.sub(r"; DrawLess enabled (true|false)", "", r["context"]) for r in runs}
    if len(normalized) != 1:
        warnings.append("Report contexts differ beyond the master switch")
    cameras = {(s["Camera XYZ"], s["Yaw / pitch / FOV"]) for r in runs for s in r["samples"]}
    if len(cameras) != 1:
        warnings.append("Camera differs between runs")
    for key in ("Display loaded", "Item loaded", "Players", "Bone carriers"):
        change = spread([s[key] for r in runs for s in r["samples"]])
        if change > 2:
            warnings.append(f"{key} sampled range/mean is {change:.2f}% (>2% screening threshold)")
    off_spread = spread([r["mean_observed_fps"] for r in runs if r["role"] == "off"])
    if off_spread > 5:
        warnings.append(f"OFF FPS range/mean is {off_spread:.2f}% (>5% screening threshold)")
    pairs = []
    for index in range(1, len(runs), 2):
        baseline = (runs[index - 1]["mean_observed_fps"] + runs[index + 1]["mean_observed_fps"]) / 2
        on = runs[index]["mean_observed_fps"]
        pairs.append(dict(on=runs[index]["phase"], bracketed_off_fps=baseline, on_fps=on,
                          change_percent=(on / baseline - 1) * 100))
    if len(pairs) < 3:
        warnings.append("Fewer than three ON runs: exploratory comparison only")
    return manifest, runs, pairs, warnings


def markdown_cell(value: object) -> str:
    return str(value).replace("|", r"\|").replace("\n", " ").replace("\r", " ")


def render(output: Path, manifest: dict, runs: list[dict], pairs: list[dict], warnings: list[str],
           reviewed_plots: tuple[str, ...] = ()) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from cycler import cycler

    palette = json.loads(Path(__file__).with_name("palette.json").read_text(encoding="utf-8"))
    background, primary = palette["background"], palette["primary"]
    mint, green, violet = palette["mint"], palette["green"], palette["violet"]
    text, muted = palette["text"], palette["muted_text"]

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11,
                         "axes.spines.top": False, "axes.spines.right": False,
                         "figure.facecolor": background, "axes.facecolor": background,
                         "savefig.facecolor": background, "text.color": text,
                         "axes.labelcolor": text, "axes.titlecolor": text,
                         "axes.edgecolor": muted, "xtick.color": muted, "ytick.color": muted,
                         "grid.color": mint, "axes.axisbelow": True,
                         "axes.prop_cycle": cycler(color=[primary, mint, violet, green]),
                         "legend.facecolor": background, "legend.edgecolor": muted,
                         "legend.labelcolor": text, "legend.framealpha": 1,
                         "svg.hashsalt": "drawless-bench-v1"})
    colors = [mint if r["role"] == "off" else primary for r in runs]
    on_runs = [r for r in runs if r["role"] == "on"]
    review_exports = {}

    def phase_label(run):
        return re.sub(r"^spawn_", "", run["phase"]).upper()

    def save(fig, name):
        if name in reviewed_plots:
            fig.set_layout_engine(None)
            fig.subplots_adjust(left=.09, right=.98, bottom=.22, top=.78)
            fig.suptitle("DrawLess / Benchmark 01", x=.09, y=.97, ha="left", fontsize=17, weight="bold")
            fig.text(.09, .895, "Animated Item Display spawn · 0.1.0 + Sodium · No ImmediatelyFast · Minecraft 26.2 / Fabric",
                     fontsize=10, color=muted)
            if name == "phase-fps":
                footnote = f"{len(runs)} × 20 s phases · Recorded order · OFF controls bracket each ON run · Single session"
                limitation = "Ryzen 5 5600H / RTX 3060 Laptop GPU · Scene-specific result · Full conditions: Benchmark 01 report"
            else:
                frames = sum(r["cache_summary"]["counter_interval_frames"] for r in on_runs)
                footnote = f"Warm ON intervals · {frames:,.0f} frames · First-to-last sample counter differences, divided by frames"
                limitation = "Measured CPU scopes only · Excludes cold start, GPU execution and unmeasured overhead"
            fig.text(.09, .075, footnote, fontsize=10, color=muted)
            fig.text(.09, .035, limitation, fontsize=10, color=muted)
        else:
            fig.suptitle(manifest["title"], fontsize=11, color=muted)
            fig.get_layout_engine().set(rect=(0, .09, 1, .91))
            fig.text(.015, .015, "DRAFT — manual environment and fidelity review required", fontsize=9, color=muted)
        for axis in fig.axes:
            for spine in axis.spines.values():
                spine.set_alpha(.35)
        fig.savefig(output / f"{name}.png", dpi=180, bbox_inches="tight", facecolor=background)
        fig.savefig(output / f"{name}.svg", bbox_inches="tight", facecolor=background, metadata={"Date": None, "Creator": None})
        if name in reviewed_plots:
            review_exports[name] = {
                suffix + "_sha256": hashlib.sha256((output / f"{name}.{suffix}").read_bytes()).hexdigest()
                for suffix in ("png", "svg")}
        plt.close(fig)

    fig, ax = plt.subplots(figsize=(11, 6 if "phase-fps" in reviewed_plots else 5.4), layout="constrained")
    fps = [r["mean_observed_fps"] for r in runs]
    bars = ax.bar(range(len(runs)), fps, color=colors, width=.64)
    for bar, value, color in zip(bars, fps, colors):
        ax.annotate(f"{value:.2f}", (bar.get_x()+bar.get_width()/2, bar.get_height()),
                    xytext=(0, 4), textcoords="offset points", ha="center", va="bottom", color=color)
    ax.set(xticks=range(len(runs)), xticklabels=[phase_label(r) if "phase-fps" in reviewed_plots else r["phase"] for r in runs], ylabel="Mean observed FPS",
           ylim=(0, max(fps) * 1.18), title="Mean FPS across all seven phases" if "phase-fps" in reviewed_plots else "DrawLess | Every recorded phase")
    ax.tick_params(axis="x", labelrotation=0 if "phase-fps" in reviewed_plots else 25)
    ax.grid(axis="y", alpha=.18)
    save(fig, "phase-fps")

    fig, ax = plt.subplots(figsize=(11, 5.4), layout="constrained")
    role_indices = {"off": 0, "on": 0}
    for i, run in enumerate(runs):
        index = role_indices[run["role"]]
        role_indices[run["role"]] += 1
        color = primary if run["role"] == "on" else (mint, violet)[index % 2]
        ax.plot([s["s"] for s in run["samples"]], [s["FPS"] for s in run["samples"]],
                marker=("o", "s", "^", "D")[index % 4], markersize=3.8, linewidth=1.6,
                color=color, label=run["phase"], linestyle="--" if run["role"] == "off" else "-")
    ax.set(xlabel="Seconds within each phase", ylabel="Sampled Minecraft FPS (~1 Hz)",
           title="Sampled FPS | This is not a per-frame timing trace")
    ax.legend(fontsize=9, ncol=2)
    ax.grid(alpha=.18)
    save(fig, "sampled-fps")

    has_particles = all(any("loaded" in s for s in r["particle_samples"]) for r in runs)
    fig, axes = plt.subplots(3 if has_particles else 2, 1,
                             figsize=(11, 9 if has_particles else 6.6), layout="constrained")
    for key, color in (("Display loaded", violet), ("Item loaded", mint)):
        values = [[s[key] for s in r["samples"]] for r in runs]
        means = [statistics.mean(v) for v in values]
        axes[0].errorbar(range(len(runs)), means,
                         yerr=([m-min(v) for m,v in zip(means,values)], [max(v)-m for m,v in zip(means,values)]),
                         marker="o", capsize=4, label=key, color=color)
    axes[0].set(ylabel="Loaded objects", title="Workload control | Bars show sampled minimum / maximum")
    axes[0].set_ylim(0, max(s["Display loaded"] for r in runs for s in r["samples"]) * 1.15 + 1)
    axes[0].legend()
    axes[1].plot(range(len(runs)), [statistics.mean(s["Players"] for s in r["samples"]) for r in runs],
                 "o-", color=mint, markerfacecolor=green, markeredgecolor=mint, markersize=7)
    axes[1].set(ylabel="Mean sampled players")
    axes[1].set_ylim(0, max(s["Players"] for r in runs for s in r["samples"]) * 1.15 + 1)
    if has_particles:
        values = [[s["loaded"] for s in r["particle_samples"] if "loaded" in s] for r in runs]
        means = [statistics.mean(v) for v in values]
        axes[2].errorbar(range(len(runs)), means,
                         yerr=([m-min(v) for m,v in zip(means,values)], [max(v)-m for m,v in zip(means,values)]),
                         marker="o", capsize=4, color=mint)
        axes[2].set(ylabel="Loaded particles", ylim=(0, max(max(v) for v in values) * 1.15 + 1))
    from matplotlib.ticker import MaxNLocator
    for ax in axes:
        ax.yaxis.set_major_locator(MaxNLocator(integer=True))
    for ax in axes:
        ax.set_xticks(range(len(runs)), [r["phase"] for r in runs], rotation=25)
        ax.grid(alpha=.18)
    save(fig, "workload")

    cache_cost_keys = ("captureTotalMs_per_frame", "uploadTotalMs_per_frame", "drawTotalMs_per_frame")
    has_cache_costs = bool(on_runs) and all(all(k in r["cache_summary"] for k in cache_cost_keys) for r in on_runs)
    if has_cache_costs:
        fig, ax = plt.subplots(figsize=(11, 6 if "cache-cpu" in reviewed_plots else 5.4), layout="constrained")
        for index, (key, label, color) in enumerate(zip(cache_cost_keys,
                ("Capture CPU", "Upload CPU submission", "Draw CPU submission"),
                (primary, violet, mint))):
            values = [r["cache_summary"][key] for r in on_runs]
            bars = ax.bar([i + (index-1)*.24 for i in range(len(on_runs))], values,
                          width=.22, label=label, color=color)
            ax.bar_label(bars, fmt="%.3f", padding=4, fontsize=10, color=text if "cache-cpu" in reviewed_plots else color)
        ax.set(xticks=range(len(on_runs)), xticklabels=[phase_label(r) if "cache-cpu" in reviewed_plots else r["phase"] for r in on_runs],
               ylabel="CPU ms / frame" if "cache-cpu" in reviewed_plots else "CPU ms / frame (cumulative counter differences)",
               title="Live cache: measured CPU costs" if "cache-cpu" in reviewed_plots else "Live cache processing | Warm sampled intervals, not GPU elapsed time")
        ax.set_ylim(0, max(r["cache_summary"][k] for r in on_runs for k in cache_cost_keys)*1.4)
        ax.legend(fontsize=9)
        ax.grid(axis="y", alpha=.18)
        save(fig, "cache-cpu")

    if set(review_exports) != set(reviewed_plots):
        raise ValueError("A requested reviewed plot could not be generated from the available counters")
    if review_exports:
        export_record = dict(schema_version=1, status="reviewed_figure_exports", figures=review_exports,
                             generator_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                             palette_sha256=hashlib.sha256(Path(__file__).with_name("palette.json").read_bytes()).hexdigest(),
                             inputs=[dict(file=r["file"], sha256=r["sha256"]) for r in runs],
                             scope="Selected figure presentation reviewed; does not certify all measurements or publish files")
        (output / "figure-exports.json").write_text(json.dumps(export_record, indent=2)+"\n", encoding="utf-8")

    mean_change = statistics.mean(p["change_percent"] for p in pairs)
    summary = dict(schema_version=1, status="draft_manual_review_required", environment=manifest["environment"],
                   title=manifest["title"], runs=runs, pairs=pairs,
                   mean_paired_change_percent=mean_change, warnings=warnings, notes=manifest.get("notes", []),
                   generator=dict(python=platform.python_version(), matplotlib=matplotlib.__version__,
                                  source_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                                  palette=palette,
                                  palette_sha256=hashlib.sha256(Path(__file__).with_name("palette.json").read_bytes()).hexdigest()),
                   unavailable=["per-frame trace", "p95/p99 frame time", "1% low", "GPU elapsed time",
                                "recorder overhead calibration", "automated live-server visual parity"])
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    lines = ["# " + markdown_cell(manifest["title"]), "", "**DRAFT — manual review required; not a published claim.**", "",
             f"Mean paired FPS change: **{mean_change:+.2f}%** across {len(pairs)} ON runs.", "",
             "Each ON is compared with the mean FPS of its adjacent OFF runs. No confidence interval is inferred.", "",
             "## Review findings", ""]
    lines += ["- " + markdown_cell(w) for w in warnings] or ["- No automatic screening flags. Manual review is still required."]
    lines += ["", "## Environment (declared by the operator)", "", "| Field | Value |", "|---|---|"]
    lines += [f"| {key} | {markdown_cell(value)} |" for key, value in manifest["environment"].items()]
    lines += ["", "Artifact hash and source association are operator declarations, not verified by this parser.", "",
              "## Runs", "", "| Phase | Role | Mean observed FPS | Input SHA-256 |", "|---|---|---:|---|"]
    lines += [f"| {r['phase']} | {r['role']} | {r['mean_observed_fps']:.2f} | {r['sha256']} |" for r in runs]
    lines += ["", "## Report contexts", ""]
    lines += [f"- {r['phase']}: {markdown_cell(r['context'])}" for r in runs]
    lines += ["", "## Paired comparisons", "", "| ON | Bracketed OFF FPS | ON FPS | Change |", "|---|---:|---:|---:|"]
    lines += [f"| {p['on']} | {p['bracketed_off_fps']:.2f} | {p['on_fps']:.2f} | {p['change_percent']:+.2f}% |" for p in pairs]
    lines += ["", "![Every recorded phase](phase-fps.png)", "", "![Sampled FPS](sampled-fps.png)", "", "![Workload](workload.png)", "",
              "## Sampled CPU stages", "", "CPU inclusive timings, ms/sampled frame. Do not add nested stages.", "",
              "| Phase | Stage | ms/sampled frame |", "|---|---|---:|"]
    lines += [f"| {r['phase']} | {markdown_cell(k)} | {v:.5f} |" for r in runs for k,v in r["sampled_cpu_ms"].items()]
    lines += ["", "## Live cache costs", "", "Ranges cover sampled frames only. Counter deltas cover the first-to-last sample interval, not the full run.", "",
              "Tracked buffer bytes are not total VRAM; upload bytes/frame are traffic. CPU timings are not GPU time.", "",
              "| Phase | Metric | Value |", "|---|---|---|"]
    lines += [f"| {r['phase']} | {k} | {markdown_cell(v)} |" for r in runs for k,v in r["cache_summary"].items()]
    if has_cache_costs:
        lines += ["", "![Warm live-cache CPU costs](cache-cpu.png)"]
    lines += ["", "## Particle workload", "",
              "All loaded particles, not only visible particles. Ranges are sampled minima/maxima.", "",
              "| Phase | Mean loaded | Sampled range | Tick ms/tick | Extraction ms/frame |", "|---|---:|---|---:|---:|"]
    for run in runs:
        rows = run["particle_samples"]
        loaded = [r["loaded"] for r in rows if "loaded" in r]
        if not loaded:
            continue
        costs = []
        for total, count in (("tickTotalMs", "ticksTotal"), ("frameTotalMs", "framesTotal")):
            value = "unavailable"
            if len(rows) > 1 and all(total in r and count in r for r in (rows[0], rows[-1])):
                dt = rows[-1][total] - rows[0][total]
                dc = rows[-1][count] - rows[0][count]
                if dt >= 0 and dc > 0:
                    value = f"{dt/dc:.5f}"
            costs.append(value)
        lines.append(f"| {run['phase']} | {statistics.mean(loaded):.2f} | {min(loaded)}–{max(loaded)} | {costs[0]} | {costs[1]} |")
    lines += ["", "## Notes", ""]
    lines += ["- " + markdown_cell(n) for n in manifest.get("notes", [])]
    lines += ["", "## Missing evidence", "", "- No per-frame percentiles, 1% low, GPU elapsed time or recorder-overhead calibration.",
              "- Original OFF/ON screenshots and animation review must be supplied separately.",
              "- Counts and unchanged rounded camera samples do not prove identical visible output.",
              "- Preserve all original inputs and their hashes; review privacy before publishing raw files.", ""]
    (output / "report.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inspect", type=Path, help="Inspect one real probe without plotting or benchmark claims")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--reviewed-plots", nargs="+", choices=("phase-fps", "cache-cpu"), default=[],
                        help="Export selected figures without DRAFT after environment and fidelity review")
    args = parser.parse_args()
    try:
        if args.inspect:
            if args.manifest or args.output or args.reviewed_plots:
                parser.error("--inspect cannot be combined with generation")
            run = parse_probe(args.inspect)
            run["cache_summary"] = cache_summary(run)
            print(json.dumps({k:v for k,v in run.items() if k not in ("samples", "live_samples", "particle_samples")}, ensure_ascii=False, indent=2))
            return 0
        if not args.manifest or not args.output:
            parser.error("Use --inspect, or both --manifest and --output")
        manifest, runs, pairs, warnings = load_series(args.manifest.resolve())
        if args.reviewed_plots and warnings:
            raise ValueError("Resolve screening flags before exporting reviewed figures: " + "; ".join(warnings))
        if args.reviewed_plots and (len(runs) != 7 or len(pairs) != 3 or
                                   any(len(r["samples"]) != 20 for r in runs)):
            raise ValueError("The reviewed Benchmark 01 layout requires seven 20-sample phases and three ON comparisons")
        if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
            raise ValueError("Output must be a new or empty directory; existing results are never overwritten")
        args.output.mkdir(parents=True, exist_ok=True)
        render(args.output, manifest, runs, pairs, warnings, tuple(args.reviewed_plots))
        print(f"Draft report: {args.output.resolve() / 'report.md'}; screening flags: {len(warnings)}")
        return 0
    except (OSError, ValueError, KeyError, TypeError, ImportError) as error:
        print(f"Benchmark input error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
