# FTC season lifecycle and autonomous adapter audit

Pass 257 fully reviews the season AresRobot composition root, ARESAuto and TestAuto entry
points, AresAutoDSL adapter, and their two lifecycle test files. Shared robot internals
were traced to establish ownership; their broader partial reviews remain open.

## Corrected findings

- Initialization guarded only generated registration. Earlier season setup failures could
  leave an already-created base robot open. All setup after base construction now belongs
  to one rollback transaction, including tuning and controller creation. Shared close owns
  hardware neutralization and cleanup of registered resources.
- Generated registration transferred ownership one object at a time but abandoned the
  remaining constructed objects on failure. Both installers now share one rollback path:
  close unaccepted objects in reverse order, once per identity, preserve accepted ownership,
  continue after cleanup failures, and rethrow the original failure.
- Season update faults discarded safety failures and lost the interrupt flag. Both safety
  operations still run; unique secondary failures are retained, self-suppression cannot
  replace the primary error, and interrupted work restores the thread flag. Retried latched
  failures retry safety without rerunning the frame or duplicating the same diagnostic.
- Drive, gamepad, pose reset, alliance changes and calibration arm calls could reach their
  controllers after a season/shared fault or close. They now reject terminal instances.
  Update also rejects close. Safe disarming remains available.
- Facade close repeated subsystem safety/cleanup already owned by shared close. It now
  disarms calibration and delegates once, continuing cleanup after failure and remaining
  terminal after a failed close. Shared close still neutralizes hardware and closes registered
  subsystems and services. No shared safety step or calibration lease was removed.
- Missing field assets could display an unrelated prior parser diagnostic. The diagnostic
  now uses the current asset read failure. Missing/invalid field contracts still clear tags
  and block field-dependent autonomy while retaining manual-drive fallback.

Normal frames retain shared update, RobotClock timestamp, one cached season sensor read,
one output write using the newly computed power scale, then rate-limited telemetry. No
per-frame collection was introduced. Registration bookkeeping and exception aggregation
allocate only during startup/rollback or faults. This is structural efficiency evidence,
not a measured allocation profile or robot-loop latency benchmark.

## Validation and identity

Twelve tests failed against the original production file; all twelve pass after the fix.
The new lifecycle file has 24 passing methods. One added real shared-runtime/SDK-mock
integration case proves generated-runtime construction failure blocks START/loop and still
closes the robot without saving a usable autonomous pose. A second adapter construction
case propagates the original season failure after base cleanup. Existing catalog, alliance,
field-tag unit conversion, missing-device policy, teleop transition and simulator lifecycle
tests remain intact. One initial integration assertion expected a redundant SDK zero write;
inspection showed CachedDcMotorEx suppresses it, so that fixture assertion was corrected.
It is not counted among the twelve production regressions.

Focused validation: 33 passing tests across three suites. Final full validation:

| Suite | Passed | Failed/errors/skipped |
| --- | ---: | ---: |
| FTC TeamCode | 168 | 0 |
| FTC simulator | 13 | 0 |

The 181 full results include the focused methods; they are not counted twice. Every prior
143 TeamCode and 13 simulator method remains passing. Generated-project verification and
Android assembleDebug also passed. Commands were run from ARES-FTC:

```text
gradlew.bat :TeamCode:verifyAresProject :TeamCode:testDebugUnitTest :simulator:test :TeamCode:assembleDebug
```

The run used the unchanged isolated candidate 17.0.42-rc.526a048d8dfb with its explicit local
repository, one worker, no parallel compilation, and the evidence-local test startup heap.
All 410 candidate file hashes were reverified. No library source or release identity changed.
FTC source tree: d15a7b3688f89512710f1bbe470f724ad9faa534. ARESLib source tree: 526a048d8dfb1092e89be95c8e906e9fbc516027.
Machine-readable evidence is in ARESLib-Kotlin/build/audit-pass257-verified-evidence,
including original failing XML, focused/full XML, commands/logs, identities and summary.

## Scope and limitations

The thin autonomous adapters retain generated catalog/default/hash ownership, the existing
test-auto entry, alliance locks, runtime policy, field/footprint validation and shared
lifecycle delegation. Generated output and upstream FtcRobotController were not edited.
Construction and fault tests isolate shared services where required; other integration and
simulator tests run real shared lifecycle code with mocked hardware. No physical hardware,
Driver Station UI, deployment, timed robot benchmark, push, merge or release was performed.
The OpMode owner must quiesce its loop before close; arbitrary concurrent lifecycle calls
are outside this contract. Public base access still exposes shared APIs with their own
review scope. Library safety repetition and exception self-suppression candidates remain
queued for a separate library pass and are not claimed fixed here.

The ledger accounts for 3,105 tracked files: 1,444 fully reviewed and complete, 193 partial
and 1,468 pending. There are 1,661 unfinished files and no stale or orphaned records.
This closes three pending production files, the partial autonomous adapter, and the existing
pending autonomous test file; the new test file and this report are reviewed too. File
completion records scoped evidence, not universal branch coverage or physical validation.
