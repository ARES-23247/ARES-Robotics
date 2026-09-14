# FRC starter pose-controller bounds, feedback, and lifecycle audit

Pass 267, 2026-09-14, following the [composition audit](frc-starter-composition-ownership-audit.md).
This pass reviews `StarterFrcDriveToPoseTask` in
[StarterFrcAutonomousRuntime.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterFrcAutonomousRuntime.kt).
The separate selector, capability, marker, and match-lifecycle regions remain partially reviewed;
the enclosing file is not marked complete.

## Confirmed defects

The controller limited X and Y independently, permitting a diagonal command up to sqrt(2) times its
declared translation speed. Requested translation is now bounded by vector magnitude before the
acceleration ramp. The final command also respects the current translation and angular limits.
Previously a lowered live velocity scale took effect gradually through deceleration, even when the
new scale was zero. A tighter limit now applies immediately; that safety change takes priority over
the ordinary acceleration bound. With a stable limit, vector and angular acceleration retain their
configured bounds, including changing feedback over 600 deterministic test frames.

Repeated timestamps previously invented another nominal 20 ms period, allowing repeated calls to
grow the command without elapsed time. They now retain the prior command without advancing PID or
ramp state, while still applying a newly tightened envelope. A clock rewind fails neutral. The first
valid sample keeps the existing nominal 20 ms control period, timestamp zero remains valid, and later
positive periods retain the existing 50 ms integration cap. This is controller integration time,
not measured robot loop telemetry.

Completion counted calls rather than distinct observations. It could count one observation three
times, or accept a stale third observation before execute performed freshness validation. Completion
now validates feedback and configuration first, requires distinct observation timestamps, and resets
the consecutive count outside tolerance. Missing, future, stale, reversed, or invalid feedback fails
the task. The validity check reads finite raw estimator coordinates and heading before angle wrapping
can map an invalid raw heading to the shared helper's legacy zero fallback.

An invalid PID result was treated as valid zero effort on one axis while another axis continued to
move. Nonfinite scale values could also disable PID bounds through the PID API's deliberate NaN-limit
semantics. The composition now rejects nonfinite settings and invalid acceleration, checks all three
PID `lastCalculationValid` results, and neutralizes the complete task when any calculation fails.
Invalid target coordinates or raw heading reject construction. Repairing data cannot resume an
already failed task without explicit reinitialization.

The inherited pause returned no neutral action, leaving the previous drive command active during
preemption. Pause now emits neutral and clears controller/ramp/settling history, so resumed motion
starts from a neutral ramp. Direct execute previously continued producing motion after the default
Task implementation marked timeout failure. It now honors terminal status before calculating.
Finally, terminal cleanup removed timeout metadata that constructor-only configuration never
restored. The task retains its configured timeout, including a caller-supplied shorter duration,
and reapplies it before each initialization.

## Efficiency and ruled-out findings

The controller now reads primitive immutable estimator fields instead of constructing a convenient
`Pose2d` view in both completion and execute. Its validated target heading is normalized once and
retained. Command/neutral action lists remain reused, and reset logic is shared across initialization,
pause, and terminal release. Bytecode inspection verifies no `getEstimatedPose` call in the controller.
This removes explicit allocation opportunities; it is not a measured claim about whole-loop GC or
JIT escape analysis.

The old local angle loop looked unbounded in isolation, but its only call subtracted two already
normalized `Rotation2d.radians` values. No reachable infinite-loop defect was established. It was
removed as redundant normalization in favor of the existing shared bounded helper. Large finite raw
angles and the short signed arc across +/-pi are exercised by tests. No WPILib or shared-library fix
is claimed.

## Validation and remaining work

The unchanged-source baseline had **14 tests, 14 failures**, each checked against its actual assertion
or missing neutral result. The final product command `verifyAresProject test` passed **157 tests**
with zero failures, errors, or skips: all 137 prior cases plus 20 new controller tests. Existing tests
still verify deterministic target arrival, stale-feedback failure, timeout neutralization, typed
velocity/acceleration behavior, no-motion fallback, selection precedence, and alliance mirroring.
Baseline and final invocations are recorded separately rather than added together.

The new fixture uses immutable feedback snapshots, a controlled RobotClock, and the actual PID/task
implementations. It restores prior clock mode/time and resets task metadata. The product suite also
includes the existing native HAL/robot tests; this pass does not start a competition loop, simulator
GUI, external peer, or physical robot, and makes no deadline or physical-stop claim.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass267-verified-evidence/`. The library tree and
all 410 files of candidate `17.0.44-rc.f9e7569ea873` remain unchanged. Changes stay local. Marker child
failure propagation, marker completion semantics, catalog fallback safety, and match-runtime
cancellation/timing remain next investigations. Studio's existing alignment gate and unapproved
archive/reference migration remain unchanged; no gate was bypassed.
