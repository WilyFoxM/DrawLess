# DrawLess benchmark: release3

Result: **complete**. Target: `full`.

**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.

Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.

Mean paired FPS change: **+30.49%**.

## Recorded phases

| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |
| --- | --- | ---: | ---: | ---: | --- |
| release3_off1 | complete | 181.29 | 3626 | 20.001 | [Report](drawless-20261031-221612-105-release3_off1.md) |
| release3_on1 | complete | 237.80 | 4756 | 20.000 | [Report](drawless-20261031-221634-116-release3_on1.md) |
| release3_off2 | complete | 181.02 | 3621 | 20.003 | [Report](drawless-20261031-221656-128-release3_off2.md) |
| release3_on2 | complete | 235.07 | 4701 | 19.998 | [Report](drawless-20261031-221718-138-release3_on2.md) |
| release3_off3 | complete | 176.79 | 3534 | 19.990 | [Report](drawless-20261031-221740-150-release3_off3.md) |
| release3_on3 | complete | 216.61 | 4333 | 20.004 | [Report](drawless-20261031-221802-165-release3_on3.md) |
| release3_off4 | complete | 159.56 | 3191 | 19.999 | [Report](drawless-20261031-221824-175-release3_off4.md) |

## Bracketed comparisons

| ON phase | Neighboring OFF mean FPS | ON FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 181.15 | 237.80 | +31.27% |
| 4 | 178.90 | 235.07 | +31.40% |
| 6 | 168.17 | 216.61 | +28.80% |

## Population-matched comparison

Windows are the one-second intervals between samples. A window is stable when every measured frame rendered the same number of avatars (players and mannequins). Only avatar counts seen in stable windows of every phase are used; each phase's mean frame time is weighted per count with weights shared by all phases. Unmatched windows remain in the raw results above.

Common avatar counts: none.

| Phase | Windows | Stable | Matched | Matched seconds | Mean matched avatars | Standardized FPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| release3_off1 | 20 | 18 | 0 | 0.00 | 0.00 | – |
| release3_on1 | 20 | 19 | 0 | 0.00 | 0.00 | – |
| release3_off2 | 20 | 19 | 0 | 0.00 | 0.00 | – |
| release3_on2 | 20 | 19 | 0 | 0.00 | 0.00 | – |
| release3_off3 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release3_on3 | 20 | 19 | 0 | 0.00 | 0.00 | – |
| release3_off4 | 20 | 19 | 0 | 0.00 | 0.00 | – |

## Population-adjusted comparison

Each one-second window gives a mean frame time and a mean rendered avatar count. One slope (frame milliseconds per avatar) is estimated from variation within phases only; each phase is then moved to the series' mean avatar count. A fit with separate OFF and ON slopes is the sensitivity check. This corrects avatar-count drift only, not other scene changes.

Slope 0.2590 ± 0.0400 ms per avatar (OFF-only 0.2590, ON-only 0.2590); reference 8.66 avatars.

| Phase | Windows | Mean avatars | Raw frame ms | Shift ms | Adjusted FPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| release3_off1 | 19 | 8.44 | 5.499 | +0.057 ± 0.009 | 180.01 |
| release3_on1 | 19 | 8.00 | 4.209 | +0.171 ± 0.026 | 228.30 |
| release3_off2 | 19 | 8.00 | 5.527 | +0.171 ± 0.026 | 175.49 |
| release3_on2 | 19 | 8.00 | 4.256 | +0.171 ± 0.026 | 225.86 |
| release3_off3 | 19 | 8.18 | 5.635 | +0.124 ± 0.019 | 173.64 |
| release3_on3 | 19 | 10.00 | 4.614 | -0.347 ± 0.054 | 234.35 |
| release3_off4 | 19 | 10.00 | 6.274 | -0.347 ± 0.054 | 168.71 |

| ON phase | Neighboring OFF adjusted FPS | ON adjusted FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 177.75 | 228.30 | +28.44% |
| 4 | 174.56 | 225.86 | +29.39% |
| 6 | 171.18 | 234.35 | +36.91% |

Mean population-adjusted change: **+31.58%** (separate OFF/ON slopes: +31.58%).

## Review flags

- AFK frame throttling was temporarily disabled during this series; original inactivity policy: afk. The original policy was restored afterwards.
- OFF controls varied by 12.44% (range/mean; screening threshold 5%).
- Player population changed across samples: 9–11. Review scene comparability.
- No rendered avatar count was stable in every phase: no population-matched comparison.
- Population shift uncertainty reaches 1.16% of a phase's frame time; treat adjusted pairs as rough.
- Loaded particle population varied by more than 2%: 1604–1820. Review particle workload comparability.
- One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.
- Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.

## Environment

framebuffer=1920x1129; fpsLimit=260; vsync=false; inactivityFpsLimit=minimized; fov=110; renderDistance=7; camera=FIRST_PERSON; resourcePacks=[vanilla, file/resources.zip]; profiler=false

Builds, original settings, all samples and unrounded results: [summary.json](summary.json).
Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.
