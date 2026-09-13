# Tuning persistence and writer lifecycle audit

Pass 234 continues the file-level audit with robot-local tuning persistence and its FTC/FRC
owners. Source commit `2801d2627e0886fc8b0c1bb80987a1ce98715301` binds library tree
`a559b2f1934c9317ec4b67b2dd812d867f91f6df`; local candidate
`17.0.41-rc.a559b2f1934c` was validated. Versions are ARES/FTC/FRC starters 17.0.41,
Studio 7.0.41 and XRP/Lightbot 3.0.40. All changes and candidate artifacts remain local.

## Confirmed defects and fixes

- **Synchronous filesystem work delayed the control update.** The FTC season composition root
  supplies `/sdcard/FIRST` on Android and a desktop project root otherwise. Both platform tuning
  calls execute inside hardware-input updates. A controlled blocked-write fixture reproduced an
  update waiting longer than its one-second test deadline. `TuningManager` now gives immutable
  snapshots to one lazy writer, retaining at most one in-flight and one latest pending snapshot.
  A 10,000-proposal test observes only the first and last snapshots written. Failed saves wait for
  a later poll, newer proposal or final close attempt; they do not spin or throw through the
  periodic control update. `localOverlayPersistenceFailure` exposes the last disk error and clears
  after recovery. This does not change consumer acceptance or substitute disk success for an ack.
- **Lexical confinement accepted a directory alias into canonical profiles.** The original store
  accepted `.ares/local/tuning` as a Windows junction to `.ares/tuning`. Persistence now anchors
  at the real project root and checks each directory before creating its next child. Project-root
  aliases remain supported; redirected local ancestors and nonordinary final destinations are
  rejected. Staged bytes are flushed and atomically replaced, with no non-atomic fallback.
  A Windows handle denying deletion reproduces a real failed replacement: original bytes survive
  and the unique staged file is removed. Cleanup errors preserve the primary failure.
- **The new worker has explicit ownership.** FTC and FRC robots close replaced tuning managers,
  reject replacement after teardown, and attempt hardware cleanup before draining disk work.
  FTC proxy cleanup is also attempted after an earlier teardown failure. Tuning close flushes an
  accepted change whose telemetry acknowledgement failed, is terminal, and surfaces unsaved data.
  An interrupted closing owner still joins its worker and retains its interruption status.

The original two-test persistence baseline failed both behavior assertions. The final focused
run passes 69 tests (38 core, 19 FTC hardware, 12 FRC hardware), including 18 added methods.
An additional suspected FRC shared-exception teardown defect did **not** reproduce: its new
baseline test passed before any change to `FrcBaseRobot`, which remains unchanged.

Fixture corrections are separate from production defects. The first baseline used an invalid
parameter key; correcting its required dotted format exposed the two intended failures. An FTC
fixture initially wrote directly to mock motors, bypassing the owned IO cache; it now energizes
through `mecanumIO.setMotorPowers` and verifies neutral output after teardown. The first tooling
run encountered sandbox denial on temporary fixtures; its log is retained separately.

## Validation

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,878 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,046 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |
| Headless browser fixtures | 9 | 0 |

There are 5,722 passing results, zero final failures/errors and six unchanged Studio opt-in skips.
Gradle suite evidence includes executed, up-to-date and cached results; focused tests are not
counted twice. Generated-project checks and FTC APK assembly passed. All 410 candidate file
hashes were reverified after consumer validation. The four normalized starter archives differ
only in release version properties. Monorepo policy passed, including 396 current-document
link checks and 38 historical skips. Both full-suite 20,000-poll allocation windows remain zero.

## Coverage and remaining boundaries

The ledger accounts for 3,032 tracked files: 1,346 reviewed, 182 partially reviewed and
1,504 pending, with zero stale or orphaned records. This is file-review and appropriate
validation accounting, not universal executable test coverage.

The changed constructor descriptors are preserved. API additions are `AutoCloseable`, tuning
close/error inspection, and the FRC close override; the private writer is not part of the API.
Idle tuning polls retain their existing allocation regression check. Explicit accepted proposals
still allocate validation and snapshot data; no whole-loop allocation or physical timing result
is claimed. Shutdown can wait on the filesystem after hardware cleanup; it has no hard deadline.

Update, publication and close require one serialized lifecycle owner. Multiple independent
processes writing the same overlay, reentrant consumer callbacks, hostile concurrent directory
replacement, final-file symlinks on other operating systems, and power-loss durability remain
outside this pass's tested guarantees. Directory aliases were exercised on Windows. The Windows
failed-replacement fixture is conditional on that platform. Studio profile-promotion persistence
was read preliminarily and remains a separate audit boundary; it was not fixed in this pass.
No live robot network, physical hardware/HIL, rendered Studio window, remote CI, push, merge or
release was performed. The overall monorepo audit remains incomplete.

Detailed machine-local evidence is in
`ARESLib-Kotlin/build/audit-pass234-verified-evidence/`: baseline XML/logs, focused XML,
candidate identity and hashes, normalized archive comparisons, final suite XML and `summary.json`.
