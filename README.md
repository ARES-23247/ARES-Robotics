# ARESLib-Kotlin

ARESLib-Kotlin is the shared Kotlin foundation for the ARES FTC, FRC, simulator, and analytics projects. It provides geometry, estimation, control, path planning, immutable robot state, hardware abstractions, telemetry, and local log transport. Season repositories supply the robot-specific hardware bindings and orchestration.

## Repository structure

| Module | Purpose | Depends on |
|---|---|---|
| `core` | State, reducers, geometry, EKF localization, controllers, safety, pathing, sequencer, NT4, telemetry, and logging | Kotlin/JVM libraries only |
| `ftc-mocks` | Desktop implementations of the FTC and Android APIs used by library code | `core` |
| `ftc-hardware` | FTC SDK adapters, mecanum robot base classes, Pinpoint, Limelight, cached hardware, and power management | `core`; mocks for compilation/tests |
| `frc-hardware` | WPILib and vendor adapters, swerve robot base classes, Limelight, telemetry, and power management | `core` |
| `simulator` | Dyn4j desktop physics, virtual driver station, OpMode runner, replay, and NT4 bridge | `core`, `ftc-hardware`, `ftc-mocks` |

The important dependency rule is that platform and season code depend inward on `core`; `core` never imports FTC or WPILib APIs.

```text
                     core
                 /     |     \
          ftc-mocks  ftc-hardware  frc-hardware
                 \     /
                  simulator

       ARES-FTC / ARES-FRC / ARES-Analytics
                   consume published modules
```

See [Architecture](docs/architecture.md) for package ownership, the robot loop, and extension points.

## Requirements

- JDK 17
- The checked-in Gradle Wrapper
- Windows PowerShell examples below use `gradlew.bat`; use `./gradlew` on macOS or Linux
- WPILib/vendor dependencies are resolved by Gradle when building `frc-hardware`

## Build and test

Run commands from this repository root:

```powershell
# Compile all production and test Kotlin sources
.\gradlew.bat compileKotlin compileTestKotlin

# Run all module tests
.\gradlew.bat test

# Run focused suites
.\gradlew.bat :core:test
.\gradlew.bat :ftc-hardware:test
.\gradlew.bat :frc-hardware:test
.\gradlew.bat :simulator:test

# Publish snapshots for sibling repositories that consume Maven Local
.\gradlew.bat publishToMavenLocal
```

Publish after every ARESLib change before building a consumer that resolves `1.0-SNAPSHOT` from Maven Local. In the four-repository workspace, the safe order is:

1. Build and test ARESLib-Kotlin.
2. Run `publishToMavenLocal` here.
3. Build ARES-FTC, ARES-FRC, and ARES-Analytics.

Local publication coordinates are defined by each module:

| Module | Coordinates |
|---|---|
| `core` | `com.areslib:core:1.0-SNAPSHOT` |
| `frc-hardware` | `com.areslib:frc-hardware:1.0-SNAPSHOT` |
| `ftc-hardware` | `com.github.ARES-23247.ARESLib-Kotlin:ftc-hardware:master-SNAPSHOT` |
| `ftc-mocks` | `com.github.ARES-23247.ARESLib-Kotlin:ftc-mocks:master-SNAPSHOT` |
| `simulator` | `com.github.ARES-23247.ARESLib-Kotlin:simulator:master-SNAPSHOT` |

Sibling repositories may also use Gradle composite substitution. Check the consumer's `settings.gradle` before diagnosing a stale dependency.

## Run the simulator

```powershell
# Starts the simulator in OpMode discovery/server mode
.\gradlew.bat :simulator:run

# Pass a season OpMode and run without the Swing driver-station window
.\gradlew.bat :simulator:run -PappArgs="--headless --opmode org.example.MyOpMode"
```

The simulator serves NT4 on port `5810` and the local log browser/API on port `5002`. A season repository normally adds the real OpMode classes to the simulator runtime; the library by itself provides the physics and runner infrastructure.

## Non-negotiable contracts

- Coordinates are meters and headings are radians, CCW-positive: `0 = +X`, `pi/2 = +Y`.
- Odometry deltas passed into `PoseEstimator` are robot-local SE(2) displacements.
- Pinpoint heading polarity is corrected once in `PinpointIO`; do not negate it downstream.
- Limelight target-space yaw is `-robotPoseTargetSpace.rotation.y`; `rotation.z` is not robot yaw.
- Library time comes from `RobotClock`, never directly from wall-clock APIs.
- Hardware is read once per loop into cached input objects; output and state getters do not read devices.
- High-frequency update, sampling, and steering paths must not allocate.
- Robots do not upload logs to cloud services. ARES Analytics pulls logs over the local network and performs cloud sync from the laptop.
- NT4 topic names are normalized without a leading slash, and an announced topic's type does not change during its lifetime.

The rationale and edge cases are documented in [Math and coordinate contracts](docs/math-and-coordinate-contracts.md) and [Telemetry and logging](docs/telemetry-and-logging.md).

## Developer documentation

- [Architecture](docs/architecture.md)
- [Math and coordinate contracts](docs/math-and-coordinate-contracts.md)
- [Telemetry and logging](docs/telemetry-and-logging.md)
- [Development, testing, and troubleshooting](docs/development.md)
- [Redux onboarding](docs/onboarding/01_redux_basics.md)
- [Desktop simulator onboarding](docs/onboarding/02_desktop_simulator.md)
- [Pathing integration onboarding](docs/onboarding/03_pathing_and_analytics.md)
- [Pit and hardware checklist](docs/onboarding/04_pit_operations_and_hardware.md)

`GEMINI.md` contains repository rules for automated contributors. The documents above are the human-facing reference and should be updated with any contract or integration change.
