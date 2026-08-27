# Project Retrospective

## Milestone: v2.6 — Dynamic Swerve Trajectory Optimization & Obstacle Avoidance

**Shipped:** 2026-05-18
**Phases:** 3 | **Plans:** 3

### What Was Built
1. Spline Curvature-based centripetal linear velocity capping to prevent tipping or sliding on sharp corners.
2. Angular steering acceleration and drive acceleration rate-limiting caps inside the Swerve Kinematics engine.
3. Local 2D costmap grid state reducer projecting distance sensors into world coordinates via EKF pose updates.
4. Lightweight Vector Field Histogram (VFH+) planner featuring detour side-locking memory and path progress projection passing checks to prevent early corner-cutting and steering oscillations.
5. High-fidelity closed-loop swerve path following simulator verification confirming collision safety and target arrival thresholds.

### What Worked
- Blending feedback force vectors relative to the VFH+ safe detour angle prevented target rush, resulting in elegant detours around costmap obstacles.
- Emphasizing path-relative progress projections instead of simple robot-coordinate boundaries guaranteed stable, consistent detour side-locking.
- Re-injecting the nominal target vector as a candidate heading when safe completely eliminated hysteresis and steering jitter.

### Key Lessons
- Side-locking memory is critical when running dynamic planners; without it, subtle lateral sensor noise or speed changes can trigger wild high-frequency trajectory oscillations.

---

## Milestone: v2.4 — FRC/FTC Vision & Multi-Sensor Kalman Filter Integration

**Shipped:** 2026-05-18
**Phases:** 4 | **Plans:** 4

### What Was Built
1. Thread-safe sliding chronological queue (`VisionMeasurementBuffer`) to sort incoming asynchronous, out-of-order vision packets.
2. High-performance, low-ambiguity and distance-based AprilTag outlier filter (`VisionOutlierFilter`).
3. EKF pose estimator (`PoseEstimator`) multi-sensor fusion with standard deviation controls and retroactive rewind/replay tracking.
4. Virtual 3D vision AprilTag camera detection system (`VisionSimulator`) under added Gaussian noise, latency delays, and outlier conditions.

### What Worked
- Creating a simulated top-down 3D visual field environment allowed testing vision-based EKF localization and outlier filtering resilience on pure JVM.
- Applying standard-deviation-weighted parameters on the Kalman Filter updates successfully smoothed trajectory lines under dynamic simulation stress.

### Key Lessons
- Thread safety and strict boundary checks are non-negotiable when dealing with high-frequency asynchronous sensory inputs, ensuring zero state crashes under intensive telemetry loads.

---

## Milestone: v1.1 — Driveable Base, Hardware Odometry & Telemetry

**Shipped:** 2026-05-16
**Phases:** 4 | **Plans:** 4

### What Was Built
1. Gamepad Input Mapping (Deadbands and curves via `InputMath`)
2. Hardware Odometry Bridge (`PinpointIO`, `OdometryMath`, `PoseUpdate`)
3. Field-Centric Drivetrain (`ChassisSpeeds.fromFieldRelativeSpeeds`)
4. FTC Dashboard & Telemetry (`FtcDashboardAdapter` and `TelemetryPacket` formatting)

### What Worked
- Extending the purely functional IO pattern to cover the Gamepad and the goBILDA Pinpoint driver proved exceptionally easy.
- Keeping math entirely pure and isolated allowed tests to confirm field-centric and odometry operations immediately without hardware.

### Key Lessons
- Providing standalone primitive-driven components (like `FtcDashboardAdapter` consuming `RobotState`) keeps the core library extremely portable. Android ART compatibility is maintained because there are zero allocations in the control loop.

## Milestone: v2.3 — FRC Autonomous Trajectory Following

**Shipped:** 2026-05-18
**Phases:** 2 | **Plans:** 2

### What Was Built
1. PathPlanner path loading and JSON deserialization from JVM classpath resources.
2. Initial trajectory alignment and odometry coordinate snap-synchronization inside `autonomousInit()` to prevent physics engine snapping instability.
3. Closed-loop PID & feedforward `HolonomicDriveController` path following logic integrated inside `ARESRobot.autonomousPeriodic()`.
4. Structured target pose telemetry and path tracking error diagnostic reporting to AdvantageScope.

### What Worked
- Replaying spline coordinates on JVM classpath tests allowed mathematical trajectory follower validation with zero dependency on simulation runtime.
- Snapping and aligning the `dyn4j` physics body coordinates at the precise path start pose before launching the controller eliminated position ejection glitches.

### Key Lessons
- Thread-safe asset loading is critical when scaling path planners to run asynchronously, avoiding file contention issues during fast automated runs.

---

## Milestone: v2.2 — FRC Physics Simulation

**Shipped:** 2026-05-18
**Phases:** 2 | **Plans:** 2

### What Was Built
1. High-fidelity dyn4j rigid-body swerve simulator with custom field obstacle collision shapes.
2. Speed regulated flywheel subsystem with momentum inertia profiles and P-controller.
3. AdvantageScope telemetry streaming of actual simulated poses and flywheel speeds.

### What Worked
- Rigid-body collisions mapping field borders and hubs made the simulator feel extremely realistic, providing highly valuable feedback for driver practice.

---

## Milestone: v2.1 — FRC CTRE Swerve Integration

**Shipped:** 2026-05-18
**Phases:** 3 | **Plans:** 3

### What Was Built
1. Platform-agnostic `SwerveModuleIO` hardware abstraction wrappers.
2. Thin airlock for CTRE CANivore bus sync (`waitForUpdate` Phoenix 6 update rate).
3. Integrated FRC `ARESRobot` central framework loop routing telemetry to WPILog.

### What Worked
- Synchronizing the central state dispatch thread directly with the CANivore thread reduced odometry latency jitter to near-zero.

