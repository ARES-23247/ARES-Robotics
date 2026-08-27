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
 *    $$y = \text{sgn}(x) \cdot |x|^p \quad (p \ge 1.0)$$
 * 3. **Radial Joystick Vector Processing**:
 *    Calculates polar magnitude $r = \sqrt{x_{\text{raw}}^2 + y_{\text{raw}}^2}$, applies deadband and curve to $r$,
 *    and projects back onto normalized direction vector $\hat{\mathbf{u}} = \frac{\mathbf{v}_{\text{raw}}}{r}$:
 *    $$\mathbf{v}_{\text{scaled}} = \hat{\mathbf{u}} \cdot \left(\frac{r - d}{1 - d}\right)^p$$
 *
 * ### Physical Units & Properties:
 * - Input/Output Signal Ranges: Normalized joystick duty cycle $[-1.0, 1.0]$
 * - Vector Direction Angle ($\theta$): Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * ### Zero-GC Guarantee:
 * Uses primitive arithmetic for scalar methods. `processJoystickVector` operates on primitive stack variables.
 */
object InputMath {
    /**
     * Applies a symmetric deadband to a scalar input signal, re-scaling the active region to $[0.0, 1.0]$.
     *
     * @param value Raw scalar input value in range $[-1.0, 1.0]$.
     * @param deadband Symmetric deadband threshold $d \in [0.0, 1.0)$.
     * @return Scaled value guaranteed to be $0.0$ within deadband and smooth up to $\pm 1.0$.
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
     * Applies an exponential power curve to a scalar input value while preserving sign.
     *
     * @param value Input value in range $[-1.0, 1.0]$.
     * @param exponent Power exponent curve factor $p$ (default $2.0$).
     * @return Curved signal output maintaining $\text{sgn}(\text{value})$.
     */
    fun applyCurve(value: Double, exponent: Double = 2.0): Double {
        return sign(value) * abs(value).pow(exponent)
    }

    /**
     * Applies radial deadband, unit-circle clamping, and exponential power curve to a 2D joystick vector $[x, y]$.
     * Preserves exact directional angle $\theta = \text{atan2}(y, x)$ while preventing square-stick corner saturation.
     *
     * @param rawX Raw X-axis joystick input $[-1.0, 1.0]$.
     * @param rawY Raw Y-axis joystick input $[-1.0, 1.0]$.
     * @param deadband Radial deadband threshold $d \in [0.0, 1.0)$ (default $0.05$).
     * @param exponent Exponential power curve factor $p \ge 1.0$ (default $1.0$).
     * @return Pair of scaled $[x, y]$ vector components bounded to unit circle magnitude $[0.0, 1.0]$.
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


