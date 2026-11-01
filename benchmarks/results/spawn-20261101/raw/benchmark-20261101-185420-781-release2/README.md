# DrawLess benchmark: release2

Result: **complete**. Target: `full`.

**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.

Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.

Mean paired FPS change: **+54.58%**.

## Recorded phases

| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |
| --- | --- | ---: | ---: | ---: | --- |
| release2_off1 | complete | 116.32 | 2326 | 19.996 | [Report](drawless-20261101-185442-811-release2_off1.md) |
| release2_on1 | complete | 190.12 | 3802 | 19.998 | [Report](drawless-20261101-185504-823-release2_on1.md) |
| release2_off2 | complete | 124.36 | 2488 | 20.006 | [Report](drawless-20261101-185526-837-release2_off2.md) |
| release2_on2 | complete | 185.04 | 3699 | 19.991 | [Report](drawless-20261101-185548-854-release2_on2.md) |
| release2_off3 | complete | 124.22 | 2485 | 20.005 | [Report](drawless-20261101-185610-873-release2_off3.md) |
| release2_on3 | complete | 190.68 | 3814 | 20.002 | [Report](drawless-20261101-185632-889-release2_on3.md) |
| release2_off4 | complete | 118.87 | 2376 | 19.988 | [Report](drawless-20261101-185654-907-release2_off4.md) |

## Bracketed comparisons

| ON phase | Neighboring OFF mean FPS | ON FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 120.34 | 190.12 | +57.98% |
| 4 | 124.29 | 185.04 | +48.87% |
| 6 | 121.54 | 190.68 | +56.88% |

## Population-matched comparison

Windows are the one-second intervals between samples. A window is stable when every measured frame rendered the same number of avatars (players and mannequins). Only avatar counts seen in stable windows of every phase are used; each phase's mean frame time is weighted per count with weights shared by all phases. Unmatched windows remain in the raw results above.

Common avatar counts: none.

| Phase | Windows | Stable | Matched | Matched seconds | Mean matched avatars | Standardized FPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| release2_off1 | 20 | 15 | 0 | 0.00 | 0.00 | – |
| release2_on1 | 20 | 14 | 0 | 0.00 | 0.00 | – |
| release2_off2 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release2_on2 | 20 | 18 | 0 | 0.00 | 0.00 | – |
| release2_off3 | 20 | 16 | 0 | 0.00 | 0.00 | – |
| release2_on3 | 20 | 17 | 0 | 0.00 | 0.00 | – |
| release2_off4 | 20 | 18 | 0 | 0.00 | 0.00 | – |

## Population-adjusted comparison

Each one-second window gives a mean frame time and a mean rendered avatar count. One slope (frame milliseconds per avatar) is estimated from variation within phases only; each phase is then moved to the series' mean avatar count. A fit with separate OFF and ON slopes is the sensitivity check. This corrects avatar-count drift only, not other scene changes.

Slope 0.2431 ± 0.0575 ms per avatar (OFF-only 0.3296, ON-only 0.0316); reference 13.71 avatars.

| Phase | Windows | Mean avatars | Raw frame ms | Shift ms | Adjusted FPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| release2_off1 | 19 | 16.05 | 8.640 | -0.568 ± 0.134 | 123.88 |
| release2_on1 | 19 | 13.53 | 5.282 | +0.044 ± 0.010 | 187.76 |
| release2_off2 | 19 | 12.90 | 8.072 | +0.198 ± 0.047 | 120.92 |
| release2_on2 | 19 | 13.21 | 5.423 | +0.122 ± 0.029 | 180.33 |
| release2_off3 | 19 | 13.50 | 8.068 | +0.051 ± 0.012 | 123.18 |
| release2_on3 | 19 | 13.54 | 5.259 | +0.041 ± 0.010 | 188.69 |
| release2_off4 | 19 | 13.25 | 8.479 | +0.111 ± 0.026 | 116.41 |

| ON phase | Neighboring OFF adjusted FPS | ON adjusted FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 122.40 | 187.76 | +53.40% |
| 4 | 122.05 | 180.33 | +47.75% |
| 6 | 119.79 | 188.69 | +57.51% |

Mean population-adjusted change: **+52.89%** (separate OFF/ON slopes: +54.58%).

## Review flags

- AFK frame throttling was temporarily disabled during this series; original inactivity policy: afk. The original policy was restored afterwards.
- OFF controls varied by 6.65% (range/mean; screening threshold 5%).
- Item Display population changed across samples: 414–438. Review scene comparability.
- Player population changed across samples: 14–19. Review scene comparability.
- No rendered avatar count was stable in every phase: no population-matched comparison.
- Population adjustment is sensitive to the slope model: common slope +52.89%, separate OFF/ON slopes +54.58%.
- Population shift uncertainty reaches 1.55% of a phase's frame time; treat adjusted pairs as rough.
- Loaded particle population varied by more than 2%: 1383–1590. Review particle workload comparability.
- One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.
- Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.

## Environment

framebuffer=1920x1129; fpsLimit=260; vsync=false; inactivityFpsLimit=minimized; fov=110; renderDistance=7; camera=FIRST_PERSON; resourcePacks=[vanilla, file/resources.zip]; profiler=false

Builds, original settings, all samples and unrounded results: [summary.json](summary.json).
Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.
