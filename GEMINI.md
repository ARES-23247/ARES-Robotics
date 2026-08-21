# ARESLib-Kotlin contributor rules

This file is the repository-specific guide for automated contributors. Read it before modifying code. Human-facing architecture and operational details live in [`docs/`](docs/).

## Repository role

ARESLib-Kotlin is the shared foundation for ARES-FTC, ARES-FRC, ARES-Analytics, and the desktop simulator. It is organized as:

- `core`: SDK-independent state, math, estimation, control, safety, pathing, sequencing, IO contracts, NT4, telemetry, and logging.
- `codegen`: generated API/source support published alongside the runtime modules.
- `ftc-hardware`: FTC hardware adapters and base robot/facade classes.
- `frc-hardware`: WPILib/vendor adapters and base robot/facade classes.
- `ftc-mocks`: desktop FTC/Android API mocks.
- `simulator`: Dyn4j physics, OpMode runner, replay, virtual driver station, and NT4 bridge.
- `simulator-runtime-windows`, `simulator-runtime-linux`, `simulator-runtime-macos`: platform-specific simulator native runtime artifacts.
- `ares-bom`: published dependency constraints for all ARES artifacts.

Platform and season code depend inward on `core`. Do not add FTC, Android, WPILib, or vendor API types to `core`. Game-specific mechanism and field code belongs in the season repositories.

See [Architecture](docs/architecture.md) for package ownership and extension guidance.

## Build and consumer order

Use the checked-in wrapper and JDK 17:

```powershell
.\gradlew.bat compileKotlin compileTestKotlin
.\gradlew.bat test
.\gradlew.bat :core:test
.\gradlew.bat :ftc-hardware:test
.\gradlew.bat :frc-hardware:test
.\gradlew.bat :simulator:test
.\gradlew.bat test apiCheck publishReleaseValidation --no-parallel "-ParesVersion=<candidate>-rc.<commit>"
```

After changing ARESLib, test it and publish the isolated validation repository before testing consumers. Pass the same candidate `-ParesVersion` plus an absolute `-ParesRepository=file:///.../build/release-repository` to every consumer. Normal consumer builds resolve the pinned version from Maven Central and the GitHub-hosted ARES Maven repository; sibling source substitution is opt-in with `-ParesUseSiblingLib=true`. Do not use `mavenLocal()` as cross-repository validation evidence. Publication coordinates are listed in [README.md](README.md).

## State and reducer contract

The normal flow is input/observation -> `RobotAction` -> `Store` -> `rootReducer` plus slice/season reducers -> `RobotState` -> controllers -> hardware outputs.

- Reducers are deterministic and contain no I/O, clock calls, logging, network work, or background jobs.
- Season reducers compose with `rootReducer`; they do not bypass it.
- Root/slice transitions normally use data-class `copy`.
- Each `Store` owns its mutable, fixed-capacity EKF replay history. Drive and vision observations must be dispatched through that store; calling `rootReducer` directly performs only stateless Redux reduction. Published `PoseEstimatorState.history` is an empty read-only compatibility view, while current pose/covariance/diagnostics and `lastObservationTimestampMs` are independently owned snapshot values.
- Some low-level EKF, diagnostic, and vision workspaces are deliberately mutable or pooled to satisfy steady-state allocation requirements. Do not retain or mutate these internals outside their owning store pipeline.
- Dispatch control actions from the main robot loop. Do not create a second control loop in store listeners.

## Time and deterministic execution

Library runtime code uses `com.areslib.util.RobotClock.currentTimeMillis()` and `RobotClock.nanoTime()`. Do not call `System.currentTimeMillis()` or `System.nanoTime()` directly. Simulation/replay control the mock clock; bypassing it breaks timeouts, latency compensation, and reproducibility.

Reducers should receive timestamps through actions rather than reading the clock themselves.

## Coordinate and estimator contract

- Field position is meters.
- Internal heading is radians, counter-clockwise positive.
- Heading zero faces `+X`; `+pi/2` faces `+Y`.
- Angular residuals are wrapped across `-pi`/`pi`.
- `PoseEstimator` odometry deltas are robot-local SE(2) displacement, not field-relative displacement.
- Delayed vision timestamps are capture timestamps in `RobotClock` milliseconds. Camera latency is subtracted exactly once at the hardware boundary.
- Vision standard-deviation inputs are standard deviations: meters for X/Y and radians for heading.

### Pinpoint

`PinpointIO` converts heading polarity once with its `isHeadingCcwPositive` configuration. After that boundary, odometry, EKF, state, telemetry, and dashboard data are CCW-positive. Never add a downstream sign correction.

### Limelight target space

For `VisionMeasurement.robotPoseTargetSpace`:

- X+ is right of the tag.
- Y+ is vertically upward.
- Z+ is outward from the tag face.
- Planar robot yaw is `-robotPoseTargetSpace.rotation.y`.

Do not use `rotation.z` as target-relative robot heading.

### Display transforms

ARES Analytics swaps/negates field axes for canvas rendering and applies an icon-angle offset. That is display-only behavior and must not enter robot, path, or estimator math.

The complete review checklist is in [Math and coordinate contracts](docs/math-and-coordinate-contracts.md).

## Hardware loop contract

All hardware reads happen once per loop during refresh/read-inputs and are stored in preallocated cached fields/input containers. Controllers, getters, telemetry, and output writers consume cached values only. Never add hidden device reads to a getter or `writeOutputs()`.

Failures and stale inputs fail closed. Invalid voltage/current/velocity data must not increase an actuator command. Mode/autonomous exception paths stop every mechanism, not just the drivetrain.

FTC drive motor map names are `fl`, `fr`, `rl`, and `rr`; consumers may support `bl`/`br` as aliases but must not change the robot hardware-map contract.

## Allocation contract

Steady-state 50-100 Hz loops, estimator/replay propagation, trajectory sampling, hardware refresh, and local planner steering must not allocate. Use preallocated buffers, primitive/direct overloads, matrix/path pools, and index loops. Do not use reflection, construct geometry/arrays per frame, or launch jobs/coroutines per update.

One-time initialization, file parsing, and explicit operator actions may allocate outside timing-critical loops. Run `com.areslib.test.ZeroGcRegressionTest` for hot-path changes.

## NT4 and logging contract

- Custom/simulator NT4 listens on port `5810`.
- `LogManagerServer` listens on port `5002`.
- NT4 topics are canonicalized without a leading slash.
- An announced NT4 topic keeps one type for its lifetime.
- Keep publisher/subscriber topic spelling, units, types, and heading signs consistent across all four repositories.
- Robots never push logs to cloud services. ARES Analytics pulls local logs and the laptop performs optional cloud sync.
- Main telemetry logs are `.csv` or `.csv.gz`; `.jsonl` remains valid for action/replay logs. Keep `LogManagerServer` discovery and download support aligned with all accepted formats.
- Simulator truth belongs only on `ARES/TruePose/*`. Redux EKF estimate belongs on `ARES/EstimatedPose/*` and `Drive/Pose_*`; odometry belongs on `Drive/Odom_*`.
- The simulator publishes `ARES/SimulatorPoseFrame` as `[true x/y/h, EKF x/y/h, odom x/y/h, sequence]`. Preserve this atomic frame and never substitute truth for estimator data or restore per-scalar simulator UI commits.
- `ARESDataLogger.stop()` drains accepted frames. Watch `droppedFrameCount`; never block a robot loop waiting for storage.

The endpoint and topic tables are in [Telemetry and logging](docs/telemetry-and-logging.md).

## Testing expectations

Use focused tests while iterating, then run every affected module. A cross-repository contract change is not complete until affected season/dashboard tests pass against the current ARESLib publication or composite build.

Add regression cases for:

- non-finite values and zero/negative time deltas;
- angle wraparound, robot-local curved motion, and delayed vision;
- hardware disconnect/stale input and stop behavior;
- path bounds, empty paths, and alliance transforms;
- malformed/oversized network and log inputs;
- concurrency/lifecycle transitions;
- steady-state allocation for hot paths.

See [Development, testing, and troubleshooting](docs/development.md) and [TEST_INFRA.md](TEST_INFRA.md).

## Change checklist

1. Preserve unrelated working-tree changes.
2. Identify all sibling-repository consumers before changing a public type, topic, unit, file format, or behavior.
3. Keep source KDoc explicit about frames, units, signs, timestamps, invalid inputs, and ownership of mutable/pool-backed data.
4. Run focused and module tests.
5. Publish an explicit prerelease version to the isolated `build/release-repository` and test affected consumers with that same version and repository URI.
6. Update the nearest document in the same change.
