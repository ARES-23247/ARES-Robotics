# ARES-FRC

ARES-FRC is the Marvin XIX season layer for team 23247's 2024 Crescendo robot. It is a Kotlin/WPILib `TimedRobot` application built on the shared ARESLib state, control, hardware, telemetry, and simulation modules.

This is intentionally **not** a WPILib Command-Based project. Driver input, autonomous events, and hardware observations become Redux actions; reducers produce immutable state; subsystem controllers turn that state into hardware outputs.

## Start here

- [Architecture and safety contracts](docs/ARCHITECTURE.md) — lifecycle, Redux flow, IO freshness, units, safety, coordinates, and hardware map.
- [Subsystem generator and hand-authoring guide](docs/SUBSYSTEM_AUTHORING.md) — capability templates, file ownership, safe regeneration, parity, and review coverage.
- [Autonomous and simulation](docs/AUTONOMOUS_AND_SIMULATION.md) — native visual autos, action
  discovery, preflight, mirroring, and the dyn4j model.
- [Build, test, deploy, and troubleshoot](docs/OPERATIONS.md) — commands, prerequisites, swerve offsets, and common failure modes.
- [Deploy-directory contract](src/main/deploy/README.md) — files copied to `/home/lvuser/deploy` on the RoboRIO.

## Architecture at a glance

```text
WPILib TimedRobot callbacks
       |
       +-- driver / autonomous controller
       |         |
       |         v
       |    Redux actions --> MarvinReducer --> immutable RobotState
       |                                          |
       v                                          v
ARESRobot 50 Hz update: refresh IO -> read sensors -> write outputs -> telemetry
       |
       +-- real robot: CTRE swerve, TalonFX mechanisms, two Limelights
       +-- desktop: dyn4j drivetrain/field plus simulated mechanism IO
```

`ARESRobot` owns WPILib lifecycle orchestration. `FrcSwerveRobot` supplies the shared robot/store/drive implementation. `MarvinReducer` composes the ARESLib `rootReducer` before applying season-specific actions, so drive, pose, alliance, and superstructure data share one state tree.

The core 50 Hz update is registered with a 5 ms phase offset. Its critical order is:

1. Refresh registered hardware exactly once.
2. Read cached drivetrain, vision, and mechanism observations.
3. Dispatch observations into Redux state.
4. Apply brownout/current scaling.
5. Write subsystem and swerve outputs.
6. Publish telemetry and logs.

Any periodic exception invokes the hardware safe path. See [Architecture and safety contracts](docs/ARCHITECTURE.md) before adding an IO implementation or mechanism control mode.

## Quick start

Run these commands from this directory in PowerShell:

```powershell
# Compile and run all JUnit 5 tests
.\gradlew.bat test

# Regenerate and verify Kotlin compiled from the offline .ares project
.\gradlew.bat generateAresProject
.\gradlew.bat verifyAresProject

# Preview or create editable subsystem starters; normal builds refresh plumbing only
.\gradlew.bat previewSubsystemChanges
.\gradlew.bat generateSubsystemStarters

# Start WPILib desktop simulation
.\gradlew.bat simulateJava

# Deploy for the default team 23247 (override only for an intentional alternate target)
.\gradlew.bat deploy -PteamNumber=23247

# Pull runtime-calibrated swerve offsets from the RoboRIO
.\gradlew.bat fetchOffsets
```

Normal builds consume the pinned ARESLib release from Maven Central. Library developers can opt into sibling source substitution with `-ParesUseSiblingLib=true`, or validate the exact unpublished binaries with:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
cd ..\ARESLib-Kotlin
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
cd ..\ARES-FRC
$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat test "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

## Non-negotiable conventions

- Pose headings are radians, CCW-positive: `0` points along field `+X`, and `pi/2` points along `+Y`.
- Field coordinates use the WPILib blue-origin convention. Native autos are authored in that frame
  and reflected across the alliance-wall axis for Red.
- The official Crescendo speaker centers used for aiming are `(0, 5.547868)` m for Blue and `(16.54175, 5.547868)` m for Red.
- Flywheel speeds are RPM. Intake, feeder, and floor speed commands are RPS.
- Cowl setpoints and lookup-table values are **mechanism rotations**, not degrees.
- Climber position setpoints are **mechanism rotations**; manual control is volts.
- Hardware getters return values cached by the current loop's refresh. Do not add direct CAN/sensor reads to getters or output methods.
- Invalid flywheel velocity or game-piece detection must remain fail-closed. Never infer validity from a numeric zero.
- Robot code communicates locally through ARES telemetry/logging. Cloud synchronization belongs on the laptop, not the RoboRIO.

## Repository map

| Path | Purpose |
|---|---|
| `.ares/project.json` | Canonical identity, geometry, coordinate convention, and runtime policy used by Studio and generation |
| `.ares/` | Canonical routines plus autonomous and action catalogs |
| `src/main/kotlin/com/areslib/frc/ARESRobot.kt` | `TimedRobot` lifecycle and real/sim composition root |
| `src/main/kotlin/com/areslib/frc/robot/` | Teleop drive and autonomous orchestration |
| `src/main/kotlin/com/areslib/frc/marvin/` | Season state, actions, reducer, facades, and mechanism controllers |
| `src/main/kotlin/com/areslib/frc/hardware/` | Marvin XIX TalonFX IO bindings |
| `src/main/kotlin/com/areslib/frc/sim/` | dyn4j physics, simulated IO, field construction, and telemetry |
| `src/main/kotlin/com/areslib/frc/generated/` | Deterministic checked-in Kotlin generated from `.ares/` |
| `src/main/deploy/paths/` | Canonical field geometry consumed by simulation and Analytics |
| `src/test/kotlin/` | Lifecycle, reducer, IO, simulation, and autonomous regression tests |

## License

ARES-authored season and simulation code is licensed under [Apache License 2.0](LICENSE). WPILib, CTRE Phoenix, and other vendor dependencies retain their own licenses and terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
