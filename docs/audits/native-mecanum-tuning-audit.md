# Native Mecanum configuration audit - pass 85

This pass continues the native FTC configuration and initialization scopes left open in pass 84.
It covers the SDK boundary, the Redux tuning caller's PIDF routing, and cached command retries.
Work is local; the tests use SDK doubles and do not measure physical hub timing.

## Findings and changes

- Live motor PID updates reached only the software controller, even in native velocity mode.
  They now configure all four hub channels in `RUN_USING_ENCODER`. The three-argument method
  preserves each channel's last accepted F; the new four-argument overload applies explicit F.
  Redux tuning forwards F and waits for native configuration to succeed before changing its
  other software settings.
- Repeating accepted gains needlessly risks bus work. Owned coefficient snapshots, read once
  during initialization, suppress unchanged writes without reading hardware during live tuning.
- Missing hardware or failed mode initialization could leave resolved motors powered and
  partially registered. Resolution now attempts every channel, initialization confirms neutral,
  and registration/polling begins only after hardware configuration succeeds. Invalid names or
  repeated motor identities reject the cluster and attempt to stop every resolved motor.
- Configuration changes now require neutral on all motors before any PIDF writes. A failed
  or invalid configuration latches output and cannot be recovered by a neutral command alone.
  Retrying valid configuration repairs the snapshot; explicit neutral recovery is still required.
  Partial writes never become the accepted snapshot and cannot suppress retries. Configuration
  changes and failures reset feedforward history; unchanged accepted gains retain history.
- A failed motor or servo write could leave the prior cached command suppressing a corrective
  write, including a motor stop. Failed writes now force the next command through. Getters
  continue returning the last accepted cached command without polling hardware.

The mock SDK gains the existing FTC `getPIDFCoefficients` boundary. Its default remains a
stateless fixture; stateful test doubles explicitly implement coefficient storage. It does not
simulate the native hub controller. Two stale HardwareRegistry comments were also corrected:
logical-name replacement updates ordered lifecycle entries as well as lookup data.

## Evidence and remaining scope

Seven initial native tests failed before the implementation. A further tuning transaction test
and two direct uncertain-write tests reproduced their respective defects before the fixes.
The existing failed-neutral-recovery regression also caught the cache interaction during module
validation. Preserved failure XML is under `ARESLib-Kotlin/build/audit-pass85-before-results/`.

The expanded native suite has 18 tests, including channel-specific F, explicit F, unchanged gains,
failed configuration and recovery, neutral ordering, failed reads, invalid constructor values,
software-mode isolation, initialization cleanup, and Redux caller retry. The cache suite adds
two lost-acknowledgement cases. Final module and candidate results are recorded below.

Geometry validation, avoiding repeated kinematics reconstruction, removal of optional tuning gains,
and whole-tuning transaction validation remain open in MecanumKinematicsController. These are not
covered by successful PIDF routing tests. Previously open FTC flywheel scopes also remain pending.
No physical timing, native SDK control dynamics, calibration accuracy, or usable Studio window is
claimed by this pass. The whole-monorepo goal remains active.

## Final validation

All 295 FTC hardware tests passed before freezing, including 68 selected affected tests. This batch adds 20 methods: 18 native configuration tests and two uncertain-write cases. Ten new methods failed before their corresponding fixes.

All library API checks passed after recording the inherited simulator getter. The API additions are the four-argument MecanumHardwareIO overload and the SDK getter in the mock, cached motor, and simulator signatures. Core API is unchanged. The initial candidate stopped at the missing simulator API baseline; the corrected tree uses a fresh candidate identity.

Source commits: `02688ee7ae557572efbdbf03c25837906c20cab8`, `58d3409f42fb6aef29a25db13267441a2638e25a`.
Library tree `6cc7c88450c7e04992b7e87a84423181484a4809`; local candidate `17.0.3-rc.6cc7c88450c7`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 1964 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover and local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs. The six conditional Studio skips remain three fresh-template checks, native file chooser, physical dashboard, and optional performance baseline.

Copied XML, per-file hashes, build logs and BOM identity are recorded under `ARESLib-Kotlin/build/audit-pass85-verified-evidence/summary.json`. The architecture document's new hardware section is validated, but a full read also found older Maven Local and FRC namespace guidance that conflicts with the canonical workspace guide. Those documentation corrections remain queued for the next source batch alongside the open tuning scopes.
