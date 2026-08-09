# ARESLib-Kotlin

A high-performance, functional, cross-platform (FTC and FRC) robotics library designed around **Immutable State Representation** (Redux-style flow) and **Decoupled Hardware Interfaces** (IO Layer pattern).

## Core Architecture

`ARESLib-Kotlin` enforces strict architectural constraints to ensure deterministic, simulation-ready, and competition-grade reliability.

### 1. Zero-GC Hot Paths
Drivetrain update cycles, state-space controllers, and pathfinders run at high frequencies (50Hz - 100Hz).
- **Rule**: Object allocations are completely prohibited inside hot paths (e.g., `update()` loops, trajectory sampling).
- **Enforcement**: We use pre-allocated buffers, primitive types, array loops (instead of iterators), and object pools (like the `kalmanGainPool` in `PoseEstimator` and the `pathPool` in `ThetaStarPlanner`) to maintain a strict zero-allocation footprint during active matches.

### 2. Redux Store Architecture
- **State**: The `RobotState` and its sub-states (`DriveState`, `SuperstructureState`) are 100% immutable Kotlin data classes.
- **Actions**: All state updates occur by dispatching `RobotAction` objects.
- **Reducers**: State transitions are handled exclusively through pure, deterministic reducer functions (e.g., `rootReducer`).

### 3. Decoupled IO Layers
Hardware interactions are fully abstracted through thin IO interfaces (e.g., `MecanumHardwareIO`, `ElevatorIO`).
- `ftc-hardware/`: Physical SDK implementations for real FTC robots.
- `frc-hardware/`: Physical WPILib implementations for FRC robots.
- `ftc-mocks/`: Pure software stubs utilizing simulated physics math from `simulator/` enabling complete desktop-level simulation without a physical robot.

### 4. AutoBuilder (Sequencer)
The `AutoBuilder` module provides a fluent, highly readable DSL for defining autonomous routines. It interfaces natively with the Redux `TaskExecutor`, yielding clean asynchronous event markers, parallel task execution, and trajectory sequencing.

## Building and Testing

We use the Gradle Wrapper for all builds.

```powershell
# Compile the library and tests
.\gradlew.bat compileKotlin compileTestKotlin

# Run all test suites
.\gradlew.bat test

# Publish to Maven Local (required before compiling ARES-Analytics or ARES-FTC)
.\gradlew.bat publishToMavenLocal
```

## Logging and Telemetry

- **Offline-First**: All logging and telemetry is completely offline-first. FTC/FRC robots do not stream directly to the cloud.
- **ARES-Analytics Integration**: The library runs an embedded `LogManagerServer` (NanoHTTPD) that exposes local endpoints to allow the desktop `ARES-Analytics` driver station application to pull the raw `.jsonl` logs.
