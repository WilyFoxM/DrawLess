# Benchmark methodology

## Scope and baseline

State the scene, artifact version/SHA-256, hardware, Java, driver, graphics settings,
resource packs, mod versions and the exact change between phases. Master OFF versus
default ON measures the default DrawLess optimization set; it does not isolate Live
geometry. Master OFF retains diagnostic hooks. Testing without the mod is a separate
installation-overhead control.

Use the public JAR for public performance claims. Development instrumentation and
synthetic correctness runs must be labelled separately.

## Runs

Warm the scene before recording. Hold camera, window, configuration and mod set
constant. Run repeated ON phases with adjacent OFF controls, then where practical
repeat with the order reversed. Preserve every input and explain interruptions.
Do not select the fastest ON run or discard controls because they reduce the gain.
Three ON runs are a preliminary series, not a claim of statistical certainty.

For the existing probe, each phase has 2 seconds of transition warmup followed by
20 seconds of measurement. The live-server protocol additionally waits 60 seconds
before the series and 10 seconds after each switch. These waits are conventions,
not proof that JIT compilation, streaming or temperature have stabilized.

## Available measurements

The existing probe reports mean observed FPS from accumulated frame intervals.
Its approximately once-per-second FPS samples are Minecraft's displayed FPS and
are not an individual-frame trace. Do not derive frame-time percentiles, 1% lows
or confidence intervals over independent runs from those samples.

Counts distinguish loaded objects, prepared states and optimized parts. A bone
carrier is not proof of an animated bone. Counts do not establish pixel equivalence.
CPU timings may be inclusive or sampled: do not add parent and child scopes or
mix ms/tick with ms/frame. CPU submission time is not GPU execution time.

Cumulative counters require differences between samples and a matching denominator.
Bytes uploaded per frame are traffic, not retained VRAM. Buffer sizes describe tracked
buffers, not total process/GPU memory. Missing metrics remain unavailable.

## Comparability and overhead

Report focus loss, menus, changed camera, populations and controls. More than 5%
OFF FPS spread (range divided by mean) and more than 2% loaded population spread
are flagged for manual review. These are declared screening thresholds, not
significance tests, automatic exclusions or proof that unflagged runs are controlled.
Rounded camera coordinates and constant sampled counts cannot prove a fixed scene.

Each ON result is compared with the arithmetic mean FPS of its adjacent OFF runs.
Report the individual paired changes and their arithmetic mean, not just the best run.
This bracketing estimates drift; it cannot remove server or temporal confounding.

Measure cache maintenance and recorder overhead separately. Detailed profiles explain
costs; headline throughput uses a light recorder. Shared-service costs are counted once.
Do not subtract an assumed recorder penalty from measured FPS.

## Fidelity and publication

Capture original OFF/ON images separately from throughput measurements. Check live
animation, lighting, tint, visibility and supported/fallback materials. Live-server
screenshots are not synchronized pixel-parity tests. GPU buffer counters do not prove
correct rendering, and a screenshot does not prove correct animation.

Publish reviewed results with raw measurements/hashes, configuration and limitations.
Preserve original inputs locally. Review inputs for private paths, chat and identifying
data before publication. Any redaction must be declared; do not silently rewrite raw
measurements. Generated drafts are not automatically suitable for a Modrinth claim.

## Figures

The published PNG figures show the headline comparison, every recorded phase and
the measured live-cache CPU scopes. Their values come from the preserved raw
measurements; generation is performed separately from the mod.
