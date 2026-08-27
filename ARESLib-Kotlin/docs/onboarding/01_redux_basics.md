# Onboarding 1: Redux and the robot state machine

ARESLib routes robot intent and observations through one store. The usual flow is:

```text
input or sensor observation
        -> RobotAction
        -> Store.dispatch
        -> rootReducer + slice/season reducers
        -> RobotState
        -> controller reads state and cached IO
```

## State, actions, and reducers

- `RobotState` is the root state snapshot. Its fields refer to drive, vision, superstructure, path, costmap, and tuning slices.
- `RobotAction` describes an event or requested transition. An action is not a hardware command.
- `rootReducer(state, action)` synchronously applies the domain reducers and returns the next root snapshot.
- `Store` owns the latest state and notifies subscribers after dispatch.

Reducers must be deterministic and free of hardware, network, file, and clock side effects. Most transitions use data-class `copy`. A few estimator/diagnostic buffers are deliberately mutable and pooled for zero-allocation loops; callers must not retain or mutate those shared internals outside their owning pipeline.

The store serializes dispatch, but robot code should still dispatch from the main robot loop. Do not use listeners as a second control loop.

## Exercise: heading-hold transition

The checked-in reference test is `core/src/test/kotlin/com/areslib/student/StudentOnboardingTest.kt`. Heading target and drive mode are separate actions:

```kotlin
val initial = RobotState()

val withTarget = rootReducer(
    initial,
    RobotAction.SetHeadingLockTarget(Math.PI / 2.0)
)
val holding = rootReducer(
    withTarget,
    RobotAction.SetDriveMode(DriveMode.HEADING_HOLD)
)

assertEquals(DriveMode.HEADING_HOLD, holding.drive.driveMode)
assertEquals(Math.PI / 2.0, holding.drive.headingLockTargetRadians)
```

Run it from the repository root:

```powershell
.\gradlew.bat :core:test --tests "com.areslib.student.StudentOnboardingTest"
```

## Adding season state

Season repositories compose their reducer around `rootReducer`. Keep reusable drive/vision/path transitions in ARESLib and game-specific mechanism state in the season repository. Never replace `rootReducer` with a season reducer that drops core transitions.

Next: [Desktop simulator](02_desktop_simulator.md).
