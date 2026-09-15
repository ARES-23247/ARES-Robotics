# Mecanum tuning transaction audit - pass 86

This pass closes the geometry, repeated construction, optional gain removal and consumed-setting
validation scopes left open in the Mecanum tuning controller. Work remains local.

## Confirmed defects and fixes

- Native gains could change before invalid geometry was rejected. Every numeric value consumed
  by the controller now validates before gain writes or software setting changes. Dimensions and
  derived linear/angular limits must be finite and positive; feedforward must be finite with
  nonnegative kV; optional slew must be finite and positive; encoder resolution must exceed the
  software feedback minimum. Invalid proposals neutralize and inhibit recovery until valid tuning
  is applied, followed by explicit neutral recovery.
- Every tuning update rebuilt the geometry solver. Unchanged dimensions now reuse it, including
  unrelated tuning changes. Changed dimensions still produce a new solver before native writes.
- Disabling reciprocal-kV speed tuning retained the previous derived speed. kV at or below the
  existing 1e-4 threshold now restores the construction limit. Both drive interfaces receive the
  same derived angular limit; the generic DriveSubsystem previously retained its initial value.
- Removing optional gains retained the last override. Native mode now restores owned per-channel
  initialization snapshots, captured after explicit construction overrides. Software mode restores
  the declared nullable PID gains; three nulls restore operation without encoder feedback. Invalid
  software construction defaults cannot be accepted through this restoration path.
- Failed native restoration cannot replace the accepted snapshot or bypass repair. Unchanged
  accepted defaults avoid additional SDK writes and reads; software restoration preserves history
  when settings are unchanged.
- The robot's tuning cache could skip the exact prior snapshot after rollback from a rejected
  attempt. It now invalidates the cache before attempting tuning and records success only after
  every existing update call returns.

The architecture document's old Maven Local validation and FRC namespace guidance was reconciled
with the canonical workspace guide in this batch, together with documentation of tuning behavior.

## Evidence and limits

The initial ten test methods failed before their corresponding fixes. Additional invalid software
default and robot rollback regressions also failed before fixes: twelve distinct failure-before
methods. The fifteen-method suite includes native/default retry, closed rejection, software numeric
feedback, unchanged slew history, and extreme finite geometry. Failure XML is preserved under
`ARESLib-Kotlin/build/audit-pass86-before-results/`.

This is validation of settings consumed by MecanumKinematicsController. The broader robot's vision,
localization, Pinpoint configuration and typed callback transactions remain separate audit scopes;
cache invalidation alone does not prove they are atomic. Hardware dynamics, physical loop deadlines,
calibration accuracy and usable Studio-window behavior are not established by SDK doubles or host
tests. The whole-monorepo audit remains active.

## Final validation

Before freezing, all 310 FTC hardware tests and all library API checks passed. The 73 selected affected tests include 15 new methods, twelve of which failed before corresponding fixes. Public API is unchanged.

Source `188f379730a20216f5428c492f44aa2d6f4fa315`; library tree `1efc651e01d5d4bb161d0fed8206ede1d1ec71c5`.
Local candidate `17.0.3-rc.1efc651e01d5`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 1979 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs. The six conditional Studio skips remain three fresh-template checks, native file chooser, physical dashboard and optional performance baseline.

Copied XML, per-file hashes, build logs and candidate BOM identity are recorded under `ARESLib-Kotlin/build/audit-pass86-verified-evidence/summary.json`. No physical timing or new standalone dashboard benchmark is claimed.
