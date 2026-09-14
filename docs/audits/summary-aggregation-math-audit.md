# Summary aggregation math and source selection audit

Pass 241 follows clean commit `3197ff8c541f353111f9ccd22951c725fc2afdc1` and reviews
the core summary aggregation boundary identified in pass 240. Studio source tree
`0d907543d16aa2b492a3f5585fe777e487543228` uses unchanged ARESLib candidate `17.0.42-rc.526a048d8dfb`
and library tree `526a048d8dfb1092e89be95c8e906e9fbc516027`.

## Confirmed findings

All 14 original audit tests failed against the preceding implementation, with no test errors
or skips. The preserved baseline demonstrates:

- Missing battery telemetry was reported as a measured 12 V.
- NaN, infinity, negative timings and textual numeric placeholders entered aggregate calculations.
  A nonfinite result then discarded otherwise valid measurements by falling back to zero.
- Broad substring matching treated gain values, current limits, timing/latency budgets and
  cumulative counters as physical telemetry. Differently sampled aliases were mixed together.
- EKF drift used the largest absolute component instead of the norm of odometry minus the
  estimate. The shared publisher emits `Drive/EKF_Drift_X/Y`; 3 m and 4 m components must
  produce 5 m, not 4 m. Components from different source times cannot form that vector.
- Vision acceptance averaged cumulative accepted counts and the periodically repeated
  last-accepted flag as though they were rates. Neither average measures event acceptance.
- Inactive path/camera placeholder values diluted cross-track error and vision latency.
- Resistance estimation ignored canonical robot total current and merged distinct observations
  within each millisecond. Conversely, differently timed channels could fabricate a paired sample.
- Older duplicate-time values could remain evidence after a newer invalid update.
- A notebook cancellation exception was swallowed after the local summary had been saved.

## Implementation and mathematical meaning

`SummaryMetricAggregator` computes all core metrics and recorded OpModes in one parameterized
SQL statement. It selects explicit topic definitions, prefers one source per metric/device for
the run, and retains the latest ordered update at each source microsecond before checking numeric
validity. Leading slashes and case normalize for selection; motor display names retain source
spelling. An invalid preferred source does not fall back to a more convenient alias.

Battery and loop aliases come from the shared metric catalog. Other accepted topics and motor
grammars are explicit in the aggregator. The supported motor families are
`Hardware/Motors/<name>/{CurrentAmps,Current,Amps}`, `Drive/MotorCurrent_<name>` and legacy
`Drive/Motor<name>/{CurrentAmps,Current,Amps}`. Total currents, tuning parameters and limits do
not become device currents. Only `Robot/OpMode` and its `OpMode` alias supply mode tags.

Means and the loop-time p95 scale nonnegative finite observations by their maximum before
aggregation, then restore units. This avoids overflow even for repeated maximum-finite inputs.
The p95 remains an interpolated percentile over every selected valid observation, using
[DuckDB's quantile_cont semantics](https://duckdb.org/docs/current/sql/functions/aggregates#quantile_contx-pos).
It is not a percentile of a downsampled visualization.

Drift components require the same source microsecond. A scaled Euclidean norm avoids overflow
in squared intermediate values. A supported scalar drift/error topic is a legacy fallback when
no complete vector is available. This measures odometry-versus-estimator disagreement, not
estimator error against physical ground truth.

Vision acceptance is the sum of nonnegative accepted-counter increments divided by the sum of
accepted and rejected increments. Counters must be paired at identical source microseconds and
be nonnegative exact integers within the safe Double integer range. The initial counts and reset
intervals are excluded; subsequent monotonic segments still contribute. When no usable counter
increments exist, a valid explicit `Vision/AcceptanceRate` can supply the metric. A sampled
last-accepted Boolean alone provides no event-rate evidence. Explicit rates outside [0,1] are
excluded. Camera/path flags, when present, must equal one at the metric's exact source time.
Absent flags retain legacy compatibility; they do not establish capture/activity provenance.

Apparent battery resistance uses `-deltaV/deltaI` only for consecutive complete paired observations
1 microsecond through 250 ms apart, with opposite-signed changes and `abs(deltaI) > 0.5 A`.
Invalid or incomplete pairs break the sequence. Both canonical robot voltage/total-current and
legacy battery aliases are supported. This is a local finite-difference estimate under roughly
constant open-circuit voltage; dynamic battery effects and measurement noise are not separated.
The interval/noise limits are heuristics, not a calibrated battery identification model.

The internal aggregate map distinguishes absent metrics from observed zero. Persisted
`SessionSummary` still uses legacy nonnullable zero sentinels; missing battery data now uses that
same unavailable convention instead of inventing 12 V. No nonfinite values enter summary JSON.
Existing summaries must be regenerated to use the corrected calculations.

Cancellation propagates from notebook integration and the secondary diagnostics catch boundary.
Ordinary optional notebook failures still preserve the successful local summary. An unused
device-name parser and duplicate test fixture construction were removed. All five existing
summary tests now close and delete their owned database directories even if assertions fail.

## Efficiency and validation

Five core aggregate queries became one statement. Its selected observations use an explicitly
[materialized CTE](https://duckdb.org/docs/current/sql/query_syntax/with#cte-materialization), shared
by scalar statistics, counters, vectors and resistance calculations. Computation remains in
DuckDB; it does not load a whole recording into JVM frame arrays. A test supplies 12,001 loop
observations and verifies one query, two returned aggregate rows, mean 6,000 and p95 11,400.
Cross-session and SQL-like session-ID inputs cannot change the parameterized selection.

Aggregate output is limited to 1,000 rows, with one extra row to detect overflow, and bounded
cells. Excess cardinality, oversized labels, malformed/nonfinite results or truncated output
fail explicitly before persistence rather than creating a partial summary. SQL scans/window
state still scale with the selected recording; these are query/row-count checks, not a
wall-clock speedup or robot-loop benchmark.

The focused run passed 33 tests: 24 new audit methods, five summary integration tests and
four advanced analytics service tests.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,196 | 6 |

Full Studio validation has 2,245 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged build dependencies include up-to-date/cache
evidence. Focused tests are not counted twice. All 410 unchanged library candidate files
were rehashed; prior library and robot consumer validation is retained. Monorepo policy
passed, including 403 current-document link checks and 38 historical skips.

## Coverage and outstanding boundaries

The ledger accounts for 3,051 tracked files: 1,375 reviewed, 193 partially reviewed and
1,483 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

The new aggregator and both summary test files are reviewed with the stated tests. The parent
SummaryEngineService remains partially reviewed: its secondary sampled diagnostics, SysId,
driver/EKF/thermal algorithms and tag recomputation need separate passes. Read-only storage,
publisher and catalog inspection established units, aliases and the query boundary without
claiming complete new coverage of those files. No physical robot, HIL, rendered Studio window,
export interaction or real-time loop timing was validated.

Core metrics share one statement, but secondary diagnostics, summary persistence, tags and
callbacks are not one transactional operation. Use completed recordings. Canonical source
selection may omit samples when a preferred stream is incomplete; exact pairing and optional
activity flags cannot establish atomic hardware capture. Scalar means remain sample-weighted,
and repeated camera latency values are not deduplicated into distinct physical camera frames.
Missing-versus-zero metadata still needs a schema/storage/consumer solution; old finite defaults
remain in already stored summaries until recomputation. No library bytes, versions, archives,
remote CI, push, merge, release or deployment changed.

Machine-local logs, XML, candidate hashes, policy results and source identity are stored in
`ARESLib-Kotlin/build/audit-pass241-verified-evidence/`. The overall monorepo audit remains active.
