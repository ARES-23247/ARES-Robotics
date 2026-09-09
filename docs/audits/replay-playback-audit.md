# Replay playback time and load ownership audit - pass 22

This pass reviews replay elapsed-time arithmetic and command boundaries, initial-load
cancellation and disposal. It extracts the playback arithmetic and public replay models
from ReplayEngineService. Public model names and the default constructor stay compatible.
The engine remains partially reviewed: database metadata, recording mutation and broader
source/UI lifecycle contracts still need work. Library and robot product code is unchanged.

## Findings and changes

- Backward clock readings previously reset the elapsed-time anchor. Recovery could count
  an already consumed interval again. Playback now holds the highest accepted reading and
  ignores duplicate/backward readings until the clock advances beyond it.
- Scaling a large clock interval could saturate a Double-to-Long conversion and overflow
  the subsequent playhead addition. Playback now compares whole elapsed milliseconds with
  the remaining duration before adding. Exceptional spans use exact integer arithmetic
  and binary-Double rate conversion to clamp or compute the loop remainder without overflow.
  Ordinary ticks retain fractional milliseconds using primitive fields. This does not
  expand the database's supported recording timestamp domain.
- A speed change between ticks applied the new speed retroactively to the entire pending
  interval. Speed and loop-policy changes now settle elapsed time under the previous
  settings. Pause also settles its partial tick. Play anchors time at the command, and
  seek/scrub reset that anchor so pre-navigation time cannot leak into the new playhead.
  Resume excludes time spent paused. A generation token rejects obsolete playback jobs.
- Initial session loads ran in the caller's context and were outside service disposal's
  ownership. They are now registered as owned deferred jobs. Supersession cancels obsolete
  loads; caller cancellation clears the current loading state; disposal cancels and joins
  all owned readers. Non-interruptible work may delay completion, but cannot publish a late
  snapshot. Obsolete cancellation cannot clear a newer session.
- A disposed engine previously retained its snapshot and accepted new loading/control
  work despite its cancelled service scope. Disposal now clears session/cache state and
  closes the engine permanently. New session loads fail before IO; valid navigation and
  playback commands cannot revive it. Repeated asynchronous disposal is safe.
- A clock exception during a tick now pauses playback and publishes an error instead of
  leaving the public state PLAYING after its coroutine exits. The clock fixture now uses
  explicit elapsed time rather than advancing time whenever code reads the clock.

## Validation

Twenty-two new methods cover playback arithmetic and engine integration: fractional time,
backward/duplicate clocks, huge scaled and signed clock spans, end/loop boundaries, invalid
inputs, reset, command timing, clock failure, terminal behavior, caller cancellation,
superseding loads and disposal during a noncancellable initial read. The helper tests also
compare 500 deterministic random spans against an independent BigInteger rational oracle.
An allocation test warms the helper before measuring 100,000 ordinary ticks; the rare
large-span fallback deliberately allocates. This measures the arithmetic helper, not the
engine's immutable snapshots or physical robot loop allocations.

All **52 focused replay methods passed**, including the allocation assertion (at most
8 KiB across 100,000 ordinary helper ticks after warmup). The complete Studio gate passed
in **2m 9s**: **1,428 passing tests and six opt-in skips** (app: 1,393 passing; shared: 17;
gateway: 18), plus **56 dashboard smoke tests** and **one performance-baseline test**.
App tests executed; shared/gateway reused unchanged results. Coverage verification,
release alignment and the Kotlin production size ratchet passed. Monorepo policy, shared
agent guidance and local links in 183 current documents passed
(`ARESLib-Kotlin/build/audit-pass22-policy.log`). The refreshed inventory accounts for
2,408 tracked files: 90 fully reviewed, 43 partially reviewed and 2,275 pending, with no
stale or orphaned entries. This is review accounting, not universal test coverage.

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| ReplayPlaybackTime | 37/38 | 43/46 |
| ReplayModels | 25/25 | No measured branches |
| ReplayEngineService | 410/422 | 197/288 |

The helper's unexecuted line is the exceptional-arithmetic path whose integral advance
remains below the remaining duration; it supports the helper's broader Long bounds, beyond
supported recording durations. Full-file review does not mean every branch executed.
Measured replay load was **15.1255 ms**, scrub p95 **24.1657 ms** and rapid-seek burst
**5.0064 ms**. The separate performance-baseline test retains its **100 ms scrub limit**.
This one sample does not explain the unresolved pass 20 105.8996 ms failure or establish
physical timing guarantees.

Validation used the unchanged local library candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. Focused logs are `ARESLib-Kotlin/build/audit-pass22-focused.log`;
the full gate is `audit-pass22-studio.log`. The dashboard snapshot is preserved as
`audit-pass22-dashboard-smoke.json`. Kover XML is
`ARES-Analytics/app/build/reports/kover/report.xml`. Library/robot suites were not rerun
for these Studio-only changes.

## Remaining scope

Recording metadata currently loads all session summaries and retains all distinct session
timestamps. Database query complexity, timestamp domain validation at query boundaries,
concurrent mutations and dense-window memory budgets remain open. The synchronous dispose
wrapper blocks its caller; callers using a dispatcher confined to that same thread must use
disposeAndJoin instead. Production construction uses the default dispatcher, and the
service registry already awaits disposeAndJoin. Broader source/UI cancellation and shutdown
contracts still need review. These tests do not establish every scheduler interleaving,
arbitrary-precision fractional playback or hardware timing.

Prior intermittent timing failures, opt-in tests, physical validation and the repository-wide
coverage goal remain open. All changes remain local.
