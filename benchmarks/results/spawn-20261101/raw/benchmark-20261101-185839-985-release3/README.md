# DrawLess benchmark: release3

Result: **complete**. Target: `full`.

**Review required before publication.** Original settings were restored; this summary does not validate visual correctness.

Each ON is compared with the arithmetic mean FPS of its neighboring OFF controls. Each phase uses 2 seconds of warmup and 20 seconds of recording. File writes occur between phases.

Mean paired FPS change: **+68.02%**.

## Recorded phases

| Phase | Result | Mean FPS | Frame intervals | Measured interval seconds | Raw report |
| --- | --- | ---: | ---: | ---: | --- |
| release3_off1 | complete | 88.10 | 1760 | 19.976 | [Report](drawless-20261101-185902-008-release3_off1.md) |
| release3_on1 | complete | 147.07 | 2941 | 19.998 | [Report](drawless-20261101-185924-028-release3_on1.md) |
| release3_off2 | complete | 94.03 | 1881 | 20.004 | [Report](drawless-20261101-185946-046-release3_off2.md) |
| release3_on2 | complete | 166.01 | 3320 | 19.999 | [Report](drawless-20261101-190008-063-release3_on2.md) |
| release3_off3 | complete | 96.74 | 1935 | 20.002 | [Report](drawless-20261101-190030-081-release3_off3.md) |
| release3_on3 | complete | 169.07 | 3380 | 19.991 | [Report](drawless-20261101-190052-103-release3_on3.md) |
| release3_off4 | complete | 103.91 | 2076 | 19.978 | [Report](drawless-20261101-190114-112-release3_off4.md) |

## Bracketed comparisons

| ON phase | Neighboring OFF mean FPS | ON FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 91.07 | 147.07 | +61.49% |
| 4 | 95.39 | 166.01 | +74.04% |
| 6 | 100.33 | 169.07 | +68.52% |

## Population-matched comparison

Windows are the one-second intervals between samples. A window is stable when every measured frame rendered the same number of avatars (players and mannequins). Only avatar counts seen in stable windows of every phase are used; each phase's mean frame time is weighted per count with weights shared by all phases. Unmatched windows remain in the raw results above.

Common avatar counts: none.

| Phase | Windows | Stable | Matched | Matched seconds | Mean matched avatars | Standardized FPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| release3_off1 | 20 | 8 | 0 | 0.00 | 0.00 | – |
| release3_on1 | 20 | 9 | 0 | 0.00 | 0.00 | – |
| release3_off2 | 20 | 11 | 0 | 0.00 | 0.00 | – |
| release3_on2 | 20 | 8 | 0 | 0.00 | 0.00 | – |
| release3_off3 | 20 | 6 | 0 | 0.00 | 0.00 | – |
| release3_on3 | 20 | 13 | 0 | 0.00 | 0.00 | – |
| release3_off4 | 20 | 17 | 0 | 0.00 | 0.00 | – |

## Population-adjusted comparison

Each one-second window gives a mean frame time and a mean rendered avatar count. One slope (frame milliseconds per avatar) is estimated from variation within phases only; each phase is then moved to the series' mean avatar count. A fit with separate OFF and ON slopes is the sensitivity check. This corrects avatar-count drift only, not other scene changes.

Slope 0.2340 ± 0.0137 ms per avatar (OFF-only 0.2531, ON-only 0.1193); reference 21.83 avatars.

| Phase | Windows | Mean avatars | Raw frame ms | Shift ms | Adjusted FPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| release3_off1 | 19 | 27.38 | 11.507 | -1.297 ± 0.076 | 97.94 |
| release3_on1 | 19 | 25.03 | 6.835 | -0.748 ± 0.044 | 164.29 |
| release3_off2 | 19 | 23.24 | 10.744 | -0.328 ± 0.019 | 96.01 |
| release3_on2 | 19 | 19.15 | 6.020 | +0.627 ± 0.037 | 150.43 |
| release3_off3 | 19 | 21.85 | 10.397 | -0.004 ± 0.000 | 96.22 |
| release3_on3 | 19 | 18.33 | 5.937 | +0.820 ± 0.048 | 148.01 |
| release3_off4 | 19 | 17.86 | 9.588 | +0.930 ± 0.055 | 95.08 |

| ON phase | Neighboring OFF adjusted FPS | ON adjusted FPS | Change |
| ---: | ---: | ---: | ---: |
| 2 | 96.98 | 164.29 | +69.41% |
| 4 | 96.11 | 150.43 | +56.51% |
| 6 | 95.65 | 148.01 | +54.74% |

Mean population-adjusted change: **+60.22%** (separate OFF/ON slopes: +62.58%).

## Review flags

- AFK frame throttling was temporarily disabled during this series; original inactivity policy: afk. The original policy was restored afterwards.
- OFF controls varied by 16.52% (range/mean; screening threshold 5%).
- Item Display population changed across samples: 414–452. Review scene comparability.
- Player population changed across samples: 17–35. Review scene comparability.
- No rendered avatar count was stable in every phase: no population-matched comparison.
- Population adjustment is sensitive to the slope model: common slope +60.22%, separate OFF/ON slopes +62.58%.
- Loaded particle population varied by more than 2%: 1389–1597. Review particle workload comparability.
- One scene/session; no confidence interval, GPU time or visual-parity conclusion is inferred.
- Particle stage timings are CPU-only; encoding is nested in preparation. Shared upload and GPU execution are not isolated.

## Environment

framebuffer=1920x1129; fpsLimit=260; vsync=false; inactivityFpsLimit=minimized; fov=110; renderDistance=7; camera=FIRST_PERSON; resourcePacks=[vanilla, file/resources.zip]; profiler=false

Builds, original settings, all samples and unrounded results: [summary.json](summary.json).
Power mode, external frame caps, resource-pack contents and visual inspection require manual confirmation.
