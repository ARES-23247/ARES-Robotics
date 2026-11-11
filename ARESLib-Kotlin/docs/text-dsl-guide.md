# ARES Text-First Kotlin DSL Guide

ARES is designed so you can author complete robots **entirely in Kotlin code** inside Android Studio, IntelliJ IDEA, or VS Code—without needing the visual ARES Robotics Studio GUI—or seamlessly mix code-first mechanisms with `.ares` project documents.

Every ARES DSL compiles down to the same immutable Redux state, zero-allocation runtime controllers, and fail-closed safety contracts used by the visual tools.

---

## 1. Which DSL Should I Use?

| Goal | DSL Entry Point | Package | Runs Where? |
| --- | --- | --- | --- |
| **FTC TeleOp lifecycle & inline controls** | `teleOp { ... }` | `com.areslib.ftc.dsl` | Directly in FTC OpMode runtime |
| **FTC Mecanum robot hardware & odometry** | `ftcMecanumRobot(hardwareMap) { ... }` | `com.areslib.ftc` | Directly in FTC OpMode `setup { }` |
| **FRC Swerve robot & zero-GC Xbox controls** | `frcSwerveRobot { ... }`, `frcControls(driverXbox) { ... }` | `com.areslib.frc` | Directly in FRC `TimedRobot` runtime |
| **Inline gamepad buttons, sticks & triggers** | `driver.a.onPress(...)`, `driver.rightTrigger.whilePressed(...)` | `com.areslib.telemetry` | Inside `teleOp { controls { ... } }` or `frcControls { ... }` |
| **Runtime autonomous / macro task tree** | `robotSequence(stateProvider, dispatcher) { ... }` | `com.areslib.sequencer` | Directly in robot loop (`Task` tree) |
| **Serializable routine document (`.aresroutine`)** | `routine("id") { ... }` | `com.areslib.routine` | Compiles via `RoutineCompiler` or codegen |
| **Serializable control scheme (`.arescontrols`)** | `controlScheme("id") { ... }` | `com.areslib.controls` | Compiles via `ControlSchemeCompiler` or codegen |
| **Subsystem definition (`.aressubsystem`)** | `subsystem("id", platform) { ... }` | `com.areslib.subsystem` | Generates Redux state, IO, controller & tests |
| **Superstructure state machine (`.aressuper`)** | `superstructure("id") { ... }` | `com.areslib.superstructure` | Generates coordinated multi-mechanism state machine |
| **Drivetrain definition (`.aresdrivetrain`)** | `ftcMecanumDrivetrain("id") { ... }`, `frcCtreSwerveDrivetrain("id", ...) { ... }` | `com.areslib.drivetrain` | Generates typed drivebase plumbing & tuning |

### Choosing Between Runtime DSLs and Document DSLs
- **Pure Code-First Workflow:** Use `teleOp { controls { ... } }` (FTC) or `frcSwerveRobot { ... }` + `frcControls { ... }` (FRC) for driver bindings and `robotSequence { ... }` for autonomous/macros. Everything lives in Kotlin source files with zero codegen steps required.
- **Document / Hybrid Workflow:** Use `subsystem { ... }`, `superstructure { ... }`, `ftcMecanumDrivetrain { ... }`, `routine { ... }`, and `controlScheme { ... }` when you want deterministic code generation (`generateAresProject`), static resource-conflict validation, and bidirectional compatibility with ARES Robotics Studio.

---

## 2. FTC TeleOp & Mecanum Robot Builder (`teleOp` & `ftcMecanumRobot`)

### Minimal 15-Line FTC TeleOp
When using the defaultREV Control Hub motor names (`fl`, `fr`, `bl`, `br`) and standard 96mm wheels, `ftcMecanumRobot(hardwareMap)` requires zero boilerplate:

```kotlin
package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.dsl.FtcTeleOpBase
import com.areslib.ftc.dsl.teleOp
import com.areslib.ftc.ftcMecanumRobot
import com.qualcomm.robotcore.eventloop.opmode.TeleOp

@TeleOp(name = "Quick TeleOp", group = "Starter")
class QuickTeleOp : FtcTeleOpBase<FtcMecanumRobot>() {
    override fun buildRobot() = ftcMecanumRobot(hardwareMap)
    override fun getBaseRobot(robot: FtcMecanumRobot) = robot.base
    override fun updateRobot(robot: FtcMecanumRobot, g1: com.areslib.telemetry.GamepadState, g2: com.areslib.telemetry.GamepadState) =
        robot.update(g1, g2)
    override fun closeRobot(robot: FtcMecanumRobot) = robot.close()

    override fun define() = teleOp {
        controls {
            driver.y.onPress("Zero Heading") { robot.base.resetGyroYaw() }
        }
        everyLoop {
            robot.driveFieldCentric(
                vx = -driver.leftStick.shapedY * 1.5,
                vy = -driver.leftStick.shapedX * 1.5,
                omega = -driver.rightStickX.shapedValue * 3.0,
            )
        }
    }
}
```

### Customizing `ftcMecanumRobot`
Override only the hardware names or physical geometry that differ from the defaults:

```kotlin
val robot = ftcMecanumRobot(hardwareMap) {
    frontLeftName = "leftFront"
    frontRightName = "rightFront"
    backLeftName = "leftRear"
    backRightName = "rightRear"
    frontLeftInverted = true
    backLeftInverted = true

    wheelRadiusMeters = 0.048
    trackWidthMeters = 0.34
    wheelBaseMeters = 0.32
    gearRatio = 1.0 / 13.7
    ticksPerMotorRev = 28.0
    maxWheelSpeedMetersPerSec = 1.8

    limelightName = "limelight"
}
```

### `teleOp { }` Lifecycle Hooks
- **`setup { }`**: Runs once during `init()`. Use for one-time camera/sensor setup.
- **`onStart { }`**: Runs once when the driver presses **Play**.
- **`controls { }`**: Registers declarative `driver` and `operator` (`AresGamepad`) bindings. Buttons or triggers held during `INIT` are automatically quarantined (`prime()`) until released and re-pressed after `start()`.
- **`everyLoop { }`**: Runs every active TeleOp cycle after `controls { }` callbacks and before `updateRobot(...)`. Optional if `controls { }` or generated drive bindings already define the OpMode's periodic behavior.
- **`onStop { }`**: Runs once when the OpMode stops before active project controls are cancelled and hardware is closed.

---

## 3. Inline Gamepad Bindings & Input Shaping (`AresGamepad`)

Inside `teleOp { controls { ... } }`, `driver` and `operator` expose zero-allocation bindings for buttons, analog axes, and 2D sticks.

### Buttons (`a`, `b`, `x`, `y`, `leftBumper`, `rightBumper`, `dpadUp`, `dpadDown`, `dpadLeft`, `dpadRight`, `start`, `back`, `leftStickButton`, `rightStickButton`)
```kotlin
controls {
    // Rising-edge press
    driver.a.onPress("Intake Collect") {
        robot.dispatch(IntakeAction.SetPower(1.0))
    }

    // Falling-edge release
    driver.a.onRelease("Intake Stop") {
        robot.dispatch(IntakeAction.SetPower(0.0))
    }

    // Active every loop while held (whilePressed and whileHeld are interchangeable aliases)
    operator.rightBumper.whileHeld("Run Feeder") {
        robot.dispatch(FeederAction.Feed)
    }

    // Redux-authoritative toggle: reads current state from the store on each rising edge
    operator.x.toggle(
        name = "Toggle Flywheel",
        currentState = { robot.store.state.shooter.enabled },
    ) { shouldEnable ->
        robot.dispatch(ShooterAction.SetEnabled(shouldEnable))
    }
}
```

### Analog Axes & Digital Trigger Thresholds (`leftTrigger`, `rightTrigger`, `leftStickX`, `leftStickY`, `rightStickX`, `rightStickY`)
Analog axes support both **continuous shaping** and **digital threshold events** (`onPress`, `onRelease`, `whilePressed`, `whileHeld`) with zero per-loop heap allocations:

```kotlin
controls {
    // Shape an analog axis and consume its value every loop
    driver.leftStickY
        .withDeadband(0.05)
        .withExponentialCurve(2.0)
        .withSlewRateLimit(3.5)
        .withInversion()

    // Use a trigger as a digital button with a custom threshold (default = 0.5)
    driver.rightTrigger
        .withDeadband(0.05)
        .onPress("Spin Up Shooter", threshold = 0.65) {
            robot.dispatch(ShooterAction.SetTargetRpm(4200.0))
        }
        .whileHeld("Hold Feed Gate", threshold = 0.85) {
            robot.dispatch(FeederAction.SetOpen(true))
        }
        .onRelease("Stop Shooter", threshold = 0.65) {
            robot.dispatch(ShooterAction.SetTargetRpm(0.0))
        }

    // Radial 2D stick shaping (deadband, curve, and slew limit preserve vector direction)
    driver.leftStick
        .withDeadband(0.06)
        .withExponentialCurve(2.0)
        .withSlewRateLimit(4.0)
}
```

---

## 4. Sequencing & Routines (`robotSequence` vs `routine`)

Both `robotSequence` (runtime task tree) and `routine` (serializable `.aresroutine` document) share the same core composition vocabulary:

| Concept | Shared Methods (Available in Both DSLs) |
| --- | --- |
| Run child steps in parallel, wait for **all** | `parallel { ... }` or `together { ... }` |
| Run child steps in parallel, stop when **first** finishes | `race { ... }` or `firstToFinish { ... }` |
| Run companions until **main** step finishes | `deadline(main = { ... }, companions = { ... })` |
| Wait for a fixed duration | `waitFor(500.milliseconds)` or `waitSeconds(0.5)` |
| Invoke a registered command/action by key | `step("intake.collect")` or `namedCommand("intake.collect")` |

### Runtime Task Tree (`robotSequence`)
Use `robotSequence` when writing autonomous sequences or TeleOp macros directly in Kotlin against your Redux `Store`:

```kotlin
import com.areslib.sequencer.robotSequence
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val scorePreloadSequence = robotSequence {
    // Dispatch a constant action or compute an action from current RobotState
    dispatch(LiftAction.SetHeightMeters(0.75))
    dispatch("Aim Turret From Odometry") { state ->
        TurretAction.SetHeadingRadians(-state.drive.odometryHeading)
    }

    // Wait for a Redux state predicate with a safety timeout
    waitUntil(timeout = 1.5.seconds) { state ->
        state.drive.odometryX >= 1.0
    }

    // Run steps in parallel (both `parallel` and `together` work)
    parallel {
        step("outtake.release")
        waitFor(300.milliseconds)
    }

    // Block-based deadline: companions are cancelled as soon as `main` finishes
    deadline(
        main = { waitSeconds(1.2) },
        companions = {
            step("intake.collect")
        },
    )

    dispatch(LiftAction.SetHeightMeters(0.0))
}
```

### Declarative Routine Document (`routine`)
Use `routine` when building validated, trigger-neutral `.aresroutine` documents for codegen, autonomous catalogs, or Studio visualization:

```kotlin
import com.areslib.routine.routine
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Display name defaults to "Auto Three Piece" when omitted
val autoThreePiece = routine("auto_three_piece") {
    driveTo(xMeters = 1.25, yMeters = 0.45, headingDegrees = 90.0) {
        motionPreset("fast")
        whileDriving("intake.collect")
        atProgress(0.65, "elevator.preRaise")
        onArrival("elevator.scoreHeight")
    }

    waitUntil("elevator.atTarget", timeout = 1.5.seconds)

    parallel {
        action("outtake.score") {
            number("speed", 0.85)
        }
        waitFor(350.milliseconds)
    }

    branch("intake.hasPiece") {
        then { call("score_second_piece") }
        otherwise { action("elevator.stow") }
    }
}
```

---

## 5. Declarative Control Schemes (`controlScheme`)

Use `controlScheme` to author `.arescontrols` documents in Kotlin with fluent axis shaping and drivetrain bindings:

```kotlin
import com.areslib.controls.DriveAxisKeys
import com.areslib.controls.RoutineInvocationPolicy
import com.areslib.controls.controlScheme

// Display name defaults to "Competition Default" when omitted
val competitionControls = controlScheme("competition_default") {
    controller("driver", profile = "vader5-pro", devicePort = 0) {
        // Fluent analog shaping bound directly to drivetrain axes
        axis("left_stick_y")
            .deadband(0.05)
            .curve(2.0)
            .invert()
            .slewRate(3.5)
            .drive(DriveAxisKeys.VX)

        axis("left_stick_x")
            .deadband(0.05)
            .curve(2.0)
            .invert()
            .drive(DriveAxisKeys.VY)

        axis("right_stick_x")
            .deadband(0.05)
            .scale(0.85)
            .invert()
            .drive(DriveAxisKeys.OMEGA)

        // Buttons, triggers, and multi-button chords
        button("a").debounce(0.03).onPress { action("intake.collect") }
        button("a").onRelease { action("intake.stop") }

        trigger("right_trigger", pressAt = 0.65, releaseAt = 0.50)
            .deadband(0.05)
            .whilePressed {
                action("shooter.spin") { number("rpm", 4200.0) }
            }

        chord("left_bumper", "right_bumper")
            .chordWindow(0.08)
            .onPress {
                routine("auto_three_piece", RoutineInvocationPolicy.TOGGLE_CANCEL)
            }
    }
}
```

---

## 6. Subsystem DSL (`subsystem` — Schema v11)

The `subsystem` DSL builds validated `SubsystemDocument` models with sensible defaults:
- `displayName` and `kotlinTypeName` are automatically derived from `documentId` when omitted (e.g., `"dual_flywheel"` -> `"Dual Flywheel"` and `"DualFlywheel"`).
- On `SubsystemPlatform.FTC`, `hardwareMapName` defaults to the hardware device `id` when not explicitly set.
- Every actuator kind (`motor`, `positionalServo`, `continuousServo`, `solenoid`, `digitalOutput`, `pwmOutput`, `buzzer`, `indicatorLight`, `prismDriver`) automatically defaults its fail-closed `safeOutput` (`0.0`, or `0.5` for positional servos).
- Role-specific state helpers (`targetDouble`, `measurementDouble`, `statusBoolean`, `configDouble`, etc.) eliminate verbose `SubsystemFieldRole` boilerplate.

### Complete Mechanism Example
```kotlin
import com.areslib.subsystem.*

val shooter = subsystem("dual_flywheel", SubsystemPlatform.FTC) {
    description = "Twin counter-rotating flywheel shooter with overcurrent recovery"

    // 1. Role-specific state fields
    val targetVel = state.targetDouble("targetRadPerSec", unit = "rad/s", minimum = 0.0, maximum = 550.0)
    val measuredVel = state.measurementDouble("velocityRadPerSec", unit = "rad/s")
    val currentAmps = state.measurementDouble("currentAmps", unit = "A")
    val atSpeed = state.statusBoolean("atSpeed")
    val idleVel = state.configDouble("idleRadPerSec", default = 80.0, unit = "rad/s")

    // 2. Hardware devices (hardwareMapName defaults to "flywheelLeft" and "flywheelRight" on FTC)
    val leader = hardware.motor("flywheelLeft") {
        measurement(measuredVel, SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND, scale = 0.10472)
        measurement(currentAmps, SubsystemMeasurementSource.MOTOR_CURRENT_AMPS)
    }
    hardware.motor("flywheelRight") {
        follows(leader, inverted = true)
    }
    hardware.indicatorLight("readyLed") {
        visualPlacement(SubsystemVisualAnchor.FRONT, forwardFraction = 0.4, leftFraction = 0.3)
    }

    // 3. Closed-loop control + feedforward
    val loop = control.velocityPid("velocityLoop", leader, targetVel, measuredVel) {
        kP = 0.04
        feedforward.simpleMotor(kS = 0.18, kV = 0.019, kA = 0.002, velocityField = targetVel)
    }

    // 4. Live dashboard / simulator tuning parameter
    tuningDouble(
        key = "dual_flywheel.velocityLoop.kp",
        defaultValue = 0.04,
        minimum = 0.0,
        maximum = 1.0,
        unit = "V/(rad/s)",
        componentUid = loop.uid,
    )

    // 5. Safety: overcurrent fault recovery + cross-subsystem interlock
    safety.faultRecovery.reverseBriefly(
        actuator = leader,
        current = currentAmps,
        currentThresholdAmps = 22.0,
    )
    interlock(
        id = "hopperClearGate",
        targetSubsystemUid = "hopper",
        targetFieldId = "positionMeters",
        comparison = InterlockComparison.GREATER_THAN,
        thresholdValue = 0.05,
    ) {
        safeFallbackValue = 0.0
    }

    // 6. Physical 2-DOF linkage & simulator game-piece interaction
    linkage {
        link1LengthMeters = 0.35
        link2LengthMeters = 0.25
        joint1Actuator = leader
    }
    implementation.simulationInteraction(
        role = SimInteractionRole.PROJECTILE_LAUNCHER,
        triggerActuator = leader,
    ) {
        launchSpeedMps = 9.5
        beamBreakField = atSpeed
    }
}
```

### Supported Hardware Builders in `hardware`
| Method | Kind | Supported Platforms |
| --- | --- | --- |
| `motor(id)` | `MOTOR` | FTC, FRC, XRP |
| `positionalServo(id)` | `POSITIONAL_SERVO` | FTC, FRC, XRP |
| `continuousServo(id)` | `CONTINUOUS_SERVO` | FTC, FRC, XRP |
| `solenoid(id)` | `SOLENOID` | FRC |
| `digitalInput(id)` | `DIGITAL_INPUT` | FTC, FRC, XRP |
| `analogInput(id)` | `ANALOG_INPUT` | FTC, FRC, XRP |
| `distanceSensor(id)` | `DISTANCE_SENSOR` | FTC, FRC, XRP |
| `colorSensor(id)` | `COLOR_SENSOR` | FTC |
| `absoluteEncoder(id)` | `ABSOLUTE_ENCODER` | FTC, FRC |
| `quadratureEncoder(id)` | `QUADRATURE_ENCODER` | FTC, FRC |
| `imu(id)` | `IMU` | FTC, FRC, XRP |
| `indicatorLight(id)` | `INDICATOR_LIGHT` | FTC, FRC, XRP |
| `prismDriver(id)` | `PRISM_DRIVER` | FTC, FRC |
| `digitalOutput(id)` | `DIGITAL_OUTPUT` | XRP |
| `pwmOutput(id)` | `PWM_OUTPUT` | XRP |
| `buzzer(id)` | `BUZZER` | XRP |

---

## 7. Superstructure State Machine DSL (`superstructure`)

Use `superstructure` in `com.areslib.superstructure` to coordinate multiple subsystems with typed ports, 1D lookup tables (LUTs), action/sensor/timed transitions, cross-subsystem clamp interlocks, and health fallbacks:

```kotlin
import com.areslib.subsystem.InterlockComparison
import com.areslib.superstructure.superstructure

val coordinator = superstructure(
    id = "scoring-coordinator",
    initialStateId = "STOWED",
    faultStateId = "STOWED",
) {
    val elevatorTarget = port(elevatorSubsystem, "targetHeightMeters")
    val elevatorMeasured = port(elevatorSubsystem, "heightMeters")
    val clawClosed = port(clawSubsystem, "targetClosed")

    val heightLut = lut("distance-to-height", inputUnit = "m", outputUnit = "m") {
        point(1.0, 0.35)
        point(3.0, 0.85)
    }

    state("STOWED") {
        targetDouble(elevatorTarget, 0.0)
        targetBoolean(clawClosed, false)
    }
    state("AIMING") {
        targetLut(elevatorTarget, heightLut, source = elevatorMeasured)
        targetBoolean(clawClosed, true)
    }

    onActionTransition("request-aim", from = "STOWED", to = "AIMING", actionKey = "superstructure.aim")
    onActionTransition("request-stow", from = "AIMING", to = "STOWED", actionKey = "superstructure.stow")

    interlock(
        id = "elevator-clamp",
        primary = elevatorMeasured,
        constrained = elevatorTarget,
        comparison = InterlockComparison.LESS_THAN,
        threshold = 0.15,
        clampMaximum = 0.50,
    )
}
```

---

## 8. Drivetrain DSL (`ftcMecanumDrivetrain` & `frcCtreSwerveDrivetrain`)

Use `ftcMecanumDrivetrain` or `frcCtreSwerveDrivetrain` in `com.areslib.drivetrain` to author validated `.aresdrivetrain` contracts in Kotlin without boilerplate:

```kotlin
import com.areslib.drivetrain.ftcMecanumDrivetrain
import com.areslib.drivetrain.frcCtreSwerveDrivetrain

val ftcDrive = ftcMecanumDrivetrain("main-drive") {
    geometry(
        wheelDiameterMeters = 0.096,
        trackWidthMeters = 0.34,
        wheelBaseMeters = 0.32,
        driveGearRatio = 19.2,
        maxLinearSpeedMetersPerSecond = 2.4,
        maxAngularSpeedRadiansPerSecond = 5.5,
    )
    tuningDouble(
        key = "drive.heading.kp",
        defaultValue = 1.8,
        minimum = 0.0,
        maximum = 20.0,
        unit = "rad/s per rad",
    )
}

val frcDrive = frcCtreSwerveDrivetrain(
    id = "swerve",
    vendorSourcePath = "src/main/java/frc/robot/generated/TunerConstants.java",
    sourceSha256 = "a".repeat(64),
    drivetrainConstantsClassName = "frc.robot.generated.TunerConstants",
    canBusName = "CANivore",
)
```

---

## 9. FRC Swerve Robot & Zero-GC Xbox Controls (`frcSwerveRobot` & `frcControls`)

Code-first FRC teams get the same declarative robot and gamepad bindings as FTC via `frcSwerveRobot` and `frcControls`:

```kotlin
import com.areslib.frc.FrcLimelightIO
import com.areslib.frc.frcControls
import com.areslib.frc.frcSwerveRobot
import edu.wpi.first.wpilibj.RobotBase
import edu.wpi.first.wpilibj.TimedRobot
import edu.wpi.first.wpilibj.XboxController

class Robot : TimedRobot() {
    private val driverXbox = XboxController(0)
    private val operatorXbox = XboxController(1)

    private val robot = frcSwerveRobot {
        swerveIO = createCtreSwerveIO()
        visionIO = FrcLimelightIO("limelight-front")
        isSimulation = RobotBase.isSimulation()
        subsystems(elevatorSubsystem, shooterSubsystem)
    }

    private val controls = frcControls(driverXbox, operatorXbox) {
        driver.leftStick.withDeadband(0.08).withExponentialCurve(2.0)
        driver.rightStickX.withDeadband(0.08).withExponentialCurve(2.0)
        driver.y.onPress("Zero Heading") { robot.swerveDrive.zeroHeading() }
        driver.rightTrigger.whileHeld("Spin Shooter", threshold = 0.5) {
            robot.store.dispatch(ShooterAction.SetTargetRpm(4200.0))
        }
    }

    override fun robotPeriodic() {
        robot.update()
    }

    override fun teleopPeriodic() {
        controls.update()
        robot.swerveDrive.driveFieldCentric(
            vx = -controls.driver.leftStick.y * 4.5,
            vy = -controls.driver.leftStick.x * 4.5,
            omega = -controls.driver.rightStickX.value * 8.0,
        )
    }
}
```

---

## 10. Whole-Project Assembly & Bidirectional `.toKotlinDsl()` (`aresProject`, `autonomousCatalog`, `tuningProfile`)

You can assemble and cross-document-validate an entire robot project in pure Kotlin—catching typos across subsystems, superstructures, routines, autonomous catalogs, and control schemes in a single unit test—and render any canonical document back into Kotlin DSL via `.toKotlinDsl()`:

```kotlin
import com.areslib.controls.DriveAxisKeys
import com.areslib.project.AresLeague
import com.areslib.project.model.aresProject
import com.areslib.project.model.formatValidationReport
import com.areslib.project.model.toKotlinDsl
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.tuning.tuningComponent
import com.areslib.tuning.tuningProfile

// Load a reviewed .arescontroller profile; never infer HID indices from binding order.
val reviewedController = ControllerProfileCodec.decode(reviewedControllerJson)
val project = aresProject("ftc-2026-alpha", AresLeague.FTC) {
    controllerProfile(reviewedController)
    identity(teamId = "23247", seasonId = "2026", robotId = "alpha", displayName = "Alpha")

    ftcMecanumDrivetrain {
        geometry(
            wheelDiameterMeters = 0.096,
            trackWidthMeters = 0.34,
            wheelBaseMeters = 0.32,
            driveGearRatio = 19.2,
            maxLinearSpeedMetersPerSecond = 2.2,
            maxAngularSpeedRadiansPerSecond = 5.0,
        )
    }

    subsystem("elevator", SubsystemPlatform.FTC) {
        val target = state.targetDouble("targetMeters", unit = "m", minimum = 0.0, maximum = 1.2)
        val pos = state.measurementDouble("positionMeters", unit = "m")
        val motor = hardware.motor("elevator") {
            measurement(pos, SubsystemMeasurementSource.MOTOR_POSITION_NATIVE, scale = 0.0005)
        }
        control.positionPid("hold", motor, target, pos) { kP = 8.0 }
    }

    routine("score-preload") {
        driveTo(xMeters = 0.8, yMeters = 0.2, headingDegrees = 90.0)
        action("subsystem.elevator.set.targetMeters") { number("value", 1.05) }
    }

    autonomousCatalog(defaultEntryId = "center-preload") {
        entry(id = "center-preload", routineId = "score-preload", xMeters = 0.0, yMeters = 0.0, headingDegrees = 90.0)
    }

    controlScheme("competition_default") {
        controller("driver", profile = "xbox-series", devicePort = 0) {
            axis("left_stick_y").deadband(0.05).drive(DriveAxisKeys.VX)
            button("b").onPress { routine("score-preload") }
        }
    }

    tuningComponent(
        tuningComponent(uid = "robot.global", projectId = "ftc-2026-alpha") {
            double(key = "vision.maxLatencyMs", defaultValue = 120.0, unit = "ms", minimum = 10.0, maximum = 500.0)
        }
    )
    tuningProfile(
        tuningProfile(profileId = "competition", projectId = "ftc-2026-alpha") {
            assignDouble("robot.global.vision-maxlatencyms", 95.0)
        }
    )
}

check(project.isValid) { project.formatValidationReport() }
val fullKotlinDsl: String = project.toKotlinDsl()
```

---

## 11. IntelliJ IDEA / Android Studio Live Templates

Import [`ARESLib-Kotlin/ide/intellij/ARES_Live_Templates.xml`](../ide/intellij/ARES_Live_Templates.xml) via **Settings → Editor → Live Templates** to get instant code completion for:
- `aresTeleOp` — Declarative `teleOp { }` block with `controls { }` and `everyLoop { }`
- `aresMecanum` — `ftcMecanumRobot(hardwareMap) { ... }` configuration block
- `aresFrcSwerve` — `frcSwerveRobot { ... }` + `frcControls(driverXbox) { ... }` block
- `aresSeq` — `robotSequence(stateProvider, dispatcher) { ... }` with `dispatch`, `waitUntil`, and `parallel`
- `aresSubsystem` — `subsystem("id", SubsystemPlatform.FTC) { ... }` with role-specific state helpers, hardware, and PID loop
- `aresSuperstructure` — `superstructure("id") { ... }` state machine with states and transitions
- `aresDrivetrain` — `ftcMecanumDrivetrain("main-drive") { ... }` configuration block
- `aresRoutine` — `routine("id") { ... }` with `driveTo`, `waitUntil`, and `parallel`
- `aresControls` — `controlScheme("id") { ... }` with fluent `.deadband().curve().invert().drive(...)`
- `aresProject` — `aresProject("project-id", AresLeague.FTC) { ... }` whole-project assembly and validation block
