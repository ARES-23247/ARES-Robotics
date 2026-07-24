package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.Gamepad
import com.areslib.input.ControllerState
import com.areslib.math.InputMath

/**
 * Adapter that converts a mutable FTC Gamepad into a pure, immutable ControllerState.
 * It applies deadbands and curves to the analog sticks.
 */
class FtcGamepadAdapter(
    private val gamepad: Gamepad,
    private val deadband: Double = 0.05,
    private val curveExponent: Double = 2.0
) {
    /**
     * Polls the hardware Gamepad and returns a new immutable ControllerState.
     */
    fun getControllerState(): ControllerState {
        // Read raw axes
        val rawLeftX = gamepad.left_stick_x.toDouble()
        // Invert Y because FTC gamepad Y is negative when pushed up
        val rawLeftY = -gamepad.left_stick_y.toDouble()
        
        val rawRightX = gamepad.right_stick_x.toDouble()
        val rawRightY = -gamepad.right_stick_y.toDouble()
        
        val rawLeftTrigger = gamepad.left_trigger.toDouble()
        val rawRightTrigger = gamepad.right_trigger.toDouble()

        // Process joysticks using radial vector magnitude deadbanding & desaturation
        val (curvedLeftX, curvedLeftY) = InputMath.processJoystickVector(rawLeftX, rawLeftY, deadband, curveExponent)
        val (curvedRightX, curvedRightY) = InputMath.processJoystickVector(rawRightX, rawRightY, deadband, curveExponent)

        return ControllerState(
            a = gamepad.a,
            b = gamepad.b,
            x = gamepad.x,
            y = gamepad.y,
            dpadUp = gamepad.dpad_up,
            dpadDown = gamepad.dpad_down,
            dpadLeft = gamepad.dpad_left,
            dpadRight = gamepad.dpad_right,
            leftBumper = gamepad.left_bumper,
            rightBumper = gamepad.right_bumper,
            start = gamepad.start,
            back = gamepad.back,
            guide = gamepad.guide,
            leftStickButton = gamepad.left_stick_button,
            rightStickButton = gamepad.right_stick_button,
            leftStickX = curvedLeftX,
            leftStickY = curvedLeftY,
            rightStickX = curvedRightX,
            rightStickY = curvedRightY,
            leftTrigger = rawLeftTrigger,
            rightTrigger = rawRightTrigger
        )
    }
}
