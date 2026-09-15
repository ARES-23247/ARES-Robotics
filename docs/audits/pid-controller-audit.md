# Basic PID controller audit - pass 12

This pass reviews the complete core `PIDController.kt`, its two existing unit-test files,
and the tier-one clamping tests. It adds numerical boundary and allocation tests. The
controller remains a primitive mathematical component; hardware enable/freshness and
actuator limits still belong to its callers and IO boundaries.

## Confirmed findings and fixes

- Anti-windup used the error sign, allowing a negative integral gain to accumulate farther
  into saturation or preventing it from unwinding. It now checks the actual candidate
  integral contribution direction. A rejected step still recomputes output from retained
  integral state, preserving the existing behavior and its regression expectations.
- Zero integral/derivative gains still updated unused state. This could poison output
  through overflow or revive old accumulated/filter state when a gain was re-enabled.
  Disabled terms now clear and bypass their corresponding calculations.
- Invalid inputs returned zero but retained prior controller history. Invalid values now
  return zero and reset history, preventing stale integral or derivative effort on recovery.
- Non-finite intermediates could escape as NaN/infinity or be hidden by clamping. Error,
  derivative, proposed integral, combined effort and retained-integral output now validate
  before output clamping. Overflow neutralizes and resets.
- Inverted intervals, inward infinite bounds, invalid deadzones and invalid continuous
  domains could continue producing effort. They now neutralize until configuration is
  repaired. Existing NaN bound sentinels and outward infinities remain explicitly
  unbounded; valid one-sided finite intervals preserve their clamping semantics.
- The old angular wrap added half a period before taking a remainder, which could
  overflow. Wrapping now reduces first. If finite endpoints have an overflowing direct
  difference, it reduces the operands separately before computing the shortest residual.
  Period and half-period are cached at configuration time.
- Invalid-input handling performed clock reads, string construction and console writes.
  The primitive controller now handles that path without IO. Rejected integral steps
  reuse proportional/derivative terms instead of multiplying them again. The first
  derivative sample also skips an unused measurement-difference calculation.

Derivative-on-measurement and its existing fixed per-update EMA coefficient of 0.2 are
preserved. The controller documentation now describes that law and the distinct P/I/D
gain units correctly. The tier-one primitive smoke test no longer claims allocation
measurement in its name; a dedicated allocation test supplies actual evidence.

## Validation

The original **15 boundary methods produced 13 failures** against the old implementation.
Their XML is retained as `ARESLib-Kotlin/build/audit-pass12-baseline.xml`. The final boundary
suite has **19 passing methods**, including additional negative-gain unwinding,
derivative re-enabling, invalid deadzone/gain recovery and inward-infinity cases. A
signed-zero assertion was corrected to accept both representations of neutral effort;
that assertion change does not change controller output behavior.

The dedicated allocation test executed and passed on this JVM. After warmup, two
consecutive 10,000-update windows allocated **zero bytes**, covering normal continuous
PID updates, gain disabling/re-enabling, and rejected-input updates. This is not a
physical loop-time measurement or an on-device allocation guarantee.

The full library test/API/local-publication gate passed for candidate
`17.0.3-rc.2393fdf0841b`, source tree `2393fdf0841b56a7302d60afa3154bf43a692465`.
Its reports contain **1,120 passing tests**, including **763 core tests**. Core, codegen
and changed dependent suites executed; unchanged project-schema and telemetry-schema
suites reused up-to-date results.

Kover reports **90/90 executable lines (100%)** and **129/140 branches (92.1%)** for
`PIDController.kt`. This is execution coverage of the current file, not proof of every
numeric combination or every caller. The XML is
`ARESLib-Kotlin/core/build/reports/kover/report.xml`.

All consumer suites executed and passed against that candidate: FTC **109**, FRC **134**,
FTC starter **14**, FRC starter **34**, and Studio **1,244 passing tests with six opt-in
skips**. Source identity, unchanged starter archive hashes, release alignment, shared
guidance, and links in 173 current documents passed. The source changes are committed
locally as `230994a2`; no remote publication or device deployment occurred.
Logs are `ARESLib-Kotlin/build/audit-pass12-*.log`.

## Remaining work

MicroPython profiled-position and feedforward behavior remain open, along with advanced
generator safety/follower contracts, other controllers, logging/transport, Studio math,
and the remaining file inventory. Fixed per-update filtering has the documented current
semantics; this pass does not introduce a new filter-time-constant API. Physical tracking,
loop jitter, motor response, and device heap behavior remain unmeasured.

The repository-wide audit goal remains active. All changes stay local; no push, merge,
remote publication, or hardware deployment is authorized by this audit.
