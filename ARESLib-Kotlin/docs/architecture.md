# Architecture

## Role in the ARES workspace

ARESLib-Kotlin owns reusable robot behavior. ARES-FTC and ARES-FRC are season layers: they provide concrete hardware bindings, season state/actions/reducers, and mode orchestration. ARES-Analytics consumes the shared model and the robot's NT4/log contracts but is not called directly by robot code.

The dependency direction is deliberate:

```text
driver or autonomous intent
          |
          v
season facade/controller
          |
          v
Store.dispatch(RobotAction)
          |
          v
rootReducer + season reducer  ---> immutable RobotState
          |                              |
          |                              v
          +---------------------- subsystem controllers
                                         |
                                         v
                                  cached hardware IO
```

Reducers calculate state only. Device reads, telemetry writes, file access, clocks, and background work belong outside reducers.

## Module boundaries

### `core`

`core/src/main/kotlin/com/areslib` contains the platform-independent implementation:

- `action`, `state`, `reducer`, and `Store.kt`: Redux state transitions.
- `math/geometry`, `math/kinematics`, and `kinematics`: geometry and drivetrain transformations.
- `math/estimation`: `PoseEstimator`, odometry propagation, delayed vision correction, and scalar Kalman filtering.
- `control`: feedback, profiles, drivetrain control, safety, SysId, and assisted actions.
- `pathing`: PathPlanner parsing, splines, trajectories, path following, costmaps, and Theta*.
- `sequencer`: task state machines and execution.
- `hardware`: SDK-independent IO contracts, cached input containers, registry, and topology models.
- `subsystem`: reusable subsystem/facade base classes.
- `networktables` and `telemetry`: the NT4 server, topic values, state publisher, and local robot web services.
- `logging`: asynchronous CSV logging, local log serving, replay support, and diagnostics.
- `util`: deterministic clock and other runtime utilities.

Do not put FTC SDK, WPILib, CTRE, REV, or Android types in this module.

### `ftc-hardware`

This module adapts FTC devices to ARESLib contracts. Important boundaries include `FtcBaseRobot`, `FtcMecanumRobot`, `MecanumHardwareIO`, `PinpointIO`, FTC vision adapters, cached hardware wrappers, and bulk sensor readers. `ftc-mocks` is compile-only for production and present at test runtime; it is not shipped as robot hardware code by this module.

### `frc-hardware`

This module adapts WPILib and vendor hardware to the same core contracts. It owns `FrcBaseRobot`, `FrcSwerveRobot`, swerve hardware IO, the FRC Limelight adapter, telemetry, and power management. Season classes intentionally live in ARES-FRC, even when they share the `com.areslib.frc` package.

### `ftc-mocks`

Mocks reproduce the subset of FTC/Android APIs needed to compile and run FTC code on a desktop JVM. They are infrastructure for tests and simulation, not a second source of robot business logic.

### `simulator`

The simulator runs real FTC OpModes against mocks and Dyn4j physics. `DesktopSimLauncher` owns deterministic time, the physics loop, OpMode lifecycle, virtual driver station, NT4 server, and local log server. Season repositories add their OpModes to its classpath.

## Robot loop ownership

The base robot loop follows this ordering:

1. Refresh hardware inputs once.
2. Dispatch observations/actions into the store.
3. Run pure reducers to produce the current state.
4. Compute controller and safety outputs from cached inputs and state.
5. Write outputs to hardware.
6. Publish telemetry/log data.

This order prevents two common faults: control decisions based on mixed-age sensor samples and getters that unexpectedly perform bus I/O. A hardware adapter should expose cached values populated by its loop refresh method.

## Extending the library

For a new reusable subsystem:

1. Put immutable state and generic actions in `core` only if multiple robots can use them.
2. Define an SDK-free IO interface and mutable cached input object.
3. Implement FTC/FRC adapters in their platform modules.
4. Keep the controller independent of the concrete adapter.
5. Compose season reducers around `rootReducer`; do not bypass it.
6. Add unit tests for the reducer, controller, invalid inputs, and hardware fault behavior.

If a feature is tied to one game's mechanism or field, it belongs in the season repository rather than ARESLib.

## Cross-repository change checklist

Before merging an ARESLib contract change:

- Search ARES-FTC, ARES-FRC, and ARES-Analytics for consumers.
- Keep telemetry topic spelling, units, and types compatible or provide an explicit migration.
- Test ARESLib, publish it to Maven Local, and then test all affected consumers.
- Verify both physical adapters and simulator mocks implement changed IO contracts.
- Update the relevant document in this directory in the same change.
