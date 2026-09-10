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
estimator accuracy. FTC and XRP walls use a center origin; FRC walls use a corner origin,
including when switching league without changing dimensions. XRP startup without an explicit
field uses XRP dimensions rather than discovering sibling FTC assets. Its fixture launch/reset
is centered in Y, facing positive X, 0.35 m from the negative-X boundary (or at field center
when the half-length is smaller). Reset uses the currently loaded field dimensions. This is
a fixture pose, not a collision-free placement guarantee for arbitrary obstacles or chassis sizes.

## AprilTag map interchange

Raw `decodeWpilib` and `encodeWpilib` preserve WPILib coordinates. Canonical consumers use
`decodeForField` and `encodeWpilibForField` instead. WPILib JSON uses the blue-wall corner
origin with +X inward, +Y left and +Z up. For a centered FTC/XRP field, the configured
`blueDriverStation` determines the inward direction: WEST is 0 degrees, SOUTH +90, EAST
180 and NORTH -90. Rendering axis preferences do not alter this robot frame.

For WPILib length L and width W, centered position is `R(theta) * (p - (L/2, W/2))`.
Orientation is pre-multiplied by `Rz(theta)`, preserving roll/pitch and rotating yaw.
Quarter-turns swap the destination X/Y extents on rectangular fields. ARES-to-ARES import
aligns the source and destination blue-wall frames using both documents' station metadata;
this is a defined import alignment rule, not a measurement of physical field placement.
Same-frame imports avoid subtracting and re-adding large offsets that would erase small poses.

Limelight `.fmap` transforms are centered in the destination's canonical axes. FRC translation
uses each supplied source dimension; only a missing axis falls back to the current target
dimension. Studio replacement adopts each known dimension independently. Merge retains the
current dimensions and existing tag IDs. Preview coordinates therefore describe the same
canonical positions that apply will store. Edits, undo and redo invalidate a pending preview;
applying an unchanged import consumes it without creating a document revision.

Format detection parses text once and rejects ambiguous/unknown shapes. A recognized format's
validation error is retained rather than being hidden by another parser's failure. All of this
is file/editor work; it does not run in a periodic control loop or establish camera calibration.

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

## Shared four-module swerve IO and configuration

`SwerveHardwareIO` module buffers use front-left/front-right/rear-left/rear-right order.
Currents are amperes; absolute encoder positions are rotations. Checked getters require at
least four entries, preserve trailing storage when the underlying getter honors that contract,
and return true only for a valid flag plus four finite values. Invalid, incomplete or failed
reads clear all four entries to NaN; getter exceptions propagate unchanged. These are cached,
single-loop checks, not a physical freshness watchdog or an actuator-enable decision.

Telemetry implementations must snapshot retained arrays before returning, as required by
`ITelemetry`. The checked read path reuses caller storage; telemetry key construction and backend
publication are outside its allocation guarantee. Covariance-unaware implementations still
receive accepted vision pose/timestamp once through the documented fallback.

`SwerveModuleConfig` constructor/copy require nonblank identities and finite geometry/calibration.
FTC hardware names remain verbatim. CAN getters reject malformed, overflowing or negative numeric
identities instead of substituting device zero; explicit zero remains valid at this shared boundary.
Vendor-specific address ranges, bus/device uniqueness, geometry plausibility and physical calibration
are platform responsibilities. Reflection/unsafe deserializers that bypass constructors need their
own validation. CAN parsing is a setup operation, not part of the checked periodic measurement path.

## CTRE swerve acquisition cache

`SwerveCtreDrivetrainReader.refresh()` revokes prior validity, samples each of its 36 cloned
status signals once and takes one owning vendor state copy. Getters never refresh signals or
fetch vendor state. One vendor clock read after acquisition supplies the shared age reference;
per-signal age checks do not repeat native clock calls. Status, timestamp validity, finite values
and age bounds are all required.
Fast current/encoder/IMU measurements and vendor motion expire after 100 ms; 4 Hz diagnostic
signals expire after 750 ms. Cached age conservatively includes RobotClock elapsed time since
acquisition started; clock rewind/overflow and late refreshes cannot renew validity.

The signal group remains unavailable if any configured signal fails validation. Vendor pose/
wheel motion is checked separately, so a bad diagnostic signal need not discard a fresh finite
odometry observation. An acquisition exception revokes both groups. Unknown numeric getters
return NaN; fault bit 6 (0x40) means unavailable instead of reporting a healthy zero fault code.
Bits 0..5 retain drive/steer hardware, brownout and temperature meanings. All four-entry getters
reject short buffers before mutation and preserve trailing caller storage.

The Phoenix source adapter owns cloned signal caches and borrows devices. Frequency configuration
failure rejects construction. It does not request unused steer-current traffic or change the
odometry-owned yaw/rate update frequencies. Native state is acquired through `getStateCopy()` to
avoid sharing CTRE's mutable callback state. That copy allocates; changed immutable ARES motion
snapshots also allocate. Cached getters and unchanged ARES snapshots reuse storage. Real CAN timing,
bus loading, device response and whole-loop allocation need hardware/runtime measurement.

## CTRE swerve output writer

Explicit X-brake requests do not depend on unused motion fields or power scale. Normal motion
requires finite X/Y/omega and finite scale, then clamps the scale to [0, 1]. Invalid input attempts
X-brake before throwing. A failed motion write also attempts brake and rethrows the original
failure; a distinct cleanup failure is suppressed without self-suppression. An already-failed
brake is not immediately retried inside the same call. X-brake requests zero drive velocity and
steering position control, not PWM-off for every actuator; actual stopping is not proven by return.

Field-centric requests explicitly select Phoenix's fixed BlueAlliance perspective because ARES
input owners already transform alliance-relative translation into the field frame. A drivetrain's
operator-perspective setting must not rotate those commands again. Both field- and robot-centric
requests use velocity drive and position steering control; selecting a coordinate frame must not
switch to the vendor's default open-loop voltage drive mode. They reuse mutable request/speed
objects. The writer and synchronous consumer share one loop; observers retaining
request values must snapshot them during the call. Valid writes and safe requests have measured
host zero-allocation paths; invalid arguments, exceptions and native execution are separate.
Physical speed limits, configuration, feedback freshness and explicit enable/arm belong to the
owning hardware/controller lifecycle and are not established by finite command values alone.

## FRC swerve bridge lifecycle and estimator inputs

`FRCSwerveHardwareIO` serializes refresh, cached reads, writes, estimator operations and close.
Motion is permitted only with fresh signal and pose/motion snapshots and no reported drive/steer
hardware, brownout or temperature faults. A denied write selects X-brake even when its unused
motion data is invalid. Refresh immediately brakes on invalid feedback; acquisition exceptions
also attempt brake and preserve the original cause. This gate does not establish robot enable,
configuration correctness, or whole-robot fault recovery. Those remain explicit owner contracts.

Close first revokes cached feedback, then attempts brake and native destruction once. It attempts
destruction even if brake fails, preserving distinct cleanup failures without self-suppression.
Repeated close/safe calls do not access native resources. Closed getters report unavailable data;
history reads return false; writes, refresh and estimator mutation reject the call. Destruction
is not retried after an exception because the vendor may already have partially destroyed resources.
Successful construction transfers close ownership; unrelated access to the native drivetrain must
not race this bridge. Lock waits and native calls have no measured deadline bound and cannot replace
an independent hardware watchdog. A request/close result does not prove physical stopping.

Vision and seed poses require finite X/Y and **raw** heading before wrapping. `Rotation2d.radians`
can turn a nonfinite input into the legacy zero fallback and must not be used to validate raw data.
Vision timestamps remain finite Phoenix-epoch seconds supplied by the caller; no latency is
subtracted here. Explicit standard deviations must be finite and positive. Invalid covariance
is rejected instead of silently using retained vendor covariance. Historical sampling writes all
three finite coordinates only on success and preserves caller storage on absence, invalid values
or exceptions. Hard pose reset revokes the old cache and brakes before the native reset; a new
valid refresh is required before motion resumes.

Cached getter allocation probes use five equal windows, requiring at least one zero-byte window
and at most 64 KiB total transient allocation, matching the existing CTRE writer probe. This is
weaker than requiring every window to be zero; small nonzero results are retained and reported.
The shared probe is calibrated against empty work and an escaping allocation on every iteration.
It does not measure native IO allocation, lock contention deadlines or physical loop timing.

## Canonical trajectory ownership and numerical sampling

`TimedTrajectory` snapshots state/event lists and nested module-force lists. Normal states whose
force list is already the immutable empty list are reused. Input lists must not mutate during
construction/copy. The owned state list supports random access, so binary-search sampling does not
degrade to indexed linked-list traversal. Exact knots and endpoints return stored states; interior
samples allocate and independently interpolate scalar fields. Opposite-sign scalar endpoints use
a convex weighted sum to avoid overflowing their difference. Heading and tangent interpolate along
the shortest wrapped arc; force feedforwards retain nearest-sample selection, choosing the later
sample at midpoint ties. This interpolation is not integration of continuous dynamics.

Raw pose headings must be finite before wrapping, and trajectory distance must be nonnegative.
Value equality, hash, copy, string and destructuring methods are retained, but `TimedTrajectory`
is now an ordinary Kotlin class so its reflection `isData` flag changes. Its public API signatures
remain checked. Converting to the distance-based `Path` rejects an unrepresentable speed magnitude;
that adapter still discards time, acceleration and module-force information by design.

## Bounded spatial generation and timing

Requests and direct spatial generation enforce a 100,000-sample budget before allocating sampled
points. A segment uses at least two subdivisions so short rest-to-rest moves have an intermediate
velocity sample. Subdivision counts round up to respect spacing. Direct spatial generation retains
its legacy invalid-spacing fallback; canonical requests reject invalid spacing explicitly.
Finite geometry that cannot fit the budget or numeric representation is rejected with an exception
at the direct spatial API, or a diagnostic at the canonical provider. Duplicate provider-engine
registrations are rejected, and provider capability is checked once per candidate per request.

The jerk-limited provider preserves each translated waypoint's heading, interpolating it over
that segment's distance. Coincident waypoints with different headings require rotation without
translation and receive an unsupported-request diagnostic. Positive tiny distances, speeds and
curvatures are no longer treated as zero. Centripetal speed limits apply at every nonzero curvature.
Heading work is hoisted out of the spatial loop; segment counts and input snapshots avoid repeated
distance calculations, capacity growth and indexed traversal of caller linked lists.

Segment time uses a normalized trapezoidal speed calculation without a minimum-time floor.
Angular-acceleration scaling checks the angular velocities actually emitted in states at their
own intervals. Square/cube roots are applied before division to avoid overflowing a ratio whose
root is representable. Uniform time stretching continues to scale velocity, acceleration and jerk
coherently. Cumulative times must remain finite and strictly distinguishable. These are discrete
finite-difference bounds on a piecewise-linear spatial seed; they do not prove continuous jerk,
force feasibility, smooth corner traversal or physical robot tracking. Requested entry/exit speeds
may be reduced by uniform time stretching, with a handover warning retained.
