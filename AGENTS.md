# ARES Robotics Workspace — Agent Guide

This workspace (`C:\Users\david\dev\robotics\ares`) contains **4 interconnected Kotlin projects** forming a unified, multi-league (FTC + FRC) robotics suite. The entire system — robot code, desktop dashboard, cloud gateway, shared math — is written in Kotlin. This guide is the map. Read it first.

## 1. The Four Projects at a Glance

| Project | Role | Tech | Key entry points |
|---|---|---|---|
| **ARESLib-Kotlin/** | Shared core library (the foundation) | Kotlin 1.9.23, JDK 17, Gradle multi-project | `core/`, `ftc-hardware/`, `frc-hardware/`, `ftc-mocks/`, `simulator/` |
| **ARES-FTC/** | FTC robot code (Android, DECODE 2025-26, team 23247) | FTC SDK 11.1, AGP 8.7, Kotlin 1.9.22 | `TeamCode/src/main/java/.../teamcode/`, `simulator/` |
| **ARES-FRC/** | FRC robot code (RoboRIO, 2024 Crescendo) | WPILib 2026.2.1, CTRE Phoenix 6, dyn4j, Kotlin 1.9.22 | `src/main/kotlin/com/areslib/frc/` |
| **ARES-Analytics/** | Desktop mission-control dashboard + cloud gateway | Compose Multiplatform 1.7.3, Ktor 3, DuckDB, JDK 17 | `app/`, `gateway/`, `shared/` |

## 2. Dependency Graph (read this before changing anything)

```
                         ARESLib-Kotlin  (foundation, pure Kotlin + math/control/pathing/state)
                       core / ftc-hardware / frc-hardware / ftc-mocks / simulator
                          │
            ┌─────────────┼──────────────────────────────┐
            ▼             ▼                                ▼
       ARES-FTC       ARES-FRC                      ARES-Analytics
   (thin season       (thin season                 (desktop dashboard;
    shell over        shell over                     :shared depends on
    ARESLib FTC)      ARESLib FRC)                   com.areslib:core)
            │             │                                │
            └────── NT4 (NetworkTables 4) ─────────────────┘
                  bidirectional telemetry contract
```

**Build order matters.** `ARESLib-Kotlin` MUST be published to local Maven before the others compile:

```powershell
# 1. Always do this FIRST after changing ARESLib-Kotlin:
cd ARESLib-Kotlin ; .\gradlew.bat publishToMavenLocal
# 2. Then build/consume projects. Coordinates: com.areslib:{core,frc-hardware,simulator}:1.0-SNAPSHOT
```

**Dependency mechanisms (multiple, intentionally):**
- **`ARES-Analytics` → ARESLib:** `mavenLocal()` artifact `com.areslib:core:1.0-SNAPSHOT` (`shared/build.gradle.kts`).
- **`ARES-FRC` → ARESLib:** BOTH composite build (`includeBuild("../ARESLib-Kotlin")` in `settings.gradle`) AND `mavenLocal()` artifacts for `core`, `simulator`, `frc-hardware`.
- **`ARES-FTC` → ARESLib:** JitPack-style coordinates `com.github.ARES-23247.ARESLib-Kotlin:<module>:master-SNAPSHOT`, with composite build substitution when the sibling repo exists.

> **Gotcha:** The package `com.areslib.frc` is **split across two repos** — base classes (`FrcSwerveRobot`, `FrcBaseRobot`, `FRCSwerveHardwareIO`, `FrcTelemetryManager`, `FrcPowerManager`, `FrcLimelightIO`) live in **ARESLib-Kotlin**'s `frc-hardware/` module, while the season layer (`ARESRobot`, `Marvin*`) lives in **ARES-FRC** in the *same package*. Same for FTC: `FtcMecanumRobot`/`FtcBaseRobot` are in ARESLib; `AresRobot`/OpModes are in ARES-FTC.

## 3. The "Season Layer" Pattern (ARES-FTC & ARES-FRC)

Both robot repos are **thin season-specific shells** over ARESLib. Almost all capability (kinematics, EKF pose, Redux store, power management, path following, sequencer, simulator, telemetry) comes from the library. A robot repo contributes only:

1. **Hardware IO bindings** for its specific hardware (`FtcIntakeIO`, `FtcFlywheelIO`, `FRCFlywheelHardwareIO`, `FRCCowlHardwareIO`, ...).
2. **Season-specific state** (`SeasonSuperstructureState`, `MarvinState`) and **Redux actions/reducers** that compose *over* ARESLib's `rootReducer`.
3. A **robot facade** (`AresRobot` in FTC, `ARESRobot` in FRC) wiring IO into subsystems.
4. **OpModes / mode orchestration** (FTC `@TeleOp`/`@Autonomous`; FRC `TimedRobot` + teleop/auto controllers).

**Redux flow (everywhere):** `Driver input → Facade → dispatch(RobotAction) → rootReducer (+season reducer) → immutable RobotState → writeOutputs(IO)`. State is 100% immutable; reducers are pure. Season reducers compose: `MarvinReducer.reduce` wraps `rootReducer`; never bypass this.

## 4. How Projects Communicate: the NT4 Contract

The dashboard is a **passive NT4 WebSocket client**. Robots/simulator publish; dashboard subscribes. Topic keys are the integration surface.

**Canonical topics (CCW-positive heading throughout):**
| Topic | Source | Meaning |
|---|---|---|
| `ARES/EstimatedPose/[0,1,2]` | sim `TelemetryPublisher` | Ground-truth pose (X, Y, heading rad) |
| `Drive/Pose_X`, `Drive/Pose_Y`, `Drive/Drive_Heading` | robot `ARESNetworkStatePublisher` | EKF pose |
| `Drive/Odom_*` | robot | Raw Pinpoint odometry |
| `ARES/Input/{vx,vy,omega,isTeleopMode,isFieldCentric,isRedAlliance,heartbeat,obstacles,...}` | dashboard → sim | Teleop drive / mode control |
| `Hardware/Motors/{name}/{Power,Velocity,CurrentAmps}` | robot | Per-motor data |
| `Topology/HardwareMap` | robot (once at init) | Serialized `HardwareTopology` JSON |
| `Superstructure/PackedState` | robot | Packed double-array subsystem state |

**Offline-first rule (CRITICAL):** Robots never push to the cloud. The `LogManagerServer` (NanoHTTPD, **port 5002**, in ARESLib core) exposes `/api/logs`, `/api/download?file=`, `POST /api/delete`. The desktop app *pulls* `.jsonl` logs over local Wi-Fi, parses to DuckDB, then the laptop handles GCS/Firestore sync via the gateway. Never add cloud calls inside robot code.

## 5. Cross-Cutting Conventions (apply to ALL projects)

These have caused real bugs. The canonical reference is **`ARESLib-Kotlin/GEMINI.md` §5** — read it before touching coordinates/heading.

### Heading & Coordinates
- **CCW-positive, math standard** everywhere: 0 rad = +X, π/2 = +Y (toward blue FTC wall). Radians internally; degrees for display only.
- **Pinpoint boundary:** `PinpointIO.kt` forces CCW-positive via `headingMult = if(isHeadingCcwPositive) 1.0 else -1.0`. **Do NOT add negations elsewhere** in the pipeline (EKF, store, telemetry, dashboard are all CCW+).
- **Dashboard field→canvas transform** (`ARES-Analytics/.../FieldCanvasUtils.kt`): `canvasX = -fieldY`, `canvasY = -fieldX`. Robot icon points RIGHT at rotation 0, so `PathRenderer.kt` applies a **mandatory `-90°` offset**. If you change the transform or icon, re-verify the offset — they are coupled.
- **Limelight target-space** (vision alignment): Y is UP (not Z). Robot heading/yaw = `-robotPoseTargetSpace.rotation.y`, NOT `.z`. See `ARESLib-Kotlin/.agents/AGENTS.md`.

### Motor names
FTC hardware-map names are `fl`, `fr`, `rl`, `rr` (**rear**, not `bl`/`br`). Dashboard visualizers must handle BOTH naming conventions.

### NT4 key normalization
All topic names are **stripped of leading `/`** (e.g. `ARES/Input/vx`, not `/ARES/Input/vx`) to avoid duplicate registration on the C++ ntcore server. Apply at publish and subscribe.

### Zero-GC hot paths
No allocations (no `DoubleArray`, `Rotation2d`, iterators, reflection) inside 50–100 Hz `update()`/sampling/steering loops. Use pre-allocated buffers and object pools (`kalmanGainPool`, `pathPool`). Prefer `when` over nested `if/else` (zero-allocation, idiomatic).

### Unified clock
**Never** call `System.currentTimeMillis()`/`nanoTime()` in library code. Use `com.areslib.util.RobotClock` so simulation/replay stay deterministic.

## 6. Per-Project Quick Reference

### ARESLib-Kotlin (`C:\Users\david\dev\robotics\ares\ARESLib-Kotlin`)
The foundation. **5 Gradle modules** (`settings.gradle.kts`): `core` → (`ftc-mocks` → `ftc-hardware`), `frc-hardware`, `simulator`. Maven group `com.areslib`, version `1.0-SNAPSHOT`.

- **`core/src/main/kotlin/com/areslib/`** packages:
  - `math/` — `geometry/` (Pose2d, Rotation2d, ChassisSpeeds, Matrix3x3), `kinematics/`, `filter/`, `coordinate/`, **`estimation/`** (EKF `PoseEstimator`, `KalmanFilter`, `OdometryFusionController`, `VisionMahalanobisFilter` — note: estimation lives under `math/`, not `control/`)
  - `control/` — `feedback/` (PID, LQR, LinearADRC), `profile/` (TrapezoidProfile), `safety/` (CurrentBudgetManager, BrownoutGuard, CBF), `drivetrain/` (HolonomicDriveController), `assist/` (SysIdManager, ShotSetup, VisionExtrinsicCalibrationController)
  - `pathing/` — `ThetaStarPlanner`, `HolonomicPathFollower`, `TrajectoryGenerator`, `SCurveTrajectoryParameterizer`, `BezierSpline`, `PathPlannerParser`/`PathPlannerAutoParser`, `AutoBuilder`, `NamedCommands`, `Costmap`, `VFHPlanner`
  - `state/` (`RobotState`, `DriveState`, `SuperstructureState`, `RobotFieldConfig`, `Alliance`), `reducer/` (`RootReducer` + slice reducers), `action/` (`RobotAction` sealed classes, `ActionLogger`)
  - `sequencer/` — `TaskExecutor`, `Task`, `RobotSequence` (NOT `auto/`)
  - `hardware/` — `HardwareRegistry` (self-registering devices + topology), `TopologyModels`, `SubsystemIO`, `drive/`, `vision/`, `actuator/`, `sensor/`
  - `logging/` — `LogManagerServer` (NanoHTTPD:5002), `ARESDataLogger`, `CloudExporter`, `DiagnosticRingBuffer`
  - `telemetry/` — `ITelemetry`, `NT4Telemetry`, `ARESNetworkStatePublisher`, `RobotWebServer` (:8082), `AresGamepad`, `TelemetryTopicConstants`
  - `networktables/` — custom NT4 server (`NT4Server` :5810, Java-WebSocket + MessagePack) + `org/frcforftc/.../NT4Compatibility.kt`
  - `subsystem/` — `Subsystem`, `SubsystemControllerBase`, `AresRobot`, `DrivetrainSubsystem`, `MecanumDriveFacade`, `SwerveDriveFacade`, `PowerManager`, `VisionTracker`
  - `drivetrain/` (`SwerveOffsetManager`), `kinematics/`, `input/`, `util/` (`RobotClock`, `PoseStorage`), `tuning/`
- **`ftc-hardware/`** — `FtcBaseRobot`, `FtcMecanumRobot`, `drivetrain/MecanumHardwareIO`, `PinpointIO`, `hardware/` (CachedHardware, FtcGamepadAdapter, FtcIndicatorLightIO, FtcPrismDriverIO, OctoquadIO, SrsHubIO, Rev bulk readers), `vision/`, `photon/`, `dsl/` (`FtcTeleOpDSL`)
- **`frc-hardware/`** — `FrcBaseRobot`, `FrcSwerveRobot`, `SwerveModuleIOPhoenix6`, `FRCSwerveHardwareIO`, `FrcLimelightIO`, `FrcPowerManager`, `FrcTelemetryManager`, `XboxControllerExt`
- **`ftc-mocks/`** — reimplements `com.qualcomm.*`, `org.firstinspires.ftc.*`, `android.*`, `com.acmerobotics.dashboard.*` so FTC code runs on desktop without Android.
- **`simulator/`** — dyn4j 2D physics, headless mode, OpMode runner, `DesktopSimLauncher` (main class), `VerificationApp`, NT4 bridge, LWJGL gamepad.
- **Docs:** `GEMINI.md` (canonical source of truth), `PROJECT.md`, `docs/onboarding/` (4 guides), `TEST_INFRA.md`, `.planning/`, `.agents/AGENTS.md` (Limelight/heading gotchas).
- **Build/test:** `.\gradlew.bat compileKotlin compileTestKotlin`, `test`, `:core:test`, `:ftc-hardware:test`, `publishToMavenLocal`. Tests are JUnit 5 with tiered E2E suites (`e2e/tier1`, `e2e/tier2`, `ZeroGcRegressionTest`).

### ARES-FTC (`C:\Users\david\dev\robotics\ares\ARES-FTC`)
FTC Android app, team **23247**, season **DECODE**. Built on FTC SDK 11.1 (the `FtcRobotController/` module is upstream boilerplate — don't edit it).

- **Team code:** `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/` (100% Kotlin despite `java/` dir):
  - `config/HardwareConstants.kt`, `dsl/` (`AresTeleOpBase`, `AresAutoBase`, `SubsystemStates`)
  - `hardware/` (`FtcIntakeIO`, `FtcFlywheelIO`, `SeasonInterfaces`)
  - `subsystems/` (`IntakeSubsystem`, `FlywheelSubsystem`, `PrismSubsystem`, `IndicatorLightSubsystem`)
  - `opmodes/` (`ARESMecanumTeleOp`, `IntakeShootTeleOp`, `ARESTuningTeleOp`, `ARESRemoteDriveOpOpMode`, `ARESAuto`, `TestAuto`, `NullOpMode`, `ARESMecanumDiagnostic`, `TeamStateStorage`) and `opmodes/robot/` (`AresRobot` facade, `AresDriveController`, `AresSuperstructureController`, `AresTelemetryHelper`)
  - `test/tools/SubsystemGenerator.kt` — interactive 6-file subsystem scaffolder following the Redux pattern
- **PathPlanner assets:** `TeamCode/src/main/assets/pathplanner/{paths,autos}/` + `paths/` (obstacles/apriltags/game_pieces). `pushPaths` Gradle task ADB-pushes these to the RC.
- **`simulator/` module** (desktop JVM, JDK 21): shares `TeamCode/src/main/java`, runs real OpModes against mocks. `runSim` → `DesktopSimLauncher --headless`; `CalibrationVerificationApp` exercises all SysId routines.
- **`.ares-robot.json`** — team/season/robot identity. **`ares_tuning.json`** — live-tuning config.
- **Build/deploy:** `.\gradlew.bat :TeamCode:assembleDebug`; deploy via `adb connect 192.168.43.1:5555` then `adb install -r`. Default FTC connection `192.168.43.1:5810`.

### ARES-FRC (`C:\Users\david\dev\robotics\ares\ARES-FRC`)
FRC RoboRIO code, **2024 Crescendo** ("Marvin XIX"), WPILib **2026.2.1**, CTRE Phoenix 6 v26.1.1 (all-TalonFX, CAN bus "CAN2"), dyn4j sim. Kotlin-first (single auto-generated `TunerConstants.java`).

- `src/main/kotlin/com/areslib/frc/`:
  - `Main.kt` (`object Main`), `ARESRobot.kt` (TimedRobot 446 lines), `PathLoader.kt`, `Dyn4jSimulation.kt`
  - `hardware/` — `FRCFlywheelHardwareIO` (4× TalonFX dev 9-12), `FRCCowlHardwareIO`, `FRCIntakeHardwareIO`, `FRCFloorHardwareIO`, `FRCClimberHardwareIO`, `FRCFeederHardwareIO`, `SeasonInterfaces`
  - `marvin/` — season logic: `MarvinState`, `MarvinAction`, `MarvinReducer` (composes `rootReducer`), `MarvinSuperstructure` (the `Subsystem`), `MarvinShooterSubsystem`/`MarvinIntakeSubsystem`/`MarvinClimberSubsystem` facades + sub-controllers (`MarvinFlywheel/Cowl/FeederController`), `MarvinConfig` (shot LUTs)
  - `sim/` — `Dyn4jPhysicsWorld`, `Dyn4jSwerveModuleSim`, `Dyn4jSimTelemetryPublisher`, `io/Simulated*IO`, `field/FrcFieldBuilder`
  - `robot/` — `FRCTeleOpDriveController`, `FRCAutoOrchestrator`
- `src/main/deploy/swerve_offsets.json`; `marvin19_layout.json` = custom dashboard layout. Custom Gradle task `fetchOffsets` pulls swerve calibration off the RIO.
- **No WPILib Command-Based** usage despite the vendordep — it's Redux actions all the way down.
- **Build/test:** `.\gradlew.bat simulateJava` (sim), `deploy` (RIO). Kover coverage; JUnit 5 tests under `src/test/kotlin/com/areslib/frc/` incl. `pathing/E2EAutonomousSimulationTest`.

### ARES-Analytics (`C:\Users\david\dev\robotics\ares\ARES-Analytics`)
Compose Multiplatform desktop dashboard + Ktor cloud gateway. Kotlin 2.0.21, Compose 1.7.3. **3 Gradle modules** (`:shared` → `:app`, `:gateway`). JDK 17.

- **`app/src/main/kotlin/com/ares/analytics/`** (MVI: `XxxState` + `sealed XxxIntent` + `XxxViewModel`):
  - `Main.kt` (single-instance lock, crash handler, 1440×900 window), `di/ServiceRegistry.kt` (~25 services, lazy tiers — **the index into all business logic**)
  - `service/` — `Nt4ClientService`+`nt4/`, `DatabaseService` (DuckDB)+`db/`, `FrameBatcher`, log decoders (`log/`: Wpi/Jsonl/Csv/Parquet/Hoot/DSLog/Rlog/Revlog/RoadRunner), analytics engines (`SummaryEngineService`, `SysIdService`, `CalibrationService`+`calibration/`, `DriverAnalysisService`, `AlertEngineService`, `AutoTunerService`, `ReplayEngineService`, `TrajectoryEstimator`), cloud (`FirebaseClientService`, `OAuthService`, `SyncEngineService`, `GoogleDriveService`, `TeamApiService`), `ProcessManagerService`, `SimulationService`, `GamepadService` (LWJGL)
  - `viewmodel/` — Main, Dashboard, FieldViewer, FieldEditor, PathPlanner, Tuning, SysId, Cloud, Profile, Settings, Onboarding, CameraStream, SubsystemGenerator + helpers (`field/`, `pathing/`, `sysid/`)
  - `ui/` — `theme/` (`AresTheme`, Colors, Type), `screens/` (16 screens), `components/dashboard/` (~40 widgets incl. FieldViewerCard, PoseViewerCard, TelemetryChartPanel, MecanumVisualizer, SwerveVisualizer, ControlLoopProfilerCard), `components/pathplanner/`, `components/core/`, `components/terminal/`, etc.
- **`gateway/src/main/kotlin/com/ares/analytics/gateway/`** — Ktor Netty on Cloud Run (:8080). `Application.kt`; `auth/FirebaseAuth.kt` (FirebasePrincipal, supports `MOCK_AUTH=true` dev); routes: `authRoutes` (`POST /api/auth/github`), `archiveRoutes` (upload-url/sync/delete/download-url, team robots CRUD), `diagnosticsRoutes` (`POST /api/diagnostics/forensics`, rate-limited).
- **`shared/`** — shared JSON (`AppJson`), `Models.kt` (field geometry, Obstacle, GamePiece, AprilTagPlacement), `PathPlannerModels.kt` (full PathPlanner v2025.0 schema), `models/` (Session, SessionSummary, TelemetryFrame, AlertRecord, WorkspaceConfig, TopologyNode, HardwareTopology, ForensicsRequest, DriverProfile).
- **Docs:** `ARCHITECTURE.md` (very detailed, 9 sections — data tiers, FrameBatcher, replay engine, SysId OLS, vision calibration, hardware topology), `AUDIT.md` (security findings), `reports/`, `.agents/AGENTS.md` (NT4 key map, canvas transform, `-90°` offset).
- **Build/run:** `.\gradlew.bat :app:run` (mainClass `com.ares.analytics.MainKt`); `.\gradlew.bat run` (root) orchestrates gateway (bg) + app (fg). Native dist: `:app:packageReleaseMsi`.
- **Known audit issues** (see `AUDIT.md`): hardcoded OAuth secret (reversed string), tenant-isolation gaps in rules, LLM→raw SQL via `Statement.execute()`, concurrency leaks in ReplayEngine. Be aware when touching auth/gateway/cloud code.

## 7. Working in This Workspace — Checklist

- **Fresh checkout?** Run `.\setup.ps1` (Windows) or `./setup.sh` (macOS/Linux) to clone all four subprojects as siblings of this file. Idempotent — existing dirs are skipped.
- **Changing ARESLib?** Run `publishToMavenLocal` in `ARESLib-Kotlin/` first, then rebuild consumers. For ARES-FRC/FTC the composite build will substitute automatically if the sibling repo exists.
- **Telemetry mismatch in the dashboard?** Check the NT4 topic map (§4 + `ARES-Analytics/.agents/AGENTS.md`), confirm leading-`/` stripping, and verify CCW+ heading consistency.
- **Heading/rotation looks wrong?** Re-read `GEMINI.md §5` and the negation rules. The usual culprits: extra negation added after `PinpointIO`, the `-90°` canvas offset, or Limelight `rotation.y` vs `.z`.
- **Writing a hot-path (robot or sim)?** Zero allocations. Use buffers/pools, `RobotClock`, `when` over nested `if`.
- **Adding a subsystem?** Follow the 6-file Redux pattern (State/Action/Reducer/IO interface/Ftc-or-Frc IO/Mock IO/Controller). See `ARES-FTC/TeamCode/.../test/tools/SubsystemGenerator.kt`.
- **Cloud/sync code?** Remember offline-first: robot serves `LogManagerServer:5002`; the laptop pulls then syncs. Never push from robot.
- **Tests:** JUnit 5 everywhere. ARESLib has tiered E2E + `ZeroGcRegressionTest`; ARES-FRC/FTC have subsystem & sim tests. Run with `.\gradlew.bat test` (per-module: `:core:test`, `:TeamCode:test`, etc.).

## 8. Key File Lookup

| Need | Go to |
|---|---|
| Canonical conventions | `ARESLib-Kotlin/GEMINI.md` |
| NT4 topic definitions | `ARESLib-Kotlin/core/.../telemetry/TelemetryTopicConstants.kt`, `ARESNetworkStatePublisher.kt`, sim `TelemetryPublisher.kt` |
| Dashboard↔topic map | `ARES-Analytics/.agents/AGENTS.md` |
| Field→canvas transform | `ARES-Analytics/app/.../ui/components/pathplanner/FieldCanvasUtils.kt`, `PathRenderer.kt` |
| Redux state shape | `ARESLib-Kotlin/core/.../state/RobotState.kt` |
| Root reducer | `ARESLib-Kotlin/core/.../reducer/RootReducer.kt` |
| Hardware registry/topology | `ARESLib-Kotlin/core/.../hardware/HardwareRegistry.kt`, `TopologyModels.kt` |
| Log server endpoints | `ARESLib-Kotlin/core/.../logging/LogManagerServer.kt` |
| FTC robot facade | `ARES-FTC/TeamCode/.../teamcode/opmodes/robot/AresRobot.kt` |
| FRC robot lifecycle | `ARES-FRC/src/main/kotlin/com/areslib/frc/ARESRobot.kt` |
| Dashboard business logic index | `ARES-Analytics/app/.../di/ServiceRegistry.kt` |
| Gateway HTTP surface | `ARES-Analytics/gateway/.../routes/{Auth,Archive,Diagnostics}Routes.kt` |
| PathPlanner file format | `ARES-Analytics/shared/.../PathPlannerModels.kt`, `ARESLib-Kotlin/core/.../pathing/PathPlannerParser.kt` |
