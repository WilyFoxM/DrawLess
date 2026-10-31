# DrawLess benchmark: release2

Result: **complete**. Target: `full`.

**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.

Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.

Mean paired FPS change: **+29.24%**.

## Recorded phases

| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |
| --- | --- | ---: | ---: | ---: | --- |
| release2_off1 | complete | 178.70 | 3574 | 20.000 | [Report](drawless-20261031-221329-204-release2_off1.md) |
| release2_on1 | complete | 229.56 | 4591 | 19.999 | [Report](drawless-20261031-221351-214-release2_on1.md) |
| release2_off2 | complete | 179.20 | 3584 | 20.000 | [Report](drawless-20261031-221413-225-release2_off2.md) |
| release2_on2 | complete | 233.32 | 4666 | 19.998 | [Report](drawless-20261031-221435-234-release2_on2.md) |
| release2_off3 | complete | 179.12 | 3581 | 19.993 | [Report](drawless-20261031-221457-248-release2_off3.md) |
| release2_on3 | complete | 233.08 | 4662 | 20.001 | [Report](drawless-20261031-221519-260-release2_on3.md) |
| release2_off4 | complete | 181.69 | 3634 | 20.002 | [Report](drawless-20261031-221541-268-release2_off4.md) |

## Bracketed comparisons

| ON phase | Neighboring OFF mean FPS | ON FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 178.95 | 229.56 | +28.28% |
| 4 | 179.16 | 233.32 | +30.23% |
| 6 | 180.40 | 233.08 | +29.20% |

## Population-matched comparison

Windows are the one-second intervals between samples. A window is stable when every measured frame rendered the same number of avatars (players and mannequins). Only avatar counts seen in stable windows of every phase are used; each phase's mean frame time is weighted per count with weights shared by all phases. Unmatched windows remain in the raw results above.

Common avatar counts: none.

| Phase | Windows | Stable | Matched | Matched seconds | Mean matched avatars | Standardized FPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| release2_off1 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release2_on1 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release2_off2 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release2_on2 | 20 | 14 | 0 | 0.00 | 0.00 | – |
| release2_off3 | 20 | 18 | 0 | 0.00 | 0.00 | – |
| release2_on3 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release2_off4 | 20 | 17 | 0 | 0.00 | 0.00 | – |

## Population-adjusted comparison

Each one-second window gives a mean frame time and a mean rendered avatar count. One slope (frame milliseconds per avatar) is estimated from variation within phases only; each phase is then moved to the series' mean avatar count. A fit with separate OFF and ON slopes is the sensitivity check. This corrects avatar-count drift only, not other scene changes.

Slope 0.1333 ± 0.0322 ms per avatar (OFF-only 0.1772, ON-only 0.0983); reference 9.06 avatars.

| Phase | Windows | Mean avatars | Raw frame ms | Shift ms | Adjusted FPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| release2_off1 | 19 | 8.98 | 5.601 | +0.010 ± 0.002 | 178.23 |
| release2_on1 | 19 | 8.66 | 4.361 | +0.054 ± 0.013 | 226.54 |
| release2_off2 | 19 | 8.35 | 5.580 | +0.095 ± 0.023 | 176.22 |
| release2_on2 | 19 | 8.68 | 4.294 | +0.050 ± 0.012 | 230.20 |
| release2_off3 | 19 | 9.38 | 5.595 | -0.043 ± 0.010 | 180.11 |
| release2_on3 | 19 | 10.14 | 4.295 | -0.144 ± 0.035 | 240.92 |
| release2_off4 | 19 | 9.22 | 5.501 | -0.021 ± 0.005 | 182.49 |

| ON phase | Neighboring OFF adjusted FPS | ON adjusted FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 177.23 | 226.54 | +27.83% |
| 4 | 178.17 | 230.20 | +29.20% |
| 6 | 181.30 | 240.92 | +32.88% |

Mean population-adjusted change: **+29.97%** (separate OFF/ON slopes: +29.94%).

## Review flags

- AFK frame throttling was temporarily disabled during this series; original inactivity policy: afk. The original policy was restored afterwards.
- Item Display population changed across samples: 426–438. Review scene comparability.
- Player population changed across samples: 9–12. Review scene comparability.
- No rendered avatar count was stable in every phase: no population-matched comparison.
- Loaded particle population varied by more than 2%: 1571–1834. Review particle workload comparability.
- One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.
- Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.

## Environment

framebuffer=1920x1129; fpsLimit=260; vsync=false; inactivityFpsLimit=minimized; fov=110; renderDistance=7; camera=FIRST_PERSON; resourcePacks=[vanilla, file/resources.zip]; profiler=false

Builds, original settings, all samples and unrounded results: [summary.json](summary.json).
Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.
