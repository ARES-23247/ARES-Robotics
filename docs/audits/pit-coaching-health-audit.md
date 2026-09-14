# Raw pit coaching health and query audit

Pass 244 follows clean commit `87109796b5d2559c8f34bfcbf433ed1c98cfa7f2`.
It reviews DiagnosticCoachService health screening and the evidence loading needed by
its health and previously reviewed localization algorithms. Studio tree `46c50fb539017b35a6002b96e6c401064eac389c`
uses unchanged ARESLib candidate `17.0.42-rc.526a048d8dfb`, source tree
`526a048d8dfb1092e89be95c8e906e9fbc516027`.

## Confirmed problems

The initial 19 regression tests produced 18 failures against the preceding implementation;
one invalid-value case already passed. Confirmed defects included:

- The first high-current sample after a long gap was discarded as a possible interval start.
  Invalid readings could be filtered out before continuity analysis, joining unsupported spans.
  An isolated peak elsewhere in the motor recording was attached to a qualifying interval.
- Current aliases competed, nested configuration paths were accepted as current measurements,
  one published drive alias was missed, and only the first qualifying motor was reported.
- Superseded source-time updates, numeric text placeholders and negative voltages could create
  battery, current, loop or guard findings. Invalid latest updates could revive older evidence.
- The initial cumulative guard counter and reset sequences were treated as activity during the
  recording. Generic guard activity and partial power scaling were labeled urgent brownouts.
- Statements that current stayed high implied continuous measurement. A reciprocal of the peak
  recorded loop period did not measure the recording's aggregate or current control frequency.
- Raw topic discovery and per-topic loading materialized entire relevant streams in JVM memory,
  then repeatedly filtered them. Loading every generated diagnostic also included unrelated SysId
  and motor results solely to obtain a handful of localization metrics.

The ARESNetworkStatePublisher and BrownoutGuard producer contracts were traced read-only.
LoopTimeMs is a recorded loop-period observation. Guard tripCount increases on worsening warning
or critical transitions, including responses to invalid voltage, and reset restores zero. These
are ARES consumer interpretation defects; this pass establishes no WPILib or CTRE defect.

## Corrected evidence selection

DiagnosticCoachSnapshotReader uses one parameterized raw query and one parameterized generated
diagnostic query. Raw queries route to the in-memory store for the exact live-telemetry session,
and to the persistent store otherwise. Generated diagnostics retain their persistent-store
ownership. The coordinator caches both read facades and shares its existing read mutex and
metrics; it does not allocate a repository or introduce another lock per query.

Exact topic matching strips leading slashes while preserving case-sensitive device identity.
Shared battery and loop aliases retain explicit catalog priority. Guard aliases prefer
Diagnostics/Power/BrownoutCount over Robot/BrownoutCount. Motor current priority is
Hardware/Motors/device/CurrentAmps, Current, Amps; Drive/MotorCurrent_device; then the legacy
Drive/MotorName/CurrentAmps, Current, Amps forms. Each device segment is nonempty and contains
no slash. No guessed equivalence between differently named devices is introduced.

The preferred source is chosen by presence. Its latest ordered update at each source microsecond
wins before finite, textual and range validation. An invalid preferred source does not borrow
values from a legacy alias. Invalid current and counter updates remain in adjacency calculations.

For each motor, a candidate current span requires valid samples at or above 40 A, consecutive
gaps no greater than 200,000 microseconds, and a duration of at least 500,000 microseconds.
A low, invalid or excessively separated sample breaks continuity; a high sample after a break
starts a new candidate. The earliest qualifying span supplies its own timestamp, count and peak.
Every qualifying motor receives a finding within the explicit result limit. Interpolation between
samples is not asserted. Thresholds remain generic screening thresholds, not mechanism limits.

Guard activity sums only positive adjacent valid counter increments. Initial baselines, resets
and comparisons crossing invalid values do not contribute. Counter values must be nonnegative
exact integers within Double's safe integer range. Counter differences and timestamp subtraction
use HUGEINT intermediates. A zero power scale is urgent guard-requested neutralization; positive
partial scaling or counter activity prompts review. Percentage display preserves tiny reductions
and does not round a positive scale to zero. No physical brownout is inferred from guard activity.

Battery and loop extrema use valid selected observations. Recorded zero voltage remains a
screening observation whose validity needs checking; it is not silently treated as a healthy
battery. Invalid or absent battery/current/loop signals remain listed as missing.

The generated query selects only six recognized localization metrics and the presence of their
two diagnostic families. Family presence supersedes old raw diagnostics even when the generated
family contains only other keys or invalid results. Unavailable values preserve the finite-value
AnalysisDiagnostic contract with an explicit text marker, and never become measured zeros.
Pass-242 localization threshold and observation semantics remain intact.

## Resource bounds and limitations

The raw query returns at most 257 rows so the reader can reject evidence exceeding its 256-row
budget. The generated query similarly detects more than eight rows. Raw motor labels are clipped
to the cell limit plus one inside SQL, then rejected if oversized. Unknown/duplicate identities,
malformed intervals, invalid numeric results, missing family provenance and truncation fail
explicitly; the UI's existing error path does not silently display a partial checklist.

The fixture with 12,002 raw observations and 2,001 generated diagnostics returns four rows
across two queries, including the correct loop peak and generated NIS. This measures query/result
counts, not elapsed-time acceleration. SQL scans and window state still scale with recording size.
DuckDB documents that window functions buffer their inputs; LAG adjacency and an explicit ROWS
frame are used here, with invalid rows retained to break the evidence chain.
See the [DuckDB window-function contract](https://duckdb.org/docs/current/sql/functions/window_functions.html).

Raw and generated reads remain separate snapshots; live retention and concurrent acquisition can
limit available evidence. There is no recording-wide transaction or generation epoch. The first
qualifying span is reported per motor, not every later interval. Unobserved resets, dropped samples,
repeated publications and missing source provenance cannot be reconstructed. Generic thresholds
and recorded samples do not establish physical safety, root cause or actual robot-loop performance.
An absent generated family cannot distinguish never-computed analysis from an empty calculation.

## Validation and coverage

Focused validation passed all 80 tests: 36 new health/query audit methods, ten coach
integration tests, 30 localization tests and four guided-analysis tests. The original 19
baseline cases now pass; 18 failed before the fixes. Owned temporary databases are closed
and removed by the fixtures. The bounded-query observation above passed in focused and full runs.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,290 | 6 |

Full Studio validation has 2,339 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged shared/gateway tasks and dependencies include
up-to-date evidence. Focused tests are not counted twice. All 410 unchanged library candidate
files were rehashed; prior library and robot consumer validation remains applicable.
Monorepo policy passed, including 406 current-document links and 38 historical skips.

The ledger accounts for 3,063 tracked files: 1,389 reviewed, 194 partially reviewed and
1,480 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

DiagnosticCoachService, the two new helpers and their tests are reviewed within these contracts.
DatabaseService retains its partial status for unrelated facade and lifecycle behavior; the
transaction coordinator and localization helper retain earlier review evidence with the new
routing and bounded-input integration checked here. Remaining independent work includes SysId
units/polarity/voltage assumptions, gravity and traction, secondary sampling, generated-tag
ownership, and other Run History dictionary rows.

No rendered-window, physical robot/HIL, robot-loop benchmark or remote CI result is claimed.
Library/version/archive changes, push, merge, deployment and release are outside this pass.
Machine-local XML, logs, candidate hashes and source identity are preserved under
`ARESLib-Kotlin/build/audit-pass244-verified-evidence/`. The overall audit remains active.
