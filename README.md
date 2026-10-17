# DrawLess

Client-side rendering optimizations for Minecraft **26.2**, Fabric, Java **25**.
Base package: `ru.wilyfox.drawless`. Current development version: **0.2.0-dev.2**;
published benchmark results below refer to **0.1.0**.

DrawLess focuses on Item Display scenes, including animated ModelEngine NPCs. It reuses
local model geometry while streaming the current transforms, light and tint, then batches
supported parts by material. Entity movement and animation ticks remain live in the default mode.

[Modrinth](https://modrinth.com/mod/drawless) · [Benchmark report](benchmarks/README.md) · [Methodology](benchmarks/METHODOLOGY.md)

## Measured performance

[![DrawLess 0.1.0: +21.24% mean paired FPS improvement in the measured Item Display scene](benchmarks/results/spawn-20261016/modrinth-card.png)](benchmarks/README.md)

One recorded Item Display spawn scene: **242.91 FPS** mean bracketed OFF baseline,
**294.50 FPS** mean ON, **+21.24%** mean paired change across three ON comparisons.
All seven 20-second phases are retained. Tested with Sodium, without ImmediatelyFast,
on a Ryzen 5 5600H / RTX 3060 Laptop GPU, Minecraft 26.2 / Fabric.

This is a single-session, scene-specific result. The [full report](benchmarks/README.md)
includes every phase, measured cache CPU costs, visual observations, environment
details, [raw measurements](benchmarks/results/spawn-20261016/raw/) and limitations.
It compares the DrawLess master switch OFF with its default optimization set ON.

## Install

Install Fabric Loader 0.19.3+ and Fabric API 0.161.0+26.2, then put the DrawLess JAR in `mods`.
**Sodium is optional**. Tested integration target: Sodium 0.9.2 for 26.2.
ImmediatelyFast is also optional and currently supplies additional global optimizations.
Neither mod is embedded or required to load DrawLess. No server-side component is needed.

Settings are stored in `config/drawless.json`.
Use **`/drawless settings`** to open its settings screen.

## Included

- A bounded Item Display model-layer cache with an adjustable/adaptive refresh budget.
- Live geometry cache with shared GPU meshes and current per-instance matrices/light/tint.
- Static scene cache for comparison; unchanged parts retain GPU geometry.
- Optional experimental extraction-state reuse and NPC pose budgeting.
- Optional particle light and all-air collision caches, both OFF by default.
- Optional Sodium integration for active brushable block entities and animated sprites.
- Short explicit rendering probes, particle workload counters and opt-in CPU scopes.
- Experimental pass-local vertex-buffer lookup reuse for text and name tags, OFF by default.
  ImmediatelyFast owns this optimization when installed; the overlapping DrawLess hooks
  are not applied. This does not disable DrawLess's Item Display optimizations.

Text buffer reuse retains only the most recent consolidatable buffer within a text
preparation group and verifies that it is still active. Pass completion, exceptions
and nested preparation discard the cached reference. It does not cache glyphs, text
content or rendered images. Enable it in settings for evaluation; no FPS improvement
is claimed yet. With `/drawless profile start`, counters report requests, reused
results and remaining lookups for both OFF and ON; the preparation scope includes
vanilla work. Capture detailed CPU profiles separately from throughput runs.

Default settings enable the model cache and Live geometry cache. Physics/visibility quantity
reductions are not part of that default. Unsupported materials, foil/glow and unsupported
item shaders return to ordinary rendering. The custom GPU path currently requires OpenGL;
Iris uses the vanilla fallback. This is a development release, not a guarantee of compatibility
with every renderer or resource pack.

The advanced Freeze, Unrender and Pause ticks modes are diagnostic modes: they can freeze
or hide NPCs and bosses. Keep Live geometry selected for ordinary play. The particle frustum
cell-cache experiment was rejected and is not included.

## Commands and diagnostics

- `/drawless` or `/drawless settings`: settings.
- `/drawless status`: current renderer status, coverage and fallback reasons.
- `/drawless probe <label>`: 2 seconds warmup, then a 20-second probe with automatic saving.
- `/drawless probe status`: probe status and last output path.
- `/drawless profile start` / `/drawless profile stop`: opt-in detailed CPU scope totals.

Reports go to `drawless-reports/` in the Minecraft instance. No recording, upload, webhook,
telemetry or account/backend service runs automatically. Reports contain scene/model counts
and camera coordinates; review them before sharing. CPU timings are not GPU elapsed times.

For A/B testing, keep the scene/camera/window/graphics identical and use OFF → ON → OFF.
The master switch disables DrawLess optimizations while preserving the diagnostic hooks.
Compare particle/entity populations, control drift and stage time alongside FPS.

## Build and local validation

```sh
./gradlew build
```

The public checkout and GitHub Actions build the mod. Test sources and fixtures
are maintained locally and are not published in this repository. In the development
workspace containing those sources, additional native checks can be run with:

```sh
./gradlew runClientGameTest
./gradlew runClientGameTest -PwithSodium -PwithImmediatelyFast
```

Release JARs do not contain test code or optional runtime mods. Build output: `build/libs/`.

## Roadmap

Further text, feature batching, framebuffer and GUI atlas optimizations are future work.
The experimental text/name-tag lookup reuse above is the first implemented step.
Measure standalone and with Sodium/IF before promoting experimental features.

## Benchmark evidence

See [benchmark methodology and selected results](benchmarks/README.md). Public
performance claims must identify the measured JAR and environment. Native correctness
tests are not server FPS benchmarks.

Source: [WilyFoxM/DrawLess](https://github.com/WilyFoxM/DrawLess). License: MIT.

## Attributions

- **RK_01 / RaphiMC and ImmediatelyFast contributors** —
  [ImmediatelyFast](https://github.com/RaphiMC/ImmediatelyFast), specifically the
  [text and name-tag fast lookup approach in v1.16.5 for Minecraft 26.2](https://github.com/RaphiMC/ImmediatelyFast/tree/85b5f04bcb099f40fa9bb19bcd4c8cd1ec561ecc/common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/fast_text_lookup).
  This informed DrawLess's own pass-scoped buffer reuse implementation, including
  active-buffer validation and shared handling for text and name tags. The IF
  glyph-renderer mixin classes are not incorporated. ImmediatelyFast's source
  remains under its [LGPL-3.0-or-later terms](https://github.com/RaphiMC/ImmediatelyFast/blob/85b5f04bcb099f40fa9bb19bcd4c8cd1ec561ecc/LICENSE).
