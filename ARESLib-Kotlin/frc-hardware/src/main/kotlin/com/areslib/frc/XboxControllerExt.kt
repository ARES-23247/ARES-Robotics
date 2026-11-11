package com.areslib.frc

import com.areslib.telemetry.GamepadState
import edu.wpi.first.wpilibj.XboxController
/**
 * Converts a WPILib [XboxController] into a platform-agnostic [GamepadState] data snapshot.
 *
 * Maps left/right sticks $[-1.0, 1.0]$, trigger axes $[0.0, 1.0]$, ABXY buttons, DPAD angle degrees ($0^\circ, 90^\circ, 180^\circ, 270^\circ$),
 * bumpers, stick buttons, and start/back controls into standard normalized fields.
 *
 * @return Immutable [GamepadState] snapshot.
 *
 * @see GamepadState
 * @see XboxController
 */
fun XboxController.toState() = GamepadState(
    leftStickX = leftX.toFloat(),
    leftStickY = leftY.toFloat(),
    rightStickX = rightX.toFloat(),
    rightStickY = rightY.toFloat(),
    leftTrigger = leftTriggerAxis.toFloat(),
    rightTrigger = rightTriggerAxis.toFloat(),
    a = aButton,
    b = bButton,
    x = xButton,
    y = yButton,
    dpadUp = pov == 0,
    dpadDown = pov == 180,
    dpadLeft = pov == 270,
    dpadRight = pov == 90,
    leftBumper = leftBumperButton,
    rightBumper = rightBumperButton,
    leftStickButton = leftStickButton,
    rightStickButton = rightStickButton,
    start = startButton,
    back = backButton
)

/**
 * Mutates an existing [GamepadState] container in-place with latest physical inputs from WPILib [XboxController].
 * Checks [DriverStation.isJoystickConnected] to clear inputs to safe defaults if controller disconnects.
 *
 * @param state Pre-allocated [GamepadState] target to update in-place.
 */
fun XboxController.updateState(state: GamepadState) {

    if (!edu.wpi.first.wpilibj.DriverStation.isJoystickConnected(this.port)) {
        state.reset()
        return
    }
    state.leftStickX = leftX.toFloat()
    state.leftStickY = leftY.toFloat()
    state.rightStickX = rightX.toFloat()
    state.rightStickY = rightY.toFloat()
    state.leftTrigger = leftTriggerAxis.toFloat()
    state.rightTrigger = rightTriggerAxis.toFloat()
    state.a = aButton
    state.b = bButton
    state.x = xButton
    state.y = yButton
    val currentPov = pov
    state.dpadUp = currentPov == 0
    state.dpadDown = currentPov == 180
    state.dpadLeft = currentPov == 270
    state.dpadRight = currentPov == 90
    state.leftBumper = leftBumperButton
    state.rightBumper = rightBumperButton
    state.leftStickButton = leftStickButton
    state.rightStickButton = rightStickButton
    state.start = startButton
    state.back = backButton
}

/**
 * Zero-allocation FRC controller session binding one or two WPILib [XboxController] instances
 * to [ com.areslib.telemetry.AresGamepad ] edge-triggered and continuous bindings (`onPress`,
 * `onRelease`, `whilePressed`, `whileHeld`, `.withDeadband()`, `.withExponentialCurve()`,
 * `.withInversion()`).
 *
 * Call [update] once per 50 Hz `teleopPeriodic()` tick to sample the controllers into
 * pre-allocated [GamepadState] buffers and evaluate all bindings without heap allocation.
 *
 * ### Example
 * ```kotlin
 * val controls = frcControls(driverXbox, operatorXbox) {
 *     driver.leftStick.withDeadband(0.08).withExponentialCurve(2.0)
 *     driver.rightStickX.withDeadband(0.08).withExponentialCurve(2.0)
 *     driver.y.onPress("Zero Heading") { robot.swerveDrive.zeroHeading() }
 *     driver.rightTrigger.whileHeld("Spin Flywheel", threshold = 0.5) {
 *         robot.store.dispatch(ShooterAction.SetTargetRpm(4200.0))
 *     }
 * }
 * ```
 */
class FrcGamepadSession(
    private val driverController: XboxController,
    private val operatorController: XboxController? = null,
    private val driverSampler: (GamepadState) -> Unit = { driverController.updateState(it) },
    private val operatorSampler: (GamepadState) -> Unit = {
        operatorController?.updateState(it) ?: it.reset()
    },
) {
    private val driverState = GamepadState()
    private val operatorState = GamepadState()

    /** Primary driver gamepad with declarative button, trigger, and stick bindings. */
    val driver: com.areslib.telemetry.AresGamepad = com.areslib.telemetry.AresGamepad()

    /** Secondary operator gamepad with declarative button, trigger, and stick bindings. */
    val operator: com.areslib.telemetry.AresGamepad = com.areslib.telemetry.AresGamepad()

    /**
     * Samples the underlying controllers in-place without firing bindings and quarantines any
     * buttons/triggers held during initialization until released.
     */
    fun prime() {
        driverSampler(driverState)
        operatorSampler(operatorState)
        driver.prime(driverState)
        operator.prime(operatorState)
    }

    /**
     * Samples the underlying controllers in-place and evaluates all registered button and trigger
     * bindings for the current 50 Hz loop tick without allocating.
     */
    fun update() {
        driverSampler(driverState)
        operatorSampler(operatorState)
        driver.update(driverState)
        operator.update(operatorState)
    }
}

/**
 * Declares zero-allocation FRC gamepad bindings over one or two WPILib [XboxController] instances.
 */
fun frcControls(
    driverController: XboxController,
    operatorController: XboxController? = null,
    block: FrcGamepadSession.() -> Unit,
): FrcGamepadSession = FrcGamepadSession(driverController, operatorController).apply(block)
