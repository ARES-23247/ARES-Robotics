package com.areslib.control.filters

/**
 * Single-Pole Discrete Exponential Moving Average (EMA) Low-Pass Filter.
 *
 * Smooths out noisy physical sensor measurements (such as distance, ultrasonic, or analog sensor inputs)
 * by applying exponential weighting decay to historical data.
 *
 * ### Mathematical Formulation:
 * Discrete exponential smoothing recurrence relation:
 * $$y_k = \alpha \cdot x_k + (1 - \alpha) \cdot y_{k-1}$$
 * For loop timestep $\Delta t$ and target cutoff frequency $f_c$:
 * $$\alpha = \frac{2\pi f_c \Delta t}{1 + 2\pi f_c \Delta t}$$
 *
 * ### Physical Units & Properties:
 * - Smoothing Factor ($\alpha$): Dimensionless ratio $\alpha \in [0.0, 1.0]$ ($1.0 = \text{no filtering}, 0.0 = \text{infinite attenuation}$)
 * - Sensor Signals ($x_k, y_k$): Physical measurement units ($m, V, rad$)
 * - Zero-GC Footprint: Operates with 100% zero heap allocations during high-frequency update loops.
 *
 * @param alpha Smoothing weight factor $\alpha \in [0.0, 1.0]$.
 */
class EMAFilter(private val alpha: Double) {
    private var previousEstimate: Double = 0.0
    private var hasFirstValue: Boolean = false

    init {
        require(alpha in 0.0..1.0) { "Alpha must be between 0.0 and 1.0" }
    }

    /**
     * Calculates the filtered estimate value given a new raw sensor input reading $x_k$.
     *
     * @param input Raw sensor measurement reading ($x_k$).
     * @return Filtered, smoothed sensor estimate ($y_k$).
     */
    fun calculate(input: Double): Double {
        if (!hasFirstValue) {
            previousEstimate = input
            hasFirstValue = true
            return input
        }

        val estimate = (alpha * input) + ((1.0 - alpha) * previousEstimate)
        previousEstimate = estimate
        return estimate
    }

    /**
     * Resets internal filter state estimates, allowing the next input reading to re-initialize the filter baseline.
     */
    fun reset() {
        hasFirstValue = false
    }
}
