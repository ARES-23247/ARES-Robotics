# ARESLib-Kotlin

## What This Is
ARESLib-Kotlin is a foundational, cross-platform (FTC and FRC) robotics library built with Kotlin 1.9+. It provides a highly performant, functional, and immutable state-driven core. By leveraging a Redux-style state store and an "IO Layer" pattern, it fully decouples pure control logic (such as Swerve, Mecanum, and Differential kinematics, and PathPlanner trajectory following) from hardware SDKs, ensuring 100% offline testability.

## Core Value
100% pure, immutable, and testable control logic completely isolated from hardware SDKs, allowing the exact same mathematical core to run flawlessly on both FTC Control Hubs and FRC RoboRIOs.

## Requirements

### Validated
- ✓ Core Functional Scaffold: Redux-style state store (RobotState, DriveState, SuperstructureState, VisionState) and immutable actions/reducers. — v1.0
- ✓ Base Interfaces: HardwareIO, RobotState, OutputCommand. — v1.0
- ✓ FTC SDK Bridge: "Hollow Wrapper" LinearOpMode that initializes the dispatcher and runs pure functions. — v1.0
- ✓ FTC IO Layer: SwerveModuleIO implementation translating REV Hub hardware maps into immutable state. — v1.0
- ✓ FRC CTRE CANivore "Airlock": Thin IO layer optimized for Phoenix 6 (`waitForUpdate` synchronization) dispatching to central store. — v1.0
- ✓ FRC Logging Adapter: Native WPILib Struct API serialization for AdvantageScope logging of data classes. — v1.0
- ✓ Kinematics Engines: Pure control logic for Swerve, Mecanum, and Differential drivetrains. — v1.0
- ✓ Functional PathPlanner Interpreter: Pure JSON parser and Trajectory follower (`calculateTrajectoryOutput`) returning Holonomic Drive Controller target speeds. — v1.0
- ✓ Gamepad & Input Mapping (Deadbands and curve mapping) — v1.1
- ✓ Hardware Odometry IO (goBILDA Pinpoint / OTOS wrapper) — v1.1
- ✓ Field-Centric Driving (Inverse rotation math) — v1.1
- ✓ FTC Dashboard Integration (Live telemetry and field rendering) — v1.1
- ✓ Mecanum Kinematics Math — v1.2
- ✓ Mecanum Hardware IO (DcMotorEx bridge) — v1.2
- ✓ Deployable TeleOp LinearOpMode — v1.2
- ✓ PathPlanner JSON parsing logic into immutable Path structs — v1.3
- ✓ Pure Holonomic Drive Controller for trajectory following — v1.3
- ✓ Autonomous OpMode for deploying paths on FTC hardware — v1.3
- ✓ Dyn4j Desktop Simulation & Visualization (AdvantageScope telemetry streaming) — v1.4
- ✓ Trajectory Following in Simulation (HolonomicDriveController + path sampling) — v1.5
- ✓ Quintic Hermite Spline calculation from waypoints. — v1.6
- ✓ Motion Profiling (Trapezoidal velocity profiles). — v1.6
- ✓ Integration of PathPlanner Event Markers as `RobotAction` triggers in the state machine. — v1.6
- ✓ Validation of smooth curved path following in the desktop simulator. — v1.6
- ✓ 3D coordinate data structures and matrix mathematics. — v1.8
- ✓ Immutable chronological array-backed Kalman Filter. — v1.8
- ✓ IO Layer abstraction for Limelight. — v1.8
- ✓ FtcRevHubIO, PinpointOdometryIO, I2C Octoquad wrappers — v1.9
- ✓ Absolute encoder wrappers for CRServos — v1.9
- ✓ Advanced Gamepad/Controller Architecture (edge detection and custom triggers) — v1.10
- ✓ Native NetworkTables 4 (NT4) Server — v1.10
- ✓ Physical Vision Hardware Wrappers (Limelight 3A & VisionPortal) — v1.10
- ✓ Centralized RobotConfig mapping — v1.10
- ✓ On-Device SD Card WPILog/CSV Logging — v1.10
- ✓ Unified Gradle build chain (JVM/Android module compatibility) — v2.0
- ✓ Mock class isolation from production code — v2.0
- ✓ Registered TeamCode OpMode with @TeleOp annotation — v2.0
- ✓ Hardware configuration mapping (motors, pinpoint, IMU) — v2.0
- ✓ Resilient telemetry on real hardware — v2.0
- ✓ Successful wireless ADB deploy to Control Hub — v2.0
- ✓ FRC CTRE Swerve Integration — v2.1
- ✓ FRC dyn4j Physics Simulation: dynamic time delta steps, floor damping, self-healing spawn guards, static wall and hub collisions — v2.2
- ✓ FRC Autonomous Trajectory Following (PathPlanner spline parsing, start coordinate snap-alignment, HolonomicDriveController loop, AdvantageScope diagnostics) — v2.3
- ✓ Thread-safe queue buffer for asynchronous vision camera measurements sorted chronologically before processing. — v2.4
- ✓ Ambiguity-based vision filter that rejects measurements with high pose ambiguity or when tag distance/rotation limits are exceeded. — v2.4
- ✓ Integrate multi-sensor vision measurements with retro-active EKF pose estimates in the `PoseEstimator`. — v2.4
- ✓ Validate correct Kalman-Filter vision pose corrections using high-fidelity simulations. — v2.4
- ✓ Hardened EKF outlier filter (3D boundaries, motion blur, and dynamic G-force collision lockout). — v2.5
- ✓ Dynamic odometry process noise scaling under high chassis tilt and beached freeze safeties. — v2.5
- ✓ Dynamic AprilTag measurement noise distance penalization and multi-tag confidence division. — v2.5
- ✓ Centripetal Limiting: Curved trajectory velocity capping ($v_{\text{limit}} = \sqrt{a_c / |\kappa|}$) based on curvature to prevent tipping. — v2.6
- ✓ Swerve Rate Limiter: Maximum angular steering acceleration and drive acceleration caps to prevent actuator saturation. — v2.6
- ✓ Sensor Costmap: Local 2D grid costmap in Redux fusing distance sensor probes with EKF coordinate offsets. — v2.6
- ✓ VFH+ Detours: Polar histogram sector binning, 3-point smoothing, detour side-locking memory, and nominal target candidate insertion. — v2.6
- ✓ Closed-Loop Verification: Collision-free trajectory following around static obstacles in physics simulator. — v2.6
- ✓ Path Chaining: Parsing and stitching multiple sequential PathPlanner paths together with smooth boundary constraint matching. — v2.7
- ✓ Dynamic Trajectory Detours: Real-time dynamic Bezier detour switching in response to costmap warnings. — v2.7
- ✓ NT4 diagnostics: Broadcast comprehensive tracking errors to AdvantageScope and FTC Dashboard in real-time. — v2.7
- ✓ Microsecond ActionLogger: Detailed JSONL command/actuator logs enabling offline deterministic simulation replay. — v2.7
- ✓ State-Driven Task Executor: Functional stack-preemptive state machine sequencing subsystem actions on path markers. — v2.7
- ✓ Safety Interlocks: Instant drive freezing and task queue aborts if EKF state covariance exceeds safety parameters. — v2.7
- ✓ E2E Simulation Validation: Fully automated physics-sim runs verifying combined kinematics, detours, and task transitions. — v2.7
- ✓ IO-01: Platform-independent SubsystemIO interfaces and inputs structures. — v2.8
- ✓ REC-01: Thread-safe raw input logger writing to microsecond-accurate JSONL. — v2.8
- ✓ REP-01: Offline replay runner executing input logs through EKF. — v2.8
- ✓ GUI-01: Native Compose Multiplatform desktop application visualizing real vs. ghost robot tracks with interactive tuning sliders. — v2.8
- ✓ TEL-01: Simultaneously stream real and ghost robot poses via NT4 to AdvantageScope. — v2.8
- ✓ FRC-SIM-01: Conditional dual-mode Swerve Hardware / Physics execution. — v2.9
- ✓ FTC-PATH-01: Dynamic PathPlanner .path loader from Control Hub filesystem. — v2.9
- ✓ FTC-EKF-01: Hardware Pinpoint & Vision measurement EKF pipeline integration. — v2.9
- ✓ FTC-EKF-02: Mahalanobis Distance / Chi-Squared outlier rejection and teleportation recovery. — v2.9
- ✓ DIAG-01: Real-robot gamepad calibrations and EKF diagnostic telemetry. — v2.9
- ✓ FRC-MS-01: FRC Superstructure Redux State Core and Pure Reducers. — v2.9
- ✓ FRC-MS-02: Platform-Agnostic Subsystem IO Boundaries. — v2.9
- ✓ FRC-MS-03: Phoenix 6 TalonFX & CANcoder CTRE Wrappers with Current Limits. — v2.9
- ✓ FRC-MS-04: High-Fidelity Superstructure Rotational and Gravity Simulator. — v2.9

- ✓ FRC-INT-01: Unified FRC Robot Loop — v3.0
- ✓ FRC-SIM-02: AdvantageScope Superstructure 2D/3D visualizer stream — v3.0
- ✓ FTC-SYS-01: Decouple simulated/mock FTC classes out of :core completely — v3.0

### Active

- [x] LOG-GOV-01: Runtime-specific sampling profiles and bounded compressed telemetry storage
- [x] LOG-GOV-02: Crash-safe active-file quarantine and ownership-scoped retention
- [x] OBS-LOG-01: NT4 and dashboard evidence for queue depth, drops, throughput, and pruning
- [x] IMPORT-GZ-01: Bounded streaming import for compressed ARES CSV logs
- [x] REPLAY-LIVE-01: Live rewind flushes pending NT4 frames, uses receipt-time persistence,
  transfers packed-pose ownership at replay boundaries, and reconstructs distinct historical poses
- [x] SOAK-FRC-01: Deterministic five-minute dashboard-control simulation
- [x] SOAK-FTC-01: One-hour real NT4 simulator control and estimator soak, with native live
  persistence, bounded pose-gap SLO, and subsecond rewind load
- [x] RELEASE-GATE-01: API compatibility plus final-coordinate validation in all three consumers

## Completed Milestone: v9.3.6 (Runtime Reliability, Logging Governance & Soak Validation)

**Goal:** Extended FTC and FRC development sessions remain responsive and diagnosable without
unbounded disk growth, hidden logger backpressure, stale estimator frames, or mutable-build runtime
failures.

**Target features:**
- LOG-GOV-01/02 (profiles, gzip, rotation, retention, recovery)
- OBS-LOG-01 (runtime health evidence)
- IMPORT-GZ-01 (safe desktop import)
- REPLAY-LIVE-01 (deterministic live and compressed-log rewind)
- SOAK-FTC-01/SOAK-FRC-01 (real and deterministic control endurance)
- RELEASE-GATE-01 (binary consumer validation before GitHub Maven publication)

### Out of Scope
- Mutable internal state within subsystems — breaks testability and the functional paradigm.
- AdvantageKit `@AutoLog` or KAPT usage — drastically increases build times and breaks cross-platform compatibility on Android.
- `com.qualcomm` or `edu.wpi` imports in pure logic files — tightly couples the logic to specific platforms.

## Context
- The library must be highly performant on the Android 9+ ART generational garbage collector, demanding minimized heap allocations within the main control loop by relying on short-lived immutable objects or value classes.
- For FRC, strict synchronization with the CANivore bus is required for zero odometry jitter.
- The project targets Kotlin 1.9+ features and standardizes on primitive-driven state handling.

## Constraints
- **Architecture**: Redux-style — State must be immutable and transitioned strictly via Actions and pure Reducers.
- **Garbage Collection**: Android ART GC constraints — Avoid heavy allocations in `opModeIsActive()` or `robotPeriodic()`.
- **Cross-Platform**: Code must build and run on both Android (FTC) and RoboRIO (FRC).
- **Tooling**: Pure data struct serialization for AdvantageScope rather than annotation generation.

## Key Decisions
| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Pure Functional Core | Maximizes testability and allows sharing 100% of mathematical logic between FTC and FRC. | ✓ Good |
| No KAPT / `@AutoLog` | Speeds up build times and ensures Android/RoboRIO cross-compatibility. | ✓ Good |
| Redux-style State Store | Centralizes all robot state, enabling time-travel debugging and unified logging. | ✓ Good |
| Chronological Vision Replay | Solves asynchronous pipeline latency by rewind/replay of historical odometry entries in EKF. | ✓ Good |
| Path Projection Side-Locking | Locks chosen detour side based on path progress vector projection to prevent oscillation. | ✓ Good |
| Target-in-Valley Candidate | Integrates direct target heading as detour candidate when unblocked to eliminate hysteresis. | ✓ Good |
| Stack-Based FSM Preemption | Allows mechanical subsystems to preempt and prioritize state transitions based on path events. | ✓ Good |
| Microsecond Action Replay | Structured JSONL telemetry logs write microsecond-accurate physical outputs to enable deterministic offline simulation replay. | ✓ Good |
| Path Progress Floor Velocity | Enforces a nominal floor velocity of 1.5 m/s in simulations when progress is driven strictly by target trajectory speed to prevent standstill profiles. | ✓ Good |
| Chi-Squared Outlier Gating | Employs Mahalanobis Distance outlier verification to isolate and reject spurious visual camera updates. | ✓ Good |
| Superstructure Redux State | Fully models FRC multi-state superstructures in pure immutable state and physics simulation. | ✓ Good |
| Runtime logging profiles | Match fidelity, desktop efficiency, and incident capture need distinct rates and budgets rather than one unbounded default. | ✓ Good |
| Completed-file ownership retention | Prune only logger-owned, finalized telemetry; never infer that an active or unrelated file is disposable. | ✓ Good |
| Real NT4 soak gate | Unit tests cannot prove control scheduling and pose-frame cadence across the actual client/server boundary. | ✓ Good |

---

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-08-20 after v9.3.6 runtime reliability validation completed*
