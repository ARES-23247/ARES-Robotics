# Milestones

## v3.0 FRC Unified Robot Integration & Full System Verification (Shipped: 2026-06-15)

**Phases completed:** 3 phases, 2 plans

**Key accomplishments:**

- Integrated the FRC Superstructure Redux state and swerve drivetrain kinematic model into a unified FRC robot execution loop.
- Set up AdvantageScope 3D visualization and telemetry streaming via NT4 for live monitoring of robot subsystems.
- Relocated pure simulated FTC classes to the `:ftc-mocks` module to achieve clean compilation boundaries and mock isolation.

---

## v2.9 Physical Deployment & FRC Redux Superstructure Architecture (Shipped: 2026-05-18)


**Phases completed:** 8 phases, 8 plans

**Key accomplishments:**

- Programmed conditional dual-mode Swerve Hardware / Physics execution dynamically toggling between physical `FRCSwerveHardwareIO` and a high-fidelity simulator body.
- Built an Android-compatible dynamic path loader scanning from local Control Hub directory (`/sdcard/FIRST/`) first and classpath resources as fallback.
- Refactored `ARESMecanumTeleOp` to pipe Pinpoint absolute odometry and Limelight measurements directly to EKF.
- Implemented standard EKF pose-directed holonomic trajectory coupling for path following.
- Developed a mathematical Mahalanobis Distance outlier filter gating noisy camera frames and coordinate glitches.
- Created a consecutive-rejection automatic recovery snap-teleport mechanism.
- Wired driver-centric gamepad calibrations to zero gyros and reset coordinates.
- Ported the FRC Superstructure to a state-driven Redux model, defining platform-agnostic IO boundaries (`FlywheelIO`, `CowlIO`, `IntakeIO`, `FeederIO`, `ClimberIO`), Phoenix 6 CTRE hardware wrappers with 40A current limits, and high-fidelity rotational/gravity physics simulations.

---

## v2.8 Deterministic Input Replay & "What-If" Ghost Simulation (Shipped: 2026-05-18)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Established unified platform-independent Subsystem IO boundaries for decoupled core validation.
- Created a thread-safe raw input logger writing microsecond-accurate JSONL logs to disk.
- Engineered a dual-state offline replay runner executing recorded logs directly through EKF pose estimation.
- Built a Kotlin Compose Multiplatform desktop application visualizing real vs. ghost robot tracks with interactive tuning sliders.
- Configured NT4 telemetry to simultaneously stream real and ghost robot trajectories to AdvantageScope.

---

## v2.7 Path Execution & Dynamic Task Planning (Shipped: 2026-05-18)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Programmed continuous multi-path trajectory chaining, automatically matching endpoint velocity and orientation constraints at segment boundaries.
- Developed real-time dynamic Bezier tangent detour switching (<20ms execution) to seamlessly steer around costmap obstacles without snapping or stopping.
- Built a pure, stack-preemptive FSM Task Executor allowing prioritized mechanical task preemption (e.g., intake vs. shoot) synchronized with path markers.
- Configured EKF covariance-derived safety interlocks to instantly freeze drive commands if pose uncertainty exceeds safe thresholds.
- Implemented high-fidelity NetworkTables 4 (NT4) telemetry for real-time AdvantageScope path-following error visualization alongside microsecond-accurate JSONL on-device ActionLogger.

---

## v2.6 Dynamic Swerve Trajectory Optimization & Obstacle Avoidance (Shipped: 2026-05-18)

**Phases completed:** 3 phases, 3 plans

**Key accomplishments:**

- Implemented centripetal velocity limiting ($v_{\text{limit}} = \sqrt{a_c / |\kappa|}$) based on path curvature to prevent robot tipping.
- Built Swerve Rate Limiting for maximum angular steering acceleration and drive acceleration caps to eliminate actuator saturation.
- Designed local 2D Grid Costmap in Redux fusing time-synchronized distance sensor scans with EKF position coordinates.
- Programmed advanced VFH+ Obstacle Avoidance featuring polar histogram sector binning, 3-point smoothing, side-locking memory, and unblocked direct-to-target valley selection.

---

## v2.5 Hardened EKF Localization & Dynamic Sensor Fusion (Shipped: 2026-05-18)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Expanded global RobotState kinematics tracking to incorporate Pitch, Roll, angular velocities, and 3-axis G-forces.
- Designed 3D spatial coordinate boundaries to filter invalid or floating camera measurements outside of the physical field area.
- Built active motion blur and linear shock rejections, discarding updates during high-speed spins (>2.0 rad/s) or dynamic collision peaks (>2.5 G).
- Created dynamic EKF Process Noise ($Q$) scaling to trust absolute tag corrections 100x more under chassis tilt, paired with automatic wheel encoder freezing under beaching situations (>15° tilt).
- Implemented quadratic standard deviation AprilTag distance growth scaling alongside joint multi-tag geometric division.

---

## v2.4 FRC/FTC Vision & Multi-Sensor Kalman Filter Integration (Shipped: 2026-05-18)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Built a thread-safe, chronological sorting buffer (`VisionMeasurementBuffer`) to process asynchronous, latency-delayed vision measurements.
- Implemented standard-deviation-driven multi-sensor Kalman Filter pose fusion supporting retroactive history rewinds/replays.
- Created a robust outlier disambiguation filter (`VisionOutlierFilter`) to reject noisy, high-ambiguity or coordinate-divergent visual updates.
- Modeled physical AprilTag targets in a high-fidelity simulator (`VisionSimulator`) to verify tracking accuracy and noise tolerance under Gaussian disturbances.

---

## v2.3 FRC Autonomous Trajectory Following (Shipped: 2026-05-18)

**Phases completed:** 2 phases, 2 plans

**Key accomplishments:**

- Thread-safe loading and parsing of PathPlanner JSON trajectory assets inside `frc-app` module.
- Smooth start coordinate snapping/alignment for both Redux state and dyn4j physics bodies.
- Feedforward/feedback `HolonomicDriveController` loop integration inside FRC `autonomousPeriodic()` loops.
- Structured streaming of target poses and active deviations to AdvantageScope.

---

## v2.2 FRC Physics Simulation (Shipped: 2026-05-18)

**Phases completed:** 2 phases, 2 plans

**Key accomplishments:**

- Implemented high-fidelity dyn4j rigid-body 2D physics swerve simulator core.
- Configured dynamic timestep updates, floor friction damping, and collision dynamics (wall and hub).
- Designed flywheel physics with angular momentum (MOI/torque) and speed regulation.
- Automated simulation telemetry streaming to AdvantageScope.

---

## v2.1 FRC CTRE Swerve Integration (Shipped: 2026-05-18)

**Phases completed:** 3 phases, 3 plans

**Key accomplishments:**

- Decoupled physical motor and sensor wrappers into platform-agnostic `SwerveModuleIO` structures.
- Implemented thin airlock for CTRE CANivore (Phoenix 6 `waitForUpdate`) thread-safe bus sync.
- Constructed FRC `ARESRobot` central framework loop routing telemetry to WPILog.

---

## v2.0 Real Robot Deployment (Shipped: 2026-05-18)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Unified multi-module gradle build chain supporting pure JVM/Android project builds.
- Isolated mock SDK classes completely from physical production modules.
- Deployed TeleOp LinearOpMode with hardware maps for motor, pinpoint, and IMU configurations.
- Hardened wireless ADB developer deployment capabilities.

---

## v1.10 Match-Ready Telemetry & Hardware Integration (Shipped: 2026-05-17)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Developed unified `ARESController` edge-detection trigger logic.
- Embedded a lightweight, pure-Kotlin NT4 WebSocket server.
- Built physical hardware vision wrappers for Limelight 3A and AprilTags.
- Implemented high-performance asynchronous thread-safe local CSV data logging.

---

## v1.9 Core Hardware IO Interfaces (Shipped: 2026-05-17)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Implemented concrete `FtcRevHubIO`, `PinpointOdometryIO`, and auxiliary `I2C Octoquad` wrappers.
- Created robust absolute PWM and analog encoder wrappers for continuous CRServos.

---

## v1.8 Vision & Localization (Shipped: 2026-05-17)

**Phases completed:** 4 phases, 4 plans

**Key accomplishments:**

- Built platform-agnostic 3D geometry matrix classes (`Pose3d`, `Translation3d`, `Rotation3d`).
- Implemented immutable array-backed Kalman Filter pose estimator.
- Added pure `VisionState` processing and pose disambiguation structures.

---

## v1.6 Advanced Path Generation (Shipped: 2026-05-16)

**Phases completed:** 3 phases, 3 plans

**Key accomplishments:**

- Implemented Quintic Hermite Spline calculation from JSON waypoints.
- Added Motion Profiling (Trapezoidal velocity profiles) for smooth time-parameterized curves.
- Integrated PathPlanner Event Markers as `RobotAction` triggers inside the state machine.
- Validated smooth curved path following in the desktop simulator with AdvantageScope visualization.

---

## v1.2 Deployable Mecanum Base (Shipped: 2026-05-16)

**Phases completed:** 3 phases, 3 plans

**Key accomplishments:**

- Successfully deployed the functional core onto FTC hardware.
- The `MecanumKinematics` translates abstract speeds to wheel vectors.
- `MecanumHardwareIO` connects abstract wheel vectors to Rev `DcMotorEx` hardware map objects.
- `ARESMecanumTeleOp` ties gamepad inputs, sensors, hardware, and state together via Redux dispatch.

---
## v1.1 Driveable Base, Hardware Odometry & Telemetry (Shipped: 2026-05-16)

**Phases completed:** 9 phases, 9 plans, 0 tasks

**Key accomplishments:**

- (none recorded)

---

## v1.0 MVP (Shipped: 2026-05-16)

**Phases completed:** 5 phases, 5 plans, 0 tasks

**Key accomplishments:**

- (none recorded)

---
