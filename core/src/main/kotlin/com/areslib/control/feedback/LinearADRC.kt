package com.areslib.control.feedback

import kotlin.math.abs

/**
 * Linear Active Disturbance Rejection Controller (LADRC) with Extended State Observer (ESO).
 *
 * Replaces classical PID control by modeling all unmodeled dynamics, physical friction, parameter drift,
 * and external disturbances into a single extended state $f(t) = x_2$.
 *
 * ### Extended State Observer (ESO) Equations:
 * Observer gains parameterized by observer bandwidth $\omega_o$: $l_1 = 2\omega_o$, $l_2 = \omega_o^2$.
 * $$\dot{\hat{x}}_1 = \hat{x}_2 + b_0 u + 2\omega_o (y - \hat{x}_1)$$
 * $$\dot{\hat{x}}_2 = \omega_o^2 (y - \hat{x}_1)$$
 *
 * ### Control Law:
 * Proportional feedback control effort parameterized by controller bandwidth $\omega_c$:
 * $$u_0 = \omega_c (r - \hat{x}_1)$$
 * $$u = \frac{u_0 - \hat{x}_2}{b_0}$$
 *
 * ### Physical Units & Properties:
 * - Control Output: Motor voltage ($V$) or normalized duty cycle ($-1.0$ to $+1.0$)
 * - Time Step: Seconds ($s$)
 * - Bandwidth $\omega_c, \omega_o$: Radians per second ($rad/s$), typically $\omega_o \approx (3 \dots 5) \cdot \omega_c$
 *
 * @param b0 Estimated input gain (responsiveness ratio $\Delta v / \Delta u$).
 * @param omegaC Controller tracking bandwidth ($\text{rad/s}$). Higher values increase response speed.
 */
class LinearADRC(
    var b0: Double,
    var omegaC: Double,
    var omegaO: Double
) {
    /** Estimated system state (position or velocity). */
    var xHat1: Double = 0.0

    /** Estimated total disturbance ($f(t)$) in physical units per second. */
    var xHat2: Double = 0.0

    private var uPrev: Double = 0.0

    private var minOutput: Double = Double.NaN
    private var maxOutput: Double = Double.NaN

    private var isContinuous: Boolean = false
    private var continuousMin: Double = 0.0
    private var continuousMax: Double = 0.0

    /**
     * Enables continuous circular input wrapping (e.g. $[-\pi, \pi]$ radians) to take the shortest angular path.
     *
     * @param minimumInput Lower bound of input range (e.g., $-\pi$).
     * @param maximumInput Upper bound of input range (e.g., $+\pi$).
     */
    fun enableContinuousInput(minimumInput: Double, maximumInput: Double) {
        isContinuous = true
        continuousMin = minimumInput
        continuousMax = maximumInput
    }

    /**
     * Sets output saturation clamping bounds.
     *
     * @param min Lower output limit.
     * @param max Upper output limit.
     */
    fun setOutputLimits(min: Double, max: Double) {
        minOutput = min
        maxOutput = max
    }

    /**
     * Resets internal observer states ($\hat{x}_1, \hat{x}_2$) to match a fresh sensor measurement.
     * Prevents control output spikes upon activation.
     *
     * @param measurement Current sensor measurement value.
     */
    fun reset(measurement: Double) {
        xHat1 = measurement
        xHat2 = 0.0
        uPrev = 0.0
    }

    /**
     * Calculates the commanded control effort $u$ based on the setpoint target and current measurement.
     *
     * @param target Desired target setpoint.
     * @param measurement Measured plant output.
     * @param dtSeconds Time step in seconds.
     * @return Commanded control effort (e.g., motor voltage or duty-cycle).
     */
    fun calculate(target: Double, measurement: Double, dtSeconds: Double): Double {
        if (dtSeconds <= 0.0) return 0.0

        var actualTarget = target
        var actualMeasurement = measurement

        if (isContinuous) {
            val range = continuousMax - continuousMin
            if (range > 1e-9) {
                var errorBound = (actualTarget - actualMeasurement) % range
    
                if (abs(errorBound) > (range / 2.0)) {
                    if (errorBound > 0.0) {
                        errorBound -= range
                    } else {
                        errorBound += range
                    }
                }
                actualTarget = actualMeasurement + errorBound
            }
        }

        val l1 = 2.0 * omegaO
        val l2 = omegaO * omegaO

        val observerError = actualMeasurement - xHat1

        xHat1 += (xHat2 + b0 * uPrev + l1 * observerError) * dtSeconds
        xHat2 += (l2 * observerError) * dtSeconds

        val kp = omegaC
        val u0 = kp * (actualTarget - xHat1)

        var u = if (abs(b0) > 1e-9) {
            (u0 - xHat2) / b0
        } else {
            0.0
        }

        u = when {
            !minOutput.isNaN() && u < minOutput -> minOutput
            !maxOutput.isNaN() && u > maxOutput -> maxOutput
            else -> u
        }

        uPrev = u
        return u
    }
}
