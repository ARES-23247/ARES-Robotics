# Loop-overrun window audit

Pass 31 audits the alert engine's loop-period moving window, source separation, numeric input
and per-update work. It preserves the existing fixed diagnostic policy: three samples strictly
above 25 ms in an inclusive source-time second, or a current sample at least 100 ms. This is
an operator diagnostic, not a robot scheduling or actuator safety controller.

## Findings and changes

- The old window compared millisecond timestamps. A sample one microsecond beyond a second
  could still contribute to an overrun alarm. Window aging now uses original source microseconds
  and preserves the inclusive exact-one-second boundary.
- All loop-time aliases shared one session window and canonical alert key. Separate sources
  could manufacture a three-sample overrun together, and a healthy alias could resolve another
  source's fault. The telemetry catalog explicitly preserves alias provenance. Each recording
  and actual normalized source key now owns a separate window and alert interval.
- Zero, negative and unusably tiny finite periods were treated as healthy observations and
  could resolve a severe fault. Invalid periods leave the last observed detector state intact;
  valid positive finite periods must also imply a finite frequency. Nonfinite/text samples
  remain excluded by the upstream engine gate. A valid healthy sample still resolves according
  to the existing policy; missing or invalid input does not fabricate recovery.
- Every loop sample allocated a timed entry, then rescanned the entire one-second queue for
  count and maximum. Queue size/work depended on arrival rate. The extracted detector owns
  three timestamp/period slots, evicts at most three aged entries and examines at most three
  retained entries per update.
  Healthy samples do not fill the retained overrun slots. Threshold constants have one owner,
  and the engine reuses its already-computed source identity for the window lookup.

The count decision depends only on the third-newest overrun. At the beginning of an occurrence,
those three slots also include its qualifying window evidence: a fourth qualifying sample
would mean the count condition was already active. While active, a separately retained
occurrence peak preserves older maxima after their slots are replaced. This is an occurrence
peak, not a general-purpose rolling-maximum API. The independent full-window oracle checks
both the decision and this peak across 50,000 deterministic samples, including resolution and
recurrence. Separate severe-event tests retain the existing policy that a valid healthy
sample can resolve an isolated severe event before one second has elapsed.

## Validation

The seven-method engine baseline reproduced five failures in 32s. Exact boundary inclusion and
older occurrence-peak preservation already passed. Evidence:
`ARESLib-Kotlin/build/audit-pass31-before.log/xml`.

There are 17 added methods: seven engine integration cases and ten detector cases. Tests cover
microsecond endpoints, actual source keys, independent alias resolution/counts, invalid periods,
strict/inclusive period thresholds, healthy-sample aging, equal timestamps with distinct sample
orders, large timestamp differences, recurrence peaks, the full-window oracle and allocation.

The focused selection passed 63 methods in 1m 55s (`ARESLib-Kotlin/build/audit-pass31-focused.log`).
The allocation test measured two consecutive 10,000-update windows with zero allocated bytes
after warmup, including rejected input. This measures only the detector; telemetry envelopes,
engine source keys, rule transitions, persistence and UI still have their own allocation costs.
The engine's source-identity reuse was finalized afterward and is included in the full gate.

The final Studio gate passed in 4m 22s (`ARESLib-Kotlin/build/audit-pass31-studio.log`):

- 1,620 ordinary methods passed, six opt-in methods skipped, plus 56 dashboard smoke methods
  and the performance-baseline method passed. Kover verification, release alignment and
  production Kotlin file-size checks passed.
- Detector source covered 27/27 executable lines and 32/32 branches. Engine source covered
  198/213 lines and 135/200 branches; its remaining composite/configuration/lifecycle paths
  are not closed by these tests. Snapshot: `ARESLib-Kotlin/build/audit-pass31-kover.xml`.
- The detector allocation check again measured two consecutive 10,000-update windows at zero
  allocated bytes. This was executed, not skipped, on the current JVM. The full-window oracle
  also passed in the final gate.
- Dashboard load was 24.396 ms, scrub p95 29.8897 ms and rapid-seek burst 5.958 ms. The fixture
  persisted/restored 12,000 frames without drops. These are desktop fixture measurements,
  not a robot loop-time benchmark. Snapshot: `ARESLib-Kotlin/build/audit-pass31-dashboard-smoke.json`.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9` from the local release
  repository. Library and robot consumers were not rebuilt for these Studio-only changes.
- Repository policy passed (`ARESLib-Kotlin/build/audit-pass31-policy.log`), including shared
  guidance and local links in 192 current documents.

The staged inventory contains 2,451 files: 149 fully reviewed, 59 partially reviewed and
2,243 pending, with no stale or orphaned records. Review accounting remains separate from
executed coverage; the entire monorepo goal is not complete.

## Limits and next work

The fixed 25/100 ms temporal policy is preserved. File-configured scalar threshold overrides
are not yet reconciled with the specialized temporal decision and peak-recording behavior;
configuration semantics remain open. This pass does not add a no-input timer or infer recovery
from absent samples. Source identities/order are supplied by the publication pipeline.

Storage/work is constant per loop source, not globally bounded across arbitrarily many recording
IDs. The engine's broader session/history retention remains open, along with motor current
windows, source freshness and unit-sensitive composite rules. Persistence failure recovery,
upstream connection ownership and remaining replay/store behavior also require further audit.
Existing intermittent and opt-in tests remain open. No physical loop-time, visible Studio,
live audio or hardware validation is claimed. All changes remain local.
