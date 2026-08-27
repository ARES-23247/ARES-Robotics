# Telemetry storage architecture

## Decision

ARES keeps DuckDB. It is a strong fit for an offline, single-laptop analytics application: it
queries Parquet directly, supports the comparisons and replay scans ARES needs, and does not make a
student operate a database server. PostgreSQL or ClickHouse would add installation, networking,
accounts, and backup administration without fixing the measured cold-start defect.

The storage layout should evolve, however. DuckDB should become the small metadata/query catalog
over immutable per-session Parquet files instead of remaining the only container for every raw
sample.

## What the cold-start investigation proved

A diagnostic copy of the production-shaped store contained 32,642,289 telemetry rows across 36
sessions. The checkpointed 3.4 GiB database opened in about 0.2 seconds, so database size and normal
DuckDB startup were not the problem. A 10 MiB write-ahead log (WAL) combined with a primary-key ART
index and three redundant secondary ART indexes remained in native WAL recovery for more than 11
minutes.

After removing the three secondary indexes, a crash-recovery probe reopened a 568 KiB WAL in 224
ms while retaining the legacy primary key. A fully index-free rebuild also preserved all
32,642,289 rows and reopened in 10 ms, but required about 100 seconds and a 1.5 GiB temporary native
memory limit. ARES therefore does not perform that disruptive rewrite invisibly during startup.

## Current hardened layout

- New `telemetry_frames` tables are append-only and have no primary, unique, or secondary ART
  indexes. `timestamp_us` plus `sample_order` carries deterministic replay order.
- Existing databases transactionally drop the three historical secondary indexes and checkpoint;
  they retain the implicit legacy primary-key index until an explicit storage migration.
- Parquet and cloud imports preserve idempotence with bounded delete-then-insert transactions rather
  than a global index on every live sample.
- Completed imports and clean shutdown are checkpoint boundaries. Frame batches are not individually
  checkpointed, avoiding an fsync in the ingestion hot path.
- Interrupted imports remain owned by `IMPORTING` sessions and are removed on recovery. Completed
  sessions remain authoritative.

## Target partitioned layout

The next storage milestone should use this conceptual ownership:

```text
~/.ares-analytics/
  catalog.duckdb                 small metadata, manifests, annotations, alerts, summaries
  data/
    workspaces/<workspace-id>/
      sessions/<session-id>/
        telemetry.parquet        immutable canonical telemetry partition
        manifest.json            identity, schema, count, bounds, SHA-256, source provenance
```

The exact paths must be derived from validated stable IDs, never display names. DuckDB views can
query only the partitions enumerated by the active workspace's catalog records. No wildcard scan of
all workspace directories is allowed.

An import writes `telemetry.parquet.partial`, verifies schema, row count, timestamp bounds, and
SHA-256, then atomically renames it and commits the catalog manifest. A crash before the catalog
commit leaves a removable partial file; a crash after the commit leaves a verified immutable
partition. Replacement creates a new verified generation before changing the catalog pointer.

## Migration and recovery

Migration must be explicit and resumable:

1. Preserve the existing DuckDB file and source-log archives.
2. Export one completed session at a time to a partial Parquet partition.
3. Verify identity, row count, timestamp bounds, strings, ordering fields, and digest.
4. Atomically install the partition and commit its catalog record.
5. Resume at the first session without a verified manifest after interruption.
6. Keep the old database until every session passes comparison and the user confirms cleanup.

If a WAL cannot be recovered promptly, ARES must not silently delete it. Preserve the database and
WAL together in a recovery snapshot and offer an explicit choice between waiting for recovery and
opening the last checkpoint. Export remains available so teams are never locked into the catalog.

## Boundaries that remain unchanged

- Local storage is authoritative; Google Drive is optional synchronization after local persistence.
- Team, season, robot, and workspace identity filters fail closed before query or export.
- Replay and comparison always order by canonical timestamps and `sample_order`, not file order.
- Raw source logs and import reports remain inspectable evidence.
- No physical-robot validation is implied by database, replay, or simulator evidence.
