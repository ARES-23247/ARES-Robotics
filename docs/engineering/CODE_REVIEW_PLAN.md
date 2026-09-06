# Monorepo code review plan

The organization and GUI audit is a targeted review, not a line-by-line review of the
entire repository. Use the following process for the comprehensive review.

## Review order

1. ARESLib state, reducers, safety, control loops, hardware IO, and telemetry contracts.
2. FTC/FRC season orchestration and simulator parity; XRP runtime and deploy preflight.
3. Project schema, compiler, code generation, persistence, and standalone starter exports.
4. Studio service lifecycle, process ownership, networking, database, and file operations.
5. Studio view models, navigation, forms, field rendering, and minimum-window workflows.
6. Gateway authentication and limits, build tooling, CI, release promotion, and documentation.

## Evidence for each batch

Record the exact commit, every reviewed source path, reviewed line ranges, findings,
fix commits, and relevant test or rendered-window evidence in the review artifact.
Include all tracked Kotlin, Python, Java, build scripts, configuration, and tests.
Distinguish ARES-owned code from upstream or generated sources; inspect generation and
integration boundaries without presenting vendor code as newly maintained ARES code.

Review correctness and organization together: ownership, callers and consumers, failure
paths, state lifetime, concurrency, determinism, hardware neutrality, API compatibility,
and whether the tests can detect a real behavioral regression. For UI code, exercise
keyboard input, cancellation, loading/error states, scrolling, and minimum window size.

A file is complete only when all its lines and relevant call boundaries have been examined.
Changing it afterward invalidates the reviewed ranges affected by the change. Track
unreviewed files explicitly and keep targeted test success separate from review coverage.
Store detailed point-in-time evidence under ignored build diagnostics or protected CI
artifacts; keep this document as the maintained review process rather than an audit snapshot.
