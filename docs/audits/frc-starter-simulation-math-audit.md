# FRC starter simulation mathematics and loop audit

Pass 262, 2026-09-14. The previous polygon pass is recorded in
[pass 261](field-polygon-validation-audit.md). This pass reviews the generic FRC starter's
educational chassis model, its collision calculations, related fixtures, field loader,
minimal entry points, and product guidance. Work remains local to the audit branch.

## Confirmed defects and changes

The initial numerical regression suite had **10 failures in 10 tests**. It demonstrated
unvalidated initial poses, unnormalized large initial angles, incorrect finite-turn
translation, time-step-dependent free motion, commanded velocity reported despite a wall
stopping motion, false fresh motion for invalid/zero intervals, nonfinite pose propagation,
partially installed failed fields, incorrect rotated bumper clamping, and lost tiny curvature.

[StarterDriveSimulation.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterDriveSimulation.kt)
now validates initial pose components and uses the existing bounded `wrapAngle` implementation
instead of angle-proportional subtraction loops. Body-frame drive intent is integrated using
the constant-twist SE(2) exponential, with a small-angle series that preserves curvature.
Field-relative translation stays linear. Accepted displacement determines mean measured
field velocity; rejected rotation reports zero angular velocity. Invalid/zero time intervals
produce no fresh motion measurement, and unrepresentable arithmetic cannot publish a
nonfinite pose or velocity.

Field replacement validates and computes its complete replacement before committing local
state. Field clamping and autonomous pose reset use the full rotated bumper footprint.
The reusable action, immutable input state, and 50 ms maximum integration interval remain.

A second regression baseline had **7 failures in 9 tests**: four obstacle types/orientations
could be crossed within one frame when the endpoint was clear, two rotation arcs crossed a
boundary between clear endpoints, and invalid raw field replacement was accepted.

[StarterDriveCollision.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterDriveCollision.kt)
now checks swept translations and the full rotation arc. It compiles blocking obstacles once
per accepted field, caching shape normalization, obstacle trigonometry, dimensions, and copied
polygon vertices. Translation resolves X then Y for wall sliding, followed by rotation.
No distance- or angle-dependent sampling loop runs in a frame. Polygon segment distance uses
scaled lengths rather than a potentially overflowing squared length or a fixed tiny-edge cutoff.
Uncertain geometric comparisons conservatively reject contact clearance.

This remains an educational collision approximation. Rectangle obstacles use expanded local
bounds; circles and polygons use the bumper's bounding circle. Envelopes can stop the robot
before exact contact. The axis/rotation split is not a continuous rigid-body solution for the
original curved center trajectory. Replacing a field can place an obstacle over the existing
pose; a valid explicit pose reset may then be needed. No slip, contact force, current draw,
or physical behavioral parity is claimed.

## Validation and performance evidence

`verifyAresProject test` passed with **59 tests, zero failures, errors, or skips** against
the unchanged local candidate `17.0.44-rc.f9e7569ea873`. All 35 prior test invocations remain;
the final suite adds 12 numerical cases, 10 sweep cases, one allocation case, and one decoder
recovery case. Baseline/focused runs are not added to this total.

The numerical fixture compares free motion with WPILib's independent `Pose2d.exp(Twist2d)`
and compares one frame with five smaller frames. A bounded test exercises `Double.MAX_VALUE`
pose reset and rotation. The sweep fixture includes rectangles, rotated rectangles, circles,
concave polygons, nonblocking shapes, and 175 rotation configurations checked against
explicitly transformed bumper corners sampled across the arc. Sampling is supporting test
evidence; the implementation uses analytic projection extrema, not those samples.

The warmed allocation test runs 50,000 setup frames and measures another 50,000 frames with
all three obstacle shapes configured. The final local JVM observation was **0 allocated bytes
and 48,447,400 ns total**, approximately **0.97 microseconds per frame**. This is a single
desktop mean, not a latency percentile, worst-case execution bound, or RoboRIO loop-time
measurement. The test permits a small fixed bookkeeping allowance and reports unsupported
allocation accounting as a skip on other JVMs; no skip occurred here.

The field-loader fixture now checks all six components of its nontrivial WPILib pose and
decoder-error recovery. Previously, the fixture populated all six components but asserted
only X and yaw. The new assertions close that evidence gap. The existing polygon rejection,
league rejection, empty simulation field, and default-dimension checks still pass.

Main's compiled bytecode confirms a public static JVM entry point that supplies
`AresStarterRobot` to `RobotBase.startRobot`. The user-owned extension object contains only
ordinary singleton construction. This is compilation/linkage inspection, not an observed
robot process or rendered simulator window. The checked-in field JSON was parsed and its
dimensions compared with the canonical project descriptor; it deliberately has no selected
season's AprilTags.

ARESLib's source tree remains `f9e7569ea873a08df0477fad1008b6f9ef4575e3`; all 410 candidate
artifact hashes remain unchanged. No other product compiler was needed for these starter-only
changes. Evidence snapshots, XML, bytecode inspection, hashes, and commands are under the local
ignored directory `ARESLib-Kotlin/build/audit-pass262-verified-evidence/`.

## Remaining work

The starter composition root and Studio bridge remain only partially reviewed. Field-apply
callback failure, revision ownership, and acknowledgement ordering need a separate integration
audit. Native NetworkTables entry lifecycle and round-trip behavior also remain open in the
telemetry adapter; its existing fixture only exercises topic normalization.

The earlier archive/reference migration still awaits approval after automatic approval review
rejected it as beyond the audit's release authorization. No tracked archives or workflow/version
references were modified here; any approved packaging proposal must include the new starter
sources. Studio/release alignment remains pending, and no physical hardware validation occurred.
