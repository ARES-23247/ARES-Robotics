# Replay pose integrity and trace sampling audit

Pass 253 completes the remaining pose, vision, lighting and trace boundary in
[ReplayFieldSnapshot.kt](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/ReplayFieldSnapshot.kt).
The game-piece behavior from [pass 252](game-piece-frame-integrity-audit.md) is retained.

Studio source tree: `8c9104a53f5a4549bc910a5c142c269e5b189509`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Confirmed defects

Fifteen regression methods failed against the original implementation:

- Packed replay poses accepted nonfinite values, invalid sequences and text-backed numeric
  placeholders. An incomplete packed frame still supplied simulator heading.
- Scalar truth accepted invalid values; estimator fallback mixed axes from different sources.
  Lighting replay displayed text placeholders as accepted numeric output.
- Vision accepted fractional or infinite target flags. Its array map mixed topic families,
  accepted incomplete triples and retained arbitrary sparse indexes. The field renderer loops
  to the largest index, making an index near Int.MAX_VALUE especially costly.
- Trace loading decimated each coordinate independently before joining. A 101-pose trajectory
  with constant heading failed to preserve the requested ten samples and endpoints.
- Finding any exact sample-order match discarded other valid timestamps. Timestamp fallback
  combined ambiguous duplicate measurements into poses that were never recorded.
- Trace loading accepted text/nonfinite coordinates, treated three coordinates as a complete
  packed simulator frame, and omitted the drive heading alias supported by replay snapshots.

These are Studio display and historical-analysis defects; they do not establish a robot EKF,
WPILib, physical control or hardware defect.

## Corrections and efficiency

Snapshot reconstruction validates complete finite numeric triples from one source. Packed poses
require all ten numeric components and an exact sequence in 0..2^53-1. Valid packed truth,
estimation and odometry remain distinct. Complete scalar sources are explicit fallbacks;
an incomplete estimator never borrows individual axes from Drive. Angles remain CCW radians
without an added wrap or unit conversion.

Vision requires a numeric boolean target flag, builds arrays only when a target exists,
chooses one topic family, and retains complete finite triples within the 4,096-element NT4
array bound. Text lighting values are excluded. Game-piece reconstruction continues using the
strict decoder and prior legacy guards.

The new [ReplayPoseRepository](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/db/ReplayPoseRepository.kt)
executes one prepared statement through the existing database read coordinator. It:

1. Collects relevant rows from the requested session and inclusive time window in one statement.
2. Builds exact timestamp/sample-order frames, or timestamp-only frames when there is exactly one
   observation per required component. Invalid rows participate in ambiguity checks.
3. Requires all ten valid packed components, including sequence, before packed truth can win.
4. Chooses one source for the trace in priority order: packed truth, scalar truth, estimator,
   Drive pose heading, then the Drive heading alias.
5. Samples complete poses in source-time/sample-order order. It preserves the first point and
   takes the last point from each of at most maxPoints-1 balanced buckets, preserving the final
   point too. At most maxPoints triples cross JDBC; the accepted budget is 2..10,000.

The query replaces up to twelve separate scalar queries and their temporary JVM join maps.
A real database metric assertion verifies one query. Sampling uses row ordinals, avoiding
timestamp-span subtraction overflow, and leaves a trajectory at or below budget unchanged.
It preserves recorded poses, not every geometric extremum in a downsampled path.

DuckDB's [window function documentation](https://duckdb.org/docs/current/sql/functions/window_functions)
defines NTILE's balanced partitions; its [numeric functions](https://duckdb.org/docs/current/sql/functions/numeric)
define the finite-value check. The repository's pinned JDBC runtime is verified by executing
the actual query in database tests, rather than relying on documentation alone.

## Validation

Focused validation passed 68 tests: 26 new pose/trace cases, six original replay field
snapshot cases, 27 game-piece integrity cases, seven replay-engine cases and two dashboard
replay cases. All 15 baseline failures pass in both focused and full validation.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,512 | 6 |

Full Studio validation has 2,561 passing results, zero failures/errors and six unchanged
opt-in skips. Focused results are not counted twice. All 410 unchanged library candidate
file hashes were rechecked. Monorepo policy passed, including 415 current-document links
and 38 historical records. The test startup heap remains an evidence-local 32 MiB with one
worker; shared build settings, maximum heaps and existing assertions are unchanged.

The six original ReplayFieldSnapshotTest methods remain. Its packed-pose fixture now uses a
valid integer sequence (9 instead of 9.25); all coordinate and source-separation assertions remain.
One early focused compile failed because Waypoint allows a nullable heading. Snapshot reconstruction
now uses a private pose type requiring a heading, and the corrected source was validated.

## Coverage and limits

The ledger accounts for 3,093 tracked files: 1,426 reviewed, 194 partially reviewed and
1,473 pending, with zero stale or orphaned records. There are 1,667 unfinished files.
ReplayFieldSnapshot is now fully reviewed; the new repository, regression file and report
are fully reviewed. DatabaseService retains its prior partial scope. File-review completion
is not executable line coverage or physical validation.

ReplayFrame carries latched values, without per-component sample timestamps. This function cannot
prove the freshness of each latched field; the replay engine remains responsible for snapshot
assembly. The new historical query requires exact source microseconds and never interpolates or
joins across them. Ambiguous scalar duplicates are omitted; exact complete frames at the same
microsecond remain supported.

The SQL query scans and groups matching history within DuckDB's configured resource limits.
The result transfer is bounded; database work is still proportional to the requested history.
Cancellation is checked before the query and during result consumption; this pass does not prove
immediate interruption of a native JDBC statement already executing. No wall-time speedup,
rendered UI, robot-loop benchmark, hardware/HIL, remote CI, library release, push or merge is claimed.
Broader live pose/vision validation, UI loading lifecycle and other database methods remain separate
review boundaries.
