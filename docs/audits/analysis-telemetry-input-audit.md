# Analysis telemetry input audit (pass 246)

## Scope and identity

This pass follows the recorded SysId alignment audit upstream into telemetry selection,
materialization and independent diagnostic persistence. It covers the new typed analysis
repository, its DatabaseService facade, automatic summary integration and explicit motor fitting.
The source base is `86b7b2895ac9282ae1122220d38cbc399d217d8a` on the local
`codex/robot-loop-math-audit` branch. Verified Studio source tree: `22e14efde2452238a9dae6453463da82e85b672e`.
The unchanged library tree is `526a048d8dfb1092e89be95c8e906e9fbc516027`, using existing
candidate `17.0.42-rc.526a048d8dfb` and its local validation repository.

## Confirmed defects and corrections

All nine original integration regressions failed against the unchanged production base:

| Boundary | Observed failure | Correction |
| --- | --- | --- |
| NIS and pose statistics | 3,001 observations lost roughly half their inputs through per-topic sampling | Read all latest source observations within an explicit group budget |
| Path tracking | A one-sample 0.5 m peak disappeared | No sampling before maximum/RMS/count calculations |
| Invalid updates | Sampling retained old numeric NIS while dropping its invalid replacement, and removed a velocity barrier before differentiation | Resolve latest sample order at each source microsecond before budgeting; retain nonnumeric barriers |
| SysId fitting | Fit rows were reduced by the loader independently of the alignment algorithm | Feed the shared alignment helper complete selected channels |
| Global truncation | 104,000 irrelevant motor configuration rows evicted a later NIS reading | Select exact relevant keys and complete motor-topic shapes; budget each analysis family independently |
| Analysis failures | A solver or driver-analysis exception prevented health and localization from being saved and left old fits in place | Assemble each family independently, retain successful families, replace stale generated results, persist explicit failure status |

The prior viewport/filter API deliberately downsamples for display; its other callers retain
that contract. Automatic summary analysis no longer uses it. The explicit three-channel motor
API now performs one prepared query instead of three full-channel reads, sharing one statement
snapshot and the same complete-input checks as automatic analysis.

## Bounded input contract

`AnalysisTelemetryRepository` runs through the existing shared coordinator, read lock, metrics
and persistent/live connection selection. A single prepared statement selects all requested
groups, deduplicates topic/source-microsecond updates by descending sample order, counts them
and returns either a complete group or only its availability metadata. Overlapping rules do
not multiply observations. Topic matching preserves case and exact motor identity; session
IDs, keys, regular expressions and limits are bound values.

Each group permits at most 100,000 latest rows, 256 topics and a 16 MiB projected transfer
budget (`64 + 4 * Unicode code-point length` bytes per row). These conservative bounds cover
row and key growth; they are not a precise JVM heap estimate. Raw string payloads are never
projected into result frames: a constant nonnumeric marker preserves their invalidity. SQL
execution still operates within the database's existing native memory/thread configuration.
No partial prefix is returned for an oversized family, and smaller groups remain usable.

SysId, EKF and path inputs have separate budgets. `InputSourceRows` records selected latest
updates before numeric validity filtering; it is not a valid-fit or independent-camera count.
`InputStatus` records `complete`, `empty`, `row_limit`, `topic_limit`, `byte_limit`, `read_failed`
or `analysis_failed`. Complete input does not promise that alignment, excitation or statistical
quality allows a numerical result. Explicit motor fitting throws on an exceeded budget rather
than returning gains fitted to a prefix. Existing insufficient-fit output remains unchanged.

Empty/unavailable generated localization families suppress obsolete raw aggregate fallback.
Failures in one analysis do not discard independent health or successful numerical families.
Coroutine cancellation propagates and does not publish a partial replacement. Diagnostic
replacement errors also propagate. Summary, health, driver reads, generated diagnostics, tags
and final summary still do not form one recording-wide transaction.

## Validation

Focused validation passed 139 tests across eight suites, including 25 new integration and
typed-repository methods. All nine preserved baseline failures now pass. The tests verify full
3,001-observation statistics and fits, a narrow path peak, invalid-update/derivative barriers,
104,000 irrelevant motor records, whole-family refusal at 100,002 SysId rows, independent
smaller-group retention, resource boundaries, failure/cancellation handling and one-query
explicit motor fitting. The real explicit API recovers synthetic kS=0.4, kV=1.6 and kA=0.32.
The first focused run had a fixture-only coroutine forwarding error; its corrected rerun passes.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,355 | 6 |

Full Studio validation has 2,404 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged shared/gateway tasks and dependencies include
up-to-date evidence. Focused tests are not counted twice. All 410 unchanged library candidate
files were rehashed; prior library and robot consumer validation remains applicable.
Monorepo policy passed, including 408 current-document links and 38 historical skips.
This is desktop/numerical evidence and measured query-count reduction, not a wall-clock or
physical robot-loop benchmark.

## Coverage and remaining work

The ledger accounts for 3,072 tracked files: 1,398 reviewed, 195 partially reviewed and
1,479 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

The new repository and its typed input/limit contracts are reviewed within the bounded
statement-snapshot contract. SummaryEngineService and DatabaseService retain partial status
outside the changed integration. No driver recommendation semantics, whole-recording generation
epoch, generated-tag ownership, multi-operation atomic persistence or continuous source-write
snapshot guarantee is claimed. Numeric solver loops remain synchronous; cooperative cancellation
during a single solver call and immediate cancellation of an executing JDBC statement are
outside this pass. The materialized SQL input can still be expensive for very long recordings;
explicit input limits prevent incomplete outputs, not a fixed execution-time guarantee.

The direct plotting/filter API and remaining comparison/dashboard consumers still require their
own interpretation audit. The next independent boundary is driver-control analysis: source
selection, irregular sample timing, filter/FFT assumptions and recommendation units. Broader
run-history/current rows also remain partial. Existing native units and signed-voltage requirements
from pass 245 remain in force; no new robot publisher or automatic hardware tuning was added.

No rendered Studio window, physical robot/HIL, robot-loop timing or remote CI result is claimed.
No library/version/archive change, push, merge, deployment or release occurred. Machine-local
baseline/focused/full XML, logs, source identity and candidate hashes are under
`ARESLib-Kotlin/build/audit-pass246-verified-evidence/`. The full monorepo goal remains active.
