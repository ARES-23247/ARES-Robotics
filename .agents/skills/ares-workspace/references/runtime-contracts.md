# Runtime contracts

## Redux and outputs

Follow: input -> action -> reducer -> immutable state -> controller -> IO. Output failures must latch safely and require an explicit confirmed neutral recovery where applicable.

## Hardware and timing

- Cache voltage, current, encoders, switches, and device health once per loop.
- Unknown current is invalid, not zero.
- A disabled, closed, unconfigured, unhomed, stale, or reset device must not accept nonzero output.
- Close resources idempotently and aggregate cleanup without skipping later resources.

## Coordinates

- Heading is CCW-positive; 0 is +X; internal angles are radians.
- Do not add a second Pinpoint or alliance sign inversion.
- Limelight target-space robot yaw is `-rotation.y`, not `rotation.z`.
- Dashboard field-to-canvas transform and robot icon rotation offset are coupled.

## Telemetry and simulation

- Normalize NT4 keys by stripping leading `/`.
- Keep atomic control frames leased and fail closed.
- Simulator applied-output state must reflect post-safety IO, not desired Redux intent.
- Publish Dyn4j truth only on `ARES/TruePose/*`. Publish the real Redux EKF estimate on `ARES/EstimatedPose/*` and `Drive/Pose_*`, and odometry on `Drive/Odom_*`; never substitute truth for estimator data.
- Preserve `ARES/SimulatorPoseFrame` as the atomic `[true x/y/h, EKF x/y/h, odom x/y/h, sequence]` UI boundary. Do not restore per-scalar simulator pose commits.
