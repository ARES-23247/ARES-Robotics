# Studio 7.0.61 integration review

This bounded release review integrates the changes submitted through `f73d4ceed` from
`antigravity/deep-audit`, based on protected main `9487e288b`, and the shared-skill update
`1e1da74a6`. It does not certify or resume a full-repository audit.

## Changes and decisions

- Corrected the submitted local-import observation eviction to use the same normalized `local:`
  key as insertion, including Driver Station companion files. The real CSV/database regression
  fails on the submitted implementation because a deleted source retains its stability observation.
  The test checks that a recreated source must establish stability again.
- Retained volatile visibility of the NT4 diagnostic timestamp and explicit closure before
  replacing inactive HTTP clients. These are defensive changes; no measured thread leak or
  robot-control failure is claimed. Existing connection, queued-start, and shutdown tests cover
  the surrounding service behavior.
- Replaced unsupported full-audit completion claims with an evidence-qualified disposition.
  The 269 changed ledger submissions retain their scope, references, fingerprints, and original
  status metadata but await substantiation. Unchanged historical evidence and deleted-file pruning
  are preserved. See the [submission review](DEEP_AUDIT_REPORT.md).
- Included the reviewed shared-skill updates for bounded audits, worker ownership, validation of
  bundled projects through Studio, and diagnosis of performance/test failures.
- Bumped Studio to 7.0.61 and aligned packaging metadata. ARESLib remains 19.1.1; its source tree
  and the five bundled starter/example archives are unchanged from the previous release.

## Validation

Local commands, results, screenshots, and release metadata are retained under `build/release-review/`
in the isolated `codex/reviewed-deep-audit-release` worktree. Validation uses released ARESLib 19.1.1.

- `:app:test` with `AutoImportServiceTest` and `Nt4ClientServiceTest`: 39 tests passed, no skips.
- `:app:test` with `Nt4DisposalAuditTest` and `Nt4IntegrationDiagnosticTest`: seven tests passed,
  including real loopback connection, terminal cleanup, cancellation, and persistence-failure cleanup.
- `:app:dashboardPerformanceBaseline`: 59 dashboard smoke checks and one budget check passed.
  All 12,000 expected samples persisted with zero drops. Desktop throughput was approximately
  264,300 frames/s, query p95 12.17 ms, replay-scrub p95 21.30 ms, and heap growth 5.56 MiB.
  The smoke limits were at least 1,000 frames/s, query p95 at most 1,000 ms, scrub p95 at most
  2,000 ms, heap growth at most 256 MiB, and zero drops; the checked-in regression baseline also passed.
  These are desktop service measurements, not target robot loop times or a before/after speedup claim.
- `python -m unittest discover -s scripts/tests`: all 107 tests passed.
- Native Windows launch rendered the actual onboarding screen in an isolated home, captured the
  owned Compose window after settled presentation, and closed through that window. The app exited,
  the runtime snapshot was cleaned, and no other task's app or process was terminated.

The failing pre-fix import test is retained as `import-before-fix.log` and `import-before-fix.xml`.
An intermediate service run was blocked by stale packaging URLs; those URLs were aligned with the
new Studio version before running the tests again. Assertions and budgets were not relaxed.
The submitted `Nt4PerformanceTest` filter matched no class; test counts above come from actual XML
results. The existing dashboard baseline supplies the performance evidence.

## Release and limitations

Required checks must pass on the final reviewed tree before protected merge. Promotion must verify
that the attested package candidate matches the complete main Git tree and publish its exact bytes.
The existing library publication and robot archives remain immutable.

Hardware is unavailable. No new controller timing, physical robot, Limelight calibration, or
all-file audit coverage claim is made. Quarantine/external-deletion/remote-source observation
retention remains outside the successful-local-import fix. Proposed library allocation optimizations
remain unmeasured and deferred.
