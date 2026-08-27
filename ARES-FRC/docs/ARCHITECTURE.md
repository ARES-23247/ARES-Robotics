# Architecture and safety contracts

## TimedRobot and Redux ownership

`Main.kt` starts `ARESRobot`, a WPILib `TimedRobot`. `ARESRobot.robotInit()` is the composition root:

- On a RoboRIO it constructs the CTRE swerve drivetrain, TalonFX mechanism IO, and the `limelight-shooter` plus `limelight-back` vision sources.
- In desktop simulation it constructs simulated mechanism IO and `Dyn4jSimulation`; there is no physical swerve or Limelight IO.
- It creates `FrcSwerveRobot` with `MarvinReducer.reduce`, then registers the season subsystem facades/controllers.
- It registers the shared update at 20 ms with a 5 ms phase offset.

WPILib mode callbacks remain thin. `FRCTeleOpDriveController` translates cached controller input into drive requests and Redux actions. `FRCAutoOrchestrator` executes the compiled routine/action tree through the same APIs. Mechanism state is never mutated directly.

`MarvinReducer` first calls the ARESLib `rootReducer`, then handles Marvin actions. This ordering is important: core pose, alliance, and power state and season superstructure state advance through one immutable `RobotState`.

## One robot-loop transaction

The shared `FrcBaseRobot.update()` sequence is the hardware transaction boundary:

1. `HardwareRegistry.refreshAll()` refreshes every registered device.
2. Robot status, mode, drivetrain, and vision observations are read.
3. Registered subsystem controllers call `readSensors()` and dispatch observation actions.
4. Power and brownout limits are calculated.
5. While enabled, subsystems and swerve call `writeOutputs()` from the resulting state. Disabled
   frames never issue normal writes; the enable-to-disable edge clears drive intent and invokes the
   physical safety path.
6. Core, season, power, registry, topology, and platform telemetry are assembled, then the FRC data
   logger is flushed exactly once so one loop produces one coherent row.

Do not reorder these phases locally. A controller must never command from a mix of this loop's state and a fresh out-of-band hardware read.

### IO freshness contract

All physical IO implementations must follow these rules:

- Perform CAN/sensor refreshes only from `refresh()` (or the framework's corresponding input-refresh phase).
- Cache the value. When freshness participates in a control or safety decision, also cache and expose its validity/status.
- Make getters pure reads of cached fields. A getter must not cause CAN traffic.
- Treat a failed status refresh as invalid data, even if the last numeric value looks plausible.
- Implement `safe()` with zero voltage/effort outputs. `HardwareRegistry.safeAll()` is the final exception path.

The current season layer has two especially important validity signals:

| Signal | Source | Fail-closed behavior |
|---|---|---|
| Flywheel readiness | Refresh status and RPM agreement for both masters and both followers | Reducer exposes measured RPM as zero when the four-signal snapshot is invalid. All “at speed” decisions additionally require every motor to be individually within 150 RPM of the target, so a healthy average cannot hide one lagging follower. |
| Feeder `pieceDetectionValid` | Beam-break/detector capability and current reading | Inventory transitions and sensor completion are ignored while invalid. Marvin XIX's physical feeder has no beam break, so physical detection is explicitly invalid rather than permanently “not detected.” |

The reducer preserves the last trusted detector edge through invalid intervals. This prevents a recovered detector from double-counting a piece.

The slamtake sequence can still operate without a detector: deploy immediately, begin retracting after 0.5 s, and finish after 1.5 s. Sensor-based early completion is permitted only when detection is valid and asserted.

### Exceptions and output limiting

Exceptions in robot, teleop, or autonomous periodic code report to Driver Station and invoke the safe hardware path. Autonomous also zeros drive, shooter, intake roller, climber voltage, and the slamtake sequence before calling `safeHardware()`.

Direct flywheel SysId voltage is authorized only in Test-enabled mode while the verified Talon
configuration, relative-mechanism homing, fatal-loop latch, mechanism safety latch, and power budget
are all healthy. Losing any permission during a run stops characterization in that same loop.
Autonomous performs the same mechanism-safety check before requesting a routine and aborts an active
routine if the latch asserts later.

Hardware registration includes the CAN2 identity of each mechanism (including every controller in
multi-motor flywheel/intake groups). `Topology/HardwareMap` is published once after registration is
complete rather than from the periodic loop.

Brownout/current scaling affects effort, not geometry:

- Voltage, velocity, and swerve requests are scaled.
- Cowl, intake pivot, and climber position targets remain physical targets; their allowed effort is scaled.
- Hardware soft limits remain the final motion boundary.

The PDH total-current read is sampled once per loop. Exceptions, non-finite/negative values, and an
enabled zero-amp reading remain explicitly invalid. Shared power management accepts the registered
branch-current fallback only when every source is fresh or covered by a fresh aggregate; otherwise
it reports invalid current and applies the critical 0.40 scale. Unknown current never becomes a
benign zero.

Never “fix” a brownout by scaling a position target toward zero; that changes the requested mechanism geometry.

## Mechanism units and gates

| Mechanism/value | Public unit | Physical notes and limits |
|---|---|---|
| Flywheel target and observation | RPM | Four TalonFX motors (CAN 9–12); converted to rotations/second at the hardware boundary. Reverse voltage is blocked. |
| Cowl target and shot lookup | Mechanism rotations | TalonFX 13. Software and hardware clamp to `0.0..1.80` rotations. Values such as `0.50` and `1.10` are rotations, not degrees. |
| Intake pivot | Degrees in the IO contract; deployed boolean in Redux | TalonFX 14. Physical IO converts degrees to rotations; controller uses 0 degrees stowed and 90 degrees deployed. Hardware soft limits are `0.0..0.30` rotations. |
| Intake roller speed | RPS | TalonFX 15. |
| Floor speed | RPS command | TalonFX 16; implemented as open-loop voltage proportional to the requested RPS. |
| Climber manual command | Volts | TalonFX 19; driver/copilot D-pad commands +6 V or -6 V. |
| Climber position command | Mechanism rotations | Sensor-to-mechanism ratio is 80:1; hardware soft limits are `0.0..1.73` rotations. |
| Feeder speed | RPS command | TalonFX 20; implemented as open-loop voltage proportional to requested RPS. Shooting uses 10 RPS. |

The cowl, intake pivot, and climber have relative-only TalonFX references. Real startup leaves all
mechanism output inhibited even when configuration succeeds. Physically place all three at their
safe zero stops, then have both operators hold Back+Start together once while Disabled or
Test-enabled. All zero writes must succeed before the latch clears; restarting the robot invalidates
the zero. Any Talon reset after configuration also invalidates the configuration latch and requires
a robot-process restart followed by safe-zero; this prevents operation with reset soft limits or
feedback ratios. Do not pass motor rotations where mechanism rotations are expected.

### Shooter authorization

The shooter is rearward-facing. Static and shoot-on-the-move aiming therefore add `pi` to the target bearing and wrap the result to the standard angle range.

Feeding is authorized only when heading error is below 0.05 rad and the flywheel observation is fresh/aligned, unless a transfer was already active. The flywheel alignment gate requires:

- `velocityValid == true`
- target speed greater than 100 RPM
- measured speed greater than 100 RPM for the normal superstructure gate
- absolute target error below 150 RPM

Shoot-on-the-move calculations use measured field-frame chassis velocity and measured angular velocity, not the commanded joystick request. Acceleration lookahead is also applied. This distinction prevents wheel slip or saturation from being treated as actual robot motion.

## Coordinates, alliance, and field targets

All pose and aiming math uses one convention:

- Meters for field positions.
- Radians internally for headings.
- CCW-positive: heading `0` is `+X`; `pi/2` is `+Y`.
- Blue-origin field frame: Blue speaker is at `X = 0`, Red speaker is at the positive-X end.

The constants used by the code are:

| Quantity | Value |
|---|---:|
| Crescendo field length | 16.54175 m (651.25 in) |
| ARESLib canonical field width | 8.21055 m |
| Official speaker center Y | 5.547868 m (218.42 in) |
| Blue speaker target | `(0.0, 5.547868)` m |
| Red speaker target | `(16.54175, 5.547868)` m |

Autonomous paths are authored in the blue-origin frame and mirrored for Red with ARESLib `AllianceMirroring` and `FieldSymmetry.MIRRORED`.

For teleop, `FRCTeleOpDriveController` currently multiplies **both** field-relative translation axes by `-1` on Red so pushing away from either alliance wall remains driver-forward. It does not invert heading or rotation. Do not add a second alliance negation downstream.

## Marvin XIX hardware map

All mechanism TalonFX devices and the generated swerve drivetrain use CAN bus `CAN2`.

| CAN ID | Device |
|---:|---|
| 9–12 | Four flywheel motors |
| 13 | Cowl |
| 14 | Intake pivot |
| 15 | Intake roller |
| 16 | Floor |
| 19 | Climber |
| 20 | Feeder |

Swerve geometry and base CTRE configuration come from generated `TunerConstants.java`; calibrated azimuth offsets come from `src/main/deploy/swerve_offsets.json` or the runtime offset file on the RoboRIO. See [operations](OPERATIONS.md#swerve-offsets).
