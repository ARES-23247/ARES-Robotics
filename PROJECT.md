# Project: ARESLib-Kotlin Fault Tolerance Hardening

## Architecture
- Core Math & Control Layer: Contains EKF, PID/LQR controllers, and Theta* pathfinding.
- State Management & Redux Engine: Centralized immutable state store and pure reducers.
- Hardware I/O Layer: Sensor and motor abstractions (I2C, UART, encoders).
- Application Layer: Top-level OpModes and robot loops.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | MathAndControlHardening | Inject numerical bounds checking (EKF, LQR, Theta*), eliminate GC allocations in hot-paths, add integral windup limits and output clamping for PID/FF. | none | COMPLETED |
| 2 | StateReduxHardening | Deep immutability audit of `RobotState` sub-states. Ensure reducers are pure functions discarding invalid `RobotAction`s safely. | none | COMPLETED |
| 3 | HardwareFaultTolerance | Add read timeouts and fallback logic for I2C/UART sensors (Pinpoint, Gyro). Add automated motor current spike limits and stall detection. | none | COMPLETED |
| 4 | ApplicationFailsafes | Wrap top-level OpMode loops in try-catch for graceful disable. Implement loop time watchdog (50Hz/100Hz budget overrun detection). | none | COMPLETED |
| 5 | E2ETestFinal | Pass 100% of the E2E test suite | M1, M2, M3, M4 | COMPLETED |

## Interface Contracts
### Application ↔ Hardware
- Hardware reads must not block indefinitely; they must return a known fallback value or exception on timeout.

### State ↔ Reducers
- `RobotState` sub-states must use immutable collections (e.g. `List`, `Map` interfaces or immutable implementations).
- Reducers must not throw exceptions on invalid actions, but instead return the previous state.

## Code Layout
- Core Math & Control: `core/src/main/kotlin/com/areslib/math`, `core/src/main/kotlin/com/areslib/control`, `core/src/main/kotlin/com/areslib/pathing`
- State Management: `core/src/main/kotlin/com/areslib/state`, `core/src/main/kotlin/com/areslib/reducer`
- Hardware I/O: `ftc-hardware/src/main/kotlin/com/areslib/ftc/hardware` and `frc-hardware/src/main/kotlin/com/areslib/frc/`
- Application: `ftc-hardware/src/main/kotlin/com/areslib/ftc/`, `frc-hardware/src/main/kotlin/com/areslib/frc/`
