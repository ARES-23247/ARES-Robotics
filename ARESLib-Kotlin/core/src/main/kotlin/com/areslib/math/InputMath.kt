package com.areslib.math

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.pow

/**
 * Driver gamepad joystick input conditioning and mathematical vector filtering utilities.
 *
 * Implements radial deadband scaling, non-linear exponential power curve mapping, and unit-circle vector
 * clamping to preserve exact directional heading angles without square-stick corner saturation.
 *
 * ### Mathematical Formulations:
 * 1. **Linear Deadband Scaling**:
 *    Removes center-stick mechanical drift while scaling remaining travel linearly to $[0.0, 1.0]$:
 *    $$y = \begin{cases} 0 & \text{if } |x| < d \\ \frac{x - \text{sgn}(x) \cdot d}{1 - d} & \text{if } |x| \ge d \end{cases}$$
 * 2. **Exponential Driver Control Curve**:
 *    Provides fine precision near origin while maintaining maximum top speed output:
 *    $$y = \text{sgn}(x) \cdot |x|^p \quad (p > 0.0)$$
 *    Exponents below one amplify small inputs; exponents above one reduce them.
 * 3. **Radial Joystick Vector Processing**:
 *    Calculates polar magnitude $r = \sqrt{x_{\text{raw}}^2 + y_{\text{raw}}^2}$, applies deadband and curve to $r$,
 *    and projects back onto normalized direction vector $\hat{\mathbf{u}} = \frac{\mathbf{v}_{\text{raw}}}{r}$:
 *    $$\mathbf{v}_{\text{scaled}} = \hat{\mathbf{u}} \cdot \left(\frac{r - d}{1 - d}\right)^p$$
 *
 * ### Physical Units & Properties:
 * - Input/Output Signal Ranges: Normalized joystick duty cycle $[-1.0, 1.0]$
 * - Vector Direction Angle ($\theta$): Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * ### Allocation and validity:
 * Scalar methods and [processJoystickVectorInto] use primitive arithmetic without per-call allocation.
 * [processJoystickVector] allocates a Pair and boxed components for convenience. Invalid observations
 * or configuration neutralize the affected scalar/vector; this does not establish controller freshness.
 */
object InputMath {
    /**
     * Applies a symmetric deadband to a scalar input signal, re-scaling the active region to $[0.0, 1.0]$.
     *
     * @param value Raw scalar input value in range $[-1.0, 1.0]$.
     * @param deadband Symmetric deadband threshold $d \in [0.0, 1.0)$.
     * @return Zero for invalid input/configuration or within deadband; otherwise rescaled travel.
     */
    fun applyDeadband(value: Double, deadband: Double): Double {
        if (!validAxis(value) || !validDeadband(deadband) || abs(value) <= deadband) return 0.0
        return sign(value) * ((abs(value) - deadband) / (1.0 - deadband))
    }

    /**
     * Applies an exponential power curve to a scalar input value while preserving sign.
     *
     * @param value Input value in range $[-1.0, 1.0]$.
     * @param exponent Positive finite power exponent (default $2.0$); values below one are supported.
     * @return Sign-preserving curved signal; zero for invalid input/configuration.
     */
    fun applyCurve(value: Double, exponent: Double = 2.0): Double {
        if (!validAxis(value) || !validExponent(exponent) || value == 0.0) return 0.0
        return sign(value) * curveMagnitude(abs(value), exponent)
    }

    /**
     * Applies radial deadband, unit-circle clamping, and exponential power curve to a 2D joystick vector $[x, y]$.
     * Preserves exact directional angle $\theta = \text{atan2}(y, x)$ while preventing square-stick corner saturation.
     *
     * @param rawX Raw X-axis joystick input $[-1.0, 1.0]$.
     * @param rawY Raw Y-axis joystick input $[-1.0, 1.0]$.
     * @param deadband Radial deadband threshold $d \in [0.0, 1.0)$ (default $0.05$).
     * @param exponent Positive finite power curve factor (default $1.0$).
     * @return Pair of scaled $[x, y]$ vector components bounded to unit circle magnitude $[0.0, 1.0]$.
     */
    fun processJoystickVector(rawX: Double, rawY: Double, deadband: Double = 0.05, exponent: Double = 1.0): Pair<Double, Double> {
        return withProcessedVector(rawX, rawY, deadband, exponent) { x, y -> Pair(x, y) }
    }

    /**
     * Writes conditioned X/Y into [output] indices 0/1 using the same law as [processJoystickVector].
     * The caller owns and reuses the array; extra entries are untouched. Invalid input/configuration
     * writes two zeros. Requires at least two entries, checked before any write. No shared scratch state.
     */
    fun processJoystickVectorInto(rawX: Double, rawY: Double, output: DoubleArray, deadband: Double = 0.05, exponent: Double = 1.0) {
        require(output.size >= 2) { "Joystick output needs at least two entries" }
        withProcessedVector(rawX, rawY, deadband, exponent) { x, y -> output[0] = x; output[1] = y }
    }

    private fun validAxis(value: Double) = value.isFinite() && value >= -1.0 && value <= 1.0
    private fun validDeadband(value: Double) = value.isFinite() && value >= 0.0 && value < 1.0
    private fun validExponent(value: Double) = value.isFinite() && value > 0.0
    private fun curveMagnitude(value: Double, exponent: Double) = when (exponent) {
        1.0 -> value
        2.0 -> value * value
        else -> value.pow(exponent)
    }

    // Inline result delivery avoids lambda objects, boxed components and temporary arrays on the Into path.
    private inline fun <T> withProcessedVector(rawX: Double, rawY: Double, deadband: Double, exponent: Double, result: (Double, Double) -> T): T {
        if (!validAxis(rawX) || !validAxis(rawY) || !validDeadband(deadband) || !validExponent(exponent)) return result(0.0, 0.0)
        val mag = kotlin.math.hypot(rawX, rawY)
        if (mag <= deadband) return result(0.0, 0.0)
        val normMag = if (mag >= 1.0) 1.0 else (mag - deadband) / (1.0 - deadband)
        val curvedMag = curveMagnitude(normMag, exponent)
        return result((rawX / mag) * curvedMag, (rawY / mag) * curvedMag)
    }
}


