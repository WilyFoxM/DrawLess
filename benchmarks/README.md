# DrawLess benchmarks

[Methodology](METHODOLOGY.md) · [0.1.0 correctness validation](VALIDATION_0.1.0.md) · [Raw measurements](results/spawn-20261016/raw/)

## Benchmark 01 — animated Item Display spawn

Recorded 2026-10-16, analysed 2026-10-17. DrawLess **0.1.0 with Sodium**, without
ImmediatelyFast, improved mean observed FPS by **21.24% across three bracketed ON
comparisons** in this scene. The mean bracketed OFF baseline is **242.91 FPS**;
mean ON is **294.50 FPS**.

[![Benchmark 01 headline comparison](results/spawn-20261016/modrinth-card.png)](results/spawn-20261016/modrinth-card.png)

This compares the DrawLess master switch OFF with its default optimization set ON.
It does not isolate one cache, compare against ImmediatelyFast or establish gains
for other scenes, computers or GPU-bound settings.

## Every recorded phase

Each ON is compared with the arithmetic mean FPS of the immediately neighboring
OFF controls. The headline is the mean of the three percentage changes.

| Order | Phase | Mean observed FPS | Adjacent OFF baseline | Paired change |
|---:|---|---:|---:|---:|
| 1 | OFF1 | 243.24 | — | — |
| 2 | ON1 | 295.31 | 243.645 | +21.205% |
| 3 | OFF2 | 244.05 | — | — |
| 4 | ON2 | 296.10 | 244.600 | +21.055% |
| 5 | OFF3 | 245.15 | — | — |
| 6 | ON3 | 292.09 | 240.495 | +21.454% |
| 7 | OFF4 | 235.84 | — | — |

[![All seven measured phases](results/spawn-20261016/phase-fps.png)](results/spawn-20261016/phase-fps.png)

All seven reports are retained. OFF4 is lower than the previous controls: OFF
max-minus-min divided by mean is **3.85%**. Bracketing incorporates this control;
it cannot remove arbitrary server changes, thermal drift or time-order effects.
The unpaired means of all four OFF and all three ON runs are 242.07 and 294.50 FPS;
those are a different calculation from the headline baseline.

Three ON runs in one session are preliminary repeated observations, not independent
sessions or a statistical confidence estimate. No p99/1% low is derived from the
approximately once-per-second displayed-FPS samples.

## Scene and fidelity

PrisonEvo-1 #5, dimension `spawn/overworld-dbb2`, camera XYZ `-21.34 / 99.12 / 0.99`,
yaw/pitch `89.89 / 4.48`, FOV 110, render distance 7, GUI scale 2.

Across all **140 scene samples**: 569 loaded Display entities, 563 Item Display,
407 bone carriers, 530 custom-model-data items, 439 passengers and one player.
Recorded camera values are identical. Each phase completed with 20 samples;
menu frames excluded and unfocused measured frames are zero in every report.
Bone-carrier count does not mean every carrier is an animated bone. Rounded
camera samples and object counts do not establish continuous camera immobility
or identical visible output.

The operator observed unchanged NPC appearance, animation, lighting and visible
parts in every ON phase. NPC pose budgeting, tick pausing, particle-light and
particle-collision experiments were OFF. This is an observation and supporting
counter evidence, not a synchronized pixel-parity test.

Mean loaded particles ranged from 1571.95 to 1646.90 across phases. The population
fluctuated naturally; ON did not consistently have fewer loaded particles.
Loaded counts are not visible-particle counts.

## What the optimization costs

CPU values use cumulative counter differences over the first-to-last sample
intervals of the ON runs, weighted by their combined **16,835 frames**. These
intervals omit recording edges and are already warm.

| Measured work | CPU ms/frame |
|---|---:|
| Live geometry capture | 0.16566 |
| Live upload submission | 0.02636 |
| Live draw submission | 0.07624 |

[![Measured live-cache CPU costs](results/spawn-20261016/cache-cpu.png)](results/spawn-20261016/cache-cpu.png)

These are specific CPU scopes, not total optimization overhead. They exclude
cold-start construction, model-layer refresh, recorder overhead and GPU execution.
Do not add them to inclusive scopes that already contain their work.

- Shared local geometry: **518,912 bytes = 506.75 KiB**, 210 retained meshes.
- Sampled index buffers: **115,920–116,496 bytes** across ON runs.
- Instance-buffer traffic: weighted **36,452 bytes/frame ≈35.60 KiB/frame**.
- Cached parts per sampled frame: **282–286**; one cached-scene draw per sampled frame.
- Ordinary prepared Item Display states: 333 in OFF, 47–51 in ON, including 32 empty
  states. These counts exclude GPU-cached parts and do not indicate missing models.
- No new mesh builds or local-geometry uploads between the first and last ON samples.
  This does not measure the initial cache construction cost.

Tracked buffers are not total GPU memory; traffic per frame is not retained VRAM.

## Environment and limitations

- Measured JAR: `drawless-0.1.0.jar`, SHA-256
  `3d69eb5f7ad88730327a97068b78c2c525a87948e7bbb960056454ca61eceb3b`.
  The installed file was checked after recording and matches the archived artifact.
- Recording-session log: Minecraft 26.2, Fabric Loader **0.20.0**, Fabric API
  0.161.0+26.2, DrawLess 0.1.0, Sodium 0.9.2+mc26.2; no ImmediatelyFast.
- GPU/driver from that log: NVIDIA GeForce RTX 3060 Laptop GPU, NVIDIA 610.62, OpenGL.
- CPU from system inventory at analysis: AMD Ryzen 5 5600H; nominal 32 GB system RAM.
- Operator-confirmed: maximized window, unlimited FPS, VSync OFF, AC power,
  Performance mode.
- Java major version 25 is logged. Exact Java build, JVM heap/arguments, framebuffer
  dimensions, full graphics preset and resource-pack version at measurement were
  not captured. Warmup/wait timing was not independently recorded.

Missing details limit exact reproduction. Current settings are not substituted for
historical evidence. Original session logs contain unrelated private information
and remain local. These results describe one recorded scene and one session.

## Original inputs

All seven original reports are preserved byte-for-byte in
[raw/](results/spawn-20261016/raw/). The identifiers below verify their contents.

| Phase | Original report | SHA-256 |
|---|---|---|
| OFF1 | [drawless-20261016-235418-647-spawn_off1.md](results/spawn-20261016/raw/drawless-20261016-235418-647-spawn_off1.md) | `7fc51a27493a133999a10a159c7d2a6e04bcfa272746ca2d811d7612e9f90001` |
| ON1 | [drawless-20261016-235458-741-spawn_on1.md](results/spawn-20261016/raw/drawless-20261016-235458-741-spawn_on1.md) | `f7e392f98d91cd959f0c1dedafe73a19ea9e56bf7a84c5a36415046b5dbed7ea` |
| OFF2 | [drawless-20261016-235533-511-spawn_off2.md](results/spawn-20261016/raw/drawless-20261016-235533-511-spawn_off2.md) | `8f82da9e96b2bada78a3a4f64ca61ff7bd65c28f0983d9eb85bb07516d8a0a6e` |
| ON2 | [drawless-20261016-235609-317-spawn_on2.md](results/spawn-20261016/raw/drawless-20261016-235609-317-spawn_on2.md) | `58ad8326e3bf530b827149532cc35fe306816cd674f3f875d44f931ea31a5df0` |
| OFF3 | [drawless-20261016-235743-952-spawn_off3.md](results/spawn-20261016/raw/drawless-20261016-235743-952-spawn_off3.md) | `a5e77f23a102ceb9a5cf2be302d63b2a2a41a7f41986565ae943688622bbee61` |
| ON3 | [drawless-20261016-235820-613-spawn_on3.md](results/spawn-20261016/raw/drawless-20261016-235820-613-spawn_on3.md) | `b2ba73790f4ed83e1515ea5aa0fc477eaf8f3d5188a3c04cc3737e8a833e58d5` |
| OFF4 | [drawless-20261016-235902-841-spawn_off4.md](results/spawn-20261016/raw/drawless-20261016-235902-841-spawn_off4.md) | `3c506e1b5025597304cc5de708cd0eefaec1449d4547a0ec104862a6d8b03a88` |
