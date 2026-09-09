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

## First-pass validation evidence

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

## Second pass: different files and boundaries

The second pass reviews spline construction and constraint sweeps, digital/analog filtering,
the delta-action Redux boundary, estimator propagation/replay ownership, and task timeout/
preemption handling. Its runtime edits are in `SplineMotionProfiler`, `EMAFilter`, `Debouncer`,
and `DriveReducer`, with the `DriveHardwareUpdate` coordinate contract clarified in `RobotAction`.
It does not repeat the first pass's controller, encoder, FTC profiler, or planner edits.

| Area | Finding and correction |
| --- | --- |
| Spline endpoint | The backward pass overwrote the forward-reachable end speed with the requested speed, bypassing acceleration and local speed constraints. Keep endpoint requests as ceilings and preserve the stricter forward result. |
| Acceleration-zone boundary | The forward and backward passes selected opposite endpoints' acceleration limits. Each edge now uses the stricter endpoint in both directions. |
| Tight-curve curvature | Clipping measured curvature to 100 per meter understated sharp bends and raised their centripetal speed ceiling. Preserve the finite-difference curvature, including small positive distance intervals. |
| Rotation interpolation | Each unanchored sample searched backward and forward for anchors, producing quadratic work on sparsely anchored paths. Walk anchor intervals once; locate explicit rotation targets with binary search while preserving earlier-sample ties and point-towards precedence. |
| Profiling allocations | Resolve speed/acceleration constraints once per sample and update privately owned point velocities/curvatures directly, avoiding duplicate zone scans and point copies. |
| EMA recovery | One NaN/infinite sample permanently contaminated filter memory. Return the invalid sample to preserve downstream fault detection, but leave filter memory unchanged so valid readings can recover. |
| Debounce replay | Clock rewinds either stalled dwell completion or reused time from the discarded timeline. Restart dwell on any backward sample timestamp; reject negative durations. |
| Raw delta odometry | `DriveHardwareUpdate` already feeds a robot-local SE(2) twist into the EKF, but raw odometry added it directly to field X/Y. Integrate the twist arc and rotate it by raw odometry's own heading; wrap the resulting heading. Absolute `PoseUpdate` behavior is unchanged. |

Before correction, 11 regression cases failed across these math/filter issues. Three additional
cases preserve rotation interpolation, target precedence, and dense-path construction behavior.
The existing drive reducer assertion now checks the finite-turn arc rather than straight addition.

The same 8,001-sample synthetic spline construction test measured a median of 68.7051 ms before
and 22.6945 ms after correction across three builds per run on this desktop JVM. This is supporting
evidence for reduced construction work, not a real-robot timing guarantee or a whole-loop benchmark.
Rotation-anchor interpolation is now linear in samples; explicit target lookup is logarithmic per
target. Point-towards and constraint-zone matching still scale with sample and zone counts.

The sampled spline profile remains spatial: it does not prove jerk limits or a swept-volume
collision guarantee, and zones narrower than sampling intervals need denser geometry sampling.
Known-route precomputation remains appropriate because construction is still synchronous.

The estimator Jacobian/covariance and store timestamp paths, task preemption/timeout ownership,
and FRC base update ordering were inspected without additional changes in this pass. This is a
bounded source audit; existing regression coverage does not prove absence of all defects.

Second-pass candidate: `17.0.3-rc.3f8b883fac92`. The final 17.0.3 identity remains the unpublished
version already prepared in this open PR; its source-tree binding and isolated candidate are new.
No published artifact is overwritten.

Second-pass local validation:

| Scope | Result |
| --- | --- |
| All ARESLib modules | 1,075 tests passed with no skips; API checks and isolated publication passed. |
| FTC robot and simulator | 109 tests passed. |
| FRC robot | 134 tests passed. |
| FTC starter | 14 tests passed. |
| FRC starter | 34 tests passed. |
| Studio shared, gateway, and app | 1,244 tests passed; six opt-in checks skipped. Release version/archive preflight passed. |
| Repository checks | Source/release policy, guidance integrity, and current documentation links passed. |

## Third pass: electrical safety and cached actuator commands

This pass reviews current estimation/calibration, brownout limiting, default voltage-to-duty
conversion, FTC motor/servo command caches, registered-current aggregation, and gravity
feedforward. Runtime edits are confined to `CurrentBudgetManager`, `BrownoutGuard`, `MotorIO`,
and `CachedHardware`; these were outside both earlier passes.

| Area | Finding and correction |
| --- | --- |
| Voltage conversion | `MotorIO.setVoltage` could write non-finite or out-of-range duty. Invalid requests/battery feedback now neutralize; finite voltage requests saturate to [-1, 1]. Adapter enable and feedback checks remain required. |
| Cached motor commands | NaN was silently ignored, retaining previous nonzero effort. Non-finite requests now become neutral commands. Finite requests are clipped before caching so cache values match accepted duty. |
| Device configuration | A device reset or motor mode change could clear hardware effort while the cache suppressed the next identical explicit command. Reset, mode, and direction operations invalidate the motor cache; servo device reset invalidates its cache. |
| Cache tolerance | Zero epsilon sent every identical command; NaN epsilon suppressed ordinary writes. Validate tolerances and explicitly suppress equality. Ten identical commands now produce one write even at zero tolerance. |
| Servo positions | Non-finite positions were inconsistently ignored or forwarded and cached. Reject non-finite positions explicitly and clip finite positions to [0, 1]. There is no invented universal servo neutral position. |
| Safety configuration | Invalid threshold ordering, scales, or hysteresis could bypass limiting or return NaN. Reject invalid constructor configuration for both safety managers. |
| Unknown current | Invalid additional measured current became zero and could release limiting. Unknown or non-finite computed totals now report NaN and disable effort; finite subsequent updates follow the existing recovery state machine. |
| Calibration | Repeated power/scale/velocity reads and a second full sum were unnecessary. Capture the model once per motor and adjust the total by the calibrated slot's change. Respect the source's cached-reading validity check, and bound the round-robin index instead of letting it overflow. |
| Brownout counter | Invalid voltage during WARNING entered CRITICAL without incrementing the documented transition counter. Count that transition once, including invalid feedback. |

Nine new regression cases failed before fixes. The complete added coverage also exercises
stale-but-finite current, arithmetic overflow, command clipping, servo recovery, and the brownout
counter. One existing test that asserted unknown current was healthy zero was corrected to enforce
the repository's invalid-current contract.

The calibration regression observes exactly one power, power-scale, velocity, and current getter
read for the selected motor. These are cached properties, so this demonstrates reduced duplicate
work, not four saved physical bus transactions. Calibration still consumes only one current source
per update, without adding hardware polling or allocation to the normal loop.

The DC motor model remains an estimate; fuse temperature, electrical transients, measured supply
current, and actual bus/loop timing require physical validation. Registered-source aggregation and
gravity feedforward were inspected without further changes. Command caches assume the wrapper
owns writes; out-of-band writes through the underlying device cannot be inferred from cached state.

Third-pass isolated candidate: `17.0.3-rc.343862ce3c59`, under the existing unpublished final
version. This audit does not publish or replace any released artifact.

Third-pass library validation: all 1,089 tests passed on the completed gate, with no skips;
API compatibility and isolated publication passed. Source/release policy, guidance integrity,
and current documentation links passed.

The initial full run failed `TelemetryUpdateE2ETest` with a 0.359 m raw-odometry/truth mismatch
after approximately 250 seconds and logged stale Pinpoint feedback. The unchanged test passed
in isolation, then passed in the complete gate rerun (5.206 seconds for that test). This suggests
timing sensitivity but does not establish the failure's root cause. No pose thresholds were
relaxed and no simulator truth was substituted for odometry or the estimator.

Third-pass robot consumer validation against that same candidate passed: FTC robot/simulator
109 tests, FRC robot 134 tests, FTC starter 14 tests, and FRC starter 34 tests, with no skips.
Studio shared/gateway/app validation passed 1,244 tests, with six opt-in checks skipped;
release version/archive preflight passed. Total passing library and consumer tests: 2,624.
Physical hardware timing and electrical measurements were not performed.

## Fourth pass: XRP field geometry and coverage accounting

The continuing repository-wide goal now has a [file-level ledger](audits/README.md).
It starts from every tracked file and separates full review, partial review, validation,
and stale evidence. The earlier 29 edited library runtime/test files are conservatively
seeded as partial review. Passing a module suite is not evidence that every file or branch
has been exercised. The goal remains active.

This pass changes the XRP starter's `simulator/field_collision.py` and adds 15 focused
tests. The complete file was inspected, including field reload, shape compilation,
intersection predicates, coordinate units, and motion constraints.

| Finding | Correction and evidence |
| --- | --- |
| Invalid live field edits | Non-finite dimensions, malformed obstacle geometry, and invalid receipt lists could install unusable state or throw after partially changing the field. Validate and compile everything before committing the field and its receipt. Invalid disk reloads retain the prior usable field. |
| Repeated shape work | Static rectangles and polygons were rebuilt during each query. Precompute immutable obstacle geometry when a field is installed. Ten point queries construct ten moving footprints instead of twenty robot-plus-obstacle rectangles in the one-obstacle regression. |
| Invalid geometry inputs | Reject invalid robot dimensions, unsupported or degenerate blocking shapes, non-finite coordinates, and crossing polygon edges. Non-finite proposed poses cannot advance the simulation. Preserve canonical circle radius in meters and rectangle rotation in degrees. |
| Translation tunneling | A clear endpoint could cross a thin rectangle, circle, or polygon. Check the swept footprint and accept only a proven clear prefix. All three shapes reproduced the defect before correction. |
| Rotational tunneling | A long robot could rotate through an obstacle or field boundary with both endpoints clear. The swept bound includes rotating corners. Both cases reproduced before correction. |
| Unbounded collision work | Sweep subdivision stops at 16 levels or 256 interval queries, whichever comes first. Unresolved contact returns the last proven clear pose. Clear translation in an obstacle-free rectangular field bypasses hull construction. |

The sweep encloses endpoint footprints in a convex hull and expands it by a conservative
rotation interpolation bound. A corner at radius `r` has second derivative magnitude
`r * deltaHeading^2` over a normalized interval; its distance from linear interpolation is
bounded by `r * deltaHeading^2 / 8`. Square expansion encloses that error disk. Refining
suspect intervals in time order avoids incorrectly rejecting an entire clear rotation
because of a coarse hull. Tests include a clear near-obstacle rotation, short heading
wrap, concave polygon notches, and sampled accepted prefixes of 100 seeded random combined
translation/rotation requests. These samples supplement the conservative bound; they are
not an exhaustive geometric proof or a floating-point formal verification.

Initial validation/geometry tests had five failing test methods (15 failing subcases and
one error); the additional sweep tests had three failing methods (five subcases). All
15 final field-audit tests pass. The initial sandboxed attempt could not access temporary
fixtures; the reproduced baseline and final runs used normal local temporary-file access.

Local validation: XRP source plus generated safety tests **65 passed**, exported
standalone XRP archive **65 passed**, repository Python tooling **44 passed**. The tooling
suite includes new regressions for new/changed/missing/deleted coverage records, evidence
requirements, and text/binary fingerprints. Studio app/archive-consumer validation:
**1,209 passed, six opt-in tests skipped**. Release preflight initially caught the stale
workflow copy of the XRP checksum; synchronizing it restored the check. Source/release
policy, guidance integrity, archive integrity, and links in 165 current documents passed.

An additional standard-library `trace` run of XRP verification passed all 65 tests and
measured **93.8% executable-line coverage** for `field_collision.py`. It is not branch
coverage. Some malformed-geometry/error branches, degenerate segment helpers, and the
work-budget fallback did not execute. Their code was inspected, but more boundary tests
remain possible. The same trace measured 50.2% for XRP hardware adapters and 20.8% for
MicroPython telemetry; these are explicitly open priorities, not covered-file claims.
Trace evidence is under `ARES-XRP-Starter/build/audit-pass4-trace` and its adjacent log.

Only the unpublished XRP 3.0.3 archive changes; its new SHA-256 is
`26c7da103b24ab4cd22a9fdc8c47d94c54f64cb4306b244e448b4b2343c95b74`.
The other three deterministic starter/example archives reproduced their previous hashes.
Library source is unchanged; Studio validation uses the existing isolated candidate
`17.0.3-rc.343862ce3c59`. Nothing was published or deployed.

This constraint models a rectangular kinematic footprint along linearly interpolated
translation and the shortest heading arc. It may stop conservatively near contact at the
subdivision/work limit. It does not model contact forces, tire slip, or the full physical
motion between encoder samples. Physical XRP validation and simulator process-lifecycle
review remain separate work.

## Fifth pass: XRP lifecycle, timing, and hardware adapters

The [fifth-pass report](audits/xrp-lifecycle-audit.md) records measured physical-loop
periods, wrap-safe scheduling, startup/shutdown fixes, output validation, buzzer recovery,
independent optional hardware channels, and per-board RGB state. It includes 24 new
tests, source references for device units, line-execution measurements, and the remaining
physical-validation boundaries. The file-level audit goal continues beyond this pass.

## Sixth pass: XRP deployment and boot recovery

The [sixth-pass report](audits/xrp-deployment-audit.md) records stricter API/capability
preflight, deterministic payload staging, bounded downloads, boot/activation fallback,
and plan/deployment digest parity. Eighteen new tests extend the interruption model
through every deployment filesystem mutation. Physical flash durability remains untested.

## Seventh pass: XRP transport and control leases

The [seventh-pass report](audits/xrp-transport-audit.md) records a late-heartbeat lease
expiry race, pending Start cleanup, bounded byte framing, failed socket setup cleanup,
and field identity validation before mutation. Eighteen new regressions pass, including
a robot/mechanism stop-and-restart test. Physical radio and loop timing remain untested.

## Eighth pass: XRP robot lifecycle and autonomous control

The [eighth-pass report](audits/xrp-robot-autonomous-audit.md) records final-heading and
waypoint-speed fixes, terminal shutdown, invalid-period/pose rejection, and reduced loop
work. Finished routines retain leased autonomous mechanism control without silently
entering teleop. Twenty-three new regressions pass; physical tracking and timing remain open.

## Ninth pass: XRP subsystem control math and sampling

The [ninth-pass report](audits/xrp-subsystem-control-audit.md) records PID stop/reset and
anti-windup fixes, wrapped/filtered derivatives, signed bang-bang hysteresis, typed target
validation, and shared raw sensor reads. Sixteen new regressions pass. Advanced profiles,
feedforward, and descriptor safety contracts remain open; the source is partially reviewed.

## Tenth pass: generated Kotlin controller boundaries

The [tenth-pass report](audits/kotlin-subsystem-control-audit.md) records negative-gain
anti-windup, one-sided bang-bang limits, unused PID state, timestamp/freshness validation,
and arithmetic-overflow handling. Compiled scenarios exercise emitted controller code.
Profile generation and advanced safety behavior remain open.

## Eleventh pass: motion profiles

The [eleventh-pass report](audits/motion-profile-audit.md) records continuous overspeed
braking, shared generated-profile integration, reference reset on invalid feedback, and
finite control-output checks. It includes compiled generated behavior, core trajectory
invariants, allocation measurements and explicit line/branch coverage limitations.

## Twelfth pass: basic PID controller

The [twelfth-pass report](audits/pid-controller-audit.md) records sign-aware integral
saturation, disabled-state cleanup, finite arithmetic and periodic differences, invalid
configuration recovery, and removal of repeated work and console IO. It includes
before/after regressions, per-thread allocation evidence and explicit coverage limits.

## Thirteenth pass: XRP profiles and feedforward

The [thirteenth-pass report](audits/xrp-profile-feedforward-audit.md) records previously
ignored profile/feedforward declarations, continuous braking and reset behavior, gravity
models, auxiliary sensor dependencies, finite outputs and reusable reference state.
Twenty-two new tests pass. Advanced descriptor safety and physical timing remain open.

## Fourteenth pass: Studio geometric calibration

The [fourteenth-pass report](audits/studio-calibration-audit.md) records stale-result
cleanup, identifiable Pinpoint fitting without its large matrix, chronological samples,
finite calibration recommendations, stable vision deviations and removal of the track
heading array. Collector completeness/import and physical calibration remain open.
