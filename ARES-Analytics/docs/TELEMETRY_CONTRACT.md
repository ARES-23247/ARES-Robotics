# ARES telemetry contract

This is the integration contract between ARESLib, the FTC/FRC season repositories, the simulator, and ARES Robotics Studio. Update it in the same change as any topic rename or type change.

## Wire protocol

- Transport: NetworkTables 4 over WebSocket, normally port `5810`.
- Subprotocol: `v4.1.networktables.first.wpi.edu`.
- Control messages: JSON arrays containing `publish`, `unpublish`, `subscribe`, `unsubscribe`, `announce`, or `unannounce` messages.
- Value updates: a MessagePack stream of four-element arrays. Each array is `[topic-or-publisher-id, timestamp, type-id, value]`; multiple arrays are concatenated in one WebSocket frame without an outer batch array.
- Publisher IDs and subscriber IDs are scoped to one WebSocket connection.
- Topic names are normalized without leading `/` for storage and matching.
- An existing topic's declared wire type is immutable for its lifetime.

The dashboard subscribes with prefix matching to:

```text
ARES             Drive              Robot             Hardware
Topology         Tuning             Profiling         Diagnostics
Vision           Path               Gamepad1          Gamepad2
Superstructure   Calibration        SysId             Swerve
Mechanism        LoopTimeMs         TimestampMs
```

## Units and coordinates

| Quantity | Contract |
| --- | --- |
| field position | meters |
| linear velocity | meters per second |
| heading | radians, counter-clockwise positive |
| angular velocity | radians per second |
| loop time | milliseconds where key ends in `Ms`; otherwise document explicitly |
| voltage/current | volts/amperes |

Field heading `0` points along field `+X`; `π/2` points along `+Y`.

The dashboard canvas uses `canvasX = -fieldY` and `canvasY = -fieldX`. The robot icon requires a `-90°` image offset because the artwork points right at zero image rotation.

## Canonical pose topics

| Topic | NT4 type | Meaning |
| --- | --- | --- |
| `Drive/Pose_X` | `double` | fused/EKF field X |
| `Drive/Pose_Y` | `double` | fused/EKF field Y |
| `Drive/Pose_Heading` | `double` | canonical fused heading |
| `ARES/EstimatedPose` | `double[]` | `[x, y, heading]` fused/EKF pose |
| `ARES/EstimatedPose/0` | `double` | pose X compatibility scalar |
| `ARES/EstimatedPose/1` | `double` | pose Y compatibility scalar |
| `ARES/EstimatedPose/2` | `double` | pose heading compatibility scalar |
| `ARES/TruePose/0` | `double` | simulator-only Dyn4j truth X |
| `ARES/TruePose/1` | `double` | simulator-only Dyn4j truth Y |
| `ARES/TruePose/2` | `double` | simulator-only Dyn4j truth heading |
| `ARES/SimulatorPoseFrame` | `double[10]` | atomic simulator render frame: truth x/y/h, EKF x/y/h, odom x/y/h, sequence |
| `ARES/GamePiecesFrame` | `double[]` | atomic typed frame: version, count, nine-value records, sequence |
| `Drive/Odom_X` | `double` | raw odometry X |
| `Drive/Odom_Y` | `double` | raw odometry Y |
| `Drive/Odom_Heading` | `double` | raw odometry heading |

The simulator publishes `ARES/TruePose/*` from Dyn4j and publishes `ARES/EstimatedPose/*`,
`Drive/Pose_*`, and `Drive/Odom_*` from the active OpMode's Redux state. It must use the same
pre-step observation for that frame. Never overwrite the estimator topics with physics truth after
publishing Redux state; doing so alternates two sources under one name and creates a visible ghost.
Although the values describe one observation cycle, NT4 transports scalar components sequentially
and suppresses unchanged values. The field viewer therefore stages `ARES/SimulatorPoseFrame` and
commits once at its changing final sequence element. A coordinate or heading is not a valid frame
marker. The viewer must not expose intermediate simulator scalars or replace the EKF with truth.

The field viewer likewise commits `ARES/GamePiecesFrame` only when its final sequence element
arrives. Each record is `[instanceKey, typeKey, x, y, rotation, width, height, shapeCode,
colorRgb]`; meters and CCW-positive radians are used throughout. This preserves identity and visual
properties across intake, inventory, ejection, FRC flight, landing, and scoring transitions.

For `live-telemetry` persistence, Analytics records the packed frame on a monotonic laptop receipt
timeline while preserving the source timestamp in the live transport state. This is intentional:
an NT4 retained sample can predate the current connection, and a simulator clock can restart at
zero. Neither event may stretch a newly opened rewind timeline. Replay owns the field pose while it
is active; live packed frames resume ownership only after the replay boundary resets the frame
accumulator.

## Drive and estimator diagnostics

| Topic | Type | Meaning |
| --- | --- | --- |
| `Drive/Velocity_X` | `double` | measured field-relative X velocity |
| `Drive/Velocity_Y` | `double` | measured field-relative Y velocity |
| `Drive/Velocity_Omega` | `double` | measured angular velocity |
| `Drive/EKF_Drift_X` | `double` | estimator X drift/error signal |
| `Drive/EKF_Drift_Y` | `double` | estimator Y drift/error signal |
| `Drive/Innovation_Theta` | `double` | latest heading innovation |
| `Robot/Odometry/Covariance` | `double[]` | `[Pxx, Pyy, Pθθ]` covariance diagonal |
| `Robot/Pose3d` | structured/raw | AdvantageScope-compatible pose |

Do not derive “drift” summary metrics from every key containing `EKF`; pose coordinates are not errors.

## Robot health

| Topic | Type | Meaning |
| --- | --- | --- |
| `Robot/BatteryVoltage` | `double` | robot supply voltage |
| `Robot/BrownoutPowerScale` | `double` | allowed output fraction `[0,1]` |
| `Robot/BrownoutState` | `string` | brownout guard state |
| `Robot/StateOfCharge` | `double` | estimated remaining battery fraction/percent as configured by producer |
| `Robot/LoopTimeMs` | `double` | main loop duration in milliseconds |
| `Profiling/LoopTime_ms` | `double` | loop-time compatibility topic |
| `Profiling/Hz` | `double` | loop frequency |
| `Diagnostics/Power/BrownoutCount` | `double` | cumulative trip count |

Summary code treats only explicit battery-voltage topics as battery voltage. Per-motor voltage is not a battery minimum.

### Robot logging health

| Topic | Type | Meaning |
| --- | --- | --- |
| `Diagnostics/Logging/Profile` | `string` | `COMPETITION`, `SIMULATION`, or `FORENSIC` |
| `Diagnostics/Logging/AcceptedFrames` | `double` | cumulative frames accepted by the bounded queue |
| `Diagnostics/Logging/WrittenFrames` | `double` | cumulative frames durably written |
| `Diagnostics/Logging/DroppedFrames` | `double` | cumulative frames rejected or lost after a sink failure |
| `Diagnostics/Logging/QueueDepth` | `double` | current asynchronous writer queue depth |
| `Diagnostics/Logging/CurrentFileBytes` | `double` | compressed bytes in the active file |
| `Diagnostics/Logging/CompletedBytes` | `double` | compressed bytes finalized during this logger lifetime |
| `Diagnostics/Logging/Rotations` | `double` | completed automatic file rotations |
| `Diagnostics/Logging/PrunedFiles` | `double` | completed files removed by retention |

Dashboard health derives log throughput from the change in current plus completed bytes. A rotation
must therefore not appear as a negative write rate. Drops and a sustained high queue are degraded
health; a normal size-based rotation is not.

## Hardware and topology

Per-device hardware metrics follow:

```text
Hardware/Motors/{device}/Power
Hardware/Motors/{device}/Velocity
Hardware/Motors/{device}/CurrentAmps
Hardware/Motors/{device}/Temperature
```

FTC drivetrain devices are normally `fl`, `fr`, `rl`, and `rr`. Dashboard widgets also accept `bl`/`br` compatibility aliases for rear motors.

`Topology/HardwareMap` is a string containing serialized `HardwareTopology`. It is generally published once at initialization and cached by robot identity.

## Vision and calibration

| Topic | Type | Meaning |
| --- | --- | --- |
| `Vision/HasTarget` | `boolean` | a usable target/measurement exists |
| `Vision/Target_X`, `Vision/Target_Y` | `double` | producer-defined target-space coordinates |
| `Vision/MeasurementCount` | `double` | number of current measurements |
| `Vision/Pose_X`, `Vision/Pose_Y` | `double` | primary vision field pose |
| `Vision/Pose_Heading` | `double` | primary vision heading |
| `Vision/Primary_TagId` | `double` | tag ID, `-1` when absent |
| `Vision/Primary_Ambiguity` | `double` | measurement ambiguity |
| `Calibration/IsActive` | `boolean` | calibration capture is active |
| `Calibration/GyroHeading` | `double` | robot gyro heading in radians |
| `Calibration/TagIndex` | `double` | selected tag index/ID |
| `Calibration/CameraIndex` | `double` | selected camera index |
| `Calibration/CameraToTag` | `double[]` | measured camera-to-tag transform parameters |
| `Calibration/TagField` | `double[]` | known tag field position/pose parameters |

Limelight target-space yaw is `-robotPoseTargetSpace.rotation.y`. Rotation Z is tilt/roll in that boundary and must not be treated as robot heading.

## Paths and superstructure

| Topic | Type | Meaning |
| --- | --- | --- |
| `Path/Active` | `boolean` | an active path exists |
| `Path/DistanceMeters` | `double` | current path progress |
| `Path/IsChained` | `boolean` | path is part of a chain |
| `Path/DetourActive` | `boolean` | dynamic detour is active |
| `Path/Error_CrossTrack` | `double` | cross-track error in meters |
| `Path/Error_AlongTrack` | `double` | along-track error in meters |
| `Path/Error_Heading` | `double` | heading error in radians |
| `Path/Points` | `double[]` | flattened `[x, y, heading, ...]` |
| `Superstructure/PackedState` | `double[]` | season-defined packed mechanism state |
| `Superstructure/IndicatorLight/{name}` | `double` | named light output/state |

Generated subsystem runtimes publish discoverable health beneath `Subsystems/<stable-id>/`.
Dashboard treats a topic as subsystem health only when it has that exact three-segment shape; it
does not infer mechanisms from arbitrary Kotlin classes or unrelated telemetry.

| Suffix | Type | Meaning |
| --- | --- | --- |
| `TelemetryHeartbeat` | `double` | monotonically changing publish sequence proving this subsystem's telemetry is still live; it is a health signal, not a mechanism measurement |
| `FeedbackValid` | `boolean` | the cached required control snapshot is valid |
| `FeedbackAgeMs` | `double` | receiver-relative age of the newest cached snapshot |
| `ConfigurationHealthy` | `boolean` | required device configuration succeeded |
| `Homed`, `Calibrated` | `boolean` | required reference/calibration has been established |
| `HomingFaultLatched`, `OutputFaultLatched` | `boolean` | explicit fail-closed latch state |
| `CurrentReadingValid` | `boolean` | required current sample is finite and fresh |
| other generated fields | declared scalar/string | target or cached measurement, shown as supporting evidence |

The desktop computes age from its own monotonic receipt time and never trusts a robot epoch as a
wall-clock timestamp. Generated runtimes increment `TelemetryHeartbeat` on every registry publish,
so unchanged but healthy state remains fresh even when the NT4 transport suppresses unchanged
values. The heartbeat is excluded from the measurements shown to students. Missing required permits
and latched faults take priority over measurements when choosing the card's plain-language status.

Consumers of `PackedState` must be versioned alongside the season producer; the array has no self-describing field names.

## Dashboard-to-target inputs

The Analytics client publishes one atomic, leased control frame. Receivers depend on its name and type rather than the numeric publisher ID.

| Topic | Type | Meaning/default |
| --- | --- | --- |
| `ARES/Input/driveFrame` | `double[8]` | protocol-v2 atomic control frame described below |
| `ARES/Input/obstacles` | `string` | legacy obstacle-only compatibility update |
| `ARES/Input/fieldConfig` | `string` | single authoritative canonical field-document update |
| `ARES/DriverStation/Command` | `string` | driver-station command |
| `ARES/DriverStation/SelectedOpMode` | `string` | selected OpMode |
| `ARES/DriverStation/MatchTime` | `double` | current match time |
| `ARES/DriverStation/MatchState` | `string` | match state |
| `SysId/Command` | `string` | characterization command |
| `SysId/EnableToken` | `string` | fresh FTC operator-arm session token; cleared on disarm |
| `SysId/EnableLease` | `double` | increasing FTC arm lease sequence, renewed every 200 ms; robot expires after 500 ms |
| `SysId/SupportedMechanisms` | `string` | comma-separated live motion capabilities explicitly implemented by the connected runtime (`LINEAR`, `ANGULAR`, `FLYWHEEL`, etc.); missing, empty, or unknown values fail closed in Studio |

`driveFrame` is exactly `[2, sessionNonce, sequence, clientMonotonicMs, vx, vy, omega, flags]`.
The nonce is a positive integral double and sequence is a non-negative integral double; both must
fit exactly below `2^53`. Sequence strictly increases within a session and client time never
regresses. `vx` and `vy` are independently bounded to `[-8, 8] m/s`; `omega` is bounded to
`[-4π, 4π] rad/s`. Flag bits are: 0 intake, 1 flywheel, 2 transfer, 3 teleop, 4 field-centric, 5 red
alliance, 6 A, 7 B, 8 X, and 9 pose reset. A new session must begin with neutral axes and all
actuator/button/reset bits clear. The Analytics publisher repeats that neutral handshake for five
frames (100 ms at 50 Hz), preventing a receiver tick from observing motion before the handshake.
Every field in a frame shares one receiver-time lease; malformed,
replayed, out-of-order, or expired frames fail closed. The desktop simulator uses a 500 ms lease;
the physical FTC Remote Drive OpMode deliberately uses a tighter 200 ms deadman lease.

### Control and telemetry rate isolation

Control, storage/analysis, and presentation are separate rate domains:

- `ARES/Input/driveFrame` is transmitted at 50 Hz. This safety-critical send path must not
  synchronously write telemetry history, access a database, update Compose state, or wait behind
  inbound telemetry work.
- `Nt4ClientService.telemetryFlow` is the unthrottled source stream. It is reserved for consumers
  that need every sample, such as logging, analysis, SysId, and alert processing.
- Compose UI consumers use `Nt4ClientService.uiTelemetryFlow`. It coalesces by topic and publishes
  the newest value at 20 Hz, preventing high-rate telemetry bursts from starving keyboard and
  rendering work on the AWT event thread.
- `DriveFrameTelemetryRecorder` is a conflated background side channel. It records only `vx`, `vy`,
  and `omega` at 10 Hz. Do not synchronously flatten all eight fields into `TelemetryStore` on each
  50 Hz control tick.

`VisionState.measurements` is ordered oldest to newest. A publisher representing the current vision
or filtered pose must use a fresh `lastOrNull()` sample, never `firstOrNull()`; using the oldest
buffer entry creates a trailing field ghost while the robot moves.

Simulator inputs must all be read from the same custom `NT4Server` instance used by Analytics. Mixing WPILib's process-local `NetworkTableInstance` subscribers with the custom server leaves values stuck at defaults.

## String and console telemetry

String frames set `TelemetryFrame.stringValue`; their numeric field is not meaningful. Replay, Parquet, CSV extras, and summaries must preserve the string.

Only a closed set of explicit console topic names may be classified as console messages. Do not use substring checks such as `contains("log")`, which misclassify ordinary topics like `Path/Logging/Position`.

## Contract checklist

When adding or changing a topic:

- [ ] Producer and consumer agree on exact NT4 type.
- [ ] Topic is under a subscribed prefix.
- [ ] Leading slash is removed for stored identity.
- [ ] Units and coordinate frame are documented.
- [ ] Simulator and real-robot sources use the same convention.
- [ ] Numeric/string replay behavior is tested.
- [ ] Dashboard input has a safe default.
- [ ] A standards-compliant NT4 peer can publish/subscribe successfully.

## Telemetry liveness

`ARES/Telemetry/FrameSequence` is a monotonically changing numeric heartbeat emitted once per
published robot-state frame. A connected NT4 socket is not proof that the robot loop is alive;
commissioning and readiness UI must observe this changing topic locally before presenting cached
hardware values as live. The counter is transport evidence only: it does not certify any sensor,
actuator, wiring, or physical safety check.
