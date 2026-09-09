# Database coordination and repository transactions - pass 25

This pass reviews the complete database coordinator, shared transaction ownership/cleanup
and native console writer. It applies those contracts to scoped metadata, evidence and
backup/import mutations. The larger repositories remain partially reviewed. Library and
robot source identity is unchanged.

## Findings and changes

- Session deletion, console insertion, evidence replacement/completion and backup imports
  committed a transaction even when the caller had disabled auto-commit. They now join
  the caller's transaction without committing or restoring its mode. Owned transactions
  retain the rollback/reset/uncertain-writer cleanup verified in pass 24.
- Older wrappers caught only Exception or replaced the original failure during cleanup.
  A cloud-import AssertionError left imported rows committed. All explicit transaction
  wrappers in the database repository directory now share the same Throwable cleanup.
  Native appenders request an activating statement; ordinary SQL mutations rely on JDBC
  statement activation and avoid that extra query.
- The console writer cached a prepared statement with pending rows after a binding failure.
  Its next call inserted a row from the failed batch. That cache and its shutdown cleanup
  are removed. Console insertion now streams into a transaction-local staging table and
  uses one SQL upsert. It avoids copied chunk lists and thousands of individual JDBC
  executions. A row-order column preserves last-occurrence severity for duplicate
  timestamp/text keys; the selected session is bound once in the upsert.
- The old 10,001-row console fixture exceeded runTest's one-minute timeout. The new writer
  processes the same fixture through the native appender and one upsert without changing
  the timeout. The staging table is dropped on success; owned transaction rollback removes
  it on failure. Caller-owned failures remain the caller's rollback responsibility.
- The coordinator sampled the clock twice for every write, but write metrics discard
  duration and only count attempts. Those clock calls are removed. Read timing and separate
  read/write mutexes remain intact. Counters describe attempts entering the IO context,
  including failures/cancellation; they are not an exact SQL statement count.

## Validation

Baseline evidence is in `ARESLib-Kotlin/build/audit-pass25-before.log` and
`audit-pass25-before-initial.xml`. It reproduced caller-ownership failures and stale console
rows, plus the large console timeout. Two initial Parquet fixture queries had alias syntax
errors, so they are not defect evidence. Corrected backup cases are preserved in
`audit-pass25-backup-before.log/xml`. The fatal-error fixture was then corrected to compare
exception type/message across coroutine stack recovery; `audit-pass25-fatal-before.log/xml`
reproduces its committed row. These fixture corrections do not relax data assertions.

Eight repository methods check caller rollback for deletion, console, evidence and backup,
native console enumeration failure/retry, large batches, duplicate replacement/session
isolation and fatal import rollback. The original binding-fault reproduction is retained
as evidence; the final writer has no cached per-row statement to bind, so its failure test
exercises enumeration and native cleanup instead. Eight coordinator methods check exact
connection routing, unused clock avoidance, read latency, failure/cancellation lock release,
independent reader progress, cancelled waiters and checkpoint coordination. Existing native
transaction/cleanup, metadata, import, backup and bundle suites also run.

Validation passed:

- 73 focused test methods, including the 16 new repository/coordinator cases.
- Full Studio gate: 1,485 ordinary methods passed, six opt-in methods skipped; all 56
  dashboard smoke methods and the performance-baseline method passed. Kover verification,
  release alignment and production file-size checks passed in 4m 13s.
- The 10,001-row console fixture completed in 0.206 seconds with its existing one-minute
  timeout. This local test is evidence of reduced execution overhead, not a hardware benchmark.
- Kover: coordinator 20/20 lines and 4/4 branches; console writer 21/21 lines and 4/4 branches;
  native wrapper 1/1 line; generic transaction helper 24/24 lines and 14/48 branches.
  Inline instrumentation includes unexecuted cleanup combinations; line coverage does not
  establish every failure-path combination.
- Dashboard replay load 29.073 ms, scrub p95 22.6875 ms and rapid-seek burst 5.4008 ms;
  12,000 frames persisted/restored without drops. This is headless validation.

Logs: `ARESLib-Kotlin/build/audit-pass25-focused.log`, `audit-pass25-studio.log` and
`audit-pass25-dashboard-smoke.json`. Validation used the unchanged library candidate
`17.0.3-rc.b81c0156add9` from the isolated local release repository. Existing public mutation
return types remain explicitly `Unit`. Repository policy passed, including guidance and links in 186 current documents. The staged
inventory has 2,423 tracked files: 109 fully reviewed, 51 partially reviewed and 2,263 pending,
with no stale or orphaned records. These are review dispositions, not universal test coverage.

## Remaining scope

The coordinator does not provide a database snapshot spanning independent read calls or
atomicity across persistent and ephemeral databases. Timings exclude time before entering
the IO context; the clock contract remains monotonic. An uncertain closed writer requires
service/database reinitialization. Successful commit followed by acknowledgement/reset
failure cannot establish exactly-once retry behavior.

Console staging reduces JVM copies and JDBC executions, but SQL engine memory/work still
scales with the batch. The writer preserves the existing timestamp/text storage contract;
broader console validation, same-time display ordering and source interpretation remain
adjacent review scopes. Diagnostics read timing/routing, source-evidence validation,
backup/export SQL and filesystem behavior, schema recovery and remaining telemetry queries
remain open. Prior intermittent timing failures, opt-in tests and physical robot validation
are still open. All changes stay local.
