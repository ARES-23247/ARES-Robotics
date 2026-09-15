# Shared FTC safety and failure handling audit

Pass 258 is in progress. Six regressions were reproduced against unchanged library production
source and corrected locally. The goal and this pass remain unfinished pending local archive
update approval, Studio validation and final repository policy/coverage accounting.

## Changes and evidence

- The shared frame now attempts hardware safety before stderr/telemetry diagnostics. A failed
  diagnostic sink cannot prevent the stop or replace the original control failure.
- Latched frames retry safety without rerunning control. They retain the first safety failure
  once, including when it first occurs on a later retry. A 1,000-retry test verifies the retained
  exception graph remains bounded; later retry interrupts still reach the caller.
- Shared cleanup and calibration shutdown preserve direct/wrapped interruption, retain distinct
  secondary exceptions once, and continue remaining cleanup/neutralization operations.
- Mecanum safety no longer creates a three-lambda array or traverses the registry twice. It still
  disarms calibration, neutralizes the drivetrain and attempts every registered device once.
- The existing builder test now closes its robot and verifies active-instance ownership clears.
- Development guidance now documents the failure contract and corrects the removed clock setter,
  obsolete fixed candidate example, consumer scope, toolchain wording and port/ownership advice.

The suspected self-suppression issue in direct Kotlin calls was disproved by compiled bytecode,
the existing shared-failure regression and a new same-instance calibration/device case. Those
calls use Kotlin's guard. Duplicate secondary diagnostics and lost interruptions were reviewed
separately. CachedDcMotorEx also legitimately suppresses unchanged SDK zero writes; tests verify
physical mock motor values after an actual nonzero command rather than requiring redundant writes.

Six original tests failed; all six now pass. Focused validation passed 69 methods across four
suites, including ten new safety tests. Full validation completed so far:

| Suite | Passed | Failed/errors/skipped |
| --- | ---: | ---: |
| ARESLib | 2,894 | 0 |
| FTC TeamCode | 168 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |

There are 3,428 passing full-suite results; focused tests are not counted twice. Library API
checks, generated-project checks, FTC APK assembly and isolated candidate creation passed.
The library's existing 2,884 test methods and outcomes remain intact. Source commit:
163f013a1ed2e6db96c902ea9ec0e662207194cc. Library tree:
3973a4d0eeb4b6e3d4afe0822c756073f55327dd. Candidate:
17.0.43-rc.3973a4d0eeb4, with all 410 file hashes reverified.

## Pending approval and validation

Studio stopped before its tests at verifyReleaseVersionAlignment: the local workflow still
references ARES 17.0.42 while the canonical manifest now requires 17.0.43. No check was bypassed.
Automatic approval review rejected replacing the four tracked starter archives and updating
their workflow/version/checksum references, including after read-only scope verification.
No archive migration or reference update has occurred.

The proposed FTC/FRC/XRP ZIPs change only canonical release properties. Lightbot additionally
contains the exact AresRobot, AresAutoBaseTest and new FtcSeasonLifecycleAuditTest changes already
validated in pass 257 and against this candidate. All targets are local and Git-recoverable;
the proposal includes no publishing, deployment, workflow dispatch, push or merge.

Evidence and the complete approval preview are under
ARESLib-Kotlin/build/audit-pass258-verified-evidence: archive-update-review.md,
archive-review.json, release-reference-proposal.patch, baseline.xml, focused-xml, xml,
candidate-manifest.json, summary.json and the actual command logs. Approval is followed by
the exact archive/reference update, Studio checks and final policy/ledger verification.

Full-file review applies to the new failure helper/tests, the builder test, and the development
guide. The three larger robot/calibration files retain their previous partial scopes; robot
replacement/shared globals, concurrent lifecycle ownership and other recorded calibration
work are not closed by these tests. File validation records remain pending until this checkpoint
can finish. No physical robot, driver-station UI, hardware timing, allocation profile of the
entire loop, remote CI or release was exercised.
