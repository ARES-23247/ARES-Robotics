# Logging retention, recovery and event cleanup audit

Pass 229 reviewed `LoggingPolicy.kt`, `RobotLogEnvironment.kt`, `DsEventLogParser.kt`,
the existing four-test `LoggingPolicyTest.kt`, and four new regression classes in full.
`ARESDataLogger.kt` retains its partial status: this pass traced governance at construction,
writer rotation, finalization and shutdown, without certifying the whole logger lifecycle.
LogManagerServer discovery, downloads, dashboard and concurrent request ownership are a
separate pending scope. No upstream WPILib code changed.

## Confirmed behavior and fixes

Retention previously removed a candidate from its working list before knowing whether
deletion succeeded. An undeletable oldest file could make count-based pruning stop with
too many real files. Results also reported too few surviving files, and the retention floor
could prevent later deletable candidates from being considered. The new pass counts only
successful deletions, tries each eligible candidate once, and keeps failed files in both
remaining-count and byte accounting. The minimum retained count still takes precedence
over storage budgets. Results describe a snapshot, not a transactional directory inventory.

Each candidate's modification time and length are now read once. Sorting uses those
snapshots, with filename order breaking timestamp ties deterministically. Iteration replaces
repeated removal from the front of an array list. This removes repeated metadata calls
during sorting and quadratic list shifting during pruning; it does not remove the sort or
its per-file snapshot storage.

Stale recovery could overwrite an occupied quarantine destination created after the name
was selected. A controlled filesystem regression reproduced replacement of other recovery
evidence on this Windows host. Recovery now uses the default move operation without
replacement and lets a later pass retry collisions, eliminating the redundant atomic/fallback
attempt. Java explicitly allows an atomic move to replace an existing target, even when
replacement was not separately requested. See the [Java Files.move contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).
This is not a cross-process transaction or a claim about crash atomicity on every filesystem.

Stale-age comparison now orders timestamps before subtraction: future timestamps remain
protected even when signed subtraction would wrap, and an age greater than the signed-long
range still counts as stale. The normal threshold remains inclusive. Case-insensitive active
filename recognition now removes the same suffix consistently when constructing the
quarantine name. Existing quarantine collisions and live writer locks remain protected.

Driver Station event cleanup previously rescanned and rebuilt the remaining string for every
metadata tag. It now scans forward into one builder with work linear in input length for the
fixed tag set. Existing tag-delimited behavior is preserved: recognized metadata is discarded
through the next opening angle bracket, unknown/literal text survives, and message/details
markers keep their legacy space requirement. This is not a general XML parser, and no new
Driver Station file-format conformance claim is made. Severity matching and precedence are
unchanged.

Logging configuration required no runtime change. Tests exercise all three profiles, their
sampling intervals and budget invariants, accepted retention flag spellings, invalid explicit
configuration, and every policy constructor constraint. System-property overrides are restored
after each test. Android/roboRIO detection and environment-variable fallback were source
reviewed; no physical platform detection experiment was performed.

## Regression evidence

The original implementation ran 22 new cases with nine failing assertions across two baseline
runs. Seven failing cases exposed accounting, replacement, timestamp and suffix behavior;
two establish the new deterministic tie ordering and one-read metadata efficiency requirements.
Those counts are failing test scenarios, not nine distinct production defects. Baseline logs
and four XML suites are preserved under the pass evidence directory. There were no fixture
compilation failures. The final extreme-age test also checks subtraction overflow for a valid
past timestamp after the initial future-timestamp failure was recorded.

All 26 focused checks passed: 22 new methods plus four existing logging-policy integration
tests. Coverage includes failed/all-failed deletes, count/byte/free-space budgets, retention
floors, owned suffix filtering, metadata read counts, ordering, real file locks, target collision,
stale-age thresholds, a 10,000-event cleanup input, literal text and severity precedence.
The policy integration tests also cover compressed output and duration rotation.

## Candidate and validation

Local source commit `ab3e502c78f5536969e2c770611bd7ebccff5162` binds library tree
`c5a9c65bbd2fe2a47b6cea2b20003e05f2c389cc`. Candidate
`17.0.36-rc.c5a9c65bbd2f` was validated in the isolated local repository.
Versions are ARES/FTC/FRC starters 17.0.36, Studio 7.0.36 and XRP/Lightbot 3.0.35.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,782 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,614 passing results, zero failures/errors and six unchanged Studio
opt-in skips. These cover three starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. Gradle results may be executed,
up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 391 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## Coverage and limits

The ledger accounts for 3,009 tracked files: 1,306 fully reviewed, 175 partially reviewed
and 1,528 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

Governance runs synchronously during logger construction and on the writer thread during
rotation/finalization. The submitted-frame path does not directly perform directory retention;
shutdown waits for writer completion. Reducing this work can reduce startup and background
logging cost, but no allocation-total, throughput, physical robot loop-time, hardware-in-loop,
or actuator-response measurement was performed. No rendered Studio window was inspected.
Filesystem errors and concurrent external directory mutation are not made transactional here;
broader logger worker-failure/shutdown ownership remains partial. Tests and artifact validation
were local. Nothing was pushed, merged, remotely published, or deployed.

Evidence: `ARESLib-Kotlin/build/audit-pass229-verified-evidence/summary.json`, focused/baseline
XML, full library/consumer logs, candidate hash manifest and normalized archive comparisons.
