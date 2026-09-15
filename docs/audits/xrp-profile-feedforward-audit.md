# XRP profiles and feedforward audit - pass 13

This pass implements and reviews the previously missing MicroPython profiled-position
and feedforward paths. It reviews the complete new numeric helper and its two test files.
The larger descriptor runtime remains partially reviewed: advanced safety and follower
declarations still need their own implementation/validation passes.

## Confirmed findings and fixes

- `PROFILED_POSITION_PID` previously ran ordinary PID directly against the final target.
  It now follows a reusable trapezoidal reference with bounded acceleration and zero
  terminal velocity. Changed goals and reduced velocity constraints brake continuously;
  they do not snap velocity to zero or to the new limit. Angular goals wrap over one turn.
- Declared feedforward was ignored. Simple motor, elevator, arm and two-joint arm models
  now contribute motor volts before output clamping and anti-windup. Profile velocity and
  average step acceleration supply defaults; explicit state references override them.
  Velocity loops default to target velocity, and other unprofiled loops default to zero.
- Feedforward measurement dependencies now require available sensors, including optional
  devices used by the active controller. Fields must be numeric, and feedforward requires
  a PID motor loop. Missing references, invalid math and unsupported models neutralize.
- Profile history resets on stop/fault and starts again from fresh measurement. Finite
  validation precedes profile-state commits. Every loop result is prepared before any
  nonzero actuator write, so invalid auxiliary feedforward cannot briefly energize an
  earlier loop.
- Profiles are allocated once per configured loop and reused. Empty optional models use
  a shared fallback, and gain reads avoid a per-step generator. Python numeric results
  still allocate; these changes do not establish zero allocation or device loop timing.

The analytic zero-terminal-velocity profile follows the core contract reviewed in pass 11.
Two-joint gravity uses the canonical relative elbow angle, link masses and centers of mass,
including centers at the pivot. Schema comments now describe the existing profile defaults
for feedforward. Four-bar feedforward remains unsupported, as in schema validation.

## Validation

The initial 13 subsystem test methods produced **22 failures** against the old runtime
(`ARESLib-Kotlin/ares-micro/build/audit-pass13-baseline.log`). The final changes add
**22 methods**: 17 subsystem cases and five analytic helper cases. They cover acceleration,
arrival, reversal, overspeed, step partitioning, overflow, wrapped goals, reset/recovery,
feedforward signs/gravity, field overrides, sensor availability, saturation and write order.
All **123 shared Python tests pass**.

Standard-library trace, including test discovery/imports, reports **116/121 executable
helper lines (95.9%)** and **246/258 subsystem lines (95.3%)** exercised. This is CPython
line execution, not branch coverage or proof of all MicroPython numeric cases. Reports are
under `ARESLib-Kotlin/ares-micro/build/audit-pass13-trace`.

Source and freshly extracted standalone XRP verification each pass **107 tests**. The
initial restricted source run reported errors and stalled during filesystem/deployment
tests; it was stopped and is not counted as a pass. The unchanged rerun with integration
test process/temp-directory access passed. No board was contacted or deployed.

The XRP archive SHA-256 is
`f15c77a60b08d1a3432c562fc05b802336ae0ae993ee09a597815033116a493d`.
Other starter archives reproduced their existing hashes. The local library candidate is
`17.0.3-rc.b81c0156add9`, source tree `b81c0156add956c02f8bf080eb0081703fc6a99b`.
Library test/API/local-publication validation passed; JVM test tasks reused their
up-to-date **1,120 passing** results because this pass changes Python behavior and KDoc.
Consumer validation passed: FTC **109**, FRC **134**, FTC starter **14**, FRC starter
**34**, and Studio **1,244 passing with six opt-in skips**. Robot/starter test tasks and
Studio shared/gateway tasks reused up-to-date results; Studio app tests executed against
the refreshed bundle. These counts describe the applicable reports, not fresh execution
of every unchanged test.

Source/archive changes are committed locally as `55bd2e37`. Source identity, archive pins
and monorepo policy passed. Shared guidance and links in 174 current documents passed.
Logs are `ARESLib-Kotlin/build/audit-pass13-*.log` and
`ARESLib-Kotlin/ares-micro/build/audit-pass13-*.log`.

## Remaining work

Advanced descriptor safety/follower behavior, other library controllers, logging and
transport, Studio analytics math, and the rest of the file inventory remain open. Physical
tracking, loop jitter, motor response and device heap behavior remain unmeasured. The
repository-wide goal remains active; all work stays local without push, merge or release.
