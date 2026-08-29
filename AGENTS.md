# ARES Robotics Workspace — Agent Guide

This repository is the authoritative **ARES Robotics source monorepo**. It contains six isolated
Gradle products that form one multi-league (FTC + FRC) robotics suite. ARES-owned application code
is Kotlin-first; upstream FTC controller sources and generated vendor bindings may remain Java.
The isolated Gradle builds are deliberate: Android/FTC, GradleRIO/WPILib, Compose Desktop, and the
published library have different toolchains and release boundaries.

## 1. Products at a Glance

| Project | Role | Tech | Key entry points |
|---|---|---|---|
| **ARESLib-Kotlin/** | Published foundation, schema/model/compiler, codegen, hardware modules, and simulation foundations | Kotlin 2.4.10, JDK 17, Gradle 8.14.5 | `project-schema/`, `project-model/`, `project-compiler/`, `core/`, `codegen/`, hardware/runtime/simulator modules, `ares-bom/` |
| **ARES-FTC/** | GUI-authored Lightbot reference robot and FTC season/simulator product | Kotlin 2.4.10, FTC SDK 11.1.0, AGP 8.7.0, Gradle 8.9 | `TeamCode/`, `simulator/`, `.ares/` |
| **ARES-FRC/** | FRC season, roboRIO/vendor adapters, and WPILib/HAL simulator product | Kotlin 2.4.10, WPILib 2026.2.1, Phoenix 26.1.1, Gradle 8.11 | `src/main/kotlin/com/areslib/frc/`, `.ares/` |
| **ARES-Analytics/** | ARES Robotics Studio desktop app, analytics database, cloud-optional services, and gateway | Kotlin 2.4.10, Compose 1.11.1, Ktor 3.5.2, DuckDB 1.1.3, Gradle 8.14.5 | `app/`, `gateway/`, `shared/` |
| **ARES-FTC-Starter/** | Canonical standalone FTC starter source exported into deterministic release archives | Same FTC toolchain | project root |
| **ARES-FRC-Starter/** | Canonical standalone FRC starter source exported into deterministic release archives | Same FRC toolchain | project root |

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

**Release-validation order matters.** Normal consumers resolve immutable ARESLib binaries from Maven
Central and the monorepo's GitHub-hosted Maven branch at
`https://raw.githubusercontent.com/ARES-23247/ARES-Robotics/maven`. Existing releases in the former
ARESLib repository remain immutable legacy artifacts. `release/ares-versions.properties` is the
single final-version manifest; `release/ares-source-tree.txt` binds its ARES version to the exact
library source tree. A packaging retry reuses any existing Maven publication byte-for-byte. After
changing the library, bump the ARES version and source-tree identity, then publish a unique isolated
candidate before testing any consumer:

```powershell
# 1. Always do this FIRST after changing ARESLib-Kotlin.
cd ARESLib-Kotlin
.\gradlew.bat test apiCheck publishReleaseValidation --no-parallel "-ParesVersion=<final>-rc.<commit>"
# 2. Test every consumer with the same candidate and an absolute file URI.
"-ParesVersion=<final>-rc.<commit>"
"-ParesRepository=file:///C:/absolute/path/ARESLib-Kotlin/build/release-repository"
```

**Dependency mechanisms:**
- **Normal builds:** consumers import `org.aresfirst.ares:ares-bom:<aresVersion>` and versionless modules from Maven Central and the monorepo Maven branch.
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

Studio is a **bidirectional NT4 WebSocket client**. Robots/simulators publish telemetry; Studio also
publishes leased controls and field configuration. Topic keys are the integration surface.

**Canonical topics (CCW-positive heading throughout):**
| Topic | Source | Meaning |
|---|---|---|
| `ARES/TruePose/[0,1,2]` | sim `DesktopSimLauncher` | Dyn4j ground-truth pose (X, Y, heading rad) |
| `ARES/EstimatedPose/[0,1,2]` | sim/robot telemetry publisher | Redux EKF estimate (X, Y, heading rad) |
| `Drive/Pose_X`, `Drive/Pose_Y`, `Drive/Pose_Heading` | robot `ARESNetworkStatePublisher` | EKF pose (`Drive/Drive_Heading` supported as alias) |
| `Drive/Odom_*` | robot | Raw Pinpoint odometry |
| `ARES/Input/driveFrame` | dashboard → sim / FTC Remote Drive | Atomic leased v2 control frame (`double[8]`) |
| `ARES/Input/{obstacles,fieldConfig}` | dashboard → sim | Non-control field configuration payloads |
| `Hardware/Motors/{name}/Velocity` | `velocities[i]` | MecanumVisualizer |
| `Hardware/Motors/{name}/CurrentAmps` | `currents[i]` | MecanumVisualizer |

> **Atomic simulator frames:** `ARES/SimulatorPoseFrame` carries truth, estimate, odometry, and a
> sequence value. Studio commits that packed frame atomically and ignores legacy simulator pose
> scalars once ownership is established. Never substitute truth for the EKF estimate.

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

### Simulator Pose Telemetry Ownership
`DesktopSimLauncher` publishes `ARES/TruePose/*` from Dyn4j ground truth. `TelemetryPublisher.publish(activeInstance.store.state)` is the **only** publisher of `Drive/Pose_*`, `Drive/Odom_*`, and `ARES/EstimatedPose/*`; those topics must expose the real Redux estimator and odometry state. Never call `publishEstimatedPose()` with Dyn4j truth after publishing the store, because that alternates two different values on the estimator topics and hides EKF behavior. Publish truth from the same pre-step observation consumed by the current OpMode tick so truth, odometry, and EKF are time-aligned while moving.

The NT4 transport delivers pose components and pose sources sequentially, not as one Compose state transaction, and suppresses scalars that did not change. The simulator therefore publishes `ARES/SimulatorPoseFrame` as `[true x/y/h, EKF x/y/h, odom x/y/h, sequence]`; the changing final sequence forces one complete frame every loop. `FieldPoseFrameAccumulator` commits only after array element 9 and then ignores legacy simulator pose scalars. Do not replace this with a coordinate/heading end marker, restore per-scalar simulator `LivePoseState` updates, or make the UI substitute truth for EKF: unchanged headings do not arrive, per-scalar rendering paints a deterministic ghost, and truth substitution hides estimator defects.

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

### ARESLib-Kotlin (`ARESLib-Kotlin/`)
The foundation. Its authoritative module list is `settings.gradle.kts`; it includes the project
schema/model/compiler, core, codegen, FTC/FRC hardware and runtime modules, mocks, simulation
foundation and platform runtimes, and `ares-bom`. Published coordinates use
`org.aresfirst.ares`; Kotlin packages remain `com.areslib`.

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

### ARES-FTC (`ARES-FTC/`)
FTC Android app, team **23247**, season **DECODE**. Built on FTC SDK 11.1 (the `FtcRobotController/` module is upstream boilerplate — don't edit it).

- **Canonical robot source:** `.ares/` documents describe the Lightbot reference robot: mecanum
  drivetrain, two independently controlled indicator lights, and one Prism underbody light. The
  Gradle code-generation tasks produce mechanical runtime and verification sources under generated
  directories; do not add hand-maintained copies of generated robot plumbing to `TeamCode/src`.
- **Team-owned source:** `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/` contains the thin
  OpMode, policy, telemetry, drive-controller, and explicit extension boundary around generated
  robot code. Generic intake and flywheel capabilities remain ARESLib templates, but are not part of
  Lightbot.
- Subsystem authoring preserves domain, control, hardware, simulation, generated plumbing, and
  verification responsibilities. File count is not a design goal; generated mechanical files and
  tests belong under Gradle generated directories.
- **Canonical autonomous assets:** `.ares/routines/`, `.ares/autonomous-catalog.json`, `.ares/action-catalog.json`, and the generated project source. Loose PathPlanner/`.aresauto` deployment is unsupported.
- **`simulator/` module** (desktop JVM, JDK 21): shares `TeamCode/src/main/java`, runs real OpModes against mocks. `runSim` → `DesktopSimLauncher --headless`; `CalibrationVerificationApp` exercises all SysId routines.
- **`.ares/project.json`** — canonical team/season/robot, authoring-model, coordinate, footprint, and runtime identity. Older split identity files are unsupported. **`ares_tuning.json`** — live-tuning config.
- **Build/deploy:** `.\gradlew.bat :TeamCode:assembleDebug`; deploy via `adb connect 192.168.43.1:5555` then `adb install -r`. Default FTC connection `192.168.43.1:5810`.

### ARES-FRC (`ARES-FRC/`)
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

### ARES-Analytics (`ARES-Analytics/`)
ARES Robotics Studio plus its Ktor gateway. Kotlin 2.4.10, Compose 1.11.1, Ktor 3.5.2, DuckDB
1.1.3. The isolated build contains `:shared`, `:app`, and `:gateway`; the gateway targets JRE 17.

- **`app/src/main/kotlin/com/ares/analytics/`** (MVI: `XxxState` + `sealed XxxIntent` + `XxxViewModel`):
  - `Main.kt` (thin composition root: 1440×900 window + wiring) delegating lifecycle to `desktop/` (`DesktopInstanceLock`, `DesktopCrashHandler`, `DesktopWindowPresentationController`, `NativeWindowProbe`, `DesktopStartupMachine`, `DesktopShutdownCoordinator`), `di/ServiceRegistry.kt` (~25 services, lazy tiers — **the index into all business logic**)
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

### Do not conflate these seven failure modes

| Failure mode | Observable evidence | Correct response |
|---|---|---|
| **Orphaned lock owner** | A prior `com.ares.analytics.MainKt` JVM is still alive; the next process prints `App is already running (failed to acquire app.lock). Exiting.` and shows no window. | Confirm the ARES process with `jps -lv`, then run `ARES-Analytics\.\gradlew.bat killExisting`. Do not kill every Java process and do not delete `app.lock`; the operating-system file lock, not the file's existence, is authoritative. |
| **Missing desktop Main dispatcher** | Compose starts, but StateFlow/lifecycle collection fails or the window disappears. | Keep `org.jetbrains.kotlinx:kotlinx-coroutines-swing` beside `kotlinx-coroutines-core` and keep `DesktopCoroutineDispatcherTest`. `coroutines-core` alone is insufficient for Compose Desktop's Swing event thread. |
| **Native window/rendering regression** | The JVM and UI coroutines remain alive but no visible top-level HWND is capturable, or the window is blank/intermittent. | Let Compose/Skiko select its renderer. Preserve the explicit window state and presentation behavior in `Main.kt`, then verify a real HWND with the Compose desktop capture workflow. |
| **Windows activation race** | `Desktop window presented` reports a real HWND, but the developer never sees the window or only sees it intermittently. `SetForegroundWindow` may legally reject a Gradle-launched child process, and an immediate native topmost-then-demote sequence can put the window behind the terminal before it paints. | Keep startup topmost state owned by the Compose `Window`, release it after the bounded startup interval, and require `Desktop startup presentation settled: alwaysOnTop=false, focused=true, active=true, showing=true`. Do not force focus/Z-order with Win32 calls. |
| **AWT event-thread crash** | The console reports `CRITICAL FAULT: Uncaught exception in thread 'AWT-EventQueue-0'` and names a `~/.ares-analytics/logs/crash-*.log`; the window may freeze or disappear while service threads keep the JVM and lock alive. | Read the newest crash log and fix the first relevant application stack frame. Then close through the verified window or use scoped `killExisting` cleanup. Treat the orphaned lock owner as a consequence, not the root cause. |
| **Incomplete runtime class output** | A crash log reports `NoClassDefFoundError` / `ClassNotFoundException` for an application `*Kt` class even though its `.kt` source exists. | Stop the verified ARES process, then run `.\gradlew.bat :app:clean :app:compileKotlin --no-build-cache --rerun-tasks`. Confirm the class is regenerated before relaunching. An ordinary incremental compile may reuse the same incomplete cache entry. |
| **Concurrent agent rebuild/cleanup** | A healthy window vanishes while another task runs, or a delayed `NoClassDefFoundError` occurs after source/build output changed. Inspect concurrent Gradle command lines; the historical `clean -> killExisting` dependency forcibly closed the UI, while a mutable `build/classes` launch could lose lazy-loaded bytecode. | Preserve the isolated `:app:run` classpath and keep `clean` independent of `killExisting`. Only a new `run` may replace an existing ARES process. Compile/test/clean may run while the app is open; their new code takes effect only after the next launch. |

Offline NT4 connection failures and Google Drive sign-in errors are expected when those services are unavailable. They are not evidence that desktop window creation failed.

### Startup invariants agents must preserve

- `app/build.gradle.kts` must retain `kotlinx-coroutines-swing` at the same version as `kotlinx-coroutines-core`.
- Do **not** add `skiko.renderApi`, `skiko.renderApi.fallback`, forced Direct3D, forced OpenGL, or forced software-renderer JVM properties as a general startup fix. A renderer experiment requires its own branch, before/after captures on the affected machine, and a fallback/removal plan.
- `Main.kt` must retain an explicit floating, centered `1440 x 900 dp` window, `visible = true`, and a `1100 x 700` AWT minimum size. The bounded Compose-owned startup `alwaysOnTop` state and the `toFront()` / `requestFocus()` presentation calls live in `DesktopWindowPresentationController` and must keep the same guarantees. Startup sequencing follows the explicit `DesktopStartupMachine` state order (CREATING→OPENED→PRESENTED→SETTLED; CLOSING→CLOSED on shutdown).
- Keep the `Desktop window presented` diagnostic. It proves that the AWT peer reached the presentation hook; it does **not** replace screenshot verification.
- Keep final visibility/focus presentation deferred through `EventQueue.invokeLater`; the Compose `Window` peer is created asynchronously. The diagnostic must report the final bounds and `showing=true` from that deferred event.
- On Windows, a usable window means the exact HWND returned for the Compose `java.awt.Window` by `Native.getWindowPointer(window)` is simultaneously present in `EnumWindows`, owned by the current ARES PID, valid, and visible. AWT `isShowing=true`, any other same-process helper HWND, `IsWindowVisible` on a stale/reused handle, or a live JVM is not sufficient. Keep the exact-peer ownership check and recovery timer.
- Native APIs in normal startup are observation-only. Do not call `AttachThreadInput`, `ShowWindow`, `SetWindowPos`, `BringWindowToTop`, or `SetForegroundWindow`, and do not toggle `java.awt.Window.isVisible` to recreate a peer. Those operations race Compose's native lifecycle and Windows may reject foreground activation without error. Compose owns visibility and the bounded always-on-top interval; the settled diagnostic must prove it later returned to `alwaysOnTop=false`.
- The AWT-thread health check (`DesktopWindowPresentationController`, probing via `NativeWindowProbe`) must not call `GetWindowTextLength` / `GetWindowText` while enumerating desktop HWNDs. Those APIs can synchronously message AWT's toolkit window and deadlock the event queue. Match the peer handle by pointer identity; title matching belongs in the external capture/interaction scripts.
- Initial native presentation must be scheduled from `windowOpened` after that lifecycle callback returns. A generic startup `EventQueue.invokeLater` can run before `componentShown` / `windowOpened` and validate a transient peer. Keep the bounded delayed fallback for the case where listeners attach after the opened event. A valid startup trace orders `Desktop window shown` and `Desktop window opened` before `Desktop window presented after ...`.
- A fatal uncaught `AWT-EventQueue` exception or unexpected disposal of the only Compose window must terminate the process after logging. An unusable desktop JVM must not remain alive solely to hold `app.lock`.
- Keep the single-instance lock, bounded service disposal, and hard-exit watchdog unless the replacement is tested for normal close, hung shutdown, relaunch, and stale-process recovery.
- Keep `kotlin.incremental=false` in `ARES-Analytics/gradle.properties`. Large Compose source changes have twice produced delayed missing-class crashes (`SuperstructureStudioScreenKt` and a nested `FieldCanvas...WhenMappings`) from incomplete incremental outputs.
- Keep the `:app:run` runtime snapshot in `app/build.gradle.kts`. A running JVM must load project classes, classpath resources, project-owned artifacts, and `compose.application.resources.dir` from its unique `ares-analytics-run-*` temp snapshot, never mutable `build/` paths that another agent can clean or replace. Keep the finalizer that removes the snapshot after exit.
- `clean` must **never** depend on `killExisting`. Only `run` may depend on the scoped replacement task. A compile, test, or clean performed by another agent is not authority to close a developer's visible app.
- In the `:app:run` task graph, `killExisting` must run after `:app:jar`. A broken replacement build must fail before terminating the current healthy window.
- A direct packaged-app launch does not run Gradle's `killExisting` task. Do not assume that behavior exists outside `:app:run`.

### Mandatory launch/debug workflow for every agent

1. Run `git status --short --branch` in `ARES-Analytics` and preserve all unrelated or in-progress edits.
2. Separate compilation from presentation: run `.\gradlew.bat :app:compileKotlin` first. A successful compile does not prove a window exists.
3. Before killing anything, inspect Java command lines with `jps -lv | Select-String 'com\.ares\.analytics\.MainKt'`.
4. If a verified ARES JVM owns the lock but has no usable window, run `.\gradlew.bat killExisting` from `ARES-Analytics` and report the PID that was terminated.
5. If the crash is `NoClassDefFoundError` / `ClassNotFoundException` for an application class whose source exists, run `.\gradlew.bat :app:clean :app:compileKotlin --no-build-cache --rerun-tasks`; do not trust an incremental `FROM-CACHE` result for that recovery.
6. Launch with `.\gradlew.bat :app:run` for released dependencies, or add `"-ParesUseSiblingLib=true"` only when intentionally validating sibling ARESLib source. Require the `Isolated desktop runtime classpath at ...ares-analytics-run-*` log; otherwise concurrent builds can corrupt the running app.
7. Require `Desktop window shown` and `Desktop window opened` before a `Desktop window presented after windowOpened` (or explicit startup-fallback) log ending in `showing=true, nativeVisible=true, hwnd=<value>`. Then require `Desktop startup presentation settled: alwaysOnTop=false, focused=true, active=true, showing=true`. This second line proves the window remained presented after the bounded topmost interval instead of briefly flashing and falling behind the launcher.
   Strict capture must return that same HWND for the visible `ARES Robotics Studio` window. A different same-process HWND or full-desktop fallback image is not proof. If the launcher and capture helper are isolated onto different Windows desktops/window stations, set `ARES_ANALYTICS_STARTUP_CAPTURE=<absolute-png-path>` and `ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE=true` for that verification run. The app then captures its own window after settled state and posts `WM_CLOSE` to the exact HWND; both variables are opt-in and normal launches perform no capture I/O.
8. Inspect the captured image for actual app content rather than accepting a process ID, Gradle task state, or blank frame.
   If the exact ARES HWND has a black client area on the first capture, keep that same process alive, check for an AWT/render error, wait one paint interval, and capture the same HWND again. A rendered recapture is delayed painting; a persistently blank capture is a startup failure.
9. If the console reports an uncaught `AWT-EventQueue-0` exception, inspect the named crash log before cleanup. The first relevant application frame is evidence of the initiating UI defect; the remaining process and lock are secondary effects.
10. Close the app through its window so `disposeAndJoin()` and the shutdown watchdog are exercised. Use the tester skill's native `-CloseWindow` action; do not automate Alt+F4 through `SendKeys`, which can be delivered to a focused Compose text field as input. Use `killExisting` only as cleanup if graceful close fails.
11. Confirm `jps -lv` no longer lists `com.ares.analytics.MainKt`.
12. If startup, `Main.kt`, `ServiceRegistry`, Compose/coroutines dependencies, or Skiko settings changed, launch and capture a second time after a clean shutdown. This catches invisible lock owners and one-launch-only success.

Never report "the app launches" based only on `BUILD SUCCESSFUL`, a long-running Gradle process, `MainScreen` logs, or a screenshot tool's full-screen fallback. The required evidence is a visible ARES HWND containing rendered UI, followed by a shutdown that leaves no ARES JVM.

When multiple agents are active, inspect `jps -lv` / `Win32_Process.CommandLine` before attributing a disappearance to Compose. Another agent's build may explain the timing. Do not revert that agent's source edits; first verify whether their task is still writing, wait for a coherent compile boundary, then validate the combined tree. The isolated runtime intentionally does not hot-reload those edits.

Only one Gradle invocation may compile/clean the same Analytics module at a time. The running app is isolated from those outputs, but two compiler processes can still contend over `app/build/classes` and fail with `Could not delete ...build\classes\kotlin\main`. Wait for the existing wrapper command to finish; do not kill its compiler daemon or delete its outputs underneath it.

The detailed diagnostic decision tree and exact capture/cleanup commands live in `.agents/skills/compose-desktop-tester/references/startup-recovery.md`.

## 7A. FTC Simulator Runtime and Control Reliability (MANDATORY)

`ARES-FTC\TeamCode:runSim` starts a **headless physics/NT4 server**, not a ready-to-drive
OpMode. “Port 5810 is online” proves only that the server is listening. A controllable robot
requires all of these states in order:

1. Analytics is connected to `127.0.0.1:5810` with Local Sim selected.
2. A published TeleOp is selected and the Driver Station sends `INIT`, then `START`.
3. Dashboard local control is explicitly armed.

Preserve these invariants:

- `TeamCode/build.gradle` must keep the unique `ares-ftc-sim-run-*` runtime snapshot for
  `runSim`. The simulator must not load TeamCode/FtcRobotController classes or jars from mutable
  workspace `build/` output. On Windows a live mutable classpath locks `classes.jar`; concurrent
  compile/verification can then fail or replace lazily loaded bytecode.
- Require the launch line `[ARES-FTC] Isolated simulator runtime classpath at ...` before treating
  a developer run as rebuild-safe. A green compile or `:simulator:test` does not exercise this
  long-running runtime contract.
- Never start a second simulator merely because Analytics does not own the process. If Local Sim is
  already online on port 5810, use the existing server or stop it from the process that launched it.
  A second `runSim` can fail during compilation or NT4 binding while the first server remains alive.
- Keep `LocalSimulatorControlBar` visible above the configurable Dashboard grid for an FTC Local
  Sim target. Saved/custom layouts may omit or place the full Driver Station and Gamepad Monitor
  widgets below the fold; those layouts must not hide the required start/arm path.
- When Local Sim is offline, that strip's primary action must be the labeled **Launch simulator**
  button wired to the same guarded `ProcessManagerService.runSimulation` path as the execution
  toolbar. Do not send users to an icon-only toolbar control or leave a disabled **Start driving**
  button as the only apparent action. While the managed process starts, show the connecting state;
  after NT4 connects, replace the primary action with **Start driving**.
- A fresh Analytics session has no successful build evidence. If the current authored project is
  eligible for verification but simulation is blocked only by that missing evidence, the strip must
  offer **Verify & launch**. It runs the existing compile-only verification, launches automatically
  only after a successful result, and leaves the simulator stopped after failure or cancellation.
- Keep the window-level `DesktopDriveKeyDispatcher`. Compose buttons, dropdowns, and text fields may
  own focus and consume keys before a root `onPreviewKeyEvent` modifier observes them. The dispatcher
  must remain inert unless Dashboard is active, Local Sim is selected and connected through a
  loopback host, local control is armed, and keyboard mode is selected. Focus, target, connection,
  page, and arm-state changes must neutralize every latched input immediately.
- Dashboard/simulator drive publication is a loopback-only capability. Keep the non-loopback rejection
  inside `Nt4ClientService.publishDriveFrame`, in addition to UI gating, so stale Compose state cannot
  send motion to a physical Control Hub or roboRIO. Do not broaden this allowlist beyond
  `127.0.0.1`, `localhost`, and `::1`.
- Do not weaken the neutral v2 `ARES/Input/driveFrame` handshake, session/sequence monotonicity,
  receiver lease, or explicit arm to make a demo move. If publication pauses long enough to exceed
  the receiver lease, begin a new session and publish at least five neutral frames (100 ms at 50 Hz)
  before resuming motion. A single 20 ms neutral frame can fall entirely between receiver polls and
  permanently disarm that session.
- Treat control, raw telemetry, and UI telemetry as three independent rate domains. The
  `ARES/Input/driveFrame` control heartbeat is safety-critical and stays at 50 Hz; its send path must
  never synchronously write `TelemetryStore`, touch a database, update Compose state, or wait behind
  inbound telemetry. `Nt4ClientService.telemetryFlow` is the full-rate stream for recording and
  analysis services. Compose UI consumers must use `Nt4ClientService.uiTelemetryFlow`, which keeps
  only the latest value per topic and fans it out at 20 Hz.
- `DriveFrameTelemetryRecorder` is intentionally a lossy, background side channel. It records only
  `vx`, `vy`, and `omega` at 10 Hz for charts/history. Do not restore the previous behavior that
  flattened all eight drive-frame fields into synchronous telemetry writes on every 50 Hz control
  tick; that created hundreds of contended writes per second and periodically expired the simulator
  input lease.
- `VisionState.measurements` is ordered oldest to newest. Any current-pose telemetry derived from
  that buffer must select a fresh `lastOrNull()` measurement, never `firstOrNull()`. Publishing the
  oldest entry produces the visibly trailing dotted EKF/vision ghost even though estimation itself
  is current.

### Mandatory FTC simulator verification

1. Preserve unrelated edits, especially `ARES-FTC/TeamCode/src/main/assets/paths/field.json`.
2. Launch `ARES-FTC\.\gradlew.bat :TeamCode:runSim` and require the isolated-runtime line, NT4
   startup on 5810, Driver Station Server Mode, and the published TeleOp/Auto counts.
3. While that exact simulator remains alive, rerun
   `:FtcRobotController:bundleLibCompileToJarDebug --rerun-tasks`. It must succeed and the simulator
   must remain alive; otherwise the runtime is still using mutable build output.
4. Launch/capture Analytics through the §7 workflow. On Dashboard, require the Local Simulator strip
   to name a TeleOp and explain that an online server can still be waiting for a TeleOp.
5. Choose a drive-capable TeleOp, use **Start driving**, and require simulator logs for the selected
   class, `INIT`, successful initialization, `START`, and `OpMode STARTED`.
6. Verify the strip reports `TELEOP RUNNING` and `ARMED`. Hold W briefly, release W,
   and prove `ARES/TruePose` changed and then settled. A button state, transmitted frame, changing
   chart, or live JVM alone is not proof of physical simulated motion.
7. Stop the OpMode, close Analytics through its window, stop `runSim`, and confirm neither JVM remains.

## 8. Working in This Workspace — Checklist

- **Fresh checkout?** Clone this repository once, then run `.\setup.ps1` (Windows) or `./setup.sh`
  (macOS/Linux). Setup validates that all six imported Gradle products, their preserved histories,
  release manifest, and monorepo policies are present; it never downloads or overwrites source.
- **Changing ARESLib?** Run `apiCheck publishReleaseValidation` in `ARESLib-Kotlin/`, then rebuild consumers with `-ParesRepository=<absolute validation-repository path>`. Source substitution requires explicit `-ParesUseSiblingLib=true`.
- **Telemetry mismatch in the dashboard?** Check the NT4 topic map and dashboard variable mapping (§4), confirm leading-`/` stripping, and verify CCW+ heading consistency.
- **Heading/rotation looks wrong?** Re-read `GEMINI.md §5` and the negation rules in §5 above. Usual culprits: extra negation after `PinpointIO`, the `-90°` canvas offset, or Limelight `rotation.y` vs `.z`.
- **Writing a hot-path (robot or sim)?** Zero allocations. Use buffers/pools, `RobotClock`, `when` over nested `if`.
- **Adding a subsystem?** Use a canonical `.aressubsystem` document or an explicit code-first/hybrid
  extension package. Preserve domain, control, hardware, simulation, generated plumbing, and
  verification boundaries; do not optimize for a fixed file count. See
  `ARESLib-Kotlin/docs/subsystem-dsl.md` and the Studio Subsystem Builder.
- **Cloud/sync code?** Remember offline-first: robot serves `LogManagerServer:5002`; the laptop pulls then syncs. Never push from robot.
- **Analytics launches but shows no window?** Follow §7 and the Compose desktop tester startup-recovery reference. Check for an orphaned lock owner before changing rendering or UI code.
- **Tests:** ARESLib, FRC, starters, and Studio use JUnit 5/Kotlin test where configured. FTC Android
  unit tests use JUnit 4.13.2; its desktop simulator tests use JUnit 5. Run the dependency-ordered
  monorepo matrix with `.\build.ps1 -Task Test`, or the product-specific task documented above.

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
- **Strict Branch Discipline:** Keep feature work isolated on designated branches; never commit
  directly to `main` unless explicitly instructed. Release work merges through protected pull
  requests.
