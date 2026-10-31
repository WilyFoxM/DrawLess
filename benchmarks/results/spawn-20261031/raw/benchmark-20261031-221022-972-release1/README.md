# DrawLess benchmark: release1

Result: **complete**. Target: `full`.

**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.

Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.

Mean paired FPS change: **+28.45%**.

## Recorded phases

| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |
| --- | --- | ---: | ---: | ---: | --- |
| release1_off1 | complete | 178.22 | 3564 | 19.998 | [Report](drawless-20261031-221045-003-release1_off1.md) |
| release1_on1 | complete | 233.16 | 4663 | 19.999 | [Report](drawless-20261031-221107-016-release1_on1.md) |
| release1_off2 | complete | 183.97 | 3680 | 20.003 | [Report](drawless-20261031-221129-027-release1_off2.md) |
| release1_on2 | complete | 232.53 | 4651 | 20.001 | [Report](drawless-20261031-221151-039-release1_on2.md) |
| release1_off3 | complete | 178.63 | 3572 | 19.997 | [Report](drawless-20261031-221213-049-release1_off3.md) |
| release1_on3 | complete | 230.58 | 4612 | 20.001 | [Report](drawless-20261031-221235-062-release1_on3.md) |
| release1_off4 | complete | 180.69 | 3614 | 20.001 | [Report](drawless-20261031-221257-070-release1_off4.md) |

## Bracketed comparisons

| ON phase | Neighboring OFF mean FPS | ON FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 181.09 | 233.16 | +28.75% |
| 4 | 181.30 | 232.53 | +28.26% |
| 6 | 179.66 | 230.58 | +28.35% |

## Population-matched comparison

Windows are the one-second intervals between samples. A window is stable when every measured frame rendered the same number of avatars (players and mannequins). Only avatar counts seen in stable windows of every phase are used; each phase's mean frame time is weighted per count with weights shared by all phases. Unmatched windows remain in the raw results above.

Common avatar counts: [9].

| Phase | Windows | Stable | Matched | Matched seconds | Mean matched avatars | Standardized FPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| release1_off1 | 20 | 19 | 19 | 19.06 | 9.00 | 178.09 |
| release1_on1 | 20 | 19 | 19 | 19.02 | 9.00 | 233.10 |
| release1_off2 | 20 | 18 | 1 | 1.00 | 9.00 | 183.16 |
| release1_on2 | 20 | 18 | 12 | 12.03 | 9.00 | 233.68 |
| release1_off3 | 20 | 18 | 18 | 18.06 | 9.00 | 179.30 |
| release1_on3 | 20 | 18 | 8 | 8.02 | 9.00 | 238.75 |
| release1_off4 | 20 | 19 | 19 | 19.06 | 9.00 | 180.67 |

## Population-adjusted comparison

Each one-second window gives a mean frame time and a mean rendered avatar count. One slope (frame milliseconds per avatar) is estimated from variation within phases only; each phase is then moved to the series' mean avatar count. A fit with separate OFF and ON slopes is the sensitivity check. This corrects avatar-count drift only, not other scene changes.

Slope 0.1324 ± 0.0314 ms per avatar (OFF-only -0.0362, ON-only 0.1672); reference 9.01 avatars.

| Phase | Windows | Mean avatars | Raw frame ms | Shift ms | Adjusted FPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| release1_off1 | 19 | 9.00 | 5.616 | +0.001 ± 0.000 | 178.04 |
| release1_on1 | 19 | 9.00 | 4.291 | +0.001 ± 0.000 | 232.98 |
| release1_off2 | 19 | 8.10 | 5.432 | +0.120 ± 0.028 | 180.11 |
| release1_on2 | 19 | 9.37 | 4.310 | -0.048 ± 0.011 | 234.63 |
| release1_off3 | 19 | 9.02 | 5.589 | -0.001 ± 0.000 | 178.97 |
| release1_on3 | 19 | 9.57 | 4.344 | -0.074 ± 0.017 | 234.18 |
| release1_off4 | 19 | 9.00 | 5.536 | +0.001 ± 0.000 | 180.60 |

| ON phase | Neighboring OFF adjusted FPS | ON adjusted FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 179.07 | 232.98 | +30.11% |
| 4 | 179.54 | 234.63 | +30.68% |
| 6 | 179.79 | 234.18 | +30.25% |

Mean population-adjusted change: **+30.35%** (separate OFF/ON slopes: +29.46%).

## Review flags

- AFK frame throttling was temporarily disabled during this series; original inactivity policy: afk. The original policy was restored afterwards.
- Item Display population changed across samples: 426–438. Review scene comparability.
- Player population changed across samples: 10–13. Review scene comparability.
- Fewer than 5 population-matched windows in at least one phase: no population-matched comparison.
- Loaded particle population varied by more than 2%: 989–1808. Review particle workload comparability.
- One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.
- Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.

## Environment

framebuffer=1920x1129; fpsLimit=260; vsync=false; inactivityFpsLimit=minimized; fov=110; renderDistance=7; camera=FIRST_PERSON; resourcePacks=[vanilla, file/resources.zip]; profiler=false

Builds, original settings, all samples and unrounded results: [summary.json](summary.json).
Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.
