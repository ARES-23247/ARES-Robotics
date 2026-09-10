# Weak registry cleanup and robot clock audit

Pass 65, 2026-09-10. Sources `d0a8607e` and `f66f6f84`, local candidate `17.0.3-rc.834f69846257`,
bound to the exact library tree in `release/ares-source-tree.txt`. This pass reviews all of
WeakIdentityMap and RobotClock, their registry call sites, and three complete test files.

## Confirmed inefficiency and change

WeakIdentityMap previously searched its entry array for each collected reference and removed
that entry individually. A collection burst could therefore cause quadratic searching and
array shifting while holding the registry monitor. Cleanup now drains queued notifications,
compacts live entries in insertion order in one pass, and removes the unused tail. Work is
O(entries + queued references), with no temporary cleanup collection. Ordinary identity
lookup and explicit removal remain linear, appropriate to the deliberately small registry.

The initial seven regression tests ran against the old implementation: six semantic tests
passed and the operation-count test failed. For 512 cleared, enqueued keys, cleanup required
131,840 counted entry operations. The corrected implementation requires 1,024: 512 reads
and 512 tail removals, with no writes or shifted slots in this fully collected case. This
is deterministic operation-count evidence, not a measured CPU speedup or loop-latency claim.

Tests also verify mixed live/collected insertion order and values, identity-only keys whose
equality/hash methods throw, nullable values, replacement and absent removal, delayed queue
notifications, cleared references before enqueue, visitor exception/monitor release, and
concurrent independent updates. Test-only reflection clears/enqueues references and instruments
the private entry list; it does not depend on requesting garbage collection or add runtime hooks.

The map strongly holds values, so values must not retain their own keys. The two production
registries store status enums and timeout state without such key retention. The only production
visitor, the timeout watchdog scan, does not mutate the map. The visitor contract now explicitly
requires no reentry or waiting on another thread using the map because callbacks run under its
monitor. This pass does not introduce a general reentrant callback contract.

## Clock review

No numerical runtime defect was found in RobotClock. Its immutable volatile mode snapshot
publishes a complete timestamp per getter call. Separate calls can observe separate mode changes;
the API does not promise a transaction across getters. Live milliseconds advance from a fixed
wall-clock anchor using elapsed nanoseconds. Mock nanoseconds intentionally preserve the low
64 bits of milliseconds multiplied by one million. Documentation now describes these limits.

The JVM nanosecond source has an arbitrary origin, can be negative, and supports elapsed
subtraction within its signed interval range. Raw timestamp ordering and comparisons with
epoch time are inappropriate; sufficiently long intervals overflow. See the
[Java 17 System.nanoTime contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#nanoTime()).
Mode changes and mock rewinds do not define forward intervals. The fixed live millisecond
anchor is valid for elapsed times below 2^63 nanoseconds, rather than indefinitely.

Four added contract tests use a BigInteger low-bit oracle for five boundary and 2,000 seeded
random signed millisecond values (seed 6501), check six one-millisecond intervals including
nanosecond sign wrap, exercise four readers during 20,000 alternating mock publications,
and bracket the live millisecond result against its original monotonic anchor after leaving
mock mode. Existing tests now subtract nanosecond readings rather than ordering them and
guarantee their owned reader stops in a finally block. Concurrent getters are checked
independently; the tests do not assume atomicity across adjacent calls.

## Validation

The final focused suite passed 131 tests with no failures, errors or skips, including 14 new
methods: seven weak-map tests, four clock contract tests and three allocation tests. Focused
Kover covers WeakIdentityMap's 39/39 executable lines, 28/28 branches and 8/8 methods, and
RobotClock's 12/12 lines, 4/4 branches and 6/6 methods. This coverage is local to these classes.

The allocation probe observed zero bytes across 64 mixed 128-entry cleanup batches. Five fixed
batches of 10,000 three-getter clock reads measured [0, 1024, 0, 0, 0] bytes in live mode and
[0, 0, 0, 0, 0] in mock mode. The escaped-array control measured 48,000 bytes. An initial clock
probe failed at 792 bytes after warming a different loop site; warming the batch method itself
passed the focus but then failed at 624 bytes in the full suite. Both failure XML files are
retained in `ARESLib-Kotlin/build/audit-pass65-allocation-initial.xml` and
`audit-pass65-allocation-full-initial.xml`. Their precise VM cause is not established.

The final clock probe keeps five measurements, requires at least one batch within 256 bytes,
and bounds aggregate overhead at 4,096 bytes. This detects sustained per-read object allocation
while allowing occasional fixed overhead; it is not proof that every invocation allocates zero
bytes. Registry cleanup retains its single 256-byte budget. Both skip explicitly if the JVM
allocation counter is unsupported. Setup, mode changes and reflection are outside measurement.
The initial candidate `17.0.3-rc.2e1426def67a` failed library validation and was not consumed;
the changed test source is bound to the new candidate above.

Baseline logs/XML and final focused XML/Kover are retained under
`ARESLib-Kotlin/build/audit-pass65-before-evidence` and `audit-pass65-focused-evidence`.
The full library gate passed 1,624 tests without failures, errors or skips, API compatibility,
core Kover and isolated candidate publication in 1m8s. Its clock samples were [744, 0, 0, 0, 0]
bytes in live mode and all zero in mock mode; cleanup remained zero and calibration 48,000 bytes.
Source policy passed, including source identity, archives, agent guidance and local links in
226 current documents (38 historical exclusions). Candidate consumers passed in dependency order:
FTC 109 tests, FRC 134, FTC starter 14 and FRC starter 34, generated-project verification and both
FTC application assemblies. Studio passed in 2m16s with shared/gateway/app tests rerun: 1,779
passes and six opt-in skips. Dashboard smoke 56 and performance one reran and passed, along with
coverage, release alignment and production-file-size checks. Final XML, core Kover, logs and
SHA-256 manifests are retained in `ARESLib-Kotlin/build/audit-pass65-verified-evidence`.
Six opt-in skips remain limitations.

## Limits

Reference enqueue behavior is exercised deterministically; actual GC scheduling and reclamation
latency are not measured. Registry construction still allocates entries and weak references.
Mock-mode changes allocate their immutable mode snapshots. No physical hardware, live Studio
window, hosted CI, wall-clock adjustment, clock drift or hardware-loop jitter was tested. The
full-monorepo audit goal remains active.
