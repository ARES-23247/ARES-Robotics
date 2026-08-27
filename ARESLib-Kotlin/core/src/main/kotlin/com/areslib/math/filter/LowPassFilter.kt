package com.areslib.math.filter

/**
 * Single-pole Discrete Infinite Impulse Response (IIR) Low-Pass Filter.
 *
 * Implements a time-constant parameterized exponential moving average filter. Smooths high-frequency electrical
 * noise, analog sensor jitter, and battery voltage fluctuations while maintaining loop-time independence ($\Delta t$).
 *
 * ### Mathematical Formulation:
 * Filter smoothing factor $\alpha$:
 * $$\alpha = \frac{\Delta t}{RC + \Delta t}$$
 * Filtered output update:
 * $$y_k = \alpha \cdot x_k + (1 - \alpha) \cdot y_{k-1}$$
 *
 * ### Physical Units & Properties:
 * - Time Constant ($RC$): Seconds ($s$). Cutoff frequency $f_c = \frac{1}{2\pi RC}$ Hz.
 * - Time Step ($\Delta t$): Seconds ($s$)
 * - Input/Output: Arbitrary physical measurement units ($V$, $A$, $m$, $m/s$)
 *
 * ### Zero-GC Guarantee:
 * $O(1)$ scalar math execution with zero dynamic memory allocation.
 *
 * @param timeConstantSeconds Time constant $RC$ in seconds ($s$). Larger values provide smoother filtering but introduce lag.
 */
class LowPassFilter(
    private var timeConstantSeconds: Double
) {

    private var lastEstimate = 0.0
    private var hasFirstValue = false

    /** Current filtered output estimate ($y_k$). */
    val value: Double get() = lastEstimate

    /**
     * Updates the filter with a new raw measurement.
     *
     * @param measurement Noisy raw input value ($x_k$).
     * @param dtSeconds Time elapsed since last call in seconds ($\Delta t$).
     * @return Filtered output estimate ($y_k$).
     */
    fun calculate(measurement: Double, dtSeconds: Double): Double {
        if (!measurement.isFinite() || !timeConstantSeconds.isFinite() || !dtSeconds.isFinite()) {
            return lastEstimate
        }

        if (!hasFirstValue) {
            lastEstimate = measurement
            hasFirstValue = true
            return measurement
        }

        if (timeConstantSeconds <= 0.0) {
            lastEstimate = measurement
            return measurement
        }

        val dt = if (dtSeconds > 0.0) dtSeconds else 0.0

        val alpha = dt / (timeConstantSeconds + dt)
        lastEstimate = alpha * measurement + (1.0 - alpha) * lastEstimate
        return lastEstimate
    }

    /**
     * Resets internal filter memory to a specified baseline value.
     *
     * @param value Baseline value to seed the filter memory.
     */
    fun reset(value: Double = 0.0) {
        lastEstimate = value
        hasFirstValue = true
    }

    /**
     * Clears internal filter state so the next input sample snaps directly without filtering.
     */
    fun clear() {
        hasFirstValue = false
        lastEstimate = 0.0
    }

    /**
     * Updates the filter time constant $RC$.
     *
     * @param rcSeconds New time constant in seconds ($s$).
     */
    fun setTimeConstant(rcSeconds: Double) {
        timeConstantSeconds = rcSeconds
    }
}
