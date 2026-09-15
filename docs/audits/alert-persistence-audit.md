# Alert persistence and shutdown audit

Pass 33 reviews storage failure isolation, outstanding alert updates, retry work and shutdown
ordering in Studio's diagnostic engine. It does not change robot control or actuator safety.

## Findings and changes

- A database exception propagated out of the alert collector after its in-memory transition
  committed, cancelling subsequent telemetry evaluation and the target-epoch listener. The
  same unchanged alert then had no transition to trigger a retry. A separate single writer
  now retains unsaved records and retries independently of new telemetry.
- Database IO held the diagnostic transition mutex. Slow storage delayed resolution, new
  faults, target changes and acknowledgment. Transitions now enqueue a snapshot immediately;
  one coroutine serializes storage outside that mutex.
- A failed triage write left the UI acknowledged without a retry path. Acknowledgment and
  resolution update the retained occurrence's latest snapshot, and successful older writes
  cannot remove a newer snapshot that arrived during IO.
- Each unsaved occurrence retains its initial record and latest state. Intermediate peaks
  coalesce, while the opening record is written before its resolution. This preserves the
  existing opened/resolved integration-event boundary and record ID. Database alert storage
  uses INSERT OR REPLACE, so an uncertain write can be repeated under the same alert ID.
- A conflated wake signal does not queue one message per update. Failures back off from
  250 ms to at most 30 seconds, and new telemetry cannot bypass that delay. Failed entries
  rotate to the end so a rejected occurrence cannot starve unrelated records. Successful IO
  resets the backoff. Cancellation remains cancellation and unsaved counts stay truthful.
- The alert panel exposes failed/stopped saving states. Its empty message says no alerts were
  observed; an empty list cannot establish that every robot system is nominal.
- Normal service disposal stops evaluation, drains accepted updates, and joins the writer
  before DuckDB closes. A failed five-second drain retains its queue and retry worker and
  returns failure; the service registry leaves DuckDB open and reports failed shutdown. A
  later drain can succeed. Pause stops evaluation while persistence continues. Terminally
  disposed engines cannot restart, and immediate cancellation preserves unsaved status.

Pending storage is bounded to two record snapshots per unsaved occurrence and one wake signal.
It is not globally bounded across an indefinitely long storage outage and arbitrarily many
occurrences. Unlike the old per-transition IO, repeated peak updates during slow/failing storage
do not create an unbounded sequence of duplicate writes for the same occurrence.

## Validation

The three-method baseline reproduced all three failures in 26s after a fixture compile fix:
collector termination, missing unchanged-write retry and failed acknowledgment persistence.
Evidence: `ARESLib-Kotlin/build/audit-pass33-before.log` and
`ARESLib-Kotlin/build/audit-pass33-before-xml`.

The first focused selection passed 79 methods in 1m 33s. Further integration checks cover suspended
storage, target-reset history, pause/restart and normal/failed disposal. Writer tests exercise
coalescing before/during IO, chronological opening/resolution, autonomous retry, fair rotation,
retry timing/caps, cancellation, idle wakeup and retained shutdown failure. Headless alert-panel
renders cover saving, retrying, stopped and saved states.

The final focused selection passed all 21 added methods in 1m 29s
(`ARESLib-Kotlin/build/audit-pass33-focused-final.log`). Eight are engine integration cases,
12 exercise the writer directly and one produces four headless panel snapshots. All four
PNGs under `ARES-Analytics/app/build/diagnostics/alert-persistence-audit` were inspected;
retrying/stopped warnings are legible, while ordinary saving/saved states omit failure text.

The final Studio gate passed in 2m 24s (`ARESLib-Kotlin/build/audit-pass33-studio.log`):

- 1,671 ordinary methods passed, six existing opt-in methods skipped, plus 56 dashboard smoke
  methods and the performance-baseline method passed. Kover verification, release alignment
  and production Kotlin file-size checks passed.
- Writer source covered 60/60 executable lines and 30/34 branches. Engine covered 206/220 lines
  and 123/178 branches; the panel covered 55/125 lines and 13/48 branches. ServiceRegistry
  covered only 38/277 lines and 0/66 branches: its shutdown call/close guard was reviewed and
  compiled, but the full registry shutdown was not executed. These three files remain partial.
  Snapshot: `ARESLib-Kotlin/build/audit-pass33-kover.xml`.
- Final new-test XML is retained under `ARESLib-Kotlin/build/audit-pass33-final-xml`.
  Coalescing 1,000 pending peak updates around suspended IO produced only the initial and final
  writes. Slow-write engine resolution completed before the storage gate was released.
- Dashboard load was 19.7378 ms, scrub p95 22.1212 ms and rapid-seek burst 5.2955 ms. The fixture
  persisted/restored 12,000 frames without drops; these are desktop fixture measurements.
  Snapshot: `ARESLib-Kotlin/build/audit-pass33-dashboard-smoke.json`.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9`. Library and robot
  consumers were not rebuilt for these Studio-only changes. Only the immediate-disposal KDoc
  was clarified after the test gate; executable source remained unchanged.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass33-policy.log`), including shared
guidance and local links in 194 current documents. Inventory: 2,462 tracked files, 160 fully
reviewed, 61 partially reviewed and 2,241 pending, with no stale or orphaned records. Test
execution, line/branch coverage and file review remain separate accounting dimensions.

## Limits and next work

The queue is in process memory. Immediate emergency disposal, a process crash, power loss or
the application's outer forced-shutdown watchdog can still lose unsaved records; this pass
adds no disk spool and claims no crash durability. The five-second drain bounds its waiting
period but cannot force non-cooperative JDBC IO to terminate. Global occurrence retention,
whole-application shutdown policy and external integration delivery require separate review.

The engine, alert panel and service registry remain partially reviewed. Other composite rule
configuration, CAN/vision domains, upstream connection ownership and remaining replay/store
behavior stay open. Existing intermittent and opt-in tests also remain open. Headless rendering
is not evidence of a visible Studio window or live audio, and no physical hardware was tested.
All changes stay local and the full monorepo audit goal remains active.
