# Advanced analytics math, evidence and query audit

Pass 240 reviews previously pending Studio advanced analytics calculations, signal loading,
recommendations and their display labels. The preceding clean commit is
`c24a4148e2aa430747744d8068d93802cb356ad7`. Studio source tree `0ad19593e853cb62cdfd00087b9f8cd51df48970`
uses unchanged local ARESLib candidate `17.0.42-rc.526a048d8dfb` and library tree
`526a048d8dfb1092e89be95c8e906e9fbc516027`.

## Findings and fixes

1. **Pearson correlation underflowed or overflowed on finite data.** The previous unscaled
   second moments and variance product lost tiny correlations and produced NaN for large
   signals. Independently centering/scaling both signals before online covariance accumulation
   preserves correlation while keeping intermediate arithmetic bounded. Constant signals
   remain unavailable. Summary means now normalize positive finite values before averaging,
   including equal maximum-finite values. Invalid fractions, negative metrics and ambiguous
   zero metrics cannot create a comparison or apparent 100% improvement.
2. **Sample alignment inflated and mixed evidence.** A sparse right-hand sample could be
   reused for many left samples. Text placeholders and nonfinite values counted as numeric
   observations; millisecond rounding joined different source times. Each queried signal is
   filtered to its session/topic, sorted once by source microseconds/order and deduplicated
   with the last ordered update winning. Invalid latest values suppress older numeric data
   at the same timestamp, and only finite numeric observations remain. Correlations
   use greedy nearest unused pairs, earlier ties and a 100 ms bound. Pose/input vector
   components require equal source microseconds and a complete matching topic family.
3. **Driver smoothness ignored direction and depended on recording rate.** Alternating
   full-speed opposite commands previously appeared perfectly smooth. Smoothness now measures
   normalized vector variation per observed second against a fixed 50 ms reference. It requires
   at least nine adjacent intervals of 1 microsecond through 250 ms. Invalid/out-of-range axes
   are excluded. Activity replaces the misleading displayed term "decisiveness"; the weighted
   command-pattern score is explicitly heuristic, not a measure of driver skill.
4. **Heatmap speed and occupancy labels overstated the data.** The first observation added
   a fictitious zero-speed interval; averaging by visits biased speed low, and integer
   milliseconds erased short intervals. Cell speed now equals total observed chord distance
   divided by total elapsed time for segments ending in that cell. It is null without an
   interval. Components cannot mix incomplete pose families; packed simulator estimator
   components 3/4 take priority, without substituting truth. Out-of-range cell indexes are
   skipped. Canvas spans/offsets widen integer subtraction before arithmetic. Labels describe
   retained sample counts rather than dwell time.
5. **Evidence counts and confidence were fabricated from metric magnitude.** A 0.4 m summary
   error became 400 samples. Summary suggestions now report one summary statistic and state
   that raw sample count is unavailable. Unrelated baseline counts no longer increase support
   for current-run command/electrical advice. UI and Markdown show a bounded evidence score,
   not a percentage probability. Nonfinite summary values cannot trigger warnings or advice.
   Electrical advice describes an association and asks for inspection rather than inferring
   a cause or automatically changing gains/current limits.
6. **Topic suffixes confused units.** Any `/Voltage` could become battery voltage, and a
   tuning parameter ending `/Current` could become motor amperage. Electrical correlation now
   selects catalog battery topics and the supported `Hardware/Motors/<name>/Current[Amps]`
   or `Drive/MotorCurrent_<name>` families, paired with their corresponding velocity aliases.
   Leading slashes normalize consistently. Arbitrary current/voltage suffixes are insufficient.
7. **Safe analysis swallowed cancellation and repeated repository work.** Recent-summary
   lookup errors escaped its typed outcome boundary, while cancellation became a normal failure.
   All reads now share the same boundary and propagate cancellation. Report work executes on
   the default worker dispatcher. Range and current summary are fetched once; recent baseline
   objects are reused. Missing telemetry and disabled baselines avoid unnecessary reads.
   Each selected series is fetched/prepared once, with the 5,000-point limit checked before
   numerical work and at most 16 motor topics selected deterministically.

All 17 original regression methods failed against the preceding implementation with no test
errors or skips; the failing XML is preserved. Additional tests cover signed numeric extremes,
large offsets, constant signals, sorting/identity/text handling, nearest ties/skew, time-weighted
speed, singleton/negative/oversized cells, estimator priority, invalid command ranges, missing
telemetry, later cancellation, canonical motor aliases, support labels and point-limit failures.
The four existing service tests retain real, owned DuckDB roundtrips and workspace isolation.

## Efficiency evidence

The alignment scan uses a monotonic cursor and no per-left candidate list; paired values are
not copied into another numeric-pair list. A counted 5,000-pair test bounds right-input access
to eight reads per pair. Signal preparation sorts once per selected series; subsequent users
share the prepared snapshot. Twenty available motors produce 16 analyzed motors and 17 total
series queries, including exactly one battery query. Recent analysis verifies one range read,
one current-summary read and one summary-list read without refetching selected baseline rows.
These are operation-count checks, not robot-loop or wall-clock performance benchmarks.

## Validation

The focused run passed 34 tests: 30 new audit methods and four existing service tests.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,172 | 6 |

Full Studio validation has 2,221 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged build dependencies include up-to-date/cache
evidence. Focused tests are not counted twice. All 410 unchanged library candidate files
were rehashed; prior library and robot consumer validation is retained. Monorepo policy
passed, including 402 current-document link checks and 38 historical skips.

## Coverage and remaining limits

The ledger accounts for 3,048 tracked files: 1,371 reviewed, 193 partially reviewed and
1,484 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

The service, card and summary producer retain partial review status. Upstream summaries still
lack per-metric presence and sample-count metadata, use default zero values, and the summary
producer can even substitute 12 V for missing battery data. This pass conservatively omits
ambiguous zeros in comparisons; genuine zero measurements are also omitted until presence can
be distinguished. It cannot recover upstream fabricated finite defaults or misclassified SQL
aggregates. A follow-up must trace summary generation/storage/consumers, including broad motor
current selectors in SummaryEngineService and RunComparisonService. Only the relevant initial
aggregation/metric-default portion of SummaryEngineService was reviewed here.

Correlations describe at most 5,000 retained samples per signal and can be biased by downsampling,
autocorrelation, unequal rates and greedy asymmetric pairing. Nearby source times do not prove
atomic hardware capture. Exact vector matching can deliberately produce no score/map when
independently downsampled axes lack matching times. Activity/consistency remain sample-weighted
heuristics; they are not calibrated measures of skill or task success. Heatmap chord speeds do
not reconstruct unobserved motion or allocate a crossing segment among intermediate cells.
Summary selection and telemetry reads are not one transactional recording snapshot. Completed
recordings are the appropriate input; repository-wide query scalability remains separate work.

The card's label/arithmetic changes compile and are included in Studio tests; no rendered window,
export-dialog interaction or UI lifecycle validation was performed. No physical robot/HIL,
real-time loop benchmark, library/version/archive mutation, remote CI, push, merge, release or
deployment occurred. Prior library/robot consumer evidence is retained because those bytes are
unchanged. Machine-local evidence is under `ARESLib-Kotlin/build/audit-pass240-verified-evidence/`.
The overall monorepo audit remains active.
