package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.Gamepad
import com.areslib.input.ControllerState
import com.areslib.math.InputMath

/**
 * Adapter converting a Qualcomm FTC SDK [Gamepad] into an immutable [ControllerState] snapshot.
 *
 * Inverts raw FTC Y-axis stick inputs (where pushing up outputs negative values), applies radial vector magnitude deadbanding,
 * and shapes stick sensitivity using non-linear curve exponents ($y = x^n$).
 *
 * ### Mathematical Formulations:
 * 1. Y-axis inversion:
 *    $$y_{raw} = -y_{gamepad}$$
 * 2. Radial deadband and curve processing:
 *    $$\mathbf{v}_{curved} = \text{processJoystickVector}(x_{raw}, y_{raw}, \text{deadband}, \text{curveExponent})$$
 *
 * ### Range & Boundaries:
 * - Normalized Sticks: Double-precision values $[-1.0, 1.0]$.
 * - Triggers: Double-precision values $[0.0, 1.0]$.
 * - Deadband: Absolute radial threshold below which stick values are suppressed to zero.
 *
 * @param gamepad Qualcomm FTC SDK [Gamepad] hardware instance.
 * Each poll reads each SDK field once. One reusable primitive buffer avoids intermediate Pair/boxing
 * allocations; the returned immutable snapshot is still newly allocated. Use from one polling owner.
 * Invalid stick coordinates neutralize that whole stick, and invalid triggers neutralize individually.
 * This adapter supplies values, not connection/age evidence; callers own freshness and output leases.
 *
 * @param deadband Radial joystick deadband threshold $[0.0, 1.0)$ (default 0.05).
 * @param curveExponent Positive finite sensitivity exponent $n$ (default 2.0).
 *
 * @see ControllerState
 * @see InputMath.processJoystickVector
 */
class FtcGamepadAdapter(
    private val gamepad: Gamepad,
    private val deadband: Double = 0.05,
    private val curveExponent: Double = 2.0
) {
    private val stick = DoubleArray(2)

    /**
     * Polls current hardware gamepad state and returns an immutable, deadbanded [ControllerState] object.
     *
     * @return Immutable [ControllerState] snapshot populated with processed joystick and button states.
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
        InputMath.processJoystickVectorInto(rawLeftX, rawLeftY, stick, deadband, curveExponent)
        val leftX = stick[0]
        val leftY = stick[1]
        InputMath.processJoystickVectorInto(rawRightX, rawRightY, stick, deadband, curveExponent)

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
            leftStickX = leftX,
            leftStickY = leftY,
            rightStickX = stick[0],
            rightStickY = stick[1],
            leftTrigger = validTrigger(rawLeftTrigger),
            rightTrigger = validTrigger(rawRightTrigger)
        )
    }

    private fun validTrigger(value: Double) = if (value.isFinite() && value >= 0.0 && value <= 1.0) value else 0.0
}

