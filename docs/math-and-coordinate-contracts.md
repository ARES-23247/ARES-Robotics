# Math and coordinate contracts

These conventions are part of the public API. A sign, frame, unit, or timestamp mistake can produce plausible telemetry while making localization and control mathematically wrong.

## Canonical 2D field frame

| Quantity | Convention |
|---|---|
| Position | meters |
| Velocity | meters per second |
| Heading | radians internally |
| Angular velocity | radians per second |
| Positive rotation | counter-clockwise |
| Zero heading | robot facing field `+X` |
| `+pi/2` heading | robot facing field `+Y` |
| Time deltas | seconds |
| Absolute timestamps | `RobotClock` milliseconds unless an API explicitly says otherwise |

Normalize angular differences with `wrapAngle`; never subtract headings and use the raw result across the `-pi`/`pi` boundary.

## Robot-local versus field-relative motion

`PoseEstimator.addOdometryObservation` and `addOdometryObservationDirect` accept robot-local displacement:

- `deltaX`: forward motion in the robot frame.
- `deltaY`: leftward motion in the robot frame.
- `deltaHeadingRad`: CCW rotation during the sample.

The estimator applies the SE(2) exponential to form the constant-curvature local arc, then rotates that arc by the pre-update field heading. Do not pre-rotate these deltas into field coordinates. Covariance propagation linearizes the same arc used for the state transition.

Invalid/non-finite motion or a non-positive `dtSeconds` leaves the estimator state unchanged. Tilt, angular disagreement, and motion rate scale process covariance. The beached state uses hysteresis and freezes odometry propagation until the robot recovers.

## Delayed vision measurements

The pose estimator stores a fixed history of timestamped pose/covariance snapshots. A valid delayed measurement is applied at the nearest historical state, after which later robot-local arcs and process noise are replayed to the present. Therefore:

- Measurement timestamps must be capture timestamps, not receipt timestamps.
- Camera latency must be subtracted once at the hardware boundary.
- The history and measurement must use the same field frame.
- A pose reset must reset the estimator/history coherently; do not splice a new pose into old history.

Vision input is rejected when required data is invalid, no tags are reported, ambiguity exceeds the configured maximum, the covariance cannot be inverted, the observation is outside the field/history contract, or its Mahalanobis innovation exceeds the configured threshold. `PoseEstimatorState.lastMeasurementAccepted` and `lastRejectionReason` are intended for diagnostics.

The measurement standard-deviation vector contains standard deviations, not variances: X/Y are meters and heading is radians. The estimator squares/scales them when constructing measurement covariance.

## Pinpoint boundary

`ftc-hardware/.../PinpointIO.kt` is the only place where Pinpoint heading polarity is corrected:

```kotlin
val headingMult = if (isHeadingCcwPositive) 1.0 else -1.0
val heading = headingMult * driver.getHeading(AngleUnit.RADIANS)
```

All downstream values—Redux state, EKF, telemetry, and dashboard inputs—are CCW-positive. If a robot reports reversed heading, verify the physical mounting flag and a known positive turn. Do not add a compensating negation to a reducer or dashboard.

Pinpoint pod offsets and encoder resolution are configured in millimeters/ticks-per-millimeter at the device boundary; reported pose and velocity are converted to meters and meters per second.

## Limelight target space

Target-space axes are different from the field frame:

- X+: right of the tag when facing it.
- Y+: vertically upward.
- Z+: outward from the tag face.
- Robot yaw for target alignment: `-robotPoseTargetSpace.rotation.y`.

`rotation.z` is tilt/roll in this target-space interpretation, not the robot's planar yaw. Use the full field-space `targetPose` for EKF fusion and target-space pose only for relative alignment controllers.

## Field-to-screen transforms

ARES Analytics converts field coordinates to canvas coordinates with swapped/negated axes. The dashboard's robot icon also has its own zero-angle orientation. Those display transforms do not change the robot/EKF frame and must never leak back into robot math.

## Alliance transforms

Alliance mirroring is a field transform, not a heading-sign change. Apply it at one explicit boundary. Field-centric joystick transforms, path mirroring, vision field poses, and simulator spawn selection must agree on the active alliance. A second mirror or heading negation can look correct on one half of the field and fail on the other.

## Numerical review checklist

For every estimator, controller, profile, or trajectory change, verify:

- Frames and units are documented on inputs and outputs.
- `NaN`, infinity, zero/negative `dt`, empty data, and singular matrices fail safely.
- Angular residuals are wrapped.
- Boundary conditions include nonzero initial/final velocity where supported.
- The state equation and covariance Jacobian describe the same transition.
- Delayed-data replay uses the same process-noise scaling as forward propagation.
- Tests cover straight motion, pure rotation, curved motion, wraparound, delayed measurements, and invalid inputs.
- Hot-path convenience overloads do not conceal allocation in the loop.
