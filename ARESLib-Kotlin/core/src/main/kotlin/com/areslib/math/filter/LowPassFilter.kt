package com.areslib.math.filter

/**
 * Single-pole Discrete Infinite Impulse Response (IIR) Low-Pass Filter.
 *
 * Implements a time-constant parameterized exponential moving average filter. Smooths high-frequency electrical
 * noise, analog sensor jitter, and battery voltage fluctuations using the actual timestep ($\Delta t$).
 * This is backward-Euler RC discretization; changing timestep partitioning changes its approximation error.
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
     * Nonfinite input/configuration or a nonpositive/nonfinite timestep holds the current estimate,
     * including before the first sample and in bypass mode. Finite nonpositive RC selects bypass.
     */
    fun calculate(measurement: Double, dtSeconds: Double): Double {
        if (!measurement.isFinite() || !timeConstantSeconds.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0.0) {
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

        if (measurement == lastEstimate) return lastEstimate
        val previousIsMajor = timeConstantSeconds >= dtSeconds
        val small = if (previousIsMajor) dtSeconds else timeConstantSeconds
        val large = if (previousIsMajor) timeConstantSeconds else dtSeconds
        val ratio = small / large
        val divisor = 1.0 + ratio
        var major = if (previousIsMajor) lastEstimate else measurement
        var minor = if (previousIsMajor) measurement else lastEstimate
        // Raise subnormal signals before weighting so their partial products do not round away.
        val scaled = maxOf(kotlin.math.abs(major), kotlin.math.abs(minor)) < java.lang.Double.MIN_NORMAL
        if (scaled) { major = Math.scalb(major, 54); minor = Math.scalb(minor, 54) }
        val minorPart = if (ratio < java.lang.Double.MIN_NORMAL) scaledProductRatio(minor, small, large) / divisor
            else minor * (ratio / divisor)
        var estimate = major / divisor + minorPart
        if (scaled) estimate = Math.scalb(estimate, -54)
        // Rounding of two same-sign weighted terms must not overflow or leave their convex interval.
        lastEstimate = estimate.coerceIn(minOf(lastEstimate, measurement), maxOf(lastEstimate, measurement))
        return lastEstimate
    }

    /** Preserve a representable value*small/large even when small/large itself underflows. */
    private fun scaledProductRatio(value: Double, small: Double, large: Double): Double {
        if (value == 0.0) return value
        val valueExponent = Math.getExponent(value)
        val smallExponent = Math.getExponent(small)
        val largeExponent = Math.getExponent(large)
        val fraction = Math.scalb(value, -valueExponent) * Math.scalb(small, -smallExponent) /
            Math.scalb(large, -largeExponent)
        return Math.scalb(fraction, valueExponent + smallExponent - largeExponent)
    }

    /**
     * Resets internal filter memory to a specified baseline value.
     *
     * @param value Baseline value to seed the filter memory. Nonfinite baselines clear history.
     */
    fun reset(value: Double = 0.0) {
        if (!value.isFinite()) { clear(); return }
        lastEstimate = value
        hasFirstValue = true
    }

    /**
     * Clears internal filter state so the next finite input with positive finite time snaps directly.
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
