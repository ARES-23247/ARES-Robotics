# Test infrastructure

ARESLib uses Gradle for every module. Core and hardware suites run on JUnit Platform; the simulator module currently includes JUnit 4 tests. Tests are organized by behavior and owning module rather than by one global test harness.

## Run tests

```powershell
# Entire repository
.\gradlew.bat test

# By module
.\gradlew.bat :core:test
.\gradlew.bat :ftc-hardware:test
.\gradlew.bat :frc-hardware:test
.\gradlew.bat :simulator:test

# One class or package pattern
.\gradlew.bat :core:test --tests "com.areslib.math.estimation.PoseEstimatorTest"
.\gradlew.bat :core:test --tests "com.areslib.e2e.*"
```

The `frc-hardware` task extracts WPILib/vendor desktop JNI libraries. When installed, it runs tests with `C:/Users/Public/wpilib/2026/jdk/bin/java.exe` to match the WPILib runtime.

## Coverage by area

| Area | Representative tests |
|---|---|
| Geometry and kinematics | `GeometryTest`, `ChassisSpeedsTest`, `MecanumKinematicsTest`, `SwerveKinematicsTest` |
| Estimation | `PoseEstimatorTest`, `PoseEstimatorHardeningTest`, `PoseEstimatorVisionHardeningTest`, `EstimatorMathRegressionTest` |
| Control and safety | PID/LQR/ADRC/profile tests, `BrownoutGuardTest`, `CurrentBudgetManagerTest`, `SafetyFaultToleranceTest` |
| Pathing | parser parity, spline/trajectory, costmap, path safety, chaining, Theta*, follower, and correctness regression tests |
| State | root/slice reducer, immutability, and action-safety tests |
| Hardware | IO simulations, registry, sensor threading, FTC/FRC adapter and power tests |
| Telemetry and logs | NT4 protocol/hardening, robot web server, logger, log manager, replay, and diagnostics tests |
| Simulator | telemetry/input end-to-end and simulated vision field-of-view tests |
| Allocation | `ZeroGcRegressionTest` and E2E GC-avoidance coverage |

## E2E tiers

The current `com.areslib.e2e` packages contain requirement-level coverage for math bounds, PID clamping, Redux safety, state behavior, hardware fault tolerance, and loop failsafes. The checked-in tiers are the source of truth; do not rely on historical planned-count tables.

E2E tests complement focused mathematical and adapter tests. Passing only `com.areslib.e2e.*` does not validate all networking, logging, pathing, platform-native, or simulator behavior.

## Test design requirements

For new behavior, include the applicable categories:

- Nominal result with physically meaningful units.
- Boundary values and angle wraparound.
- `NaN`, infinity, empty input, and non-positive `dt`.
- Stale/disconnected hardware and stop behavior.
- Deterministic mock-time progression.
- Delayed/out-of-order data for estimator, replay, and networking code.
- Malformed and bounded-size inputs for parsers/protocols.
- Concurrency and lifecycle transitions for background workers/servers.
- Steady-state allocation for robot-loop changes.

Avoid sleeps and wall-clock assumptions. Use `RobotClock` mock time where the production code uses robot time, and restore system time in teardown/finally blocks.

## Cross-repository verification

After ARESLib tests pass:

```powershell
.\gradlew.bat publishToMavenLocal
```

Then run the affected ARES-FTC, ARES-FRC, and ARES-Analytics suites. This is required for changes to public APIs, season interfaces, PathPlanner parsing, coordinate conventions, NT4 topics/types, or log formats.

## Port-owning tests

Network integration tests may bind ports `5810`, `5002`, or `8082`. Do not run multiple suites that own the same port concurrently. Ensure test/server shutdown completes before rerunning after a failure.
