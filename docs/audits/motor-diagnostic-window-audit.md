# Motor diagnostic evidence and current-window audit

Pass 32 reviews the legacy MotorIO stall/disconnection diagnostic, current averaging and
motor-temperature dispatch in Studio. It is an operator diagnostic, not actuator control.
The recognized motor names remain fl, fr, rl, rr, bl and br.

## Findings and changes

- Missing velocity was substituted with zero and voltage could substitute for normalized
  duty. Both could manufacture a stall. The diagnostic now requires actual Power in [-1,1],
  finite Velocity in encoder ticks/s, and finite nonnegative CurrentAmps.
- Cached power and velocity had no age limit. Every contributing field must now be within
  an inclusive source-time second of the latest contributing observation. Missing, stale,
  invalid or text feedback is unknown and cannot fabricate a fault or recovery.
- Nonfinite current was dropped before invalidating cached evidence; negative current was
  added to the mean. Invalid current now clears that motor's averaging evidence. New valid
  current can recover the diagnostic without inheriting an invalid interval's old mean.
- Any motor update rescanned all six motors, aged their current buffers and could resolve an
  unobserved motor's fault. Exact topic routes now evaluate only the affected motor. Scalar
  temperature rules run once on that temperature's own observation and retain configured
  thresholds; the duplicate all-motor thermal scan is removed.
- Millisecond rounding retained current one microsecond beyond the one-second boundary.
  Aging now uses original source microseconds. Independently delayed fields retain the latest
  contributing source timestamp for alert transitions; they cannot move an occurrence backward.
- Per-sample objects and full-buffer sums are replaced by a lazily allocated primitive ring
  and weighted mean tree. Each current sample updates a logarithmic tree path; expiration
  visits at most 512 retained samples, each with a bounded tree path. Power/velocity updates
  reuse the retained mean after aging. Nonnegative weighted means avoid overflowing sums and
  recover small remaining values when an enormous sample expires, without rolling-total drift.

The window retains at most 512 current samples per motor/session. If more fit in the inclusive
second, the result is explicitly unknown until all omitted samples expire. It never silently
substitutes the last 512 samples for the full window. This bounds per-motor storage, not the
number of recording IDs retained by the whole engine. Means retain ordinary floating-point
rounding and sample weighting; this is not a time-weighted electrical current integral.

## Validation

The nine-method baseline reproduced eight failures in 27s. Valid moving feedback resolving an
existing fault already passed. Evidence: `ARESLib-Kotlin/build/audit-pass32-before.log/xml`.
The first focused selection passed 81 methods in 1m 34s, including the then-28 new methods
(`ARESLib-Kotlin/build/audit-pass32-focused.log`). Two additional integration methods cover
configured temperature thresholds and delayed current's occurrence timestamp.

The 30 added methods cover missing/invalid/stale fields, duty and velocity units, independent
motors, recovery, strict diagnostic thresholds, exact time boundaries, equal-time sample weights,
capacity overflow, clear/reset, delayed samples, timestamp endpoints and finite extreme values.
An independent full-sample oracle checks 10,000 deterministic updates across ring wrap/expiry.
The motor-state allocation test measures two consecutive 10,000-update windows after warmup.
It covers the helper and its current window, not telemetry envelopes, source-key objects,
alert transitions, persistence, transport or the UI.

The final Studio gate passed in 3m 45s (`ARESLib-Kotlin/build/audit-pass32-studio.log`):

- 1,650 ordinary methods passed, six existing opt-in methods skipped, plus 56 dashboard smoke
  methods and the performance-baseline method passed. Kover verification, release alignment
  and production Kotlin file-size checks passed.
- Current-window source covered 45/45 executable lines and 41/42 branches. Motor-state source
  covered 29/29 lines and 56/59 branches. Engine source covered 194/208 lines and 116/166
  branches; remaining engine behavior stays open. Snapshot: `ARESLib-Kotlin/build/audit-pass32-kover.xml`.
- The allocation test executed and again measured two consecutive 10,000-update windows at
  zero bytes. The full-sample oracle and all 30 added methods passed. Focused final XML:
  `ARESLib-Kotlin/build/audit-pass32-final-xml`.
- Dashboard load was 18.6567 ms, scrub p95 25.4925 ms and rapid-seek burst 5.6791 ms. The fixture
  persisted/restored 12,000 frames without drops. These desktop fixture timings are not a robot
  loop benchmark. Snapshot: `ARESLib-Kotlin/build/audit-pass32-dashboard-smoke.json`.
- Validation used unchanged isolated candidate `17.0.3-rc.b81c0156add9` from the local release
  repository. Library and robot consumers were not rebuilt for Studio-only changes.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass32-policy.log`), including shared
guidance and local links in 193 current documents. The staged inventory contains 2,457 files:
155 fully reviewed, 59 partially reviewed and 2,243 pending, with no stale or orphaned records.
Review accounting is independent of executed suite and line/branch coverage. The monorepo goal
is not complete.

## Limits and next work

The source contract was traced through MotorIO and its FTC/simulator telemetry. XRP publishes
left/right velocity in meters/s, and other producers use unit-suffixed keys; this pass does not
invent conversions or expand the legacy recognized topic set. No physical robot, electrical
measurement, visible Studio window or live audio validation is claimed.

No-input timers are not added. Unknown feedback preserves the last observed fault rather than
claiming recovery. Configured synthetic Stall/Disconnected thresholds and other composite-policy
configuration semantics remain open, as do persistence failure recovery, global session/history
retention, upstream connection ownership and remaining replay/store behavior. The alert engine
remains partially reviewed. Existing intermittent and opt-in validation concerns remain open.
All work stays local; the monorepo-wide goal is active.
