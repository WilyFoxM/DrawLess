# DrawLess benchmark: release1

Result: **complete**. Target: `full`.

**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.

Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.

Mean paired FPS change: **+61.18%**.

## Recorded phases

| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |
| --- | --- | ---: | ---: | ---: | --- |
| release1_off1 | complete | 103.49 | 2070 | 20.003 | [Report](drawless-20261101-185040-815-release1_off1.md) |
| release1_on1 | complete | 170.47 | 3409 | 19.997 | [Report](drawless-20261101-185102-830-release1_on1.md) |
| release1_off2 | complete | 109.99 | 2199 | 19.993 | [Report](drawless-20261101-185124-838-release1_off2.md) |
| release1_on2 | complete | 175.95 | 3518 | 19.994 | [Report](drawless-20261101-185146-854-release1_on2.md) |
| release1_off3 | complete | 112.10 | 2242 | 20.000 | [Report](drawless-20261101-185208-868-release1_off3.md) |
| release1_on3 | complete | 183.34 | 3667 | 20.001 | [Report](drawless-20261101-185230-883-release1_on3.md) |
| release1_off4 | complete | 109.61 | 2191 | 19.988 | [Report](drawless-20261101-185252-902-release1_off4.md) |

## Bracketed comparisons

| ON phase | Neighboring OFF mean FPS | ON FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 106.74 | 170.47 | +59.71% |
| 4 | 111.05 | 175.95 | +58.45% |
| 6 | 110.86 | 183.34 | +65.38% |

## Population-matched comparison

Windows are the one-second intervals between samples. A window is stable when every measured frame rendered the same number of avatars (players and mannequins). Only avatar counts seen in stable windows of every phase are used; each phase's mean frame time is weighted per count with weights shared by all phases. Unmatched windows remain in the raw results above.

Common avatar counts: none.

| Phase | Windows | Stable | Matched | Matched seconds | Mean matched avatars | Standardized FPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| release1_off1 | 20 | 9 | 0 | 0.00 | 0.00 | – |
| release1_on1 | 20 | 15 | 0 | 0.00 | 0.00 | – |
| release1_off2 | 20 | 9 | 0 | 0.00 | 0.00 | – |
| release1_on2 | 20 | 8 | 0 | 0.00 | 0.00 | – |
| release1_off3 | 20 | 16 | 0 | 0.00 | 0.00 | – |
| release1_on3 | 20 | 14 | 0 | 0.00 | 0.00 | – |
| release1_off4 | 20 | 14 | 0 | 0.00 | 0.00 | – |

## Population-adjusted comparison

Each one-second window gives a mean frame time and a mean rendered avatar count. One slope (frame milliseconds per avatar) is estimated from variation within phases only; each phase is then moved to the series' mean avatar count. A fit with separate OFF and ON slopes is the sensitivity check. This corrects avatar-count drift only, not other scene changes.

Slope 0.1044 ± 0.0237 ms per avatar (OFF-only 0.1374, ON-only 0.0623); reference 16.91 avatars.

| Phase | Windows | Mean avatars | Raw frame ms | Shift ms | Adjusted FPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| release1_off1 | 19 | 18.66 | 9.649 | -0.182 ± 0.041 | 105.63 |
| release1_on1 | 19 | 18.76 | 5.880 | -0.192 ± 0.044 | 175.80 |
| release1_off2 | 19 | 16.87 | 9.108 | +0.005 ± 0.001 | 109.74 |
| release1_on2 | 19 | 16.85 | 5.688 | +0.007 ± 0.002 | 175.59 |
| release1_off3 | 19 | 14.92 | 8.961 | +0.208 ± 0.047 | 109.06 |
| release1_on3 | 19 | 15.68 | 5.450 | +0.129 ± 0.029 | 179.26 |
| release1_off4 | 19 | 16.67 | 9.166 | +0.026 ± 0.006 | 108.79 |

| ON phase | Neighboring OFF adjusted FPS | ON adjusted FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 107.69 | 175.80 | +63.25% |
| 4 | 109.40 | 175.59 | +60.50% |
| 6 | 108.92 | 179.26 | +64.57% |

Mean population-adjusted change: **+62.78%** (separate OFF/ON slopes: +62.85%).

## Review flags

- AFK frame throttling was temporarily disabled during this series; original inactivity policy: afk. The original policy was restored afterwards.
- OFF controls varied by 7.92% (range/mean; screening threshold 5%).
- Item Display population changed across samples: 414–447. Review scene comparability.
- Player population changed across samples: 15–25. Review scene comparability.
- No rendered avatar count was stable in every phase: no population-matched comparison.
- Loaded particle population varied by more than 2%: 1378–1595. Review particle workload comparability.
- One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.
- Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.

## Environment

framebuffer=1920x1129; fpsLimit=260; vsync=false; inactivityFpsLimit=minimized; fov=110; renderDistance=7; camera=FIRST_PERSON; resourcePacks=[vanilla, file/resources.zip]; profiler=false

Builds, original settings, all samples and unrounded results: [summary.json](summary.json).
Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.
