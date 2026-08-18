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
    ARESLib FTC)      ARESLib FRC)                   org.aresfirst.ares:core)
            │             │                                │
            └────── NT4 (NetworkTables 4) ─────────────────┘
                  bidirectional telemetry contract
```

**Release-validation order matters.** Normal consumers resolve immutable ARESLib binaries from Maven Central **and the GitHub-hosted ARES Maven repository** (`https://raw.githubusercontent.com/ARES-23247/ARESLib-Kotlin/maven`, the `maven` branch of ARESLib-Kotlin) under identical `org.aresfirst.ares` coordinates. The GitHub channel exists because Central's free tier enforces monthly publishing quotas (file/release counts); publish new releases there with `.\gradlew.bat publishGitHubRepository -ParesVersion=<final>` and push `build/github-repository` to the `maven` branch, then optionally stage to Central when quota allows. After changing the library, publish its isolated validation repository before testing consumers:

```powershell
# 1. Always do this FIRST after changing ARESLib-Kotlin:
cd ARESLib-Kotlin ; .\gradlew.bat apiCheck publishReleaseValidation
# 2. Test consumers with -ParesRepository=<absolute path>/ARESLib-Kotlin/build/release-repository.
#    Published coordinates use org.aresfirst.ares:<artifact>:<aresVersion>.
```

**Dependency mechanisms:**
- **Normal builds:** all consumers import `org.aresfirst.ares:ares-bom:<aresVersion>` and versionless module coordinates from Maven Central.
- **Unpublished binary validation:** pass `-ParesRepository=<path>/build/release-repository` after running ARESLib's `publishReleaseValidation`.
- **Focused source development:** pass `-ParesUseSiblingLib=true` to opt into the sibling composite build. This is never automatic.

> **Gotcha:** The package `com.areslib.frc` is **split across two repos** — base classes (`FrcSwerveRobot`, `FrcBaseRobot`, `FRCSwerveHardwareIO`, `FrcTelemetryManager`, `FrcPowerManager`, `FrcLimelightIO`) live in **ARESLib-Kotlin**'s `frc-hardware/` module, while the season layer (`ARESRobot`, `Marvin*`) lives in **ARES-FRC** in the *same package*. Same for FTC: `FtcMecanumRobot`/`FtcBaseRobot` are in ARESLib; `AresRobot`/OpModes are in ARES-FTC.

## 3. The "Season Layer" Pattern (ARES-FTC & ARES-FRC)

Both robot repos are **thin season-specific shells** over ARESLib. Almost all capability (kinematics, EKF pose, Redux store, power management, path following, sequencer, simulator, telemetry) comes from the library. A robot repo contributes only:

1. **Hardware IO bindings** for its specific hardware (`FtcIntakeIO`, `FtcFlywheelIO`, `FRCFlywheelHardwareIO`, `FRCCowlHardwareIO`, ...).
2. **Season-specific state** (`SeasonSuperstructureState`, `MarvinState`) and **Redux actions/reducers** that compose *over* ARESLib's `rootReducer`.
3. A **robot facade** (`AresRobot` in FTC, `ARESRobot` in FRC) wiring IO into subsystems.
4. **OpModes / mode orchestration** (FTC `@TeleOp`/`@Autonomous`; FRC `TimedRobot` + teleop/auto controllers).

**Context parameters (Kotlin 2.4):** Season-layer helpers in ARES-FTC may declare `context(ctx: FtcTeleOpContext<...>)` and resolve implicitly inside `teleOp { }` blocks (verified by `ContextParameterProbeTest`). This is allowed **only in internal app/season code** - never in published ARESLib signatures, and never for stored-block invocation, until explicit context arguments leave the experimental compiler flag.

**Redux flow (everywhere):** `Driver input → Facade → dispatch(RobotAction) → rootReducer (+season reducer) → immutable RobotState → writeOutputs(IO)`. State is 100% immutable; reducers are pure. Season reducers compose: `MarvinReducer.reduce` wraps `rootReducer`; never bypass this.

## 4. How Projects Communicate: the NT4 Contract

The dashboard is a **passive NT4 WebSocket client**. Robots/simulator publish; dashboard subscribes. Topic keys are the integration surface.

**Canonical topics (CCW-positive heading throughout):**
| Topic | Source | Meaning |
|---|---|---|
| `ARES/EstimatedPose/[0,1,2]` | sim `TelemetryPublisher` | Ground-truth pose (X, Y, heading rad) |
| `Drive/Pose_X`, `Drive/Pose_Y`, `Drive/Pose_Heading` | robot `ARESNetworkStatePublisher` | EKF pose (`Drive/Drive_Heading` supported as alias) |
| `Drive/Odom_*` | robot | Raw Pinpoint odometry |
| `ARES/Input/driveFrame` | dashboard → sim / FTC Remote Drive | Atomic leased v2 control frame (`double[8]`) |
| `ARES/Input/{obstacles,fieldConfig}` | dashboard → sim | Non-control field configuration payloads |
| `Hardware/Motors/{name}/Velocity` | `velocities[i]` | MecanumVisualizer |
| `Hardware/Motors/{name}/CurrentAmps` | `currents[i]` | MecanumVisualizer |

> **Warning:** Both `ARES/EstimatedPose/2` and `Drive/Drive_Heading` map to `robotHeading`. The last-arriving value wins per render frame. Ensure both sources publish consistent data.

**Offline-first rule (CRITICAL):** Robots never push to the cloud. The `LogManagerServer` (NanoHTTPD, **port 5002**, in ARESLib core) exposes `/api/logs`, `/api/download?file=`, `POST /api/delete`. The desktop app *pulls* `.jsonl` logs over local Wi-Fi, parses to DuckDB, then the laptop handles GCS/Firestore sync via the gateway. Never add cloud calls inside robot code.

## 5. Cross-Cutting Conventions (apply to ALL projects)

These have caused real bugs. The canonical reference is **`ARESLib-Kotlin/GEMINI.md` §5** — read it before touching coordinates/heading.

### Heading & Coordinates
- **CCW-positive, math standard** everywhere: 0 rad = +X, π/2 = +Y (toward blue FTC wall). Radians internally; degrees for display only.
- **Pinpoint boundary:** `PinpointIO.kt` forces CCW-positive via `headingMult = if(isHeadingCcwPositive) 1.0 else -1.0`. **Do NOT add negations elsewhere** in the pipeline (EKF, store, telemetry, dashboard are all CCW+).
- **Dashboard field→canvas transform** (`ARES-Analytics/.../FieldCanvasUtils.kt`): `canvasX = -fieldY`, `canvasY = -fieldX`. Robot icon points RIGHT at rotation 0, so `PathRenderer.kt` applies a **mandatory `-90°` offset**. If you change the transform or icon, re-verify the offset — they are coupled.

### Limelight Target-Space Coordinate System
When working with `VisionMeasurement.robotPoseTargetSpace` (AprilTag alignment), the axes are **non-obvious**:
- **X+**: Right of the tag (when facing it). **Y+**: Upward (VERTICAL — different from FTC field Z-up!). **Z+**: Outward from tag face.
- **Robot heading = `-robotPoseTargetSpace.rotation.y`**, NOT `.rotation.z` (which is tilt, not heading).
- Negate `rotation.y` because the Limelight's Y-axis rotation convention is opposite to CCW-positive.

```kotlin
// ✅ CORRECT
val robotYaw = -robotPoseTargetSpace.rotation.y
// ❌ WRONG — this is roll/tilt
val robotYaw = robotPoseTargetSpace.rotation.z
```

### Simulator State Sync Pitfall
The sim's `DesktopSimLauncher` maintains a local `var state = RobotState()`. This is **NOT synced** with the OpMode's Redux store for drive data (only superstructure is synced). `TelemetryPublisher.publishEstimatedPose()` must use `currentPose` (Dyn4j ground truth), NOT `state.drive.poseEstimator.estimatedPose` (always default/zeroed).

### Vision & Kidnapped Robot Recovery
- **FtcVisionTracker.kt**: Do NOT use `isInInit` flag. The snap triggers ONCE when `hasInitializedPoseWithVision` is false, then relies on `consecutiveVisionRejections >= 10` for further snaps during active play.
- **Alliance & Field-Centric Drive**: Mirror BOTH translational axes at the season input boundary. FTC currently mirrors for BLUE; FRC currently mirrors for RED because their driver-origin conventions differ. Do not add a second mirror in shared drivetrain math.
- **Simulator Alliance State**: The sim teleport ONLY happens on INIT. If you switch alliance in the dashboard, the simulator MUST dispatch `SetAlliance` to the OpMode's store. The dashboard's `ARES/Input/isRedAlliance` NT4 topic MUST default to `true` so the simulator matches the OpMode's default Red alliance on startup.

### Motor names
FTC hardware-map names are `fl`, `fr`, `rl`, `rr` (**rear**, not `bl`/`br`). Dashboard visualizers must handle BOTH naming conventions.

### NT4 key normalization
All topic names are **stripped of leading `/`** (e.g. `ARES/Input/vx`, not `/ARES/Input/vx`) to avoid duplicate registration on the C++ ntcore server. Apply at publish and subscribe.

### Zero-GC hot paths
No allocations (no `DoubleArray`, `Rotation2d`, iterators, reflection) inside 50–100 Hz `update()`/sampling/steering loops. Use pre-allocated buffers and object pools (`kalmanGainPool`, `pathPool`). Prefer `when` over nested `if/else` (zero-allocation, idiomatic).

### Hardware read caching
All hardware reads (voltage sensors, encoders, servo positions, analog inputs) must happen **once per loop** in `readSensors()`/`refresh()`/`update()` and be stored in cached fields. Getters and `writeOutputs()` must **never** trigger direct hardware reads — always reference the cached value.

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
  - `telemetry/` — `ITelemetry`, `NT4Telemetry`, `ARESNetworkStatePublisher`, `RobotStatusTracker`, `AresGamepad`, `TelemetryTopicConstants`
  - `networktables/` — custom NT4 server (`NT4Server` :5810, Java-WebSocket + MessagePack) + `org/frcforftc/.../NT4Compatibility.kt`
  - `subsystem/` — `Subsystem`, `SubsystemControllerBase`, `AresRobot`, `DrivetrainSubsystem`, `MecanumDriveFacade`, `SwerveDriveFacade`, `PowerManager`, `VisionTracker`
  - `drivetrain/` (`SwerveOffsetManager`), `kinematics/`, `input/`, `util/` (`RobotClock`, `PoseStorage`), `tuning/`
- **`ftc-hardware/`** — `FtcBaseRobot`, `FtcMecanumRobot`, `drivetrain/MecanumHardwareIO`, `PinpointIO`, `hardware/` (CachedHardware, FtcGamepadAdapter, FtcIndicatorLightIO, FtcPrismDriverIO, OctoquadIO, SrsHubIO, Rev bulk readers), `vision/`, `photon/`, `dsl/` (`FtcTeleOpDSL`)
- **`frc-hardware/`** — `FrcBaseRobot`, `FrcSwerveRobot`, `SwerveModuleIOPhoenix6`, `FRCSwerveHardwareIO`, `FrcLimelightIO`, `FrcPowerManager`, `FrcTelemetryManager`, `XboxControllerExt`
- **`ftc-mocks/`** — reimplements `com.qualcomm.*`, `org.firstinspires.ftc.*`, `android.*`, `com.acmerobotics.dashboard.*` so FTC code runs on desktop without Android.
- **`simulator/`** — dyn4j 2D physics, headless mode, OpMode runner, `DesktopSimLauncher` (main class), `VerificationApp`, NT4 bridge, LWJGL gamepad.
- **Docs:** `GEMINI.md` (canonical source of truth), `.planning/PROJECT.md` (roadmap), `docs/onboarding/` (4 guides), `TEST_INFRA.md`.
- **Build/test:** `.\gradlew.bat compileKotlin compileTestKotlin`, `test`, `:core:test`, `:ftc-hardware:test`, `apiCheck`, `publishReleaseValidation`. Tests are JUnit 5 with tiered E2E suites (`e2e/tier1`, `e2e/tier2`, `ZeroGcRegressionTest`).

### ARES-FTC (`C:\Users\david\dev\robotics\ares\ARES-FTC`)
FTC Android app, team **23247**, season **DECODE**. Built on FTC SDK 11.1 (the `FtcRobotController/` module is upstream boilerplate — don't edit it).

- **Team code:** `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/` (100% Kotlin despite `java/` dir):
  - `config/HardwareConstants.kt`, `dsl/` (`AresTeleOpBase`, `AresAutoBase`, `SubsystemStates`)
  - `hardware/` (`FtcIntakeIO`, `FtcFlywheelIO`, `SeasonInterfaces`)
  - `subsystems/` (`IntakeSubsystem`, `FlywheelSubsystem`, `PrismSubsystem`, `IndicatorLightSubsystem`)
  - `opmodes/` (`ARESMecanumTeleOp`, `IntakeShootTeleOp`, `ARESTuningTeleOp`, `ARESRemoteDriveOpOpMode`, `ARESAuto`, `TestAuto`, `NullOpMode`, `ARESMecanumDiagnostic`, `TeamStateStorage`) and `opmodes/robot/` (`AresRobot` facade, `AresDriveController`, `AresSuperstructureController`, `AresTelemetryHelper`)
  - `test/tools/SubsystemGenerator.kt` — interactive 6-file subsystem scaffolder following the Redux pattern
- **Canonical autonomous assets:** `.ares/routines/`, `.ares/autonomous-catalog.json`, `.ares/action-catalog.json`, and the generated project source. Loose PathPlanner/`.aresauto` deployment is unsupported.
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
  - `service/` — `Nt4ClientService`+`nt4/`, `DatabaseService` (DuckDB)+`db/`, `FrameBatcher`, log decoders (`log/`: Wpi/Jsonl/Csv/Parquet/Hoot/DSLog/Rlog/Revlog/RoadRunner), analytics engines (`SummaryEngineService`, `SysIdService`, `CalibrationService`+`calibration/`, `DriverAnalysisService`, `AlertEngineService`, `ReplayEngineService`, `TrajectoryEstimator`), desktop-owned OAuth/Google Drive sync, `ProcessManagerService`, `SimulationService`, and `GamepadService` (LWJGL)
  - `viewmodel/` — Main, Dashboard, FieldViewer, FieldEditor, PathPlanner, Tuning, SysId, Cloud, Profile, Settings, Onboarding, CameraStream, SubsystemGenerator + helpers (`field/`, `pathing/`, `sysid/`)
  - `ui/` — `theme/` (`AresTheme`, Colors, Type), `screens/` (16 screens), `components/dashboard/` (~40 widgets incl. FieldViewerCard, PoseViewerCard, TelemetryChartPanel, MecanumVisualizer, SwerveVisualizer, ControlLoopProfilerCard), `components/pathplanner/`, `components/core/`, `components/terminal/`, etc.
- **`gateway/src/main/kotlin/com/ares/analytics/gateway/`** — Small Ktor Netty service on Cloud Run (:8080). Google OIDC authentication, per-subject rate limiting, a 1 MiB body limit, `GET /healthz`, and the pit-forensics diagnostics route. Session storage and Google Drive synchronization remain desktop-owned.
- **`shared/`** — shared JSON (`AppJson`), `Models.kt` (field geometry, Obstacle, GamePiece, AprilTagPlacement), `PathPlannerModels.kt` (full PathPlanner v2025.0 schema), `models/` (Session, SessionSummary, TelemetryFrame, AlertRecord, WorkspaceConfig, TopologyNode, HardwareTopology, ForensicsRequest, DriverProfile).
- **Docs:** `README.md`, `ARCHITECTURE.md`, `docs/TELEMETRY_CONTRACT.md`, and `docs/OPERATIONS.md` describe current behavior. `AUDIT.md` and `reports/` are dated evidence snapshots and may include remediated findings.
- **Build/run:** `.\gradlew.bat :app:run` (mainClass `com.ares.analytics.MainKt`); `.\gradlew.bat run` (root) orchestrates gateway (bg) + app (fg). Native dist: `:app:packageReleaseMsi`.
- **Audit status:** Never infer the live backlog from `AUDIT.md` severity counts; verify each dated finding against current source and tests.

## 7. ARES-Analytics Desktop Launch Reliability (MANDATORY)

The desktop app has a single-instance lock and a native Compose/AWT window. A JVM can therefore be alive while no usable window exists, and a second launch can exit successfully without showing anything. Treat "the command is still running" and "the window is visible" as separate facts.

### Do not conflate these four failure modes

| Failure mode | Observable evidence | Correct response |
|---|---|---|
| **Orphaned lock owner** | A prior `com.ares.analytics.MainKt` JVM is still alive; the next process prints `App is already running (failed to acquire app.lock). Exiting.` and shows no window. | Confirm the ARES process with `jps -lv`, then run `ARES-Analytics\.\gradlew.bat killExisting`. Do not kill every Java process and do not delete `app.lock`; the operating-system file lock, not the file's existence, is authoritative. |
| **Missing desktop Main dispatcher** | Compose starts, but StateFlow/lifecycle collection fails or the window disappears. | Keep `org.jetbrains.kotlinx:kotlinx-coroutines-swing` beside `kotlinx-coroutines-core` and keep `DesktopCoroutineDispatcherTest`. `coroutines-core` alone is insufficient for Compose Desktop's Swing event thread. |
| **Native window/rendering regression** | The JVM and UI coroutines remain alive but no visible top-level HWND is capturable, or the window is blank/intermittent. | Let Compose/Skiko select its renderer. Preserve the explicit window state and presentation behavior in `Main.kt`, then verify a real HWND with the Compose desktop capture workflow. |
| **AWT event-thread crash** | The console reports `CRITICAL FAULT: Uncaught exception in thread 'AWT-EventQueue-0'` and names a `~/.ares-analytics/logs/crash-*.log`; the window may freeze or disappear while service threads keep the JVM and lock alive. | Read the newest crash log and fix the first relevant application stack frame. Then close through the verified window or use scoped `killExisting` cleanup. Treat the orphaned lock owner as a consequence, not the root cause. |

Offline NT4 connection failures and Google Drive sign-in errors are expected when those services are unavailable. They are not evidence that desktop window creation failed.

### Startup invariants agents must preserve

- `app/build.gradle.kts` must retain `kotlinx-coroutines-swing` at the same version as `kotlinx-coroutines-core`.
- Do **not** add `skiko.renderApi`, `skiko.renderApi.fallback`, forced Direct3D, forced OpenGL, or forced software-renderer JVM properties as a general startup fix. A renderer experiment requires its own branch, before/after captures on the affected machine, and a fallback/removal plan.
- `Main.kt` must retain an explicit floating, centered `1440 x 900 dp` window, `visible = true`, a `1100 x 700` AWT minimum size, and the `toFront()` / `requestFocus()` presentation calls unless a tested replacement provides the same guarantees.
- Keep the `Desktop window presented` diagnostic. It proves that the AWT peer reached the presentation hook; it does **not** replace screenshot verification.
- Keep the single-instance lock, bounded service disposal, and hard-exit watchdog unless the replacement is tested for normal close, hung shutdown, relaunch, and stale-process recovery.
- A direct packaged-app launch does not run Gradle's `killExisting` task. Do not assume that behavior exists outside `:app:run`.

### Mandatory launch/debug workflow for every agent

1. Run `git status --short --branch` in `ARES-Analytics` and preserve all unrelated or in-progress edits.
2. Separate compilation from presentation: run `.\gradlew.bat :app:compileKotlin` first. A successful compile does not prove a window exists.
3. Before killing anything, inspect Java command lines with `jps -lv | Select-String 'com\.ares\.analytics\.MainKt'`.
4. If a verified ARES JVM owns the lock but has no usable window, run `.\gradlew.bat killExisting` from `ARES-Analytics` and report the PID that was terminated.
5. Launch with `.\gradlew.bat :app:run` for released dependencies, or add `"-ParesUseSiblingLib=true"` only when intentionally validating sibling ARESLib source.
6. Require both the `Desktop window presented` log and a strict capture of a visible top-level `ARES Analytics` window. A full-desktop fallback image is not proof.
7. Inspect the captured image for actual app content rather than accepting a process ID, Gradle task state, or blank frame.
8. If the console reports an uncaught `AWT-EventQueue-0` exception, inspect the named crash log before cleanup. The first relevant application frame is evidence of the initiating UI defect; the remaining process and lock are secondary effects.
9. Close the app through its window so `disposeAndJoin()` and the shutdown watchdog are exercised. Use the tester skill's native `-CloseWindow` action; do not automate Alt+F4 through `SendKeys`, which can be delivered to a focused Compose text field as input. Use `killExisting` only as cleanup if graceful close fails.
10. Confirm `jps -lv` no longer lists `com.ares.analytics.MainKt`.
11. If startup, `Main.kt`, `ServiceRegistry`, Compose/coroutines dependencies, or Skiko settings changed, launch and capture a second time after a clean shutdown. This catches invisible lock owners and one-launch-only success.

Never report "the app launches" based only on `BUILD SUCCESSFUL`, a long-running Gradle process, `MainScreen` logs, or a screenshot tool's full-screen fallback. The required evidence is a visible ARES HWND containing rendered UI, followed by a shutdown that leaves no ARES JVM.

The detailed diagnostic decision tree and exact capture/cleanup commands live in `.agents/skills/compose-desktop-tester/references/startup-recovery.md`.

## 8. Working in This Workspace — Checklist

- **Fresh checkout?** Run `.\setup.ps1` (Windows) or `./setup.sh` (macOS/Linux) to clone all four subprojects as siblings of this file. Idempotent — existing dirs are skipped.
- **Changing ARESLib?** Run `apiCheck publishReleaseValidation` in `ARESLib-Kotlin/`, then rebuild consumers with `-ParesRepository=<absolute validation-repository path>`. Source substitution requires explicit `-ParesUseSiblingLib=true`.
- **Telemetry mismatch in the dashboard?** Check the NT4 topic map and dashboard variable mapping (§4), confirm leading-`/` stripping, and verify CCW+ heading consistency.
- **Heading/rotation looks wrong?** Re-read `GEMINI.md §5` and the negation rules in §5 above. Usual culprits: extra negation after `PinpointIO`, the `-90°` canvas offset, or Limelight `rotation.y` vs `.z`.
- **Writing a hot-path (robot or sim)?** Zero allocations. Use buffers/pools, `RobotClock`, `when` over nested `if`.
- **Adding a subsystem?** Follow the 6-file Redux pattern (State/Action/Reducer/IO interface/Ftc-or-Frc IO/Mock IO/Controller). See `ARES-FTC/TeamCode/.../test/tools/SubsystemGenerator.kt`.
- **Cloud/sync code?** Remember offline-first: robot serves `LogManagerServer:5002`; the laptop pulls then syncs. Never push from robot.
- **Analytics launches but shows no window?** Follow §7 and the Compose desktop tester startup-recovery reference. Check for an orphaned lock owner before changing rendering or UI code.
- **Tests:** JUnit 5 everywhere. ARESLib has tiered E2E + `ZeroGcRegressionTest`; ARES-FRC/FTC have subsystem & sim tests. Run with `.\gradlew.bat test` (per-module: `:core:test`, `:TeamCode:test`, etc.).

## 9. Key File Lookup

| Need | Go to |
|---|---|
| Canonical conventions | `ARESLib-Kotlin/GEMINI.md` |
| NT4 topic definitions | `ARESLib-Kotlin/core/.../telemetry/TelemetryTopicConstants.kt`, `ARESNetworkStatePublisher.kt`, sim `TelemetryPublisher.kt` |
| Dashboard↔topic map | This file §4 (Dashboard variable mapping table) |
| Field→canvas transform | `ARES-Analytics/app/.../ui/components/pathplanner/FieldCanvasUtils.kt`, `PathRenderer.kt` |
| Redux state shape | `ARESLib-Kotlin/core/.../state/RobotState.kt` |
| Root reducer | `ARESLib-Kotlin/core/.../reducer/RootReducer.kt` |
| Hardware registry/topology | `ARESLib-Kotlin/core/.../hardware/HardwareRegistry.kt`, `TopologyModels.kt` |
| Log server endpoints | `ARESLib-Kotlin/core/.../logging/LogManagerServer.kt` |
| FTC robot facade | `ARES-FTC/TeamCode/.../teamcode/opmodes/robot/AresRobot.kt` |
| FRC robot lifecycle | `ARES-FRC/src/main/kotlin/com/areslib/frc/ARESRobot.kt` |
| Dashboard business logic index | `ARES-Analytics/app/.../di/ServiceRegistry.kt` |
| Gateway HTTP surface | `ARES-Analytics/gateway/.../Application.kt`, `routes/DiagnosticsRoutes.kt` |
| PathPlanner file format | `ARES-Analytics/shared/.../PathPlannerModels.kt`, `ARESLib-Kotlin/core/.../pathing/PathPlannerParser.kt` |

## 10. Engineering Truthfulness & Anti-Hallucination Directives

All agents operating in this workspace must adhere to strict factual precision:
- **Zero Hyperbole:** Never use promotional or celebratory adjectives (e.g. "PERFECT TUNE!", "flawless", "100% no-code"). State exact engineering facts, line numbers, and limitations.
- **Explicit Fidelity Boundaries:** Always distinguish between simplified educational previews (1D Euler step response, 2D trig kinematics drawings) and production robot runtime or multi-body Dyn4j physics simulations. Explicitly state what educational sandboxes ignore.
- **Accurate Scope Boundaries:** Delineate where GUI scaffolding ends and Kotlin programming begins. Do not claim the system is "100% no-code" when competition logic and hardware mapping require code inspection.
- **Differentiate Preexisting vs. New:** Clearly state what was already present in the codebase versus what was incrementally added in the current task.
- **Strict Branch Discipline:** Keep feature work isolated on designated branches; never commit directly to `master` unless instructed.
