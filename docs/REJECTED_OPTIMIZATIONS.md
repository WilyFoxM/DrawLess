# Rejected optimization experiments

These decisions record approaches that were implemented and evaluated, then removed
or left out of DrawLess. Passing correctness checks or increasing cache hits is not
enough: the saved work must also justify validation, invalidation, memory and lookup
costs. Each decision applies to the particular implementation and workload tested.

The measurements below are local CPU experiments, not release benchmarks or FPS
claims. This page summarizes retained development records; the local fixtures,
full audit outputs and archived prototypes are not included in this repository.
The public [benchmark reports](../benchmarks/README.md) cover separate workloads.

## Particle spatial frustum cache — retired on 2026-10-15

**Decision:** do not carry the spatial cell cache into DrawLess. This experiment
predates the separation of DrawLess into its own mod; its results are historical
evidence for that decision, not measurements of a released DrawLess build.

### Approach

Group particle positions into spatial cells and reuse visibility results when the
cell permits an exact answer. The final candidate increased cells from 2×2×2 to
4×4×4 blocks, reused results across passes only when every projection/view matrix
value and camera coordinate matched, and retained a last-cell shortcut to avoid
repeated hash lookups. Matrix validity checks moved to view changes. Boundary and
unsupported cases still used the original `Frustum.pointInFrustum` check; changes
to the frustum during a pass invalidated reuse.

### Evidence and cost

Correctness checks passed with Sodium 0.9.2 and ImmediatelyFast 1.16.5: 126,125
comparisons against vanilla, including boundaries, distant coordinates, camera and
projection replacement, unsupported frustum subclasses and equivalent frustum
instances. A scene with 1,000 real smoke particles retained the visible count.
These checks support the tested visibility decisions, not universal visual parity.

The final CPU comparison used 1,600 deterministic points interleaved among 80
spatial groups. Each measurement covered 500 passes, following 500 warm-up passes
per path. The table gives medians of seven measurements with alternating order;
both paths used the same pass begin/end wrapper.

| Camera | Ordinary point checks, ms / 500 passes | Cell cache, ms / 500 passes |
| --- | ---: | ---: |
| Stationary | 3.9843 | 11.1121 |
| Moving 0.001 block per pass | 3.8271 | 14.0738 |

The original point test was already cheap. Eligibility checks, cell coordinates,
table access and diagnostics cost more than the work they avoided, including with
a stationary camera. Earlier server measurements had also shown higher extraction
time; the controlled comparison above was sufficient to stop this candidate.

This comparison excludes particle simulation, lighting, vertex generation and GPU
drawing. It does not predict a whole-frame slowdown or establish that all particle
visibility optimizations are unprofitable.

### Outcome and reconsideration

The cache, its mutation hooks and its setting were removed from the experimental
implementation. DrawLess continues to use the ordinary particle visibility path;
retiring this cache does not disable vanilla culling. Frustum rejection is also
distinct from occlusion, particle ticking and particle-count reduction.

Reconsider only with a different hypothesis that demonstrably saves more work than
it adds, accounts for camera/projection changes and boundary fallbacks, and wins
against the actual vanilla path in representative workloads. A higher hit rate
alone is not sufficient reason to restore the same cache.

## Additional per-particle light reuse — rejected during 1.1.0-dev.4 development

**Decision recorded 2026-10-26:** keep the existing shared light cache. The extra
strategies below were removed from the final
[particle query reuse change](https://github.com/WilyFoxM/DrawLess/pull/11).
`ParticleLightCache` and its query mixin retain the implementation from the
[preceding main revision](https://github.com/WilyFoxM/DrawLess/commit/fffd23841af79cc2090e58d74fbedceeefb47a7a).

### Approaches

- Store a particle's last base-light result alongside a validity token, capture
  frame and position eligibility flag. Reuse required the same position and valid
  world/tick/published-light state; subclass-specific lighting remained live.
  The candidate added two `long` fields, one `int` and one `boolean` per particle
  beyond the existing position field: 21 bytes of field payload before alignment.
- Select that path adaptively when the preceding validity epoch had at least four
  extraction passes, using the shared table for lower reuse opportunities. This
  was based on observed passes, not a hard-coded FPS threshold.
- Push light invalidation through shared-owner events to reduce per-query polling,
  and try separating common and uncommon wrapper paths to improve JIT inlining.

### Evidence and cost

Local native-client batches compared the actual transformed
`Particle.getLightCoords` call chain with the preceding implementation, private
prototypes and, in the final control, the actual light-cache OFF path. The fixture
used 512 particles in a static world over 20 simulated client ticks, with 1, 2, 3,
4, 8 or 20 extraction passes per tick. The refined comparisons used 64 warm-up
batches and the median of nine timed batches, recording CPU time and allocated
bytes per call. Early short-warm-up pilots were exploratory only.

Last-result reuse could improve high-reuse cases, but did not establish a stable
net improvement in the production call chain. Low-reuse cases exposed the extra
validation cost; adaptive selection and wrapper changes did not produce a
consistent advantage across runs. Inlining and allocation behavior varied between
JVM processes. The candidate also increased per-particle retained state, a cost
that call-level allocation measurements do not capture.

A simplified last-value prototype was faster in some batches, but omitted parts
of production validation and the transformed call chain. Its results were not
accepted as evidence that the complete implementation would win. Timings from
different JVM processes were not treated as a precise release-to-release ratio.

An earlier field recording at approximately 30 FPS showed higher total particle
extraction CPU time with light caching. That broader scope did not isolate light
queries. Controlled final checks with one or two extraction passes per tick found
the existing cache faster than its actual OFF path; an isolated light-query
regression was not established. These tests do not resolve every low-FPS workload
or prove a whole-game FPS benefit.

### Outcome and reconsideration

The additional per-particle sample fields, adaptive selector and experimental eager
invalidation strategy are absent from the final change. This decision does not
reject the existing shared light cache, its published-light invalidation, or the
separate block-state reuse and context-aware collision work in the same change.
Both particle cache options remain experimental and OFF by default in that build.

Reconsider only with cheaper validity checks or a demonstrably different reuse
opportunity. Measure the real transformed path at both low and high passes per
tick, repeat across JVM runs, include retained memory and allocations, exercise
world/light/tick invalidation, and then confirm the effect in a controlled game
scene. A fast simplified lookup or a large hit count alone is insufficient.
