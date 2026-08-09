package com.areslib.control.feedback

import com.areslib.util.RobotClock

/**
 * Pure Mathematical Proportional-Integral-Derivative (PID) Feedback Controller with Anti-Windup and Continuous Input Domain Wrapping.
 *
 * Designed for zero-allocation high-frequency (50Hz–1000Hz) closed-loop feedback control in robotics Redux architecture pipelines.
 *
 * ### Discrete-Time Control Law:
 * Error definition: $e(k) = r(k) - y(k)$
 * Integral accumulation with anti-windup clamping:
 * $$I(k) = \text{clamp}\left(I(k-1) + e(k) \Delta t, I_{min}, I_{max}\right)$$
 * Finite-difference derivative:
 * $$\dot{e}(k) = \frac{e(k) - e(k-1)}{\Delta t}$$
 * Total control output with output saturation clamping:
 * $$u(k) = \text{clamp}\left(K_p \cdot e(k) + K_i \cdot I(k) + K_d \cdot \dot{e}(k), u_{min}, u_{max}\right)$$
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
    private var prevError: Double = 0.0
    private var prevMeasurement: Double = 0.0
    private var totalError: Double = 0.0
    private var setpoint: Double = 0.0
    
    private var filteredDerivative: Double = 0.0
    private val derivativeAlpha: Double = 0.2
    
    private var isContinuous: Boolean = false
    private var continuousMin: Double = 0.0
    private var continuousMax: Double = 0.0

    private var minOutput: Double = Double.NaN
    private var maxOutput: Double = Double.NaN
    private var minIntegral: Double = Double.NaN
    private var maxIntegral: Double = Double.NaN

    /** Error deadzone threshold below which control effort output is forced to 0.0. */
    var deadzone: Double = 0.0

    private var lastWarningTime: Long = 0L

    /**
     * Enables continuous circular input domain wrapping (e.g., $[-\pi, +\pi]$ or $[0, 360^\circ]$) to compute the shortest error path.
     *
     * @param minimumInput Lower bound of continuous domain (e.g. $-\pi$).
     * @param maximumInput Upper bound of continuous domain (e.g. $+\pi$).
     */
    fun enableContinuousInput(minimumInput: Double, maximumInput: Double) {
        isContinuous = true
        continuousMin = minimumInput
        continuousMax = maximumInput
        reset()
    }

    /**
     * Sets the minimum and maximum control output clamping bounds $[u_{min}, u_{max}]$.
     *
     * @param min Minimum allowable control output.
     * @param max Maximum allowable control output.
     */
    fun setOutputLimits(min: Double, max: Double) {
        minOutput = min
        maxOutput = max
    }

    /**
     * Configures absolute anti-windup limits $[I_{min}, I_{max}]$ on the accumulated integral sum.
     *
     * @param min Minimum allowable integral sum bound.
     * @param max Maximum allowable integral sum bound.
     */
    fun setIntegratorRange(min: Double, max: Double) {
        minIntegral = min
        maxIntegral = max
    }

    private var isFirstStep: Boolean = true

    /**
     * Resets accumulated integral error sum ($I = 0.0$) and previous error state to zero.
     */
    fun reset() {
        prevError = 0.0
        prevMeasurement = 0.0
        totalError = 0.0
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
        if (!measurement.isFinite() || !setpoint.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0.0) {
            val now = RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("PIDController: Invalid inputs detected (measurement=$measurement, setpoint=$setpoint, dtSeconds=$dtSeconds). Returning safe fallback 0.0.")
                lastWarningTime = now
            }
            return 0.0
        }

        var error = setpoint - measurement

        if (isContinuous) {
            val errorBound = (continuousMax - continuousMin) / 2.0
            error = inputModulus(error, -errorBound, errorBound)
        }

        if (kotlin.math.abs(error) < deadzone) {
            prevError = error
            return 0.0
        }

        val measurementDerivative = if (isFirstStep) 0.0 else (measurement - prevMeasurement) / dtSeconds
        filteredDerivative = derivativeAlpha * measurementDerivative + (1.0 - derivativeAlpha) * filteredDerivative
        
        isFirstStep = false
        prevMeasurement = measurement
        prevError = error

        val preSatOutput = p * error + i * totalError - d * filteredDerivative
        val isSaturated = (!maxOutput.isNaN() && preSatOutput > maxOutput && error > 0) || 
                          (!minOutput.isNaN() && preSatOutput < minOutput && error < 0)
                          
        if (!isSaturated) {
            totalError += error * dtSeconds
            if (!minIntegral.isNaN()) { totalError = kotlin.math.max(totalError, minIntegral) }
            if (!maxIntegral.isNaN()) { totalError = kotlin.math.min(totalError, maxIntegral) }
        }

        var output = p * error + i * totalError - d * filteredDerivative
        
        if (!minOutput.isNaN()) { output = kotlin.math.max(output, minOutput) }
        if (!maxOutput.isNaN()) { output = kotlin.math.min(output, maxOutput) }
        
        return output
    }
    
    private fun inputModulus(input: Double, minimumInput: Double, maximumInput: Double): Double {
        var modulus = input - minimumInput
        val wrapInput = maximumInput - minimumInput
        
        if (wrapInput <= 0) return input
        
        val numMax = (modulus / wrapInput).toInt()
        modulus -= numMax * wrapInput
        
        if (modulus < 0) {
            modulus += wrapInput
        }
        
        return modulus + minimumInput
    }
}
