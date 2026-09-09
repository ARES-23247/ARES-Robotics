# Telemetry density and metadata queries - pass 23

This pass covers replay histogram arithmetic, selected-session metadata queries and the
database read paths used by replay, guided analysis, alerts, bundles and synchronization.
It fully reviews the extracted density query and new tests. The larger repositories and
consumers remain partially reviewed; their remaining writes, lifecycle and cloud behavior
are not closed by these changes. Library identity and robot code are unchanged.

## Findings and changes

The previous histogram cast a fractional bucket position to INTEGER. DuckDB rounds this
conversion, so samples near the end of one interval could appear in the next. The query
now uses integer division over widened integer intermediates. Interior bins are half-open
and the final bin includes the maximum timestamp. This agrees with DuckDB's documented
[integer division](https://duckdb.org/docs/lts/sql/functions/numeric) and avoids its
[floating-point-to-integer rounding](https://duckdb.org/docs/current/sql/data_types/numeric).
Integration tests run against the actual bundled JDBC engine, rather than relying only
on documentation for its behavior.

Single-instant recordings previously produced all-zero density. They now occupy the first
bin, consistent with the replay playhead's zero-duration position. Empty recordings still
produce zero bins. Bucket counts must be between 1 and 10,000, bounding the returned/JVM
histogram allocation; the only production caller requests 100. Normalization divides counts
by the peak count, not by total samples. Duplicate samples contribute independently.

Bounds and bucket counts previously came from separate statements on the writer connection.
They now come from one statement on the session's read connection, preserving live/persistent
routing and a common statement snapshot. Only aggregated bins enter JVM memory. The SQL
engine still processes the recording; this is not a fixed database-memory or query-time bound.

Replay and four other consumers loaded every completed session to select one ID, decoding
all unrelated metadata. A prepared completed-session lookup now selects only the requested
row. It preserves missing/importing-session behavior and exact workspace checks in callers.
Guided analysis also uses the existing workspace-filtered, ordered query instead of filtering
and sorting all sessions again. Six metadata readers now use the read coordinator instead
of holding the ingestion writer lock.

Tag and match edits previously committed the session and summary rows independently.
They now share one transaction, so a failed second update rolls back the first. Tag JSON
is encoded once and reused for both rows. Existing caller-owned transactions retain their
ownership; the helper commits/restores auto-commit only when it opened the transaction.

## Validation

The original implementation failed five of eight new density regressions. The preserved
baseline log and XML are `ARESLib-Kotlin/build/audit-pass23-density-before.log` and
`audit-pass23-density-before.xml`. Tests cover interval boundaries, zero duration, empty and
one-bin recordings, invalid/excessive bucket counts, live routing, read coordination and
twelve wide sparse histograms compared with an independent BigInteger oracle. Metadata
tests cover completed/importing/missing identities, bound SQL identifiers, unrelated malformed
metadata, read coordination and all workspace identity fields with chronological ordering.
Three further methods check paired tag/match success, nullable fields, missing summaries,
rollback of each edit when the second table is unavailable, and a successful later write.
There are sixteen new methods in total. Failure injection drops a table only in an owned,
disposable fixture database.

All **94 focused methods passed**. The complete Studio gate passed in **2m 19s**:
**1,444 passing tests and six opt-in skips** (app: 1,409 passing; shared: 17; gateway: 18),
plus **56 dashboard smoke tests** and **one performance-baseline test**. App tests executed;
shared/gateway reused unchanged results. Coverage verification, release alignment and the
production Kotlin size ratchet passed. Repository policy, shared guidance and links in
184 current documents passed (`ARESLib-Kotlin/build/audit-pass23-policy.log`).

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| TelemetryDensityQuery | 14/14 | 12/12 |
| SessionMetadataRepository | 281/289 | 52/68 |
| TelemetryRepository | 320/332 | 93/138 |

Kover measures JVM execution, not SQL engine internals. The independent histogram oracle
and database boundary tests check SQL results. Larger repository files retain partial review
credit despite extensive suite execution. The refreshed inventory contains 2,412 tracked
files: 94 fully reviewed, 49 partially reviewed and 2,269 pending, with no stale/orphaned
records. File review accounting is separate from branch coverage.

Measured replay load was **17.237501 ms**, scrub p95 **22.743301 ms**, and rapid-seek burst
**3.864001 ms**. The separate performance-baseline test retained its **100 ms scrub limit**.
No threshold was relaxed. These observations do not establish a physical timing improvement
or explain the unresolved pass 20 105.8996 ms failure.

Validation used the unchanged local ARES candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. The final focused log is
`ARESLib-Kotlin/build/audit-pass23-focused-final.log`; the full gate is `audit-pass23-studio.log`.
The measured dashboard report is preserved as `audit-pass23-dashboard-smoke.json`.
Kover XML is `ARES-Analytics/app/build/reports/kover/report.xml`. Library and robot suites
were not rerun for these Studio-only changes; no live cloud or robot actions were performed.

## Remaining scope

TelemetryRepository's ingestion/export/cursor/pruning behavior and SessionMetadataRepository's
other mutations, JSON/domain validation, import deduplication and lifecycle remain open.
The selected-session lookup avoids decoding unrelated rows; it does not guarantee constant
database query time or protect a selected row with malformed metadata. Multiple separate
reader calls can still observe different committed database states. Replay retains all
distinct timestamps and window memory still depends on topic density. Existing cloud/source
consumers retain their own broader identity, cancellation and external-action review scopes.
Prior intermittent failures, opt-in tests and physical validation remain open. Changes stay local.
