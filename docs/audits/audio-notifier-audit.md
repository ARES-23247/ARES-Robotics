# Alert audio timing and lifecycle audit

Pass 38 reviews Studio alert-tone timing, admission, coroutine ownership, waveform generation
and Java Sound cleanup. This is a desktop notification channel, not robot actuator output.

## Contract and implementation evidence

- Wall-clock subtraction and a zero timestamp sentinel are replaced by monotonic nanosecond
  differences and an explicit first-attempt flag. Exact 1.5-second boundaries are eligible;
  ordinary nanoTime wraparound is preserved by signed subtraction. The cooldown starts when
  the playback worker begins its attempt, not when that worker was queued or when a speaker
  physically produces sound. Clock regressions in an injected source cannot shorten it.
- Previously, a busy trigger advanced the timestamp and delayed a subsequent valid attempt.
  Admission now reserves at most one queued/running child job under a monitor. Busy/rejected
  calls leave timing unchanged; cancelled owners and jobs cancelled before dispatch do not
  consume the cooldown. Playback and native cleanup run outside that admission monitor.
- Stop remains reusable, close is terminal, and reservation ownership lasts until cleanup has
  completed. Engine stop, target reset and disposal request audio cancellation. Normal joined
  disposal waits for accepted child jobs before completing; a failed storage drain still
  requests audio cancellation while retaining the service's existing failed-drain semantics.
- Broad runCatching is removed. Fatal errors, cancellation and unexpected exception types propagate;
  unavailable/incompatible/denied audio is optional and a later alert can retry after cooldown.
  Failures are not automatically retried in the background.
- Two independently opened streaming lines and 2,000 sine evaluations per alert are replaced
  by one finite clip and one cached 2,400-byte waveform: signed 8-bit mono at 8 kHz, 100 ms at
  1 kHz, 50 ms silence, then 150 ms at 1.2 kHz. Device loading can still allocate/copy internally.
- The adapter handles duplicate/foreign events, cancellation during open, and missing completion
  events. A two-second coroutine timeout releases a suspended event wait. Listener removal
  and line closure use nested resource cleanup so secondary failures do not hide a primary one.

The Java Sound adapter design uses a preloaded finite Clip rather than streaming each tone
through a separate line and blocking drain. Java's [Clip contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/sound/sampled/Clip.html)
defines loading audio bytes on open; [DataLine](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/sound/sampled/DataLine.html)
defines STOP events when presentation ceases and documents that drain can block. These API
contracts support the event-driven implementation; mock events do not prove actual speaker output.

## Validation

The initial baseline stopped on a test-only generic type-inference error; the assertion's expected
list was explicitly typed and rerun. Evidence: `ARESLib-Kotlin/build/audit-pass38-baseline-compile.log`.
The corrected baseline reproduced eight failures in 34s (`ARESLib-Kotlin/build/audit-pass38-before.log/xml`).
Clock/dispatcher/playback seams preserved the previous request-time millisecond logic for this
baseline. The zero/negative-origin tests additionally guard the move to an arbitrary-origin
monotonic clock; they are not claims that present-day Unix time is negative.

The first focused run passed 25/30 methods in 2m 7s. A null Mockito matcher prevented a failure
stub from being installed and left two following tests with unfinished matcher/stubbing state;
the fixture now uses a direct mock answer. Two exception identity assertions were corrected to
accept coroutine stack-recovery copies while verifying type, message, the original cause and
suppressed cleanup failures. Production code was unchanged by these corrections. Evidence:
`ARESLib-Kotlin/build/audit-pass38-focused-first.log` and `ARESLib-Kotlin/build/audit-pass38-focused-first-xml`.
The non-cancellable cleanup-gate fixture also releases its gate in finally on an assertion failure.

The corrected focused run passed all 30 methods without skips in 43s
(`ARESLib-Kotlin/build/audit-pass38-focused.log`): 15 notifier tests, ten Java Sound/waveform
tests and five engine lifecycle tests. An eight-thread synchronized admission test executed;
this does not prove every possible scheduler interleaving. Adapter tests use mocked Clip
objects and injected playback; they do not require or establish audible output.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass38-policy.log`), including shared
guidance and links in 199 current documents.

The full Studio gate passed in 3m 32s (`ARESLib-Kotlin/build/audit-pass38-studio.log`):

- 1,777 ordinary methods passed, with six existing opt-in methods skipped. The 56 dashboard
  smoke methods and the performance-baseline method passed. Unchanged shared/gateway tests
  were up-to-date; the app suite executed. Coverage verification, release alignment and
  production Kotlin file-size checks passed.
- All 30 added tests passed. Final XML: `ARESLib-Kotlin/build/audit-pass38-final-xml`.
- Notifier source covered 27/27 executable lines and 17/20 branches; Java Sound/waveform source
  covered 28/28 lines and 15/16 branches; the engine covered 188/190 lines and 102/126 branches.
  Snapshot: `ARESLib-Kotlin/build/audit-pass38-kover.xml`. This does not establish every scheduler
  interleaving, native device behavior or full engine contract coverage.
- Dashboard replay load was 31.1605 ms, scrub p95 39.952 ms and rapid-seek burst 8.5715 ms.
  The fixture persisted/restored 12,000 frames without drops. Snapshot:
  `ARESLib-Kotlin/build/audit-pass38-dashboard-smoke.json`. These measurements do not isolate
  audio performance or measure physical robot loop timing.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9`. Library and robot
  consumers were not rebuilt for Studio-only changes.

Inventory: 2,482 tracked files, 182 fully reviewed, 61 partially reviewed and 2,239 pending,
with no stale or orphaned records. File-level review accounting is separate from executed
tests and measured line/branch coverage.

## Limits and next work

Native device acquisition, open and close can block inside a provider. Coroutine cancellation
and a timeout can bound a suspended wait for completion events, but cannot promise a hard
deadline for an unresponsive native call. Actual device availability, audible quality, mixer
buffering and physical output-stop latency require platform audio validation. Desktop test
timing is not physical robot loop timing. Remaining engine/transport lifecycle, global retention,
platform policy consistency, historical policy identity, existing intermittent/opt-in tests and
whole-application shutdown remain open. All changes stay local; the full audit goal stays active.
