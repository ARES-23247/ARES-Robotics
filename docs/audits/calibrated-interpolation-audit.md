# Calibrated interpolation audit - pass 41

This pass reviews complete `InterpolatingTable.kt` (including Interpolatable), the existing
`InterpolatingTableTest.kt`, and three added test files. Searches of library, robot, starter
and Studio source found no production call site; this remains a published general-purpose
library API and receives full dependency-ordered candidate validation.

## Findings and fixes

- Converting keys to Double before subtracting them collapsed adjacent large Long values
  and high-precision decimal intervals. Finite BigInteger keys beyond Double's range also
  collapsed to infinity. Integral differences are now formed exactly before conversion;
  unsigned distances represent the full nonnegative separation of signed Long endpoints.
  BigInteger/BigDecimal differences use decimal division before the final Double conversion.
- Opposite-sign finite Double endpoints could overflow the span, producing zero or NaN
  instead of the intended interpolation fraction. Those spans use half-scale arithmetic.
  Ordinary finite spans keep their direct path, including subnormal intervals.
- Nonfinite queries could clamp to a calibration endpoint and return a potentially active
  command. They now return null. Invalid calibration keys throw before mutation. The same
  contract applies to Float; supported integral and arbitrary-precision numeric types retain
  their natural ordering. Unsupported Comparable/Number types are rejected at insertion
  and return null when queried, instead of appearing usable until an interior lookup fails.
- Interior queries performed repeated tree searches for exact, floor and ceiling keys and
  their values. Ordered entries now locate exact matches or both interpolation neighbors
  with one binary search. Reads remain O(log N), with lower repeated search work and no
  temporary map entries. New-key insertion changes from tree O(log N) to O(N) array shifting;
  this is an explicit tradeoff for configure-then-query calibration use, not a general claim
  that every workload is faster. Replacing an existing calibration only changes its value.

Exact and clamped results retain value identity. Distinct Double signed zeros remain
distinct ordered keys; numerically equal BigDecimals with different scales replace one
entry. The public constructor, methods and generic signatures are unchanged. An interior
ratio is finite and bounded for supported standard numeric keys, subject to ordinary
floating-point rounding. Interpolatable implementations still own output validity,
allocation and exceptions; exceptions propagate without changing stored calibration.

The table is mutable and single-owner. Immutable keys and stable natural ordering are
required. Big-number operations allocate and their cost depends on operand precision;
this pass does not promise bounded memory/time for arbitrarily large numeric objects or
provide concurrent mutation support. A calibration result does not establish measurement
freshness or permission to command hardware.

## Validation

All seven baseline methods failed against the old implementation in 10s. They cover
overflowing finite spans, adjacent large Longs, huge integers, decimal precision, nonfinite
query/insertion and unsupported keys. Evidence: `ARESLib-Kotlin/build/audit-pass41-before.log`
and `ARESLib-Kotlin/build/audit-pass41-before.xml`.

The first focused run stopped at a test-fixture compilation error in 4s: the comparison-count
BigDecimal subclass needed explicit Byte/Short conversions for the Kotlin compiler. Those
conversions were added; production code was unchanged by that correction. The corrected
focused run passed all 24 methods in 5s: 13 new boundary/ownership methods, three new oracle/
search methods, one new allocation method, two existing table methods and five existing
zero-GC regressions. Logs: `ARESLib-Kotlin/build/audit-pass41-focused.log` and
`ARESLib-Kotlin/build/audit-pass41-focused-fixed.log`.

The independent ratio oracles generate 5,000 floating triples across exponents -1074 through
1023 and 5,000 signed-Long configurations, comparing distinct-endpoint cases to 80-digit
decimal results. They allow four Double ulps and assert finite [0, 1] ratios. Other tests
cover insertion order, replacement, exact/boundary identity, natural numeric ordering,
all supported primitive key families, full Long spans, subnormal spans and callback failure.
These finite fixtures do not prove correctly rounded output for every numeric input.

A 4,096-entry table locates an interior result with at most 13 observed key comparisons.
The allocation test executed without a skip and measured zero internal allocation in two
consecutive warmed-up 10,000-query windows. It deliberately reuses boxed primitive keys and
a mutable test interpolation result to isolate the table's own lookup/arithmetic work;
caller boxing and production value construction can still allocate. Arbitrary-precision
interpolation is excluded from this zero-allocation claim. Physical loop timings remain
unmeasured.

Source commit `95e5f800` binds candidate `17.0.3-rc.79a4fed61c3d` to library tree
`79a4fed61c3df9be85d2c020100feff27c80158b`. The branch's planned final version remains
17.0.3, already bumped from local main's 17.0.2. Candidate publication is local only.

The full library test/API/Kover/local-publication gate passed in 1m 24s, with 1,174 tests
passing and no failures or skips. Changed and dependent suites executed; unchanged project-
schema and telemetry-schema tests were up-to-date. Kover reports 48/48 executable lines and
56/56 branches covered in InterpolatingTable.kt. This does not prove every numeric key/value
combination or concurrent behavior. Evidence: `ARESLib-Kotlin/build/audit-pass41-library.log`
and the explicit XML manifest and coverage snapshot in
`ARESLib-Kotlin/build/audit-pass41-verified-evidence`.

FTC (109 tests), FRC (134), FTC starter (14) and FRC starter (34) passed against this exact
candidate. Generated-project verification ran, and both FTC debug APKs built. Only invoked
debug and simulator variants are counted; historical release-variant XML is excluded.
Logs: `ARESLib-Kotlin/build/audit-pass41-{ftc,frc,ftc-starter,frc-starter}.log`.

Studio passed the unchanged candidate in 3m 22s: 1,777 ordinary tests passed (six opt-in
skips), plus 56 dashboard checks and one performance baseline. Kover, version alignment and
the production-file size ratchet passed. Shared/gateway suites were up-to-date; app and both
dashboard tasks executed. The dashboard persisted/restored all 12,000 frames without drops;
replay load was 17.8903 ms, scrub p95 20.4876 ms and rapid-seek burst 5.3522 ms. These are
headless host measurements, not visible Studio or physical robot evidence. Log:
`ARESLib-Kotlin/build/audit-pass41-studio-after-standby.log`; exact XML and coverage snapshots
are in the verified-evidence directory.

The original pre-pause Studio and policy attempts were interrupted. After confirming their
old handles/processes had ended, validation resumed. That Studio attempt exited with two
one-minute coroutine timeouts: DatabaseBackupExporterInvariantTest's hostile native import
and EngineeringNotebookDraftServiceTest's invalid AI claim fallback. Windows System events
record standby beginning at 20:16:36 local on September 9, during the former test, and exit
at 21:58:22; the latter test recorded 5,970.721 seconds. This supplies host-interruption
evidence consistent with the timeouts, without proving every internal wait's cause. The
unchanged full rerun passed both tests. No timeout or assertion was weakened. Failed XML,
`audit-pass41-studio-resumed.log` and `audit-pass41-standby-events.json` are retained under
`ARESLib-Kotlin/build`; the failed XML snapshot is separate from passing counts.

Resumed repository policy checks passed: shared guidance, local links in 202 current documents
(38 historical documents excluded), and version/source/archive alignment. Evidence:
`ARESLib-Kotlin/build/audit-pass41-policy-resumed.log`. Inventory after this pass accounts for
2,495 tracked files: 208 reviewed, 63 partial and 2,224 pending, with no stale/orphaned records.
Executed suites do not confer review credit on otherwise unreviewed files.

## Remaining work

Remaining kinematics and estimation files are next, followed by the existing controller
DSL, connection/age ownership and Studio policy/lifecycle/retention queue. Hardware timing,
prior intermittent validation concerns, opt-in tests and remaining file inventory stay open.
No push, merge, remote release or device operation is authorized by this audit. The full
repository goal remains active.
