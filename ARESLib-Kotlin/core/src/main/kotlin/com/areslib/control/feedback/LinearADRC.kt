package com.areslib.control.feedback

import kotlin.math.abs

/**
 * Linear Active Disturbance Rejection Controller (LADRC) with Extended State Observer (ESO).
 *
 * Replaces classical PID control by treating all unmodeled dynamics, internal parameter variations, physical friction,
 * and external disturbances as an extended total disturbance state $f(t) = x_2$.
 *
 * ### Extended State Observer (ESO) Equations:
 * Observer gains parameterized by observer bandwidth $\omega_o$: $l_1 = 2\omega_o$, $l_2 = \omega_o^2$.
 * $$\dot{\hat{x}}_1 = \hat{x}_2 + b_0 u + 2\omega_o (y - \hat{x}_1)$$
 * $$\dot{\hat{x}}_2 = \omega_o^2 (y - \hat{x}_1)$$
 *
 * ### Control Law Equations:
 * Proportional tracking feedback effort parameterized by controller bandwidth $\omega_c$:
 * $$u_0 = \omega_c (r - \hat{x}_1)$$
 * $$u = \frac{u_0 - \hat{x}_2}{b_0}$$
 *
 * ### Physical Units & Bandwidth Recommendations:
 * - Control Output ($u$): Motor Voltage ($V$) or normalized duty cycle ($-1.0 \dots +1.0$)
 * - System Input Gain ($b_0$): System responsiveness parameter ($\Delta y / \Delta u$)
 * - Controller Bandwidth ($\omega_c$): Radians per second ($rad/s$), controls response speed
 * - Observer Bandwidth ($\omega_o$): Radians per second ($rad/s$), typically set to $\omega_o \approx (3 \dots 5) \cdot \omega_c$
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * Calculation rejects non-finite data/state, negative bandwidths, overflowing cached observer gains,
 * invalid limits/continuous ranges, and unrepresentable intermediate results. Rejection returns zero
 * and resets the observer to the finite measurement (or zero). The existing numerical operating
 * range requires |b0| > 1e-9 and continuous period > 1e-9. Zero bandwidth is allowed.
 * Observer updates commit together only after all next-state values are finite.
 *
 * @property b0 Estimated system input gain parameter ($\Delta \text{velocity} / \Delta \text{voltage}$).
 * @property omegaC Controller tracking bandwidth in radians per second ($rad/s$).
 * @property omegaO Extended state observer bandwidth in radians per second ($rad/s$).
 */
class LinearADRC(
    b0: Double,
    omegaC: Double,
    omegaO: Double
) {
    /** Internal composition status, false for rejected configuration or arithmetic. */
    internal var lastCalculationValid: Boolean = false
        private set

    var b0: Double = b0
        set(value) {
            field = value
            invB0 = if (abs(value) > 1e-9) 1.0 / value else 0.0
        }

    var omegaC: Double = omegaC
        set(value) {
            field = value
            kp = value
        }

    var omegaO: Double = omegaO
        set(value) {
            field = value
            l1 = 2.0 * value
            l2 = value * value
        }

    private var invB0: Double = if (abs(b0) > 1e-9) 1.0 / b0 else 0.0
    private var kp: Double = omegaC
    private var l1: Double = 2.0 * omegaO
    private var l2: Double = omegaO * omegaO

    /** Estimated system state $\hat{x}_1$ (position or velocity). */
    var xHat1: Double = 0.0

    /** Estimated total disturbance state $\hat{x}_2 = f(t)$ in physical state units per second. */
    var xHat2: Double = 0.0

    private var uPrev: Double = 0.0

    private var minOutput: Double = Double.NaN
    private var maxOutput: Double = Double.NaN

    private var isContinuous: Boolean = false
    private var continuousMin: Double = 0.0
    private var continuousMax: Double = 0.0

    /**
     * Enables continuous circular input domain wrapping (e.g. $[-\pi, +\pi]$ radians) to take the shortest angular path.
     *
     * @param minimumInput Lower bound of continuous input domain (e.g. $-\pi$).
     * @param maximumInput Upper bound of continuous input domain (e.g. $+\pi$).
     */
    fun enableContinuousInput(minimumInput: Double, maximumInput: Double) {
        isContinuous = true
        continuousMin = minimumInput
        continuousMax = maximumInput
    }

    /**
     * Sets output saturation limits $[u_{min}, u_{max}]$ on commanded control effort.
     *
     * @param min Lower allowable control output bound.
     * @param max Upper allowable control output bound.
     */
    fun setOutputLimits(min: Double, max: Double) {
        minOutput = min
        maxOutput = max
    }

    /**
     * Resets internal observer state estimates ($\hat{x}_1 = y_{current}, \hat{x}_2 = 0$) to match a new initial measurement.
     * Prevents initial control effort spikes upon activation.
     *
     * @param measurement Measured plant output value to reset observer position to.
     */
    fun reset(measurement: Double) {
        lastCalculationValid = false
        xHat1 = measurement
        xHat2 = 0.0
        uPrev = 0.0
    }

    /**
     * Calculates commanded control effort $u(k)$ based on target setpoint $r$, current plant measurement $y$, and loop timestep $\Delta t$.
     *
     * @param target Desired target setpoint $r$.
     * @param measurement Measured plant output $y$.
     * @param dtSeconds Timestep duration in seconds ($\Delta t > 0$).
     * @return Commanded control effort $u(k)$ (e.g. Volts or duty cycle).
     */
    fun calculate(target: Double, measurement: Double, dtSeconds: Double): Double {
        lastCalculationValid = false
        if (!target.isFinite() || !measurement.isFinite() ||
            !dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !b0.isFinite() || abs(b0) <= 1e-9 || !omegaC.isFinite() || omegaC < 0.0 ||
            !omegaO.isFinite() || omegaO < 0.0 || !l1.isFinite() || !l2.isFinite() ||
            !xHat1.isFinite() || !xHat2.isFinite() ||
            minOutput == Double.POSITIVE_INFINITY || maxOutput == Double.NEGATIVE_INFINITY ||
            (!minOutput.isNaN() && !maxOutput.isNaN() && minOutput > maxOutput) ||
            (isContinuous && (!continuousMin.isFinite() || !continuousMax.isFinite() ||
                !(continuousMax - continuousMin).isFinite() || continuousMax - continuousMin <= 1e-9))
        ) {
            return neutralAndReset(measurement)
        }

        var actualTarget = target
        var actualMeasurement = measurement

        if (isContinuous) {
            val range = continuousMax - continuousMin
            if (range.isFinite() && range > 1e-9) {
                // Unwrap the measurement onto the observer's local branch before the ESO
                // update, then place the target on the shortest branch from that sample.
                actualMeasurement = xHat1 + wrapToHalfRange(actualMeasurement - xHat1, range)
                actualTarget = actualMeasurement + wrapToHalfRange(actualTarget - actualMeasurement, range)
            }
        }

        val observerError = actualMeasurement - xHat1

        val nextXHat1 = xHat1 + (xHat2 + b0 * uPrev + l1 * observerError) * dtSeconds
        
        val u0 = kp * (actualTarget - nextXHat1)
        val uUnsat = (u0 - xHat2) * invB0

        if (!nextXHat1.isFinite() || !uUnsat.isFinite()) return neutralAndReset(measurement)

        val u = when {
            !minOutput.isNaN() && uUnsat < minOutput -> minOutput
            !maxOutput.isNaN() && uUnsat > maxOutput -> maxOutput
            else -> uUnsat
        }

        val isSaturated = u != uUnsat
        val nextXHat2 = if (!isSaturated || kotlin.math.sign(observerError) != kotlin.math.sign(u - uUnsat)) {
            xHat2 + (l2 * observerError) * dtSeconds
        } else xHat2
        if (!u.isFinite() || !nextXHat2.isFinite()) return neutralAndReset(measurement)

        xHat1 = nextXHat1
        xHat2 = nextXHat2

        uPrev = u
        lastCalculationValid = true
        return u
    }

    private fun neutralAndReset(measurement: Double): Double {
        reset(if (measurement.isFinite()) measurement else 0.0)
        return 0.0
    }

    private fun wrapToHalfRange(value: Double, range: Double): Double {
        var wrapped = value % range
        val halfRange = range * 0.5
        if (wrapped > halfRange) wrapped -= range
        if (wrapped < -halfRange) wrapped += range
        return wrapped
    }
}
