# Database metrics and action storage - pass 24

This pass fully reviews DatabaseMetrics, RobotActionRepository and the shared native-appender
transaction helper, plus their existing/new focused tests. It covers latency arithmetic, concurrent snapshots, recording allocations,
action batch atomicity, read coordination, timestamp/session domains and deterministic ties.
It does not close the broader coordinator, schema, log importer or cloud bundle review.
Library identity and robot code remain unchanged.

## Findings and changes

- Summing query durations in a Long could overflow and report a negative average. Metrics
  now maintain a running Double mean without truncating the nanosecond quotient. Mean and
  maximum cover the tracker lifetime; p95 covers the latest 512 reads. Event counts saturate
  at Long.MAX_VALUE rather than wrapping. Beyond saturation the running mean is approximate.
- Independent atomic updates to count, duration total and maximum could be combined with
  a different generation of the sample buffer. The baseline concurrent test reported
  1.245714 ms for identical 1 ms samples. One lock now captures coherent counters, mean,
  maximum and an owned primitive array; sorting happens after releasing that lock.
- The old lower-index percentile selected 1 ms from two readings of 1 and 100 ms. P95 now
  explicitly uses nearest rank, ceil(0.95 * n), so at least 95 percent of retained readings
  are at or below the reported value. This is a deliberate percentile convention change;
  the old three-sample fixture now expects the maximum rather than its middle reading.
  Dashboard health thresholds are unchanged.
- The boxed deque allocated 2,400,000 bytes across 100,000 read/write pairs in the baseline.
  A fixed primitive ring and primitive counters remove per-record boxing and redundant
  atomic work. Snapshots still allocate an owned array and result object. The unused
  write-duration clamp was removed; the public write argument remains for compatibility.
- Closing an appender after enumeration failed could flush already buffered action rows.
  The reproduced failure persisted the first row of a failed two-row batch. An owned
  transaction now spans append, flush and close, then commits. Failure rolls back before
  restoring auto-commit. A caller-owned transaction remains under the caller's control.
- JDBC auto-commit false alone did not start the native appender transaction. The first
  attempted fix still failed four transaction tests. The installed 1.5.5.1 driver bytecode
  and matching [connection source](https://raw.githubusercontent.com/duckdb/duckdb-java/v1.5.5.1/src/main/java/org/duckdb/DuckDBConnection.java)
  confirm lazy transaction state; [statement execution](https://raw.githubusercontent.com/duckdb/duckdb-java/v1.5.5.1/src/main/java/org/duckdb/DuckDBPreparedStatement.java)
  starts it. A shared helper now executes one small statement before opening the appender.
  Both action and telemetry appenders use it. The extra statement establishes the required
  transaction context; it is not a per-row query. Telemetry appender cleanup also uses use
  so a close failure is suppressed behind an original failure rather than replacing it.
- Cleanup failure could still commit partial work: after a rollback failure, restoring
  auto-commit committed the pending row. A reset failure could also replace the original
  exception. The helper now closes an uncertain writer after rollback/reset failure and
  attaches cleanup errors to the original cause. It never resets auto-commit after failed
  rollback. Recoverable commit failure rolls back and permits later writes.
- Action reads now use the persistent read connection/coordinator. Actions remain durable
  records even when their session label is live; this does not reroute them into the
  ephemeral telemetry table. Timestamp/session validation rejects malformed inputs and
  stored rows, while preserving legitimately absent run/robot identities and duplicate rows.
- Same-millisecond action ordering omitted fields that could distinguish records. Additional
  robot/match/alliance tie-breakers make returned order deterministic. This is an explicit
  storage ordering, not reconstruction of physical event order lost by millisecond timestamps.

## Validation

The original implementation failed twelve of sixteen new tests. Baseline evidence is
`ARESLib-Kotlin/build/audit-pass24-before.log` and the two preserved
`audit-pass24-before-DatabaseMetricsAuditTest.xml` /
`audit-pass24-before-RobotActionStorageAuditTest.xml` reports.

Metrics regressions check empty/negative durations, nearest-rank tail guarantees at seven
sample sizes, huge durations, subnanosecond averages, ring eviction versus lifetime values,
injected clock delegation, concurrent snapshots and allocation after warmup. Action tests
check read routing, failed enumeration, invalid domains, duplicates and complete field
round trips, missing/isolated sessions, deterministic ties, malformed stored timestamps
and empty batches. Six further methods use production schema initialization and separate
JDBC readers to verify action/telemetry caller-owned commit visibility and rollback for
persistent/live storage, rollback after flushed rows on exception/error/cancellation, and
failure inside a caller-owned transaction. Three cleanup methods inject rollback, reset and commit faults through a JDBC wrapper
while checking actual data visibility on a separate reader. There are **25 new methods**
in total. Two cleanup defects were reproduced in `audit-pass24-cleanup-before.log` and
`audit-pass24-cleanup-before.xml` before the final helper correction.
The intermediate four-failure transaction run is preserved as `audit-pass24-focused.log`
and `audit-pass24-lazy-transaction-*.xml`. It demonstrates why JDBC flags alone were
insufficient; the final tests verify actual visibility and rollback.

All **111 final focused methods passed**. The final complete Studio gate passed in
**2m 43s**: **1,469 passing tests and six opt-in skips** (app: 1,434 passing; shared: 17;
gateway: 18), plus **56 dashboard smoke tests** and **one performance-baseline test**.
App tests executed; shared/gateway reused unchanged results. Coverage verification, release
alignment and the Kotlin production size ratchet passed. Monorepo policy, shared guidance
and links in 185 current documents passed (`ARESLib-Kotlin/build/audit-pass24-policy.log`).

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| DatabaseMetrics | 39/39 | 6/8 |
| RobotActionRepository | 46/46 | 14/14 |
| DuckDbAppenderTransaction | 23/23 | 14/22 |
| TelemetryRepository | 318/329 | 90/128 |

Counter-saturation branches and some secondary cleanup-failure combinations were not
executed. Full-file review is distinct from complete branch execution; TelemetryRepository
retains partial review credit. The allocation assertion passed without skipping: at most
8 KiB across 100,000 warm read/write pairs, versus the observed original 2,400,000 bytes.
This is a bounded recording-allocation measurement, not a whole-application zero-allocation claim.

Measured replay load was **29.2738 ms**, scrub p95 **21.8455 ms**, rapid-seek burst
**4.581099 ms**, and ingestion **218,399 frames/s**. The performance baseline retained its
**100 ms scrub limit**; no threshold changed. These samples do not establish an isolated
throughput improvement or explain the unresolved pass 20 scrub timing failure.

Validation used the unchanged local candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. The final focused log is
`ARESLib-Kotlin/build/audit-pass24-focused-verified.log`; the final gate is
`audit-pass24-studio-final.log`. The dashboard snapshot is preserved as
`audit-pass24-dashboard-smoke.json`. Kover XML is
`ARES-Analytics/app/build/reports/kover/report.xml`. Library/robot suites were not rerun
for these Studio changes. No physical robot or cloud account operations were performed.

The refreshed inventory has **2,418 tracked files: 103 fully reviewed, 49 partially reviewed
and 2,266 pending**, with no stale or orphaned entries. This accounting does not establish
repository-wide test coverage.

## Limits and remaining work

No claim is made that every OS scheduler interleaving or resource failure was exercised.
A writer closed after uncertain transaction cleanup needs database/service reinitialization;
continuing to append through that connection would fail. Cleanup cannot guarantee recovery
when the underlying connection itself cannot close.
Counter saturation is a guard beyond practical event volumes, not an exact unbounded event
counter. Allocation measurement covers recordRead/recordWrite, not snapshot or database IO.
Action retrieval still loads all rows for the selected session and has no fixed memory/time
bound. Telemetry batches routed to persistent and live databases remain separate storage
transactions; no cross-database atomicity is claimed. A cancelled caller can observe cancellation after a transaction has already committed;
atomic batches do not establish exactly-once retries. Payload/identity interpretation beyond
the timestamp/session storage domain belongs to the action schema and importer.

DatabaseTransactionCoordinator, older manual transaction cleanup in metadata/telemetry
mutations, metrics sampling in
dashboard health, SQL/import failure handling and broader session memory budgets remain
open. Prior intermittent timing failures, opt-in tests and physical robot validation remain
open. All changes stay local.
