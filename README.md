# DrawLess

Client-side rendering optimizations for Minecraft **26.2**, Fabric, Java **25**.
Base package: `ru.wilyfox.drawless`. Current development version: **1.0.0-rc.1**.
Published benchmarks retain their original **0.1.0** and **0.2.0-dev.4** build labels.

DrawLess focuses on Item Display scenes, including animated ModelEngine NPCs. It reuses
local model geometry while streaming the current transforms, light and tint, then batches
supported parts by material. Entity movement and animation ticks remain live in the default mode.

[Modrinth](https://modrinth.com/mod/drawless) · [Benchmark report](benchmarks/README.md) · [Methodology](benchmarks/METHODOLOGY.md)

## Measured performance

[![Benchmark 02: +34.53% mean paired FPS change in one recorded development-build scene](benchmarks/results/spawn-20261017/modrinth-card.png)](benchmarks/README.md)

**Benchmark 02 — 0.2.0-dev.4 with Sodium, EXPANDED batching and text buffer reuse:**
**241.88 FPS** mean bracketed OFF baseline, **325.39 FPS** mean ON, **+34.53%** mean
paired change across three ON comparisons. Particle caches were OFF. All seven
20-second phases are retained. Tested on a Ryzen 5 5600H / RTX 3060 Laptop GPU,
Minecraft 26.2 / Fabric, without ImmediatelyFast.

This is a single-session, scene-specific **development-build result for a selected
preset**, not a measured upgrade percentage from 0.1.0 or a result for final 0.2.0
defaults (text reuse defaults OFF). OFF controls varied by **12.03%**, and the Display
population changed late in the series. The [full report](benchmarks/README.md)
includes every phase, raw measurements, CPU scopes, environment and limitations.

The earlier **0.1.0 Benchmark 01** remains in the same report: **+21.24%** mean paired
change, **242.91 FPS** bracketed baseline and **294.50 FPS** ON in its original scene.
The two series have different scenes/settings and do not establish a version-to-version
improvement. Performance depends on the scene and rendering bottleneck.

## Install

Install Fabric Loader 0.19.3+ and Fabric API 0.161.0+26.2, then put the DrawLess JAR in `mods`.
**Sodium is optional**. Tested integration target: Sodium 0.9.2 for 26.2.
ImmediatelyFast is also optional and currently supplies additional global optimizations.
Neither mod is embedded or required to load DrawLess. No server-side component is needed.

Settings are stored in `config/drawless.json`.
Use **`/drawless settings`** to open its settings screen.

Settings use the DrawLess navy/orange palette, with categories for Rendering,
Particles, Advanced and Diagnostics. Search finds settings across all categories;
hover a row for its full description. ON/OFF controls, reversible mode selectors
and draggable numeric fields keep values separate from their labels. Left/right
arrows adjust focused controls; Tab moves focus and scrolls settings into view.
Use Ctrl+F for search, or Page Up/Down to scroll. Escape clears a search before
closing the menu. Numeric dragging supports Shift for finer adjustment.

Changes apply immediately and save automatically after an interaction. Resetting
all settings requires confirmation. Unavailable IF/Iris-owned controls explain why
they cannot be edited and preserve the saved preference. English and Russian menu
translations follow the Minecraft language setting. This release candidate changes
the settings experience; it introduces no new rendering optimizations or benchmark claims.

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
- Feature batching with OFF, LIMITED and EXPANDED modes, EXPANDED by default.
  LIMITED combines equivalent materials in vanilla-reorderable groups; EXPANDED also
  permits reordering in originally ordered groups. Disabled with IF or Iris.

Text buffer reuse retains only the most recent consolidatable buffer within a text
preparation group and verifies that it is still active. Pass completion, exceptions
and nested preparation discard the cached reference. It does not cache glyphs, text
content or rendered images. Enable it in settings for evaluation; no FPS improvement
is claimed yet. With `/drawless profile start`, counters report requests, reused
results and remaining lookups for both OFF and ON; the preparation scope includes
vanilla work. Capture detailed CPU profiles separately from throughput runs.

Feature batching cycles OFF → LIMITED → EXPANDED on the Rendering settings page.
It compares pipeline,
output target, GPU transform slice, texture/sampler bindings and scissor values using
the group's existing material list. It retains no additional cache and does not change
global scissor equality or cross-group boundaries. EXPANDED lifts the group's ordering
restriction while retaining material equality and geometry-consolidation requirements.
LIMITED preserves the vanilla ordering restriction. EXPANDED is the default for new
settings and configurations without a batching setting. Explicitly saved OFF, LIMITED
or EXPANDED settings are preserved. Older boolean-only configurations with
`featureBatching: true` remain LIMITED when no `featureBatchingReorder` field is present.

**EXPANDED can change overlapping layers.** Controlled GPU cases reproduce changes
in alpha blending and opaque overdraw when an ordered A/B/A sequence becomes A/A/B.
These are known fidelity limitations, not successful image-parity results. The operator
reported no visible issues while playing with EXPANDED across three locations; this
does not establish equivalence for every material or resource pack. Switch to LIMITED
or OFF in Rendering settings if overlapping text, backgrounds or layers look incorrect.

Detailed profiles record searches, reused draws, misses and actual draw submissions;
`promotedSearches` and `promotedReuses` identify work enabled by lifting the order restriction.
Search and submission scopes measure CPU work, not GPU elapsed time. The counters cover
the `RenderTypeFeatureRenderer` path, not every draw call in the game. No scene-level
FPS improvement is claimed for this experimental setting.

Default settings enable the model cache, Live geometry cache and EXPANDED feature batching.
Physics/visibility quantity reductions are not part of that default. Unsupported materials,
foil/glow and unsupported
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

Spotless checks Java formatting as part of `build`; apply the pinned Google Java
style and remove unused imports with:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
```

Java uses two-space indentation and LF line endings. Formatting covers local test
sources when present; benchmark raw files are excluded and preserve their original bytes.

JaCoCo produces local HTML and XML reports for unit tests:

```sh
./gradlew test jacocoTestReport
```

The HTML report is `build/reports/jacoco/test/html/index.html`. Native client
coverage is explicitly enabled for correctness runs, separately from FPS benchmarks:

```sh
./gradlew jacocoClientReport jacocoCombinedReport -PnativeCoverage -PwithSodium
```

Native and combined reports are under `build/reports/jacoco/client-sodium/` and
`build/reports/jacoco/combined-sodium/`. Without `-PwithSodium`, the variant is
`standalone`; adding `-PwithImmediatelyFast` adds the `-if` suffix. Normal game
runs are not instrumented by this setup.

Reports retain all main classes, including uncovered code. Mixin methods execute
inside Minecraft classes and are not measured by the native coverage collector;
their original classes remain uncovered in the report. Coverage does not establish
visual correctness or performance. No minimum percentage is enforced yet.

Test sources and coverage results remain local. A fresh public checkout and GitHub
CI check formatting and build the mod; coverage tasks without their local test
sources are skipped rather than publishing a coverage percentage.

Mutation testing is an explicit local check, separate from `build` and native client
checks. With local unit test sources present, run:

```sh
./gradlew pitest
```

The pinned PIT/JUnit 5 setup first runs the unit suite, then applies `DEFAULTS`
mutations to `DisplayFrameBudget`, `LiveGeometryEncoding` and `PassLocalBufferReuse`.
The corresponding three test classes check budget decisions, packed geometry indices
and pass-scoped buffer reuse without launching Minecraft. PIT changes bytecode in
its test processes, not the source files. It is not added to the release runtime.

Reports are `build/reports/pitest/index.html` and `build/reports/pitest/mutations.xml`.
`KILLED` means a test detected a mutation; `SURVIVED` and `NO_COVERAGE` need review.
Inspect timeouts and execution errors separately, and document equivalent mutations
that do not change observable behavior. No mutation-score threshold is enforced yet.
These results describe the selected unit-tested logic, not Mixin hooks, GPU rendering,
visual fidelity or FPS. The task is skipped in a public checkout without local unit
sources; GitHub CI does not run the private mutation suite or publish its score.
Reports and test sources remain local.

## Roadmap

Further text, framebuffer and GUI atlas optimizations are future work.
Text/name-tag lookup reuse remains optional and experimental. Continue evaluating
feature batching across scenes and renderers, including frame-time stability and
the known layer-ordering limitations.

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
- **RK_01 / RaphiMC and ImmediatelyFast contributors** — the
  [enhanced batching and scissor-state comparison approach](https://github.com/RaphiMC/ImmediatelyFast/tree/85b5f04bcb099f40fa9bb19bcd4c8cd1ec561ecc/common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/enhanced_batching)
  informed DrawLess's own prepared-material comparison and optional promotion of
  ordered groups to reorderable batches. LIMITED retains vanilla order permissions;
  EXPANDED evaluates the broader reordering approach using the existing group list.
  IF's scissor and group mixin implementations are not incorporated.
