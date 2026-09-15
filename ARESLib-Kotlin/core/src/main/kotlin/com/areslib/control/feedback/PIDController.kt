package com.areslib.control.feedback

import kotlin.math.abs
import kotlin.math.sign

/**
 * Pure Mathematical Proportional-Integral-Derivative (PID) Feedback Controller with Anti-Windup and Continuous Input Domain Wrapping.
 *
 * Designed for zero-allocation high-frequency (50Hz–1000Hz) closed-loop feedback control in robotics Redux architecture pipelines.
 *
 * ### Discrete-Time Control Law:
 * Error definition: $e(k) = r(k) - y(k)$
 * Integral accumulation with anti-windup clamping:
 * $$I(k) = \text{clamp}\left(I(k-1) + e(k) \Delta t, I_{min}, I_{max}\right)$$
 * Filtered derivative on measurement (avoids a derivative kick on target changes):
 * $$D(k) = 0.2 \frac{y(k)-y(k-1)}{\Delta t} + 0.8 D(k-1)$$
 * An integral step that drives output farther into saturation is rejected using its
 * contribution direction, including negative integral gains. Total output with clamping:
 * $$u(k) = \text{clamp}\left(K_p \cdot e(k) + K_i \cdot I(k) - K_d \cdot D(k), u_{min}, u_{max}\right)$$
 *
 * ### Physical Units & Properties:
 * - Gains ($K_p, K_i, K_d$): Output effort per error unit
 * - Control Effort Output ($u$): Motor Voltage ($V$) or normalized duty cycle ($-1.0 \dots +1.0$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 * - Memory Footprint: 100% Zero-GC allocation compliance during update cycles.
 *
 * @property p Proportional gain coefficient $K_p$.
 * @property i Integral gain coefficient $K_i$.
 * @property d Derivative gain coefficient $K_d$.
 */
class PIDController(
    var p: Double,
    var i: Double,
    var d: Double
) {
    /** Composition status for the last calculation; neutral output alone does not imply validity. */
    var lastCalculationValid: Boolean = false
        private set

    private var prevMeasurement: Double = 0.0
    private var totalError: Double = 0.0
    private var setpoint: Double = 0.0
    
    private var filteredDerivative: Double = 0.0
    private val derivativeAlpha: Double = 0.2
    private val derivativeRetention: Double = 1.0 - derivativeAlpha
    
    private var isContinuous: Boolean = false
    private var continuousPeriod: Double = 0.0
    private var continuousHalfPeriod: Double = 0.0
    private var continuousInputValid: Boolean = true

    private var minOutput: Double = Double.NaN
    private var maxOutput: Double = Double.NaN
    private var minIntegral: Double = Double.NaN
    private var maxIntegral: Double = Double.NaN
    private var outputLimitsValid: Boolean = true
    private var integralLimitsValid: Boolean = true

    /** Finite, nonnegative error deadzone. Invalid values make calculations neutralize and reset. */
    var deadzone: Double = 0.0

    /**
     * Enables continuous circular input domain wrapping (e.g., $[-\pi, +\pi]$ or $[0, 360^\circ]$) to compute the shortest error path.
     *
     * @param minimumInput Lower bound of continuous domain (e.g. $-\pi$).
     * @param maximumInput Upper bound of continuous domain (e.g. $+\pi$).
     */
    fun enableContinuousInput(minimumInput: Double, maximumInput: Double) {
        isContinuous = true
        continuousPeriod = maximumInput - minimumInput
        continuousHalfPeriod = continuousPeriod * 0.5
        continuousInputValid = minimumInput.isFinite() && maximumInput.isFinite() &&
            continuousPeriod.isFinite() && continuousHalfPeriod > 0.0
        reset()
    }

    /**
     * Sets the minimum and maximum control output clamping bounds $[u_{min}, u_{max}]$.
     *
     * NaN disables that side of the bound; outward infinities are also unbounded.
     * Invalid intervals make subsequent calculations return neutral until repaired.
     *
     * @param min Minimum allowable control output.
     * @param max Maximum allowable control output.
     */
    fun setOutputLimits(min: Double, max: Double) {
        minOutput = min
        maxOutput = max
        outputLimitsValid = validLimits(min, max)
    }

    /**
     * Configures absolute anti-windup limits $[I_{min}, I_{max}]$ on the accumulated integral sum.
     *
     * NaN disables that side of the bound; outward infinities are also unbounded.
     * Invalid intervals make subsequent calculations return neutral until repaired.
     *
     * @param min Minimum allowable integral sum bound.
     * @param max Maximum allowable integral sum bound.
     */
    fun setIntegratorRange(min: Double, max: Double) {
        minIntegral = min
        maxIntegral = max
        integralLimitsValid = validLimits(min, max)
    }

    private var isFirstStep: Boolean = true

    /**
     * Resets accumulated integral error sum ($I = 0.0$), previous measurement state, and the
     * derivative filter to zero. The filtered derivative must clear with the rest or the EMA
     * keeps blending the previous motion segment's rate (~20 loops of phantom D) into the
     * first outputs after every reset.
     */
    fun reset() {
        lastCalculationValid = false
        prevMeasurement = 0.0
        totalError = 0.0
        filteredDerivative = 0.0
        isFirstStep = true
    }

    /**
     * Configures the target setpoint $r(k)$.
     *
     * @param setpoint Desired target reference value.
     */
    fun setSetpoint(setpoint: Double) {
        this.setpoint = setpoint
    }

    /**
     * Calculates control effort output $u(k)$ given current measurement $y(k)$, target setpoint $r(k)$, and loop timestep $\Delta t$.
     *
     * @param measurement Measured process variable $y(k)$.
     * @param setpoint Desired target setpoint reference $r(k)$.
     * @param dtSeconds Timestep duration in seconds ($\Delta t > 0$).
     * @return Computed control effort output $u(k)$.
     */
    fun calculate(measurement: Double, setpoint: Double, dtSeconds: Double): Double {
        this.setpoint = setpoint
        return calculate(measurement, dtSeconds)
    }

    /**
     * Calculates control effort output $u(k)$ using the pre-configured target setpoint and current measurement $y(k)$.
     *
     * @param measurement Measured process variable $y(k)$.
     * @param dtSeconds Timestep duration in seconds ($\Delta t > 0$).
     * @return Computed control effort output $u(k)$.
     */
    fun calculate(measurement: Double, dtSeconds: Double): Double {
        lastCalculationValid = false
        if (!measurement.isFinite() || !setpoint.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !p.isFinite() || !i.isFinite() || !d.isFinite() || !deadzone.isFinite() || deadzone < 0.0 ||
            !outputLimitsValid || !integralLimitsValid || !continuousInputValid
        ) return neutralAndReset()

        val error = if (isContinuous) wrappedDifference(setpoint, measurement) else setpoint - measurement
        if (!error.isFinite()) return neutralAndReset()
        if (i == 0.0) totalError = 0.0

        if (deadzone > 0.0 && abs(error) < deadzone) {
            // Suppress all effort in the deadzone while keeping the derivative baseline fresh.
            prevMeasurement = measurement
            filteredDerivative = 0.0
            isFirstStep = false
            lastCalculationValid = true
            return 0.0
        }

        val nextDerivative = if (d != 0.0 && !isFirstStep) {
            val measurementDelta = if (isContinuous) wrappedDifference(measurement, prevMeasurement)
                else measurement - prevMeasurement
            val measurementDerivative = measurementDelta / dtSeconds
            if (!measurementDerivative.isFinite()) return neutralAndReset()
            derivativeAlpha * measurementDerivative + derivativeRetention * filteredDerivative
        } else 0.0
        if (!nextDerivative.isFinite()) return neutralAndReset()

        var candidateIntegral = if (i != 0.0) totalError + error * dtSeconds else 0.0
        if (!candidateIntegral.isFinite()) return neutralAndReset()
        if (i != 0.0) {
            if (!minIntegral.isNaN()) candidateIntegral = maxOf(candidateIntegral, minIntegral)
            if (!maxIntegral.isNaN()) candidateIntegral = minOf(candidateIntegral, maxIntegral)
        }

        val proportional = p * error
        val derivative = d * nextDerivative
        val preSatOutput = proportional + i * candidateIntegral - derivative
        if (!preSatOutput.isFinite()) return neutralAndReset()
        // Compare accumulator values instead of multiplying a potentially overflowing delta.
        val integralDirection = when {
            candidateIntegral > totalError -> sign(i)
            candidateIntegral < totalError -> -sign(i)
            else -> 0.0
        }
        val rejectIntegral = (!maxOutput.isNaN() && preSatOutput > maxOutput && integralDirection > 0.0) ||
            (!minOutput.isNaN() && preSatOutput < minOutput && integralDirection < 0.0)
        var output = if (rejectIntegral) proportional + i * totalError - derivative else preSatOutput
        if (!output.isFinite()) return neutralAndReset()
        if (!minOutput.isNaN()) output = maxOf(output, minOutput)
        if (!maxOutput.isNaN()) output = minOf(output, maxOutput)

        if (!rejectIntegral) totalError = candidateIntegral
        filteredDerivative = nextDerivative
        prevMeasurement = measurement
        isFirstStep = false
        lastCalculationValid = true
        return output
    }

    private fun neutralAndReset(): Double {
        reset()
        return 0.0
    }

    private fun validLimits(minimum: Double, maximum: Double): Boolean =
        minimum != Double.POSITIVE_INFINITY && maximum != Double.NEGATIVE_INFINITY &&
            (minimum.isNaN() || maximum.isNaN() || minimum <= maximum)

    private fun wrappedDifference(left: Double, right: Double): Double {
        val difference = left - right
        // Usually one remainder is sufficient. Reduce the operands separately only
        // when finite endpoints have an unrepresentable direct difference.
        return if (difference.isFinite()) wrapDelta(difference)
            else wrapDelta(wrapDelta(left) - wrapDelta(right))
    }

    private fun wrapDelta(value: Double): Double {
        val remainder = value % continuousPeriod
        return when {
            remainder >= continuousHalfPeriod -> remainder - continuousPeriod
            remainder < -continuousHalfPeriod -> remainder + continuousPeriod
            else -> remainder
        }
    }
}
