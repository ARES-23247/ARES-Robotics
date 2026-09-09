# Alert occurrence and transition audit

Pass 29 audits scalar/composite occurrence transitions, acknowledgment, peak arithmetic,
chronology and redundant persistence. The alert engine remains partially reviewed: moving
windows, signal domains/units, source epochs, configuration and failure recovery are separate
remaining scopes. No robot control or shared-library change is involved.

## Findings and changes

- Active lookup excluded acknowledged records. An acknowledged fault therefore could never
  resolve, and another violating sample created a duplicate active record. Active lookup now
  includes acknowledged occurrences, which continue collecting peaks and resolve on healthy
  evidence. Acknowledgment never substitutes for resolution.
- A recurring fault reopened the old record without updating its trigger timestamp, destroyed
  the previous resolution/duration and could retain the wrong minimum peak. Every recurrence
  now creates a new ID and source-time interval, preserving the closed historical occurrence.
  New occurrences require their own acknowledgment. Clearing still removes only records that
  are both acknowledged and resolved; active or unacknowledged evidence remains.
- Scalar and composite paths had divergent duplicated transitions. The composite path did
  not update active peaks; the scalar path selected maxima whenever a rule had an upper bound,
  even for a larger lower-bound excursion. A shared transition defines peak as the greatest
  absolute excursion past the applicable bound. Equal excursions retain earlier evidence.
  Finite subtraction overflow is disambiguated by scaling only tied-infinity distances;
  ordinary/subnormal comparisons keep their precision.
- NaN and infinity could falsely resolve or contaminate an alert. Text telemetry carries a
  numeric placeholder, which could also be mistaken for threshold evidence. The collector
  excludes nonfinite and textual samples before caching/evaluation. Per-session/rule source
  millisecond high-water marks reject older transitions, including after healthy samples that
  created no alert. Duration uses ordered nonnegative source milliseconds.
- Unchanged scalar peaks repeatedly wrote identical records to DuckDB. Shared transitions and
  atomic commit now return no outcome for unchanged state; repeated acknowledgment is also a
  no-op. Transition/persistence and acknowledgment/clear operations share a coroutine mutex
  to preserve their ordering across suspending database writes.
- The old CAS helper retained an outcome from a failed attempt when a retry became a no-op.
  It could therefore persist or beep for a transition that did not commit. The extracted CAS
  loop returns only the successful attempt. Deterministic interference tests cover a retry
  becoming a no-op and preservation of unrelated concurrent records.

## Validation

The initial 12-method baseline reported ten failures in
`ARESLib-Kotlin/build/audit-pass29-before.log/xml`. Nine were reproduced behavioral defects;
one was an invalid test fixture attempting unsupported source timestamps. The timestamp
fixture was corrected to the actual telemetry domain, 0 through MAX_SUPPORTED_TIMESTAMP_MS.
The first focused run then passed 32 of 33 methods, with only that fixture still invalid
(`audit-pass29-focused.log`). The initial nonfinite sequence also had an insufficient final-
state-only assertion: it is now checked after every invalid sample, so a later event cannot
hide a transient false resolution.

The final additions comprise 14 lifecycle methods and eight shared-transition methods.
They cover acknowledgment/resolve/clear, recurrence history and duration, invalid/text input,
older samples, inclusive bounds, worsening composite peaks, unchanged-write suppression,
CAS retries, overflowing/subnormal peak differences and two-sided ties. An actual DuckDB
fixture checks separately persisted recurring intervals, peaks and acknowledgment. Tests use
a controlled dispatcher instead of subscription sleeps; the production default is unchanged.

Final focused validation passed all 35 selected methods in 1m 36s
(`ARESLib-Kotlin/build/audit-pass29-focused-final.log`). The unchanged-peak path then received
one allocation optimization: it returns before copying an unchanged record. The full Studio
gate validated that final production source in 3m 57s (`audit-pass29-studio.log`):

- 1,584 ordinary methods passed, six opt-in methods skipped, and 56 dashboard smoke methods
  plus the performance-baseline method passed. Kover verification, release alignment and
  production Kotlin size checks passed.
- Shared transitions covered 33/33 executable lines and 39/44 branches. Engine source covered
  175/189 lines and 124/184 branches, including existing composite tests. Input/default
  alternatives, lifecycle/error paths and remaining composite/configuration branches are not
  universally exercised. Engine line execution does not establish full engine review.
  Snapshot: `ARESLib-Kotlin/build/audit-pass29-kover.xml`.
- Dashboard load was 15.7354 ms, scrub p95 24.4547 ms and rapid-seek burst 4.5461 ms.
  The fixture persisted/restored 12,000 frames without drops. These are desktop fixture
  measurements, not an alert-engine throughput benchmark or physical loop timing.
  Snapshot: `ARESLib-Kotlin/build/audit-pass29-dashboard-smoke.json`.
- The unchanged isolated candidate `17.0.3-rc.b81c0156add9` was consumed from the local release
  repository. No library or robot consumer rebuild was needed for these Studio-only changes.
- Repository policy passed (`ARESLib-Kotlin/build/audit-pass29-policy.log`), including shared
  guidance and local links in 190 current documents.

The staged inventory contains 2,443 files: 141 fully reviewed, 55 partially reviewed and
2,247 pending, with no stale or orphaned records. Review accounting and executed coverage
remain separate; the full monorepo goal is still active.

## Limits and next work

The high-water marks compare transition milliseconds, not original source microseconds or
sampleOrder. Equal-millisecond frames are still evaluated in arrival order. Raw composite
caches/windows are not ordered or made fresh by these transition guards, and target restarts
that reuse a session identity need explicit epoch handling. Session/rule cache retention,
alert-history growth and indexed active lookup still need review. No global memory bound or
measured throughput improvement is claimed in this pass.

Configuration resets remain synchronous and separate from suspending persistence. Database
failure/cancellation can still occur after an in-memory transition; retry, collector restart
and persistence-failure recovery require a dedicated lifecycle pass. Platform configuration,
composite threshold ownership, current/loop windows, CAN units and cross-signal freshness
remain open. Existing older engine tests also need resource-cleanup review. This is desktop
unit/database evidence, not live audio, visible Studio or physical hardware validation.
Prior intermittent and opt-in checks remain open. All work remains local.
