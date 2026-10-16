# Probe reporting tool

This external tool reads existing DrawLess probe Markdown. It does not change the
game, gather hidden telemetry, control an account or publish files.

## Setup (Python 3.11+)

```sh
python -m venv build/benchmark-tools-venv
# Windows:
build/benchmark-tools-venv/Scripts/python -m pip install -r tools/benchmarks/requirements.txt
# Linux/macOS: use build/benchmark-tools-venv/bin/python instead.
```

Copy `spawn.example.json` to a private working directory and fill every required
environment field and the report paths. Relative input paths resolve from that JSON.
Use one real recording for each entry; roles must alternate OFF/ON and begin/end OFF.
The existing protocol measures the master switch with other settings held constant.

```sh
python tools/benchmarks/report.py --inspect path/to/one-probe.md
python tools/benchmarks/report.py --manifest path/to/series.json --output build/spawn-output
```

Use the venv Python for plotting. `--inspect` needs only the standard library.
Output must be new/empty. Outputs: `report.md`, `summary.json` and three PNG/SVG
plot pairs (phase means, approximately 1 Hz FPS samples, workload controls including
particles where available). A fourth plot shows cache CPU costs when all required
cumulative counters are present.

Plot styling uses the shared `palette.json`: background `#0a182a`, primary accent
`#fda24c`, supporting mint `#7dcfb6`, green `#157f1f` and violet `#6564db`.
ON phases use the primary accent; OFF controls use mint, with distinct markers and
line styles for repeated traces. Light neutral text keeps labels readable on the dark background.

## Benchmark 01 page card

`card.py` creates a 1200×800 PNG and an editable SVG from the reviewed seven-phase
summary and `palette.json`. It derives the headline and both FPS values from the
summary. The bars share a linear scale starting at zero. A JSON sidecar records
input and output identities. Screenshots are not required for the card.

PNG export uses Node.js and Sharp; install the pinned renderer dependency once:

```sh
npm install --prefix tools/benchmarks
```

```sh
python tools/benchmarks/card.py --result benchmarks/results/spawn-20261016 --output build/card-output
```

Use a new/empty output directory. The current card layout is specific to Benchmark 01
and requires seven phases and three bracketed ON runs. `--node` accepts a Node.js
executable path; `--sharp-module` optionally selects an existing Sharp installation.
The SVG remains editable text; installed fonts can affect text rendering across systems.

Every output is a draft. Inspect all screening flags, validate artifact identity
and environment, and record visual fidelity observations before selecting anything
for publication. The declared artifact hash is not independently
verified against a JAR by this parser. A report hash identifies the exact input bytes.

No per-frame percentile, 1% low, confidence interval, GPU elapsed time or calibrated
instrumentation overhead is inferred from missing data. Cache rates use available
cumulative deltas over the sampled subinterval. Nested CPU stages are not added.

See `benchmarks/METHODOLOGY.md` for the interpretation and screening thresholds.

## Reviewed Benchmark 01 figures

After reviewing the environment, raw inputs and visual fidelity, export the two
selected page figures without a DRAFT label:

```sh
python tools/benchmarks/report.py --manifest benchmarks/results/spawn-20261016/series.json --output build/reviewed-figures --reviewed-plots phase-fps cache-cpu
```

This presentation is specific to Benchmark 01 and its recorded hardware/software.
The exporter requires seven 20-sample phases, three ON comparisons and no screening
flags. The option records a completed manual review; it cannot perform that review.
It does not publish files or remove the draft status from the generated numerical
appendix or other figures. `figure-exports.json` records source and export hashes.
Copy only the selected PNG/SVG pairs and this export record into the reviewed result
directory. Keep the original `summary.json` and its provenance unchanged.
