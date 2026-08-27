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
