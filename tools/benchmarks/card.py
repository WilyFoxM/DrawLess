#!/usr/bin/env python3
"""Create the Benchmark 01 metrics card from its reviewed summary."""
from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import subprocess
from html import escape
from pathlib import Path


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def create_card(result: Path, output: Path, node: str, sharp_module: str | None) -> None:
    summary_path = result / "summary.json"
    palette_path = Path(__file__).with_name("palette.json")
    renderer_path = Path(__file__).with_name("render-svg.cjs")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    palette = json.loads(palette_path.read_text(encoding="utf-8"))
    pairs = summary["pairs"]
    if len(pairs) != 3 or len(summary["runs"]) != 7:
        raise ValueError("Benchmark 01 requires seven phases and three bracketed ON runs")
    if any(len(run["samples"]) != 20 for run in summary["runs"]):
        raise ValueError("The seven phases need their expected sample coverage")
    baseline = statistics.mean(pair["bracketed_off_fps"] for pair in pairs)
    enabled = statistics.mean(pair["on_fps"] for pair in pairs)
    improvement = statistics.mean(pair["change_percent"] for pair in pairs)
    if abs(improvement - summary["mean_paired_change_percent"]) > 1e-9:
        raise ValueError("Paired improvement differs from the reviewed summary")
    if min(baseline, enabled) <= 0:
        raise ValueError("FPS values must be positive")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Card output directory must be new or empty")
    output.mkdir(parents=True, exist_ok=True)

    width, height = 1200, 800
    bg, accent, mint = palette["background"], palette["primary"], palette["mint"]
    foreground, muted = palette["text"], palette["muted_text"]
    bar_width = 1048
    maximum = max(baseline, enabled)
    parts = [f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <title>DrawLess / Benchmark 01</title>
  <desc>Animated Item Display scene. {improvement:+.2f}% mean paired FPS improvement.
  Mean bracketed OFF baseline {baseline:.2f} FPS; DrawLess ON {enabled:.2f} FPS.
  Three bracketed ON comparisons in one recorded session, seven 20-second phases.
  DrawLess 0.1.0 with Sodium, without ImmediatelyFast. Full conditions in the benchmark report.</desc>
  <rect width="1200" height="800" fill="{bg}"/>
  <rect x="20.5" y="20.5" width="1159" height="759" rx="28" fill="none" stroke="{mint}" stroke-opacity="0.18"/>
  <g font-family="Arial, sans-serif">''']

    def text(x, y, value, size, color=foreground, weight="400", anchor="start", **attrs):
        extra = " ".join(f'{key.replace("_", "-")}="{escape(str(val), quote=True)}"' for key, val in attrs.items())
        parts.append(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" font-weight="{weight}" text-anchor="{anchor}" {extra}>{escape(value)}</text>')

    text(76, 91, "DrawLess", 28, weight="700")
    text(1124, 89, "BENCHMARK 01", 16, muted, anchor="end", letter_spacing="2")
    text(76, 128, "Animated Item Display scene", 20, muted)
    text(70, 324, f"{improvement:+.2f}%", 136, accent, weight="700", letter_spacing="-5")
    text(76, 371, "mean paired FPS improvement", 25)

    for label, value, y, color in (("DrawLess OFF", baseline, 462, mint),
                                   ("DrawLess ON", enabled, 564, accent)):
        text(76, y, label, 24, color)
        text(1124, y, f"{value:.2f} FPS", 28, weight="700", anchor="end")
        parts.append(f'<rect x="76" y="{y+20}" width="{bar_width}" height="16" rx="8" fill="{mint}" fill-opacity="0.08"/>')
        parts.append(f'<rect x="76" y="{y+20}" width="{bar_width*value/maximum:.4f}" height="16" rx="8" fill="{color}"/>')

    text(76, 638, "Mean FPS · OFF uses neighboring controls · Bars start at zero", 17, muted)
    parts.append(f'<path d="M76 672 H1124" stroke="{mint}" stroke-opacity="0.2"/>')
    text(76, 715, "0.1.0 + Sodium · No ImmediatelyFast", 18, muted)
    text(1124, 715, "3 bracketed ON comparisons", 18, muted, anchor="end")
    text(76, 750, "Single recorded scene · Full conditions and limitations: Benchmark 01 report", 17, muted)
    parts.append("</g></svg>\n")
    svg_path = output / "modrinth-card.svg"
    png_path = output / "modrinth-card.png"
    svg_path.write_text("\n".join(parts), encoding="utf-8")
    command = [node, str(renderer_path.resolve()), str(svg_path.resolve()), str(png_path.resolve())]
    if sharp_module:
        command.append(sharp_module)
    subprocess.run(command, check=True)
    if png_path.stat().st_size > 5*1024*1024:
        raise ValueError("Card exceeds the intended 5 MiB gallery budget")
    metadata = {
        "schema_version": 2, "layout_version": "metrics-v2", "canvas": [width, height],
        "mean_bracketed_off_fps": baseline, "mean_on_fps": enabled,
        "mean_paired_change_percent": improvement,
        "comparison_count": len(pairs), "phase_count": len(summary["runs"]),
        "bar_scale": "linear, shared zero origin",
        "summary_sha256": digest(summary_path), "palette_sha256": digest(palette_path),
        "generator_sha256": digest(Path(__file__)), "renderer_sha256": digest(renderer_path),
        "svg_sha256": digest(svg_path), "png_sha256": digest(png_path),
    }
    (output / "modrinth-card.json").write_text(json.dumps(metadata, indent=2)+"\n", encoding="utf-8")
    print(f"Card: {png_path.resolve()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--node", default="node", help="Node.js executable for SVG rasterization")
    parser.add_argument("--sharp-module", help="Optional absolute path to an existing sharp module")
    args = parser.parse_args()
    create_card(args.result, args.output, args.node, args.sharp_module)


if __name__ == "__main__":
    main()
