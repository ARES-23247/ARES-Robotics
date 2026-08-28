# Onboarding 3: Pathing integration

ARESLib parses and executes paths; ARES Robotics Studio or PathPlanner creates/edits the assets, and platform products decide how those assets are deployed to a robot. Keeping these responsibilities separate avoids file-system and UI assumptions in reusable robot code.

## Path pipeline

```text
PathPlanner .path/.auto JSON
        -> DynamicPathLoader / PathPlannerParser
        -> spline and trajectory parameterization
        -> Path / PathPoint samples
        -> HolonomicPathFollower
        -> drivetrain facade
```

`AutoBuilder` requires a configured `HolonomicPathFollower`. It can build a named path or parse a PathPlanner `.auto` task tree. Event markers resolve through `NamedCommands`; register every referenced name before building the auto.

## Coordinate contract

- Path X/Y are field-relative meters.
- Holonomic heading and path tangent are radians, CCW-positive.
- `PathPoint.velocityMps` is linear speed along the path.
- Curvature is inverse meters.
- Alliance mirroring occurs once when the path/auto is loaded for the selected alliance.

Do not apply the dashboard's field-to-canvas swap/negation to path data. That transform exists only for rendering.

## Safe path handling

Before following a generated or edited path:

1. Parse it and reject an empty result.
2. Check waypoints and constraints for finite values.
3. Evaluate footprint/collision safety against the active costmap.
4. Verify chained path endpoints and constraints are continuous enough for the robot.
5. Stop the drivetrain when loading or execution fails.

Path parsing and optimization allocate and belong outside the control loop. During execution, use the output-buffer sampling APIs where available instead of allocating a new `PathPoint` each frame.

## Telemetry used by the dashboard

`ARESNetworkStatePublisher` exposes `Path/Active`, progress/error fields, and a flattened `Path/Points` array. It rebuilds that flattened array only when the active path identity changes, then publishes the cached value. Pose data remains in the normal `Drive/*` and `ARES/EstimatedPose/*` topics.

## Verification

```powershell
.\gradlew.bat :core:test --tests "com.areslib.pathing.*"
```

Also run the season autonomous simulation suite because motor limits, alliance choice, field assets, and mode lifecycle are owned outside ARESLib.

Next: [Pit operations and hardware](04_pit_operations_and_hardware.md).
