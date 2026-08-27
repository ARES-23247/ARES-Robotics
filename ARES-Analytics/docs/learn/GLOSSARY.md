# ARES Robotics Studio glossary

Definitions are written for a first-year team member. “Mentor note” adds the precise system meaning where it matters.

## A–D

**ADB (Android Debug Bridge)**

A tool the laptop uses to communicate with an FTC Control Hub, including pulling log files and deploying an app. ADB connection is separate from the NT4 telemetry connection.

**Alliance**

The red or blue side assigned to the robot. Alliance can affect starting pose and field-centric controls. Set it before INIT when using the simulator.

**CCW-positive**

Angles increase counter-clockwise. ARES uses radians internally: `0` points along +X and `π/2` points along +Y.

**Cloud Sync**

An optional desktop action that copies local artifacts to Google Drive or another cloud service. It is not the live robot connection, and a cloud failure does not erase local data.

**Control Hub**

The Android computer on an FTC robot. It runs the FTC app and may provide NT4 telemetry and logs.

**Dashboard**

The main configurable screen showing live or replayed telemetry widgets. Always check its mode/target before interpreting a value.

**Drivetrain**

The motors, wheels/modules, sensors, and control logic that move the robot.

**DuckDB**

The embedded database on the laptop where Analytics stores imported sessions and derived results. It does not run on the robot.

## E–N

**EKF (Extended Kalman Filter)**

The estimator that combines motion and sensor measurements into an estimated robot pose. Raw odometry and EKF pose can differ because the estimator also models uncertainty and may use vision.

**Field-centric drive**

Driver commands are interpreted relative to the field instead of the robot's current facing direction.

**Gateway**

The small authenticated cloud service used for limited remote/AI operations. It is not in the robot-to-dashboard telemetry path.

**Imported run**

A completed log converted into a persistent local session. It can be replayed after the robot or simulator has stopped.

**INIT**

The preparation phase before an FTC OpMode starts. Some state, including simulator starting pose/alliance behavior, is established at INIT.

**JSONL**

A log format with one JSON object per line. ARES robot/simulator logs may use it.

**Live robot**

The physical FTC or FRC robot currently publishing NT4 data. Some explicit dashboard tools can send commands or tuning values, so use the team's hardware safety process.

**Local Sim / simulator**

A robot program and physics model running on the laptop. Its values are live, but they do not come from physical hardware.

**Log**

A file of time-stamped robot or simulator evidence. A logger creates it; Analytics imports the completed file.

**NT4 (NetworkTables 4)**

The live topic protocol between the robot/simulator and Analytics. Default ARES traffic uses port `5810`.

## O–R

**Offline-first**

The robot works and records without cloud access. The laptop pulls or receives data locally, then may synchronize later.

**OpMode**

An FTC program mode, such as TeleOp or Autonomous, with INIT, start, and stop lifecycle phases.

**Pose**

The robot's field position and heading: X, Y, and rotation.

**Quarantine**

The safe holding area for a log that automatic import could not decode. The evidence and error report are preserved for repair/retry.

**Recording**

Informal shorthand for a saved run. In the current workflow the robot or simulator logger creates a file; there is no required Dashboard start/stop recording button.

**Replay**

Historical playback of an imported session. Replay can drive Dashboard visualizations but cannot move robot hardware.

**RoboRIO**

The real-time controller on an FRC robot. It can publish NT4 data and store logs that Analytics pulls over SSH/SCP.

**RobotClock**

The shared deterministic time source used in ARES library code so simulation and replay follow the same timing model.

## S–Z

**Session**

A group of telemetry and metadata treated as one run. `live-telemetry` is the reserved live buffer; imported runs receive persistent session IDs.

**Simulator command**

The command Analytics launches in the selected robot project. It is optional because FTC and FRC have default commands.

**Telemetry**

Measurements and state sent for observation: pose, battery, motor current, mechanism state, alerts, and more.

**Timestamp**

The time attached to a telemetry value. Replay order and alignment depend on timestamps increasing correctly.

**Topic**

A named NT4 data channel, such as `Drive/Pose_X`. The name, type, units, and producer together form the telemetry contract.

**Workspace / robot profile**

Analytics' saved selection for one robot project: project folder, team, season, robot ID, league, target host, and optional simulator command. It is not the same as a cloud account.

## Four terms team members should not mix up

| Term | Meaning |
| --- | --- |
| **Live robot** | Current values from physical hardware |
| **Local Sim** | Current values from a laptop physics/program process |
| **Replay** | Historical values from an imported local session |
| **Cloud Sync** | Optional copying/coordination after local storage |

See [Documentation index](../INDEX.md) for task guides and [Telemetry contract](../TELEMETRY_CONTRACT.md) for canonical topic names and types.
