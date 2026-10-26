# DrawLess benchmarks

[Methodology](METHODOLOGY.md) · [0.1.0 correctness validation](VALIDATION_0.1.0.md) · [Rejected optimization experiments](../docs/REJECTED_OPTIMIZATIONS.md)

Benchmark 02 is a development-build/selected-preset result. Benchmark 01 below remains the published 0.1.0/default-preset result. Neither is a direct comparison between versions.

## Benchmark 02 — animated Item Display spawn

**+34.53% mean paired FPS change** across three bracketed ON comparisons in one recorded scene/session. Mean bracketed OFF baseline **241.88 FPS**; mean ON **325.39 FPS**.

**Measured build: 0.2.0-dev.4**, with Sodium, EXPANDED feature batching and text buffer reuse ON; particle light/collision caches OFF. This is master OFF versus that selected ON preset. It is not a comparison of version 0.1.0 with 0.2.0 and is not a benchmark of final 0.2.0 defaults (text reuse defaults OFF).

[![Benchmark 02 headline](results/spawn-20261017/modrinth-card.png)](results/spawn-20261017/modrinth-card.png)

### Every recorded phase

| Order | Phase | Mean observed FPS | Adjacent OFF baseline | Paired change |
|---:|---|---:|---:|---:|
| 1 | OFF1 | 259.42 | — | — |
| 2 | ON1 | 337.65 | 250.400 | +34.844% |
| 3 | OFF2 | 241.38 | — | — |
| 4 | ON2 | 320.78 | 235.670 | +36.114% |
| 5 | OFF3 | 229.96 | — | — |
| 6 | ON3 | 317.74 | 239.555 | +32.638% |
| 7 | OFF4 | 249.15 | — | — |

Each ON is compared with the arithmetic mean FPS of its immediately neighboring OFF phases. The headline is the arithmetic mean of all three percentage changes; it differs slightly from the ratio of the aggregate means. All seven original files are retained.

[![All seven phases](results/spawn-20261017/phase-fps.png)](results/spawn-20261017/phase-fps.png)

### Review findings and limitations

OFF values are 259.42 / 241.38 / 229.96 / 249.15 FPS: range/mean **12.03%**, above the methodology screening threshold of 5%. ON3 changes from 438 to 426 Item Displays; OFF4 remains at 426. This exceeds the 2% population screening threshold. These flags were manually reviewed and disclosed; they are not grounds for deleting runs, nor are they significance tests. Bracketing cannot remove arbitrary time/server/hardware confounding.

All three ON values exceed both neighboring OFF values, with paired descriptive changes +34.84%, +36.11% and +32.64%. The result supports a positive scene-specific signal; it does not establish a universal effect size, an independent-session confidence interval or the separate contribution of batching/text reuse/Live geometry. Do not add this percentage to Benchmark 01 or describe it as a 0.1.0-to-0.2.0 increase.

### Scene, workload and fidelity

Recorded 2026-10-17, 15:51–15:54 Europe/Moscow (UTC+03:00). PrisonEvo-1 #5; dimension `minecraft:spawn/overworld_halloween-b2b1`. Camera `-21.39 / 99.12 / 0.95`, yaw/pitch `90.84 / 6.29`, FOV 110, render distance 7, GUI scale 2. All 140 samples retain the same camera, one player, focus and detailed-profiler OFF. Every phase completed with 20 samples, zero excluded menu frames and zero unfocused measured frames.

| Phase | Item Displays min–max | Bone carriers min–max | Loaded particles min–max |
|---|---:|---:|---:|
| OFF1 | 438–438 | 296–296 | 1842–2064 |
| ON1 | 438–438 | 296–296 | 1793–2034 |
| OFF2 | 438–438 | 296–296 | 1895–2010 |
| ON2 | 438–438 | 296–296 | 1872–1971 |
| OFF3 | 438–438 | 296–296 | 1763–1977 |
| ON3 | 426–438 | 286–296 | 1926–2040 |
| OFF4 | 426–426 | 286–286 | 1833–2075 |

ON3 has four samples with 438 Item Displays and sixteen with 426. Counts of bone carriers do not prove animated-bone counts or identical visible workload. Particle populations fluctuate naturally; ON does not consistently have fewer particles. No samples were selected by population to replace the full-phase result.

The operator confirmed unchanged appearance in every ON phase, unchanged resources, unlimited FPS, VSync OFF, a maximized window, AC power and Performance mode. This is a visual observation, not synchronized pixel parity. Known controlled EXPANDED counterexamples can change overlapping alpha or opaque layers; no universal layer equivalence is claimed.

### Measured Live geometry costs

[![Measured Live CPU scopes](results/spawn-20261017/cache-cpu.png)](results/spawn-20261017/cache-cpu.png)

Values below use cumulative counter differences over the first-to-last sampled ON intervals, not one instantaneous cost or a cumulative total from before the run. Timings include the work within the named Live scopes. They exclude other rendering/optimization costs and do not measure GPU execution.

| Phase | Interval frames | Capture CPU ms/frame | Upload CPU ms/frame | Draw CPU ms/frame |
|---|---:|---:|---:|---:|
| ON1 | 6420 | 0.106283 | 0.020285 | 0.067689 |
| ON2 | 6104 | 0.103410 | 0.021907 | 0.070746 |
| ON3 | 6082 | 0.100157 | 0.020724 | 0.072646 |

Each ON sample reports 162 cached meshes, 455424 bytes of local geometry and one draw in the custom Live path; 205–209 instances, variable fallbacks and roughly 26.6 kB/frame of instance upload traffic. These are not whole-game draw counts, total VRAM or retained instance-memory estimates. The cost figure is not the complete price of all enabled optimizations.

### Artifact and environment

- Artifact: `drawless-0.2.0-dev.4.jar`; SHA-256 `44b2d2741b9d134e7dcbf4b58728e1586d4744f79654f767818dd1ce9671a72c`. The currently installed JAR matches the previously recorded dev.4 artifact hash; its file timestamp precedes the series.
- Source: `92f8ffc2715b34e649446799402c457db19c982c`.
- AMD Ryzen 5 5600H; NVIDIA GeForce RTX 3060 Laptop GPU. CPU checked by current system inventory; GPU, NVIDIA 610.62/OpenGL, Sodium 0.9.2+mc26.2, Fabric Loader 0.20.0 and Fabric API 0.161.0+26.2 are present in the recording-session log. ImmediatelyFast is absent.
- Exact framebuffer dimensions, Java runtime build, JVM heap/arguments and resource-pack version were not recorded. Current settings are not substituted for historical settings.
- The explicit probe has coarse/sampled diagnostic overhead. No calibrated recorder overhead, full per-frame trace, p95/p99, 1% low, GPU elapsed times or thermal/clock trace is available.

### Raw measurements and hashes

| Phase | Original file | SHA-256 |
|---|---|---|
| OFF1 | [drawless-20261017-155121-748-off1.md](results/spawn-20261017/raw/drawless-20261017-155121-748-off1.md) | `f061a81a9c8bf97392cbffe2d26d75bb0101afd7e347f7c2ebf6389f5ee9a16f` |
| ON1 | [drawless-20261017-155151-299-on1.md](results/spawn-20261017/raw/drawless-20261017-155151-299-on1.md) | `e7a4b3a4a800458dcd0c75a6beea4e8cf3e44ad8ed98f9d775513cd4694adbed` |
| OFF2 | [drawless-20261017-155220-742-off2.md](results/spawn-20261017/raw/drawless-20261017-155220-742-off2.md) | `d82af6db10519fc0076641a8a399da302eb361e4ba561b3c13db24d6e12449a7` |
| ON2 | [drawless-20261017-155250-548-on2.md](results/spawn-20261017/raw/drawless-20261017-155250-548-on2.md) | `cd90c0397e0c2c9b80471d750a5364778ceaf93f14305ffbf5f96242ea2be147` |
| OFF3 | [drawless-20261017-155320-522-off3.md](results/spawn-20261017/raw/drawless-20261017-155320-522-off3.md) | `92f9a5106dfd4f7d9dff72162a626ae8d23a35d01829dc2f1cef800589aab072` |
| ON3 | [drawless-20261017-155351-381-on3.md](results/spawn-20261017/raw/drawless-20261017-155351-381-on3.md) | `2d0e3c2dd2179371183c42c96801ab4dc337272f31fc03413e2e89f765cb621c` |
| OFF4 | [drawless-20261017-155422-642-off4.md](results/spawn-20261017/raw/drawless-20261017-155422-642-off4.md) | `7ef7de13b457a709617a546cd1c5ed238401d28217c2e22d5ef28046fb9df429` |

All raw files are preserved byte-for-byte. This report and figures describe the observed series, including its control drift and workload changes. The older Benchmark 01 remains a separate 0.1.0 result.

---

## Benchmark 01 — animated Item Display spawn

Recorded 2026-10-16, analysed 2026-10-17. DrawLess **0.1.0 with Sodium**, without
ImmediatelyFast, improved mean observed FPS by **21.24% across three bracketed ON
comparisons** in this scene. The mean bracketed OFF baseline is **242.91 FPS**;
mean ON is **294.50 FPS**.

[![Benchmark 01 headline comparison](results/spawn-20261016/modrinth-card.png)](results/spawn-20261016/modrinth-card.png)

This compares the DrawLess master switch OFF with its default optimization set ON.
It does not isolate one cache, compare against ImmediatelyFast or establish gains
for other scenes, computers or GPU-bound settings.

### Every recorded phase

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

### Scene and fidelity

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

### What the optimization costs

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

### Environment and limitations

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

### Original inputs

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
