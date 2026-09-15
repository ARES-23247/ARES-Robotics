# Composite vision and outlier filtering audit — pass 208

Scope: CompositeVisionIO, VisionOutlierFilter/VisionFilterConfig and the VisionIO input
contract, plus their existing tests and new numerical, lifecycle and allocation regressions.
This pass follows pass 207's controller-input work. All changes remain local.

## Confirmed issues and fixes

CompositeVisionIO retained the caller's mutable child list while sizing snapshots only
at construction. Later list mutation could change ownership or overrun snapshot storage.
It now snapshots distinct child identities in constructor order. Repeating one IO object
no longer polls or closes the same physical source twice; identity comparison does not
collapse distinct sources merely because they compare equal.

Child snapshots are cleared before every poll. Disconnected children cannot contribute
measurements, and partially written connected snapshots cannot replay prior observations.
Polling failures clear connection status, selected measurements and camera poses before
propagating. Aggregate buffers are borrowed and reused. VisionIO now documents that
consumers must finish reading within that lifetime or take owned copies for retention,
as the Redux ownership boundary does.

Close is idempotent, invalidates output and prevents subsequent polling or camera writes.
If close occurs during a child callback, the interrupted update cannot poll later children
or publish a connected aggregate. Recursive polling/forwarding is rejected. Orientation
and IMU-mode forwarding attempt all still-open children; failures invalidate output and
propagate with later failures suppressed. Close also attempts every child after Exception
or Error, retaining the primary failure. This proves cleanup attempts and lifecycle state;
it cannot guarantee physical shutdown when a child close operation itself fails.

Correlation previously compared source-specific microsecond clocks and sometimes fell
back to multiplying shared milliseconds by 1,000. Those timestamps need not share an
epoch. Multiplication and subtraction could also overflow and reorder or correlate very
distant captures. Sorting and correlation now use shared RobotClock capture timestampMs,
with an overflow-safe nonnegative difference. Native captureTimestampMicros is preserved
for platform-specific estimator conversion. Windows remain 10 ms, anchored at the oldest
remaining observation, with stable constructor/input ordering for complete quality ties.
Mount poses remain in child order. The existing published JVM microsecond constant and
its value are retained; the millisecond implementation constant is private. API checks
show no public signature or constant removal.

An observation with more tags could win a correlation window despite an invalid field
pose or malformed metadata, hiding a usable camera before the downstream filter ran.
A shared configuration-independent validity check now rejects nonfinite position,
nonunit/zero quaternion, nonpositive tag count, malformed available ambiguity, nonfinite
geometry metadata and invalid latency. Negative finite geometry metrics still mean
unavailable; explicitly unavailable ambiguity may remain NaN. Quaternion squared norm
may differ from one by at most 1e-6 to tolerate small accumulated roundoff. Invalid
orientations are rejected rather than normalized into apparently valid measurements.

Quality selection retains the prior tag-count, distance, uncertainty, span and area
priority. Squared standard deviations previously overflowed or underflowed into ties.
A common scale now preserves the ordering of summed translation variances across tested
Double scales, including norms above Double.MAX_VALUE. Missing geometry metrics compare
consistently, and distance classification no longer needs nullable Double intermediates.
Optional standard-deviation fallback remains the downstream estimator's existing policy.

The outlier filter had the same square-overflow/underflow issue in translation and shock
norms. Normal ranges retain the direct square-root path; extreme ranges use hypot.
The gyro heading is wrapped before subtraction, preserving the measured yaw when the
finite gyro value is very large. This follows the repository's represented Double radian
period; it is not arbitrary-precision trigonometric reduction.

Footprint bounds could compare an overflowing corner and expanded boundary as equal
infinities, accepting an outside corner. Those comparisons now use half-scaled arithmetic
when needed. A positive minimum-sized footprint cannot vanish when its half dimension
underflows. Ordinary rotated-footprint behavior is independently checked against explicit
four-corner transformations. These are numerical software boundaries, not physically
meaningful robot dimensions at the extremes of Double.

Scalar configuration validity is computed once per immutable configuration construction
or copy. A valid observation's Euler roll/pitch/yaw is extracted once and passed to the
field-bound check; the previous repeated validation and angle extraction are removed.
Explicit tag allowlists retain their configured behavior. Generic FRC defaults no longer
silently impose the 2024 tag range 1–16: the installed WPILib 2026.2.1 field resources
contain IDs 1–32 for both 2026 layouts and 1–22 for the 2025 layouts. The configured camera
field map owns season identities unless the application supplies an explicit allowlist.
The local WPILib JAR hash, resource names and tag IDs are recorded in
`wpilib-field-tags.json` in the evidence directory. This is an ARES default-policy fix,
not a defect or modification in WPILib.

## Validation

The unchanged implementation failed all 22 initial regression methods: 13 composite
cases and nine filter cases. Their XML is preserved. The completed pass adds 30 methods:
16 composite, 13 filter and one allocation measurement. Further checks include exact
BigDecimal variance-ordering oracles, timestamp extremes and clock-epoch disagreement,
1,000 seeded explicit footprint-corner comparisons, unknown-metadata compatibility,
configuration copies, reentrant polling, lifecycle failures and correlation boundaries.

The focused run passed 59 tests, including existing filter/metadata tests, noise rejection,
observation-noise and zero-GC regressions, and the existing FTC composite tests. The full
core suite and API checks passed before source freeze.

The existing convergence test backdated a current-time pose and supplied perfect
odometry, so its passing tolerance did not establish delayed-vision correction. It now
supplies the known truth at capture time and compares fusion with a separate biased
odometry-only store. Under deterministic noisy observations and 80 ms latency, odometry
alone ended 0.400 m off; the fused pose ended 0.06818 m off. This is a simulated test with
known reference motion, not physical camera accuracy or a general estimator error bound.
The test fixture documents capture-time truth explicitly.

The warmed desktop allocation test recorded zero bytes across 10,000 updates, at
1,255.99 ns/update in that run. It exercises four preallocated sources with twelve
observations, disconnected-source rejection, stable sorting, quality selection, hint/mode
forwarding and filtering. Aggregate lists can allocate when first reaching a new maximum
batch size; steady-state reuse is the tested boundary. The sort remains O(N squared) for
small camera batches. Large-batch latency, SDK/network polling, user IO allocations, GC
pauses and actual 50–100 Hz robot deadlines were not measured. No pre/post speedup is claimed.

Source commit: `4bf06282e1c74c42477200d82601346d0da119fc`.
Library tree: `d52ea8cbffb0e5b086828b7088eb8a10181f0f55`.
Candidate: `17.0.15-rc.d52ea8cbffb0`. Full library tests, API checks and publication to
the isolated local repository passed. The candidate manifest hashes 410 publication
files. Consumers use that exact candidate and the absolute local repository. New starter
archives differ only in version properties; old/new SHA-256 values, normalized content
comparisons and updated workflow pins are recorded.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,331 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,020 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,140 passing results, zero failures/errors and six
existing Studio skips. Gradle reused up-to-date or cached outputs where applicable; focused
runs are not counted twice. The skips cover three opt-in starter integration scenarios,
the native file chooser, the dashboard performance baseline and physical dashboard validation.
Monorepo policy passed, including source identity, versions, archive hashes, shared
guidance and links in 369 current documents; 38 explicitly historical records were skipped.

Evidence directory: `ARESLib-Kotlin/build/audit-pass208-verified-evidence/`, including
failing baselines, focused/full XML, the latency oracle, allocation output, installed
WPILib field data, candidate manifest, archive checks, policy output and summary.

## Coverage boundaries and next work

The three primary production files and eight associated test/fixture files were read
in full. File review and passing suites do not establish exhaustive line/branch coverage.
Platform tracker, Limelight timestamp and factory rollback code was read contextually to
trace the shared contract. Those broader files retain partial review; the ledger records
the inspected boundary and the remaining work rather than closing them from a suite pass.

Composite validation removes observations that are invalid without a robot-specific
configuration. A finite observation can still win selection and later fail a configured
field, motion or innovation gate. Correlation occurs within one poll batch; platform
trackers/IO own cross-poll freshness and frame de-duplication. This pass adds no universal
sample-age cutoff and does not redefine source clock conversions, recovery policy,
external-IMU enable/freshness gates, or estimator standard-deviation fallback. The existing
zero-Z acceleration sentinel and gravity convention remain unchanged.

The next distinct pass should finish FTC/FRC tracker freshness, recovery and shutdown,
then the remaining Limelight producer and factory rollback boundaries. Timestamp-overflow,
repeated/older frame identity and cleanup-failure cases in those larger scopes still need
specific validation. Broader Store configuration/collection ownership remains a separate
boundary from this filter's current-configuration predicate. Prior spline approximation
and other open ledger scopes remain active.

The ledger now accounts for 2,920 tracked files: 1,134 fully reviewed, 162 partially
reviewed and 1,624 pending, with zero stale or orphaned records. This pass closes twelve
new file records (three production files, eight tests/fixtures and this report), adds four
partial context records and extends two existing partial records. Passing suite results
do not close the remaining file-level review scopes.

No physical robot, rendered Studio session, remote workflow, deployment or public release
ran. The complete monorepo audit goal remains active.
