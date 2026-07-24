package com.areslib.math

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.pow

/**
 * Object implementation for Input Math.
 *
 * Provides mathematical state estimation, vector filtering, or kinematic matrix operations.
 *
 * ### Physical Units & Coordinates:
 * - Position: Meters ($m$)
 * - Heading: Radians ($rad$), counter-clockwise positive
 * - Time: Seconds ($s$) or milliseconds ($ms$)
 */
object InputMath {
    /**
     * Applies a symmetric deadband to the input value.
     */
    fun applyDeadband(value: Double, deadband: Double): Double {
        val denominator = 1.0 - deadband
        return when {
            abs(value) < deadband -> 0.0
            abs(denominator) < 1e-6 -> 0.0 // Guard against division by zero
            else -> (value - sign(value) * deadband) / denominator
        }
    }

    /**
     * Applies an exponential curve to the input value while maintaining its sign.
     */
    fun applyCurve(value: Double, exponent: Double = 2.0): Double {
        return sign(value) * abs(value).pow(exponent)
    }

    /**
     * Applies radial deadband, unit-circle clamping, and exponential power curve to a 2D joystick vector [x, y].
     * Preserves exact directional angle theta while preventing square-stick corner saturation.
     *
     * @return Pair of scaled [x, y] vector components bounded to unit circle magnitude [0.0, 1.0].
     */
    fun processJoystickVector(rawX: Double, rawY: Double, deadband: Double = 0.05, exponent: Double = 1.0): Pair<Double, Double> {
        val mag = kotlin.math.hypot(rawX, rawY)
        if (mag < deadband || mag.isNaN()) return Pair(0.0, 0.0)

        val normMag = ((mag - deadband) / (1.0 - deadband)).coerceIn(0.0, 1.0)
        val curvedMag = applyCurve(normMag, exponent)

        val scaledX = (rawX / mag) * curvedMag
        val scaledY = (rawY / mag) * curvedMag
        return Pair(scaledX, scaledY)
    }
}

