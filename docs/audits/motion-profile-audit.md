# Motion profile audit - pass 11

This pass reviews `TrapezoidProfile.kt`, `ProfiledPIDController.kt`, and the profiled
setpoint section of the Kotlin subsystem controller renderer. It extends executable
coverage for initial overspeed, braking, reversal, endpoint continuity, invalid inputs,
and finite arithmetic. Generated behavior is tested by compiling and executing emitted
Kotlin with fake IO.

## Confirmed findings and fixes

- An initial speed above the cruise limit could make the profile choose an infeasible
  reversed trajectory and return the unchanged state forever. An explicit initial
  braking segment now integrates position and velocity at the acceleration limit. A
  step extending beyond that segment continues through the remaining profile time.
  This also supports lowering the cruise limit while a reference is moving.
- The emitted profile used end-of-step velocity for the full position increment, doubling
  the first acceleration-step displacement. Its endpoint clamp could snap velocity to
  zero, including when a changed goal required overshoot and reversal. Generated Kotlin
  now calls the shared analytic profile with reusable state/goal/constraint storage.
  The duplicate stepping formula is removed. Continuous angular goals still use the
  nearest wrapped displacement from the current reference.
- Generated profiles retained their moving reference through invalid measurements.
  Invalid profile input now neutralizes output and resets the reference so the next
  valid measurement initializes it again.
- `ProfiledPIDController` advanced its reference before invalid feedback was rejected
  by the underlying PID. Invalid profile constraints or goals could also retain nonzero
  control effort. Validation now precedes profile advancement, resets PID history, and
  returns zero for invalid input or non-finite control effort.
- A mathematically required overshoot outside the representable `Double` range could
  emit infinite position. The profile now retains its last finite working state when
  the computed position/velocity cannot be represented. This is a numeric fallback,
  not a realizable physical trajectory at such extreme values.

The existing cutoff-distance formulation was compared with the primary
[WPILib profile source](https://github.wpilib.org/allwpilib/docs/release/cpp/_trapezoid_profile_8h_source.html).
Our added overspeed segment integrates constant acceleration rather than instantly
clipping initial speed. Trapezoidal velocity profiles constrain reference acceleration;
they do not enforce jerk limits or guarantee the physical mechanism response.

## Validation

Before fixes, four of five initial core regression methods failed; the fifth checked
partition/alias consistency and passed even for unchanged states. After the initial
braking fix, all 18 then-current profile tests passed. Three additional invalid-input
and overflow regression methods failed before their fixes. The compiled generated-profile
test failed before renderer changes; its four scenarios pass after them.

The complete affected suites pass **743 core tests** and **76 generator tests**, with no
skips. New coverage comprises eight core boundary methods, one allocation method, and
one compiled test executing four generated-profile scenarios. The two expected artifact
fingerprints were updated only after the behavior tests and generated changes were reviewed.

The allocation test passed on this JVM, observing two consecutive 10,000-update windows
with **zero allocated bytes** after warmup. It exercises core overspeed calculations and
profiled feedback using reusable buffers; it does not measure emitted-controller allocations
or on-device execution time.

Core Kover reports **95/101 executable lines (94.1%)** and **59/94 branches (62.8%)** for
`TrapezoidProfile.kt`, and **42/44 lines (95.5%)** and **41/56 branches (73.2%)** for
`ProfiledPIDController.kt`. Missing profile lines are numeric/invalid-input fallbacks;
missing wrapper lines are the output/integrator-limit forwarding methods. Full-file
review does not imply every branch executed. Coverage XML is
`ARESLib-Kotlin/core/build/reports/kover/report.xml`.

The full library test/API/local-publication gate passed for `17.0.3-rc.a9759be4af9c`,
source tree `a9759be4af9c80d8cb1c5d527339faebc843350d`. The library reports contain
**1,100 passing tests**. Core/codegen suites executed in the focused run and reused those
results in the full gate; changed dependent modules executed their suites, while the
unchanged project-schema and telemetry-schema suites reused up-to-date results.

All consumer gates passed against the same isolated candidate: FTC **109**, FRC **134**,
FTC starter **14**, FRC starter **34**, and Studio **1,244 passing tests with six opt-in
skips**. These consumer suites executed in this pass. Source identity, archive integrity,
release alignment, shared guidance, and links in 172 current documents passed. The
bundled starter archives remain unchanged.

Source changes are committed locally as `47a89803`. No push, merge, remote publication,
or hardware deployment occurred. Logs are `ARESLib-Kotlin/build/audit-pass11-*.log`.

## Remaining work

The MicroPython subsystem still needs the promised profiled-position/feedforward
behavior. The basic Kotlin PID controller needs its own negative-integral-gain,
zero-gain state, and overflow audit; wrapper output validation is not a substitute.
Other generator work remains: invalid bounded targets, feedforward families, advanced
homing/recovery state machines, and multi-loop output preparation before writes.

On-robot tracking, motor response, scheduler jitter and heap behavior remain unmeasured.
Desktop allocation measurements apply only to their exercised paths, not to every
consumer or device. The repository-wide audit goal remains active, and all changes
stay local.
