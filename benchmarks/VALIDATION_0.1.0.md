# DrawLess 0.1.0: local correctness validation

Prepared 2026-10-16 from archived local logs, JUnit XML and artifact records. The
checks listed below were performed before this documentation update; they were
not rerun merely to write this summary. These are correctness checks, not a server
FPS benchmark or proof of universal compatibility.

## Artifact and environment

- Artifact: `drawless-0.1.0.jar`, 1,417,787 bytes.
- SHA-256, rechecked against the current local file:
  `3d69eb5f7ad88730327a97068b78c2c525a87948e7bbb960056454ca61eceb3b`.
- Minecraft 26.2; Java 25; Fabric Loader 0.19.3; Fabric API 0.161.0+26.2.
- Native rendering: OpenGL, NVIDIA GeForce RTX 3060 Laptop GPU, driver 610.62.
- Optional integrations: Sodium 0.9.2 and ImmediatelyFast 1.16.5+26.2.

## Archived results

| Configuration | Coverage | Result |
|---|---|---|
| DrawLess + Sodium + ImmediatelyFast | Ten native scenarios | Passed |
| DrawLess without optional integrations | Five native scenarios: live geometry, particle collision/light, probe, settings | Passed |
| Local JUnit suite | Eleven tests; zero skipped | Zero failures/errors |

Coverage includes live motion/interpolation, lighting, shared mesh ownership, animated
atlas activity, unsupported-material fallback, resource reload, unload, master OFF,
particle state parity and standalone settings. Ninety particle-motion fixtures are
part of one native scenario; they are not ninety independent benchmark runs.

The suite also exercises experimental diagnostic modes. Their successful tests do
not make Freeze/Unrender/tick pausing suitable for fidelity-preserving performance claims.

## Visual evidence and limits

Original local OFF/ON screenshots were retained. The simple Live geometry screenshots
show the same three gold-block models; they are not the ModelEngine spawn scene and
are not used to claim an FPS gain. The screenshots occur at different world times,
so whole-image pixel equality is not claimed.

Animated behavior is covered by the native checks and separate observations, rather
than inferred from a still image. Iris, other GPUs/drivers and arbitrary resource packs
are outside this validation matrix. A successful GitHub build does not execute these
unpublished local tests.

## Retained local evidence identities

Raw validation logs and QA screenshots remain local by project policy. Hashes identify the
reviewed files; they do not make private evidence publicly reproducible by themselves.

| Evidence | SHA-256 |
|---|---|
| Native integrations log | `8c206c12863d404c01b4fac82808358308352759503a6b3f812f653094157b6f` |
| Native standalone log | `5517b885dfba4c05c1653ef8aca4c7241b50ec566c7ccd252b48155a5237a9dd` |
| Live OFF screenshot | `1bc6ddb4096e8851f77ed6d407810f591c88f084e5726252c2c495a2e7cc8f0c` |
| Live ON screenshot | `a7e505eaf4e0ed9cd11161ff966f764d53be09795fba7eaabb75c5245823e9c3` |

The later [server-spawn benchmark](README.md) reports performance
separately, with Sodium and without ImmediatelyFast. Historical development FPS and
synthetic tool-test plots are not substituted for those measurements.
