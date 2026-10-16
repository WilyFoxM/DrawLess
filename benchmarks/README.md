# DrawLess benchmarks

Benchmark methodology and selected, reviewed results belong here. Benchmark 01
contains seven real server-spawn phases, page figures and the original measurement
inputs. Missing historical environment details are listed in the report.

- [Methodology and interpretation](METHODOLOGY.md)
- [0.1.0 correctness validation](VALIDATION_0.1.0.md) — archived local checks, not FPS results.
- [Benchmark 01: server spawn, 2026-10-16](results/spawn-20261016/report.md) — seven real phases,
  +21.24% mean bracketed FPS change, DrawLess 0.1.0 with Sodium and without ImmediatelyFast.
- [Benchmark 01 raw measurements](results/spawn-20261016/raw/)
- [Reporting and figure tools](../tools/benchmarks/README.md)
- [Input example](../tools/benchmarks/spawn.example.json)

[![Benchmark 01: +21.24% mean paired FPS improvement](results/spawn-20261016/modrinth-card.png)](results/spawn-20261016/report.md)

The reporting tool consumes real DrawLess probe reports and produces draft plots,
a machine-readable summary and a Markdown report. Generated drafts stay under
`build/`; only reviewed results are selected for publication. Archived generated
appendices and supporting draft figures keep their original status. The reviewed
narrative and selected page figures explain how the measured results are interpreted.

Local test sources, screenshots and logs are not part of the public test suite.
Correctness checks and performance benchmarks are reported separately. A successful
GitHub build does not mean the local rendering tests were run by GitHub Actions.

Historical development results are not evidence of the performance of a newer JAR.
Each published result must identify its measured artifact and configuration.
