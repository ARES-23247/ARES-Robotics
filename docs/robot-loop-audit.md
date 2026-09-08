# Robot-loop and mathematical follow-up audit

This follow-up examines the robot execution paths after Studio 7.0.3 / ARES 17.0.2:
FTC sensor sampling, encoder velocity, shared trajectory control and path planning,
FRC sensor-to-state conversion, and existing estimator/allocation regression coverage.
It is a source and desktop-test audit, not a measurement of Control Hub or roboRIO timing.

## Corrected behavior

| Area | Finding | Correction / evidence |
| --- | --- | --- |
| Holonomic curve feedforward | Negative velocity bypassed the centripetal speed cap; the small-curvature cutoff could also bypass a valid low acceleration limit. | Clamp signed velocity to both limits for any nonzero curvature; exercise both curvature and velocity signs. |
| Final chassis speed | SE(2) discretization increased translation after the controller's speed clamp. | Clamp the final discretized translation vector, retaining direction and angular velocity. Exact unconstrained pose increments necessarily yield to the actuator speed limit when saturated. |
| Encoder timing | Same-millisecond samples replaced the position baseline without updating velocity, losing displacement from the next difference. Timestamp zero was treated as uninitialized. | Keep a separate initialized flag, retain the baseline until time advances, and rebase to zero velocity on a replay rewind. Getters still perform no device reads. |
| FTC sensor profiling | A pre-read frame made the later `readSensors()` return immediately, hiding its sensor duration and overruns. | Retain the actual sensor duration and count it once in core-loop diagnostics. A regression advances the sensor phase by 30 ms and verifies the real telemetry values and read count. |
| FTC IMU freshness | A sample published after the frame-start timestamp could be rejected as future-dated. | Compare sample age with the consumption time. Regression uses a sample acquired during the hardware phase. |
| No-route pathfinding | An empty planner result became a straight path through the rejected route. | Stop, mark the task failed, invoke its failure callback once, and produce no follow commands. |
| Planner endpoint/corner checks | Same-cell requests bypassed bounds/occupancy checks; fallback diagonal edges bypassed the shortcut's corner rule. | Validate both endpoints before shortcuts; check the two orthogonal neighbors before diagonal expansion. |

## Avoided work and allocations

- The active holonomic follower owns a reusable chassis-speed output. Public value-returning
  controller/discretization APIs still return independently owned objects, preserving callers that
  retain results. Invalid input explicitly clears reused output.
- Tracking diagnostics calculate wrapped angular and lateral errors only when telemetry is present.
- FRC swerve sampling reads the prior estimate's primitive fields. It materializes a pose only when
  reseeding after a beached-state recovery, rather than every frame.

`RobotLoopMathTest` compares the same controller arithmetic using owned and reused outputs after
JIT warmup. An initial desktop run measured 800,000 bytes for 20,000 owned results and zero bytes
for reused results. The reused call measured p50/p95/p99 of 300/500/700 ns on that run. These figures
include a volatile result sink and timing probes, exclude sensors/Redux/telemetry, and are not a
whole-loop speedup or a real-time guarantee. The test asserts an allocation budget, not machine-
dependent latency percentiles; it skips explicitly if allocation measurement is unavailable.

## Timing and remaining boundaries

FTC `Profiling/Total_ms` covers the measured core sensor, power, subsystem, and telemetry phases.
It excludes caller work between pre-reading and updating, season work outside the shared update,
and intentional pacing sleeps. It must not be interpreted as the entire OpMode period. The existing
overrun threshold remains strictly greater than 25 ms. FRC retains WPILib's scheduler ownership.

Hardware refresh and output paths were inspected for cached reads. Existing power-manager and
CTRE allocation regressions remain relevant. The complete runtime still allocates immutable Redux
snapshots and telemetry values; FRC season hardware also uses vendor vararg refresh calls. Removing
those allocations safely requires preserving device status/refresh semantics and measuring the
actual vendor runtime. This audit does not claim an allocation-free robot.

Path planning and trajectory generation still run synchronously at task initialization. Large or
difficult costmaps can therefore delay a frame even when steady-state following is inexpensive.
Precompute known autonomous routes. A bounded incremental planner would be a separate scheduling
change; this audit does not move control work to an unowned background loop. Grid collision checks
also do not establish a swept-volume guarantee for the later smoothed trajectory.

On hardware, record complete frame period and phase durations under representative autonomous,
vision, telemetry, and CAN loads; inspect p50/p95/p99/max, overruns, dropped logs, and freshness.
Encoder scale, slip, sensor noise, and actuator timing require physical validation.

## Validation evidence

Validation uses isolated candidate `17.0.3-rc.dab0a3a9cfdc` from the reviewed library tree.

| Scope | Result |
| --- | --- |
| All ARESLib modules | 1,061 tests passed; API compatibility and isolated publication passed. |
| FTC robot and simulator | 109 tests passed. |
| FRC robot | 134 tests passed. |
| FTC starter | 14 tests passed. |
| FRC starter | 34 tests passed. |
| Studio shared, gateway, and app | 1,244 tests passed; six opt-in checks skipped. Release version/archive preflight passed. |
| CI scope/result scripts | 41 tests passed. |
| Repository checks | Source/release policy and current documentation links passed. |

New regression tests reproduced the control-limit, encoder-timing, unreachable-route,
and planner endpoint/corner failures before their fixes. Hardware deployment and complete
physical loop-period measurements were not performed.

The six skipped Studio checks cover three generated-project integration suites, native
file-picker interaction, the dashboard performance baseline, and physical dashboard telemetry.
Hosted CI runs its separately configured integration and performance scopes; the local unit
test result does not substitute for those checks.
