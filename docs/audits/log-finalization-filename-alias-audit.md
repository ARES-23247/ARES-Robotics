# Log finalization and filename lookup audit

Pass 231 completed the outstanding filename-lookup review in `LogManagerServer.kt` and audited
the shutdown/finalization boundary in `ARESDataLogger.kt`. The server's previous request, worker,
rate-limit and dashboard evidence remains in the [preceding report](log-server-resources-dashboard-audit.md).
The logger remains partially reviewed; this report does not certify every serialization, rotation
or file-reservation failure path. No WPILib or NanoHTTPD source was changed.

## Confirmed defects and fixes

Discovery deduplicated basenames with case-sensitive string keys, while downloads searched the
actual filesystem. On this Windows filesystem, a root `run.csv` and a synced `run.CSV` produced
two listing rows, but both downloads selected the root file. The synced row therefore advertised
the wrong size and content. Discovery now suppresses a synced entry whenever the same eligible
root lookup would win a download. Exact-spelling duplicates retain the existing fast map lookup;
other spellings are checked using the filesystem rather than a universal lowercase transform.
Distinct names remain addressable on filesystems that distinguish them.

The regression checks listing size, downloaded bytes, root/synced precedence and authenticated
deletion. A second case checks composed and decomposed Unicode spellings. These are distinct on
the tested Windows filesystem and both remain listed. The tests branch on observed file identity
so they can check aliasing or distinct-name behavior without skipping the case. Linux, macOS,
Android and roboRIO filesystems were not executed in this pass.

The logging worker counted down its completion latch only after finalization returned. A retention
directory-listing exception could terminate the worker while leaving `stop()` blocked indefinitely.
The latch release and interrupt restoration now run in an enclosing `finally`, and finalization
exceptions are reported. The regression injects a filesystem access failure after successful
initialization, waits for actual executor termination, and checks that the shutdown caller returns.
Its cleanup releases the broken baseline's latch only after observing the failure, preventing a
leaked test thread without hiding the defect.

Writer finalization previously skipped `close()` when `flush()` threw, and both flush and close
failures still renamed the active file to a completed name. That could expose an incomplete CSV
or gzip stream to importers. The writer now uses exception-safe closure, and the completed rename
only occurs after flush and close succeed. Failed files retain their `.active` reservation for later
recovery; the existing lock/channel cleanup still runs. Separate injected flush and close failures
verify the close attempt, retained active name and unchanged completed-byte accounting.

These changes add no filesystem work to the producer control loop. The filename check runs during
HTTP discovery; cleanup changes run on the existing writer thread. No new throughput, allocation
total or physical loop-time measurement is claimed. Shutdown can still wait for an OS operation
that itself blocks; this fixes an abandoned latch after worker termination, not a hard IO deadline.

## Evidence

The original runtime ran 21 focused cases with four assertion failures and no fixture compilation
errors. The case-alias, retention-shutdown, flush-failure and close-failure cases failed. The Unicode
distinct-name scenario passed before and after the change. After the fixes, all 78 logging tests
passed, including five added methods. The final full-suite accounting below does not count the
focused results twice.

The first full FRC run found an existing allocation probe reporting 120 bytes where it requires
zero. The unchanged probe then passed in isolation. Its warmup and measured reset loops were
different inline call sites, and the profiler call sites were not warmed. `DeviceResetAuditTest.kt`
now warms the same measurement helper and requires two consecutive zero-byte windows of 100,000
reset-group samples, with exact device-read accounting across all seven batches. The zero-byte
requirement was not relaxed. The updated three-method class passed in focus; the full FRC suite
was repeated after this test-only correction. The initial failure and diagnostic logs are retained;
their variability does not identify a specific JVM allocation source or imply vendor/CAN timing.

Source commit `3a832621b941f7d161a7d9a8199a5eab207ec223` binds library tree
`a1c678aeabfc1fb59830a5ee37018560fe52e2a5`. Candidate `17.0.38-rc.a1c678aeabfc`
was validated locally. Versions are ARES/FTC/FRC starters 17.0.38, Studio 7.0.38 and
XRP/Lightbot 3.0.37.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,811 | 0 |
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
| Headless browser fixture checks | 9 | 0 |

There are 5,652 passing results, zero failures/errors and six unchanged Studio opt-in skips.
The skips concern three starter integration scenarios, native file chooser, dashboard performance
baseline and physical dashboard validation. Gradle results may be executed, up-to-date or cached.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generation checks passed.
All 410 candidate file hashes were reverified after consumer validation. Monorepo policy passed,
including links in 393 current documents and 38 explicitly historical skips. The four normalized
starter archives differ only in release version properties.

## Coverage and remaining boundaries

The ledger accounts for 3,018 tracked files: 1,317 fully reviewed, 178 partially reviewed
and 1,523 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable test coverage.

`LogManagerServer.kt` now has a complete accumulated file review, including the formerly open
lookup-alias boundary. The new `LogFinalizationAuditTest.kt` and the two added methods in
`LogServerRequestAuditTest.kt` were reviewed and executed. ARESDataLogger remains partial:
write/header errors, reservation races and additional replay-clock/rotation behavior still need
targeted fault checks. Adjacent desktop ingestion also remains partial, including cross-instance
archive collisions, cancellation and rollback behavior.

All changes and candidates are local. No push, merge, remote release, GitHub Actions run, native
Studio-window check, physical robot timing or hardware-in-loop validation was performed.
Evidence is under `ARESLib-Kotlin/build/audit-pass231-verified-evidence/`, including original/fixed
XML, build logs, candidate hashes, archive comparisons and the final summary.
