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

Each `Store` privately owns a fixed history of timestamped pose/covariance snapshots. The history is not copied into Redux and is never shared between robot, simulator, or replay stores. A valid delayed measurement is applied at the nearest historical state, after which later robot-local arcs and process noise are replayed to the present. Therefore:

- Measurement timestamps must be capture timestamps, not receipt timestamps.
- Camera latency must be subtracted once at the hardware boundary.
- The history and measurement must use the same field frame.
- A pose reset must reset the estimator/history coherently; do not splice a new pose into old history.
- Capture timestamps older than the most recently accepted vision observation are rejected as
  `vision_out_of_order`. Odometry replay cannot reconstruct later camera corrections, so accepting
  such a frame would erase information already fused. Independent observations at the same capture
  timestamp remain supported. Full out-of-order camera replay would require retaining measurement
  updates as well as odometry.
- Drive and vision observations must go through `Store.dispatch`; a direct `rootReducer` call has no EKF runtime owner and intentionally performs only the stateless slice transition.
- `PoseEstimatorState.history` is retained as an empty read-only compatibility view. Use the observable pose, covariance, diagnostics, and `lastObservationTimestampMs`; runtime history is not telemetry or application state.

Vision input is rejected when required data is invalid, no tags are reported, ambiguity exceeds the configured maximum, the covariance cannot be inverted, the observation is outside the field/history contract, or its Mahalanobis innovation exceeds the configured threshold. `PoseEstimatorState.lastMeasurementAccepted` and `lastRejectionReason` are intended for diagnostics.

The measurement standard-deviation vector contains standard deviations, not variances: X/Y are meters and heading is radians. The estimator squares/scales them when constructing measurement covariance.

Innovation validity is checked even when statistical outlier gating is disabled. The NIS is
computed by whitening the residual with the innovation covariance's Cholesky factor; only accepted
observations need the inverse used for the Kalman gain. Turning odometry contributes to heading
process noise even when an independent gyro-rate sample is unavailable.

## Control, wheel limits, and sampled trajectories

Continuous PID wraps both position error and the measurement difference used by its derivative.
Wheel normalization applies one scale to the entire drive vector. Negative/NaN limits and
non-finite wheel commands produce a neutral vector; positive infinity represents no speed limit.
FTC fallback and XRP wheel odometry integrate constant-curvature arcs with the SE(2) exponential.

`SCurveTrajectoryParameterizer` produces a spatial profile seed with heuristic acceleration ramps;
it does not implement a seven-phase continuous-time S-curve. `JerkLimitedTrajectoryProvider`
checks the sampled vector acceleration and finite-difference jerk and uniformly stretches time
when needed. Translation velocities and angular velocities use that same scale. A diagnostic
reports reduced nonzero entry/exit speeds. These sampled checks are not a proof of continuous jerk
through piecewise-linear geometry corners or a drivetrain force/dynamics model.

Distance sampling uses binary search over nondecreasing path distances. Mutable sample outputs
avoid allocation; object-returning convenience methods retain their existing allocation behavior.

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

ARES Robotics Studio converts field coordinates to canvas coordinates with swapped/negated axes. The dashboard's robot icon also has its own zero-angle orientation. Those display transforms do not change the robot/EKF frame and must never leak back into robot math.

## Alliance transforms

Alliance mirroring is a field transform, not a heading-sign change. Apply it at one explicit boundary. Field-centric joystick transforms, path mirroring, vision field poses, and simulator spawn selection must agree on the active alliance. A second mirror or heading negation can look correct on one half of the field and fail on the other.

## Calibrated interpolation keys and work

`InterpolatingTable` accepts finite Double/Float, Byte/Short/Int/Long, BigInteger and
BigDecimal keys. Invalid calibration keys fail before mutation; invalid or unsupported
queries return null rather than selecting a potentially active endpoint. Valid out-of-range
queries clamp to the nearest stored value. Natural ordering defines exact identity:
Double signed zeros remain distinct and differently scaled, numerically equal decimals
replace the same entry. Value interpolation and its output validity belong to `Interpolatable`.

Integer differences are formed before floating conversion; unsigned distances cover the full
signed Long range. Overflowing floating spans are rescaled, and big-number differences use
decimal division before converting the final ratio to Double. Big-number arithmetic allocates
and its cost grows with operand precision; it is not a fixed-cost robot-loop primitive.

Ordered calibration entries trade O(N) insertion for one O(log N) query search and avoid
temporary map entries. Primitive-key arithmetic uses no internal scratch allocation; boxing
at a caller and construction of interpolated values can still allocate. Calibration mutation
and reads require one owner, with immutable keys. This table does not validate sensor freshness
or authorize output merely because a calibrated value exists.

## Joystick conditioning and snapshot ownership

`InputMath` accepts finite axis observations in [-1, 1], deadbands in [0, 1), and positive
finite curve exponents. Invalid observations/configuration produce neutral output; one bad
vector coordinate neutralizes that whole vector. Exponents below one remain supported and
amplify small inputs. Zero is rejected because it makes arbitrarily small active travel jump
to full output. Every valid deadband, including the representable value immediately below
one, preserves full travel. Radial processing clamps magnitude at one and preserves direction
to floating-point precision, including square-stick corners.

The Pair-returning vector convenience API allocates. Hot paths should reuse a caller-owned
two-entry array with `processJoystickVectorInto`; entries beyond the first two are untouched.
The FTC adapter reuses one buffer and returns a new immutable `ControllerState` each poll.
It reads each SDK field once, flips FTC Y once, and neutralizes invalid triggers individually.
Snapshots must not be mutated/reused after publication. Values alone do not prove connection
or freshness, and these helpers do not replace the robot's enable/lease checks.

## Primitive filter state and time

`LowPassFilter` uses the backward-Euler RC recurrence, not the exact continuous exponential;
timestep partitioning changes its discretization error. Finite nonpositive RC selects bypass.
Both low-pass and slew calculations hold state for invalid input/configuration or nonpositive
or nonfinite elapsed time, including after clear and in bypass mode. Their first sample after
clear must have positive finite elapsed time before it can establish a fresh baseline.

Invalid low-pass or median reset values clear their sample history. A slew reset uses its
existing finite-zero fallback and remains initialized. These filters' retained outputs do not
establish sensor freshness or validity; callers must retain that separate feedback evidence.

Median updates maintain a fixed-capacity sorted window in O(N) worst-case time, and getters
read a cached result in O(1). The median rejects some isolated outliers but can delay steps and
cannot guarantee rejection of arbitrary bursts. Slew limits use cached rate magnitudes;
either sign is accepted and a zero magnitude freezes that direction. Rescaled arithmetic keeps
accepted finite filter outputs inside their endpoint interval even when direct sums, differences
or products overflow. Hot-path tests measure allocation separately from on-device timing.

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

## Two-link arm geometry and deterministic plant

Joint 1 is measured CCW from +X; joint 2 is relative to link 1. All angles are radians,
lengths meters, masses kilograms and torques N m. Geometry permits zero masses; the dynamic
plant requires positive masses. Its centroidal rod inertias are mL²/12, even with a supplied
COM offset. Custom inertias, gearbox backlash, friction beyond viscous damping, flex and motor
electrical/back-EMF dynamics are not modeled. Calibrate torque-per-volt and damping against
measured motion before treating a desktop simulation as hardware evidence.

Reachability and inverse kinematics share one normalized workspace test, with eight ulps of
normalized outer-radius roundoff. Finite lengths/targets avoid squaring overflow/underflow;
accepted boundary roundoff clamps to the physical interval. Elbow-up uses negative relative
elbow angles and elbow-down positive. An extremely short link below the normalized Double
range has unresolved orientation; its branch is selected at a right angle while preserving
the dominant link direction. Maximum physical reach may itself exceed Double range.

Buffered forward kinematics, Jacobian and gravity overloads require sufficient output length
before writing, leave trailing elements unchanged and allocate no temporary outputs. Object/
array-returning overloads allocate their documented results. Finite angle-sum overflow uses
trigonometric addition identities. Cached scaled coefficients retain determinant/gravity
products beyond intermediate Double range and combine opposing gravity terms before final
conversion. Results still have floating-point rounding error; ill-conditioned cancellation
is not an arbitrary-precision guarantee. Nonfinite raw angles yield NaN; genuinely out-of-range
final components may be infinite. Validate raw outputs before commanding hardware.

Singularity proximity is abs(sin(elbow)) < threshold, independent of link scale. Threshold
must be finite and nonnegative; zero disables the strict band. Nonfinite joint angles
conservatively report near-singular. This predicate does not prove feedback freshness or
permission to enable actuators.

The single-owner plant caches immutable inertia/gravity terms and uses a positive-term Schur
complement instead of subtracting two squared-inertia terms or imposing an absolute determinant
cutoff. Effective inertia/gravity coefficients must be representable; unsupported coefficient
ranges fail at construction. Valid external dt is finite (0, 0.1] seconds. Each call takes at
most 50 equal semi-implicit substeps, each at most 2 ms apart subject to floating rounding.
Nonfinite voltages individually become zero, and finite voltages clamp to +/-12 V. Startup and
reset obey both hard stops; outward velocity is removed at a stop while inward velocity remains.
A nonfinite integration result fails before commit and rolls back the entire external step,
including any earlier substeps. Reset rejects invalid state before mutation. Very stiff or
energetic systems may require a smaller timestep; finite results do not imply numerical stability.
The former MIN_INERTIA_DETERMINANT JVM constant remains only for binary compatibility.

Generated mock refresh neutralizes outputs, invalidates feedback/current and latches the
output fault before propagating a failed plant step. It does not stamp failed feedback as
fresh. Studio's local lab stops, clears voltage controls, retains the last committed displayed
pose and requires explicit reset after a numerical failure. These are local simulation paths,
not physical robot safety validation.

## Differential and mecanum wheel calculations

Raw chassis/wheel conversions use meters and seconds, +X forward, +Y left and CCW-positive
rotation. Differential inverse kinematics projects onto forward and angular motion. Positive
CCW rotation makes the left wheel slower and the right wheel faster. Mecanum ordering is
front-left, front-right, back-left/rear-left, back-right/rear-right; no hardware-name remapping
or field-coordinate transform occurs here.

Finite track widths and wheelbases must be positive. Means use compensated or scaled arithmetic
where required, preserving finite large velocities and subnormal geometry. Angular conversion
chooses operation order so an overflowing separation or prematurely underflowed mean/reciprocal
does not lose a representable result. Raw conversions can still return nonfinite values for
invalid inputs or genuinely out-of-range components. These are not valid feedback/actuator
values; callers must enforce the relevant IO/estimator validity contract.

Coupled differential, mecanum and swerve speed normalization preserves ratios to floating-point
precision. Invalid speed vectors and NaN/nonpositive limits neutralize all participating speeds;
positive infinity is an unlimited normalization bound. Very small scale factors apply division
before multiplication to avoid losing representable bounded results. Final finite magnitudes
are clamped to the requested bound for rounding. Unscaled immutable wheel values retain identity;
scaled values are independently allocated. Buffered methods reject undersized arrays before
mutation and leave trailing elements untouched.

Standard XRP differential and mecanum IO reuse two- and four-element wheel buffers, read each
chassis field once per drive call, and scale wheels together before converting to motor power.
Invalid chassis fields or nonfinite/nonpositive maximum linear speed command every coupled
motor neutral. Constructor wheel-radius arguments must be finite and positive; validation
does not call an overridable getter before subclass initialization. Subclasses own any overridden
configuration. Raw setPowers clamps each explicitly requested finite power, neutralizing the
complete vector if any component is invalid. Failed writes or incomplete motor refresh attempt
every stop before propagating the original failure. stop attempts every motor and retains
secondary failures as suppressed exceptions, without self-suppressing a reused exception.
Failed stop attempts do not prove physical motors reached neutral.

Drive power is computed directly as wheel speed divided by the larger of the requested maximum
linear speed and the vector's maximum absolute wheel speed. This avoids a redundant intermediate
rescaling and preserves representable power ratios even when the configured speed bound is
subnormal. Nonfinite wheel calculations still neutralize the complete output vector.

The standard drive paths are single-owner and allocation-free under the measured host fixtures.
Interface default drive methods allocate scratch for custom implementations; getWheelDistances
returns an allocated Pair, and chassis-returning math overloads allocate their result. Motor
fields consumed for wheel distances are expected to be cached by the concrete adapter. This
raw IO layer does not grant enable, establish feedback freshness or own the robot's fault latch;
those remain the controller/adapter's responsibility. Swerve speed normalization preserves
angle references; the remaining steering solver's allocation and dynamics need a separate review.

## FTC swerve module acquisition and outputs

`SwerveModuleIOFtc` captures motor ticks-per-revolution and analog range from SDK metadata;
explicit constructor overrides support calibrated fixtures or reported-shaft conventions.
The old three-argument constructor remains available but no longer guesses 2048 ticks/rev.
Metadata must be valid; desktop fixtures supply it or explicit calibration. Construction
validates first, then attempts both neutral outputs before starting a sampler. Drive and
steer must be distinct device references. The owner configures physical gearing and polarity.

Drive signals are read once each per update. The analog worker pauses nominally 5 ms between
reads; this is not a guaranteed 200 Hz sampling rate. Freshness uses acquisition-start times
and defaults to 100 ms. Slow returns, retained samples, rewind and signed overflow cannot renew
validity. Output requests require fresh drive and analog snapshots. Invalid vectors, stale
feedback, closed state and failed paired writes neutralize both motors; failed neutral writes
remain explicitly unproven and are reported. Input timestamps conservatively identify the
oldest valid acquisition start rather than a new time for a retained analog value.

Close prevents subsequent actuation, invalidates snapshots, attempts both stops, interrupts and
joins only the owned worker, and reports a 100 ms join timeout. Borrowed devices remain open.
An uninterruptible SDK read can outlive close; a timeout is not proof of worker termination.
The adapter has no independent watchdog or arm controller: the robot owns enable, fault recovery
and periodic calls. Zero-allocation fixture tests do not establish hardware loop deadlines.

## Swerve inverse kinematics and angle wrapping

`SwerveKinematics` owns immutable module geometry and requires finite, nonnegative steering
velocity, steering acceleration and drive acceleration limits. Its first nonzero call after
construction/reset seeds ideal targets; an initial zero command seeds rest for a rate-limited
start. Subsequent updates bound signed drive-speed change, steering velocity and steering
velocity change. This is a discrete limiter and may overshoot a steering target while braking.
It does not prove actual steering tracking, enable state or physical motor response.

Zero commands, invalid commands/time and unrepresentable module vectors neutralize the coupled
drive vector immediately, hold valid previous angles and clear steering velocity. Reset clears
all history. Output buffers require distinct, nonnull states for every module and reject before
mutation; trailing capacity remains untouched. The duplicate check uses six identity comparisons
for four modules, and N(N-1)/2 generally. Owning overloads allocate; the buffered path reuses
storage. `Rotation2d` is an inline value class in module state, so field replacement is not
itself boxed allocation. Real hardware timing remains an independent validation requirement.

Angle wrapping preserves already-normalized Double values and reduces large inputs modulo
the represented Double period before shifting into [-pi, pi). It is not arbitrary-precision
reduction by mathematical pi. The legacy nonfinite-input fallback is zero; consumers must
validate raw measurements before wrapping. A finite wrapped angle is not a validity signal.

## XRP desktop simulation and network control

The desktop XRP engine receives canonical v2 drive frames through its own `DriveFrameReceiver`.
A new session needs a neutral handshake; retained frames do not renew the 500 ms receiver-time
lease. Expiry, invalid payloads and disabled teleop neutralize output. Store intent alone does
not confer network authority. Raw power fixtures are available before network ownership or
after an explicit pose reset; they are not a physical hardware control API.

Commands enter Store as joystick intent, and field-relative steering uses Store estimator
heading. Ideal optical pose observations enter Store through `PoseUpdate` with no independent
IMU measurement. Packed telemetry keeps physical truth, Store estimate and Store odometry
separate. This velocity-driven Dyn4j model includes collision/damping, but no motor torque,
slip or sensor noise. Accepted step duration is finite and in (0, 0.1] seconds; observations
use accumulated simulation milliseconds. This does not establish real-time or sub-millisecond
estimator accuracy. FTC walls use a center origin; XRP/FRC walls follow their corner-origin
field documents, including when switching league without changing dimensions.

## XRP JVM lifecycle and device doubles

XrpBaseRobot is an IO lifecycle foundation, not the exported MicroPython runtime or the full
physics simulator. It does not estimate pose or measure battery voltage: pose is explicitly
reset by a caller and unmeasured battery voltage is NaN. Pose reset validates all three finite
components before replacing the previous snapshot. Concrete integrations own feedback validity,
leased/armed output gating and any overridden lifecycle methods.

Mode changes neutralize before entering INIT/AUTO/TELEOP; a failed neutral boundary leaves
DISABLED. Stop marks DISABLED before attempting neutral. Periodic validates a finite positive
dt, neutralizes INIT/DISABLED before any refresh, and refreshes drivetrain and sensors once on
success. Any failed tick marks DISABLED and attempts neutral before propagating its original
exception, suppressing a distinct cleanup failure. A later successful tick does not re-enable.
This is attempted neutral on exception, not proof that a failing physical device stopped.

IO update has no timestep argument. The default motor double advances exactly one 20 ms fixture
step per call with ideal velocity effort * 30 rad/s, irrespective of wall time or periodic dt.
Its finite commands clamp to [-1, 1] and nonfinite commands neutralize; unknown injected position
remains unknown through update/stop. This is not an inertia, back-EMF or physical motor model.
Sensor doubles retain injected cached values, including invalid values, with no-op refresh.
Line detection uses valid normalized reflectance above 0.5 (0 white, 1 black); false alone is
not proof of valid off-line feedback. Controllers must validate raw readings before motion.

Servo double positions clamp finite commands to [0, 1]; nonfinite commands throw before changing
the prior command. There is no universal safe servo angle, and retaining a prior position is
not a PWM-off operation. Channel identity and physical output safety belong to the integration.
The standard lifecycle/valid-command paths reuse storage under host allocation tests; failure
exceptions and manually replaced pose snapshots may allocate.
