# Drive facades

`DriveSubsystem`, `MecanumDriveFacade`, and `SwerveDriveFacade` send commands through the
robot's Redux store. They do not read hardware, enable a robot, or start another control loop.
Use each facade from its robot's existing loop. Configuration, fresh feedback, enabled state,
output faults and disable/close neutralization remain responsibilities of the platform pipeline.

## Commands and measurements

`DriveSubsystem.joystickDrive` accepts physical m/s and rad/s. Its default frame is field-relative;
`setChassisSpeeds` is always robot-relative: +X forward, +Y left, positive rotation CCW.
`maxSpeedMps` limits translation magnitude, and `maxAngularSpeedRadiansPerSecond` limits rotation.

The holonomic facades accept normalized translation and angular effort. Translation is projected
onto the unit disk before multiplying by `maxSpeedMps`; rotation is clamped to [-1, 1] and scaled
by `maxAngularSpeedRps`. Diagonal translation retains its direction and obeys the same speed limit
at every heading. Invalid input or nonpositive/nonfinite speed limits neutralize the whole command.

`xVelocity`, `yVelocity`, and `angularVelocity` expose cached measured velocities: field-relative
translation and CCW rad/s. Commanded velocities remain in `store.state.drive` under the full
`xVelocityMetersPerSecond`, `yVelocityMetersPerSecond`, and `angularVelocityRadiansPerSecond`
names. A measured getter does not establish freshness; `measuredMotionValid` belongs to the state
and physical output safety remains downstream. `pose`/`getEstimatedPose` are allocating convenience
views. The holonomic periodic calculations use the estimator's primitive snapshot fields instead.

Every command refreshes its `RobotClock` timestamp. The facades reuse mutable actions, so listeners
that retain commands beyond synchronous dispatch must copy or serialize them during dispatch.
`SubsystemControllerBase.dispatchOnChange` is for persistent mechanism targets; it must not suppress
the refresh of leased drive commands. Its Double overload avoids boxing unchanged values. Marvin's
controller base inherits that shared implementation.

## Heading and position hold

Field-relative driving captures heading after measured rotation settles below 0.03 rad/s. Position
hold captures the current EKF position when linear stick input is released. Both holds ignore
normalized stick noise up to 0.05. Deliberate movement releases the corresponding target, and
disabling both holds restores teleop. Position correction is tagged separately from driver input,
so it cannot release its own target. Brake requests atomically neutralize command velocities, clear
hold targets and retain X-brake until deliberate movement resumes.

Heading and position PID outputs are physical angular/linear velocities. Position uses a circular
deadzone and a circular correction-speed limit. Motor static-friction feedforward is applied by the
motor controller, not added to a desired chassis velocity. Invalid pose or time releases holds and
neutralizes; malformed targets and invalid hold tuning produce no correction for that hold.
Constructor heading gains/deadzone apply initially. A new Redux drive-tuning object subsequently
becomes authoritative. Position tuning is read from the initial state and later tuning updates.

`driveWithGamepad` preserves the existing shaped-stick behavior, turbo precedence and legacy BLUE
alliance translation inversion. Season code with its own alliance transform should pass its explicit
field-relative inputs rather than applying this helper's inversion a second time.

## Path requests

Bind path requests to the robot's existing follower and task owner during setup:

```kotlin
val executor = TaskExecutor()
val follower = HolonomicPathFollower(robotDrive) // This robot's configured DrivetrainSubsystem.
facade.configurePathFollowing(follower, executor::addTask)

facade.followPath(path) // Queue once when requested; preserve the actual estimator pose.

// In the existing enabled robot loop; dispatch returned actions before the next update:
executor.update(store.state, RobotClock.currentTimeMillis()).forEach(store::dispatch)

// On disable/stop, preserve cancellation actions and neutralize through normal robot teardown:
executor.cancelAll(store.state).forEach(store::dispatch)
```

A runtime that already owns a task executor can be used as the submission callback instead.
The follower, facade and executor must belong to the same robot. Unconfigured or empty requests
fail before submission. `FollowPathTask` validates the path, tracks progress and owns marker and
interruption behavior. Paths retain caller-owned data and must remain stable during execution.
Apply any alliance transform before requesting the path; this facade does not mirror it again.
The old pose-reset-only behavior of `followPath` has been removed.

`JoystickDriveIntent` now includes `fromPositionHold`. Old schema-1 logs without this field decode
as ordinary driver commands; explicit malformed values remain errors. Existing Java constructor
overloads remain available. Recompile Kotlin consumers because generated data-class copy/default
constructor signatures changed with the new field. Candidate validation rebuilds monorepo consumers.
