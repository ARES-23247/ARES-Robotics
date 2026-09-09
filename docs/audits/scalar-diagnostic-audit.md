# CAN, I2C and vision scalar diagnostic audit

Pass 34 reviews source identity, units, numeric domains, configured scalar thresholds and
redundant evaluation for Studio's CAN utilization, I2C timeout and Limelight FPS diagnostics.
These are operator diagnostics, not robot actuator controls.

## Findings and changes

- CAN evaluation selected the largest cached raw value across buses, then evaluated that bus
  using the current frame's timestamp. A second bus could fail to get its own occurrence, and
  legacy aliases were recorded under a synthetic source key. Each source now evaluates its own
  current observation and retains its actual normalized/configured key.
- The old threshold switched between 0.85 and 85 based on the observed value being above 1.5.
  That inferred units from magnitude, changed thresholds within a stream and could mix cached
  values in different units. Canonical ARES telemetry publishes Phoenix's ratio directly.
  [CTRE documents BusUtilization in the range 0.0 to 1.0](https://api.ctr-electronics.com/phoenix6/stable/java/com/ctre/phoenix6/CANBus.CANBusStatus.html).
  The default now compares a valid ratio against 0.85; invalid ratios preserve prior evidence.
- CAN, I2C and vision sources could run both an ordinary configured rule and a second hard-coded
  evaluation. The second pass could override a custom threshold, create a fault the configured
  rule deemed healthy, or immediately resolve a valid configured violation. Defaults now
  register only when an exact source has no configured rule, then the ordinary scalar evaluator
  runs once. Configured lower/upper bounds, names, keys and audio flags are preserved.
- Negative/fractional timeout counts and negative FPS could create or resolve faults. Supported
  timeout values are whole counts from zero through 2^53-1, the contiguous safe-integer range
  for a Double counter. FPS must be finite and nonnegative; zero is a valid measured stopped
  frame rate. No arbitrary positive FPS ceiling is invented.
- Invalid observations still advance the existing raw source-order guard, so an older healthy
  frame cannot supersede newer invalid evidence. Missing/invalid input never manufactures
  recovery; a later valid healthy observation can resolve the occurrence.
- Removing cached-source evaluation also removes its whole per-session recent-value map,
  boxed writes for every accepted frame, temporary filter lists and maximum scans. Exact
  built-in routing avoids retaining unrelated motor/CAN-prefix topics by default. Explicitly
  configured custom topics still receive ordinary scalar evaluation.

The unit contract was traced through ARESLib's ITelemetry.logCanBusStatus and
FrcTelemetryManager. These producer files were read for this boundary, not marked fully reviewed.
Threshold defaults and source domains now have one owner in ScalarDiagnosticRules.

## Compatibility and remaining semantics

The legacy `Hardware/CAN/Utilization` and `CAN/Utilization` aliases now use normalized ratios,
matching the canonical source. Percentage producers must explicitly divide by 100 before
publishing to these keys; no value-based percentage guessing remains. A percentage-specific
custom topic can use an explicit configured threshold in its declared unit. Existing external
percentage feeds/threshold files require migration; no such producer was found in the reviewed
monorepo sources. Aliases retain provenance and do not resolve or merge one another.

This pass does not add CAN error-count defaults, reinterpret cumulative I2C totals as per-loop
deltas, add no-input timers or validate every arbitrary custom telemetry key. A nonempty bus
name is required for the canonical CAN topic. Loop temporal-policy overrides, synthetic motor
rule configuration, platform threshold behavior and global history retention remain open.

## Validation

The eleven-method baseline reproduced ten failures in 1m 27s; the unchanged first-bus record
case already passed. Evidence: `ARESLib-Kotlin/build/audit-pass34-before.log` and
`ARESLib-Kotlin/build/audit-pass34-before.xml`.

The focused selection passed 103 methods in 1m 30s (`ARESLib-Kotlin/build/audit-pass34-focused.log`).
There are 23 new methods in the final tree: 16 engine integration tests and seven policy tests.
They cover independent buses/aliases, exact source keys, valid defaults/recovery, configured
one/two-sided bounds, invalid ratios/counts/rates, the safe-integer endpoint, delayed chronology,
explicit custom topics, structural topic matching and finite/threshold boundaries.

The final Studio gate passed in 2m 30s (`ARESLib-Kotlin/build/audit-pass34-studio.log`):

- 1,694 ordinary methods passed, six existing opt-in methods skipped, plus 56 dashboard smoke
  methods and the performance-baseline method passed. Kover verification, release alignment
  and production Kotlin file-size checks passed.
- Scalar policy covered 19/19 executable lines and 36/36 branches. Engine source covered 186/189
  lines and 108/134 branches. This high engine line coverage does not close its remaining
  configuration/concurrency contracts or establish full-file review.
  Snapshot: `ARESLib-Kotlin/build/audit-pass34-kover.xml`.
- All 23 added methods passed; final XML is retained in `ARESLib-Kotlin/build/audit-pass34-final-xml`.
- Dashboard load was 15.7127 ms, scrub p95 25.1739 ms and rapid-seek burst 2.5082 ms. The fixture
  persisted/restored 12,000 frames without drops. These desktop fixture metrics do not measure
  robot loop timing or isolate the speedup from removing diagnostic scans.
  Snapshot: `ARESLib-Kotlin/build/audit-pass34-dashboard-smoke.json`.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9`. Library and robot
  consumers were not rebuilt for these Studio-only changes.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass34-policy.log`), including shared
guidance and local links in 195 current documents. Inventory: 2,466 tracked files, 164 fully
reviewed, 61 partially reviewed and 2,241 pending, with no stale or orphaned records. Review
accounting remains separate from test execution and line/branch coverage.

## Limits and next work

The alert engine remains partially reviewed. Remaining loop/motor configuration semantics,
platform rules, concurrent lifecycle, global retention and other transport/store behavior need
further passes. This pass measures no physical CAN load, robot jitter, visible Studio window or
live audio. Existing intermittent and opt-in tests remain open. All changes remain local and
the monorepo-wide audit goal is active.
