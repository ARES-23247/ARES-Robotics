# Replay window and seek audit - pass 21

This pass audits replay window loading, asynchronous seek completion and cache reuse. It
extracts and fully reviews the database window source and pagination helper. The surrounding
ReplayEngineService remains partially reviewed: elapsed-time arithmetic, playback/looping,
initial-load cancellation and terminal lifecycle behavior still need dedicated passes.
Library identity and robot consumers are unchanged.

## Findings and changes

- Every playback tick or nearby seek outside the active cache previously cancelled and
  recreated its pending read. A pending window now serves later targets inside the same
  bounds and commits the latest playhead after loading. Controlled seeks to 16, 17 and
  18 seconds require one foreground source read.
- A seek into an in-flight forward prefetch previously started duplicate foreground work.
  Required seeks now await the existing deferred read. Completed prefetch hits are also
  reused. Optional prefetch failures do not invalidate the active snapshot; a later required
  read can retry independently.
- Returning to the active cache or a completed prefetch did not invalidate a different
  outstanding foreground request. Both paths now cancel and advance its request identity.
  Prefetch publication also checks an independent request identity, preventing an obsolete
  request from being accepted when newer work happens to use identical bounds.
- A required read failure previously escaped its coroutine and could leave seeking true.
  Current failures now pause playback, expose an error and end the pending seek without
  claiming a new frame. An explicit successful seek clears the error and restores readiness.
- Seeking completion was published before the new frame. It now follows the frame, playhead,
  progress and cache metadata. A synchronous completion observer regression proves that it
  sees the requested snapshot rather than the previously committed frame.
- Baseline state and maps were reset twice when installing a window. Reconstruction now
  initializes them once. Cache metric publication checks unchanged scalar fields before
  allocating another metric object on each playback commit. End-of-recording prefetch
  invalidation also updates the public metrics immediately.
- Disposal now cancels and joins the owned service job, including superseded readers still
  unwinding non-interruptible work. Joining only the latest retained job references could
  return while older owned work was still active. This is a join guarantee, not a promise
  that JDBC queries can be interrupted instantly.
- The legacy lifecycle fixture failed to close its replay engine and database before
  deleting the database file. It now cleans up owned resources in finally. Its playback
  assertion also uses one controlled clock/dispatcher and checks an exact 40 ms advance,
  replacing a virtual delay that did not control the engine's real clock.

The database source preserves strict-before baseline semantics and inclusive window bounds.
It loads all pages in database microsecond/sample-order sequence, including multiple rows
at one millisecond. The 50,000-row page size is a transfer chunk, not a retained-frame limit;
time windows are bounded, but memory still depends on recorded topic density.

## Validation

Fourteen new methods cover coalesced reads, in-flight and completed prefetch reuse, stale
foreground/same-bound prefetch rejection, failure/retry, optional failure, completion ordering,
disposal of superseded jobs and cache metrics; plus real database baseline/paging, empty
windows, duplicate ordering and nonpositive page sizes. Existing golden replay, cache/clock
and lifecycle tests also run.

The dashboard benchmark now verifies READY state and the exact committed playhead after
each timed seek. A fast failed or incomplete seek cannot masquerade as a successful latency
sample. Timing assertions are outside the measured interval. Existing performance thresholds
are unchanged. The pass 20 105.8996 ms replay-scrub failure remains unproven; these confirmed
request defects do not by themselves establish its cause.

All **30 focused replay methods passed**. The final complete Studio gate passed in
2m 46s: **1,406 tests passing and six opt-in skips** (app: 1,371 passing; shared: 17;
gateway: 18), plus **56 dashboard smoke tests** and **one performance-baseline test**.
App tests executed; shared/gateway reused unchanged results. Coverage verification, release
alignment and the production Kotlin size ratchet passed. Monorepo policy, shared guidance
and links in 182 current documents passed (`ARESLib-Kotlin/build/audit-pass21-policy.log`).

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| ReplayWindowSource | 23/23 | 8/8 |
| ReplayEngineService | 400/421 | 158/236 |

The measured replay load was **17.9093 ms**, scrub p95 **18.4098 ms**, and rapid-seek burst
**4.0583 ms**. The unchanged scrub limit is 100 ms. The controlled tests establish avoided
source reads and correct ordering; this one wall-clock sample does not establish a hardware
speedup or explain the pass 20 105.8996 ms failure.

Validation used the unchanged local library candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. Evidence logs are
`ARESLib-Kotlin/build/audit-pass21-focused-verified.log` and `audit-pass21-studio.log`.
The measured dashboard report is preserved as `audit-pass21-dashboard-smoke.json`.
Kover XML is `ARES-Analytics/app/build/reports/kover/report.xml`. Robot/library suites were
not rerun for these Studio-only changes.

## Remaining scope

ReplayEngineService's clock scaling/overflow, loop endpoints, metadata loading, input domains,
initial-load cancellation and commands after disposal remain open. Database query complexity,
concurrent mutation of recordings, session-wide timestamp retention and denser window memory
budgets also need review. Full dashboard benchmark methodology and non-replay performance
paths remain partially reviewed. The new tests use controlled dispatchers and real local
DuckDB windows; they do not measure physical robot timing or prove every scheduler interleaving.
Prior timing-sensitive failures, opt-in tests, hardware validation and the repository-wide
coverage goal remain open. All changes remain local.
