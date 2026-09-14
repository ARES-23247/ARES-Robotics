# Recorded health summaries and counter semantics audit

Pass 243 follows clean commit `6aff9f46373e19fd4e084cec8e4381f53999f2e9` and reviews
health aggregation in SummaryEngineService and the Run History table that consumes it.
Studio tree `0a64bf436605444bea8e0abeb9dad0fce1ff2b07` uses unchanged ARESLib candidate
`17.0.42-rc.526a048d8dfb` and source tree `526a048d8dfb1092e89be95c8e906e9fbc516027`.

## Findings and producer evidence

All 16 initial regression cases failed against the preceding implementation:

- Loop threshold counts came from a per-topic sample, undercounting long recordings. Broad
  loop/period substring matching and mixed aliases included configuration values and duplicates.
- Nonfinite/textual values, invalid ratios, nested configuration topics and superseded updates
  could trigger hardware tags or poison health metrics. Absent signals generated measured zeros.
- Whole-recording timestamp gaps were called communication losses. Millisecond truncation also
  lost precision at the one-second threshold. Gaps could disappear completely when no sampled
  diagnostic topics were loaded.
- CAN error state was called a total event count. Bus-off and brownout counters used their peak
  initial accumulated values as events during the selected recording, ignoring resets and devices.
- History labels repeated these unsupported meanings; integer conversion could saturate large
  counts at the 32-bit maximum.

Producer tracing refined the CAN finding: FrcTelemetryManager publishes `ErrorCount` as
`REC + TEC`, with the component counters also published separately. This is error-counter
state, not a monotonic count of physical error events. The
[CTRE CANBusStatus contract](https://api.ctr-electronics.com/phoenix6/stable/java/com/ctre/phoenix6/CANBus.CANBusStatus.html)
identifies REC/TEC separately, a bus-off count, and utilization as a 0–1 fraction. A peak
error-counter value remains useful, but cannot be summed or differenced into an error total.

BrownoutGuard increments `tripCount` on transitions to a more severe warning/critical state.
Invalid or negative voltage can also enter critical state and increment it. A warning followed
by critical can therefore contribute two trips. It does not count distinct hardware brownouts.
Reset restores its counter to zero. These are ARES interpretation defects; this pass establishes
no defect in WPILib or CTRE. Library producer code was inspected without modification.

## Exact recorded aggregates

`SummaryHealthAggregator` replaces sampled JVM health filters and the separate timestamp-gap
query with one parameterized SQL statement. The statement shares a materialized source snapshot
and returns at most nine aggregate rows. Core summary aggregation remains its separate pass-241
statement. No complete recording is materialized as JVM TelemetryFrame objects for health.

Topic matching strips leading slashes and preserves case-sensitive identities. Loop and brownout
aliases have explicit priority. CANBus takes priority over legacy CAN independently for each
bus and metric; Utilization precedes BusUtilization, and BusOffCount precedes BusOffs. Device
patterns require exactly one nonempty bus/motor segment. An invalid preferred stream does not
fall back to another alias. The latest ordered update at a source microsecond wins before
finite, textual, range or integer validation.

| New or retained metric under Diagnostics/System | Meaning |
| --- | --- |
| LoopSamplesOver40Ms | Number of valid selected recorded loop samples strictly above 40 ms |
| LoopSamples | Number of valid selected loop observations, including zero |
| RecordingGapsOver1s | Gaps strictly above one second between distinct recorded source microseconds |
| MaxCANBusUtilization | Maximum valid 0–1 ratio across selected bus streams |
| PeakCANErrorCounter | Maximum recorded ErrorCount value across selected bus streams |
| CANBusOffIncrements | Sum of valid nonnegative adjacent counter increments, independently per bus |
| MaxCANBusLatencyMs | Maximum valid nonnegative reported latency |
| BrownoutGuardTripIncrements | Sum of valid nonnegative adjacent guard counter increments |
| MotorFaultObserved | Whether a valid nonnegative integral recorded motor fault flag was nonzero |

Counters and fault flags must be exact nonnegative integers within Double's safe integer range.
An invalid sample breaks the adjacent-counter chain. Initial observations and decreasing/reset
intervals are excluded; the next observation establishes the new baseline. Equal consecutive
valid counters supply an observed zero increment. Independent buses contribute their own valid
intervals before summation. No valid interval means unavailable, not measured zero.

Counter differences and timestamp-gap subtraction use DuckDB HUGEINT intermediates. Aggregate
counts outside exact Double integer representation fail explicitly before summary persistence.
Result shape, names, duplicates, finite/range constraints, row/cell limits and truncation are
validated. A valid utilization ratio cannot become an unbounded percentage.

Recording gaps use all topics, including recordings without other diagnostic signals. Fewer
than two distinct times provides no gap measurement. The metric identifies missing recorded
time coverage; it cannot establish packet loss, transport failure or robot inactivity.

## Integration and display

Health metrics now reach persistence even if the secondary diagnostic sample is empty. Battery
screening uses the existing validated core aggregate, removing the second sampled battery scan
and its invented 12 V default. Loop/battery topics and the broad Diagnostics prefix are no longer
loaded into the secondary sample solely for health processing. Diagnostics and tags share one final persistence helper, removing the early
duplicate tag write. The database calls remain separate transactions.

New tags describe RecordingGaps, SlowLoopSamples and BrownoutGuardActivity. Existing ambiguous
tags remain untouched because they may be user-owned. Other hardware screening tags retain their
generic meaning and now require valid measurements.

RunHealthRows owns the corresponding history rows, labels, formatting, numeric extraction and
thresholds. Counts avoid 32-bit saturation. Invalid or absent values show N/A, are excluded from
numeric comparisons and do not trigger row anomalies. Ratios and fault flags have explicit
bounds. Old mislabeled aggregate keys are not silently reinterpreted as new increment metrics;
regenerate existing summaries to populate the corrected rows.

## Validation

Focused validation passed 87 tests: 28 new health audit methods, five summary integration tests,
24 core aggregation tests and 30 localization tests. The 16 initial regression cases also passed
in the first fix run. A dedicated query check verifies 6,001 loop observations, one SQL query,
three returned aggregate rows and the full 6,001 threshold count in both focused and full runs.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,254 | 6 |

Full Studio validation has 2,303 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged build dependencies include up-to-date/cache
evidence. Focused tests are not counted twice. All 410 unchanged library candidate files
were rehashed; prior library and robot consumer validation is retained. Monorepo policy
passed, including 405 current-document link checks and 38 historical skips.

## Coverage and outstanding work

The ledger accounts for 3,059 tracked files: 1,384 reviewed, 195 partially reviewed and
1,480 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

The new health SQL helper, history-row helper and audit tests are reviewed within the stated
contracts. SummaryEngineService remains partial for SysId/traction/gravity, sampled localization
loading, tag ownership and multi-operation persistence. RunDataDictionary remains partial for
its other summary, motor and SysId rows. Raw pit-coaching health algorithms, including cumulative
brownout interpretation and current/loop screening, need the next independent pass.

These counts describe recorded observations, not every physical loop or hardware event. Packet
loss, repeated publications, unobserved reset-and-catch-up intervals, firmware resets and missing
epochs can limit counter interpretation. A reset between observations cannot be reconstructed;
reported increments cover only valid observed intervals. All-sample SQL avoids diagnostic
downsampling, not acquisition loss. CAN latency may retain a producer's zero placeholder when
hardware feedback is absent; analysis cannot recover missing provenance.

Use completed recordings: core summary, health query, secondary sampled algorithms, diagnostics,
tags and summary persistence are separate operations, not one recording-wide transaction.
Existing stored defaults and labels are not migrated automatically. SQL scans/window state still
scale with recording size; reduced JVM scans and bounded aggregate rows are structural evidence,
not a measured wall-clock speedup or real-time robot benchmark.

No rendered Studio window, physical robot/HIL, robot-loop timing, remote CI, library/version/
archive changes, push, merge, deployment or release occurred. Machine-local logs, XML, source
identity, policy and candidate hashes are in `ARESLib-Kotlin/build/audit-pass243-verified-evidence/`.
The monorepo audit remains active.
