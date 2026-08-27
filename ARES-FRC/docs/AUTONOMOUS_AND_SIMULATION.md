# Autonomous and simulation

## One routine project

FRC and FTC execute the same versioned routine model produced by the Analytics visual editor or the
shared Kotlin DSL. A routine owns drive goals, waits, conditions, parallel/deadline groups, calls,
branches, and robot actions; there is no separate path file to select or keep in sync. The routine
is trigger-neutral, so it can also be a teleop macro or be called by another routine.

Canonical source files are checked into this repository:

```text
.ares/project.json
.ares/action-catalog.json
.ares/autonomous-catalog.json
.ares/routines/<id>.aresroutine
```

`project.json` defines the league, coordinate convention, canonical field dimensions, and bumper
footprint used by both Analytics placement constraints and robot-side preflight.

The autonomous catalog supplies the starting pose, enabled state, display order, alliance policy,
and safe default separately from the reusable routine. FRC publishes enabled entry IDs to
`SmartDashboard/AvailableAutos` and reads `SmartDashboard/SelectedAuto` once in
`autonomousInit`. A missing or disabled request falls back deterministically to the configured
default, then `do-nothing`, then the first enabled entry.

Analytics automatically loads `.ares/action-catalog.json` while the robot is offline. The catalog
is authoritative; it does not discover actions by scraping Kotlin text. `FrcNativeAutoContractTest`
requires the generated keys and runtime capability factories to agree.

After editing the project, regenerate and verify the Kotlin compiled onto the RoboRIO:

```powershell
.\gradlew.bat generateAresProject
.\gradlew.bat verifyAresProject
```

`build/generated/ares/main/kotlin/com/areslib/frc/generated/GeneratedAresProject.kt` is disposable
mechanical plumbing and must not be edited or committed. `compileKotlin` regenerates and validates
it from the canonical documents using the pinned ARES code generator. Generation requires neither
the RoboRIO nor a network connection.

## Coordinate and preflight contract

Autos are authored in Blue-alliance, corner-origin field coordinates:

- X increases from the Blue wall toward the Red wall.
- Y increases left when viewed from the Blue wall.
- Heading is radians internally and counter-clockwise positive.
- Red execution reflects X across the alliance wall, preserves Y, and maps heading to `pi - heading`.

Before motion, `FRCAutoOrchestrator` performs the following fail-closed preflight:

1. Resolve the selected entry from the generated, enabled autonomous catalog.
2. Require its referenced generated routine to exist.
3. Check the starting pose and every recursively called drive goal against the field using Marvin's current 0.80 m square bumper
   footprint.
4. Configure the generated action, condition, and drive factories.
5. Seed dyn4j, CTRE odometry, and Redux pose from the alliance-adjusted starting pose, except that
   `do-nothing` deliberately preserves the current localized pose.
6. Request the routine through the shared deterministic `RoutineManager`.

Any failure before or during execution cancels the task tree, zeros drive and season targets,
invokes hardware safety, publishes `ARES/Auto/Error`, and latches the run blocked.

Only the checked-in `.ares/` project and its verified generated Kotlin are supported. Loose
`.aresauto`, PathPlanner, Choreo, and deploy-time capability files are not loaded at runtime.

## Controller ownership

This project declares no generated controller scheme. Codegen v4 therefore emits no controller
runtime API, and `ARESRobot` installs only the explicit Marvin season controller. There is no
compatibility fallback or dormant binding host. If a controller scheme is added later, its FRC
adapter, validation, and single-owner lifecycle must be introduced deliberately and verified on the
Driver Station; desktop GLFW raw indexes are not interchangeable with FRC HID indexes.

## Available Marvin actions

| Action key | Behavior |
|---|---|
| `intake.collect` | Deploys intake and runs intake/floor rollers. |
| `intake.stop` | Stops intake/floor rollers without moving the pivot. |
| `intake.stow` | Stops rollers and retracts the pivot. |
| `shooter.prepare` | Commands the 4000 RPM and 1.55-rotation cowl autonomous preset. |
| `shooter.feedWhenReady` | Waits up to 2 s for fresh aligned flywheel/cowl observations, then owns one 450 ms feeder/floor transfer. |
| `shooter.stop` | Clears flywheel, feeder, floor, and transfer targets. |

Place `shooter.feedWhenReady` in a drive goal's **On arrival** list when the chassis should stop and
wait before firing. A path marker is concurrent with drive motion and therefore should only be used
when firing while moving is intentional. A readiness timeout continues without firing and leaves
the feeder/floor safely stopped.

## Desktop simulation

Start WPILib desktop simulation with:

```powershell
.\gradlew.bat simulateJava
```

When the project is opened in ARES Robotics Studio, **Local Sim → Start driving** can enable the
simulation-only Driver Station directly. The dashboard publishes a leased, neutral-first control
frame and the robot publishes an atomic acknowledgement. If the frame becomes stale or invalid,
the simulated Driver Station disables and outputs fail closed. The direct Gradle/WPILib workflow
remains available for advanced debugging and does not change physical RoboRIO behavior.

Choose a production-generated entry by setting `SmartDashboard/SelectedAuto`. Only match-reviewed
entries from `.ares/autonomous-catalog.json` appear in that chooser; the checked-in default is the
safe `do-nothing` routine. The drive-and-shoot scenario lives under `src/test/resources/ares/` and is
loaded only by automated tests, so simulation scaffolding cannot be selected on a deployed robot.
Those tests still exercise the production compiler, task executor, Redux actions, swerve follower,
and mechanism IO against a deterministic dyn4j world.

Before deployment:

- Run the selected auto for Blue and Red and verify the mirrored starting pose.
- Confirm every editor action appears in `.ares/action-catalog.json` and has a runtime factory.
- Exercise both successful and timed-out `shooter.feedWhenReady` behavior.
- Move a pose to a field edge and confirm preflight blocks a footprint that crosses the wall.
- Select a missing entry and confirm the safe fallback is reported and outputs remain safe.
- Run `verifyAresProject` and inspect the generated Kotlin diff.
- Run `..\verify-autos.ps1` from the workspace root.

The default simulator has no trusted feeder beam-break sensor, matching the current physical robot.
Do not force detector validity merely to make inventory bookkeeping advance.
