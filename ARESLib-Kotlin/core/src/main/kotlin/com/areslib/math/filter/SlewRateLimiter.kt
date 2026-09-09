package com.areslib.math.filter

/**
 * Signal rate-of-change limiter (Slew Rate Limiter).
 *
 * Prevents rapid changes in control signals by bounding the derivative $\frac{du}{dt}$ between asymmetric positive ($r_{\text{pos}}$)
 * and negative ($r_{\text{neg}}$) rate limits. Essential for smoothing driver joystick inputs, limiting drivetrain acceleration
 * to prevent wheel slip, and mitigating high-current battery brownout spikes.
 *
 * ### Mathematical Formulation:
 * $$\Delta u = \text{coerceIn}(u_{\text{input}} - u_{\text{last}}, -|r_{\text{neg}}| \cdot \Delta t, |r_{\text{pos}}| \cdot \Delta t)$$
 * $$u_{\text{output}} = u_{\text{last}} + \Delta u$$
 *
 * ### Physical Units:
 * - Signal $u$: Arbitrary units (e.g. Volts $V$, Duty cycle percent $-1.0 \dots +1.0$, or Velocity $m/s$)
 * - Rate Limit $r$: Signal units per second (e.g. $V/s$, $1/s$, or $m/s^2$)
 * - Time Step $\Delta t$: Seconds ($s$)
 *
 * ### Zero-GC Guarantee:
 * Operates in $O(1)$ time using primitive scalar arithmetic with zero dynamic memory allocation.
 *
 * @param positiveRateLimit Rate-of-increase magnitude per second. Signs are normalized; zero freezes increases.
 * @param negativeRateLimit Rate-of-decrease magnitude per second. Signs are normalized; zero freezes decreases.
 * Defaults to $-positiveRateLimit$. Nonfinite rates hold output until repaired.
 * @param initialValue Starting output signal value before first update (default: 0.0).
 */
class SlewRateLimiter(
    positiveRateLimit: Double,
    negativeRateLimit: Double = -positiveRateLimit,
    initialValue: Double = 0.0
) {

    private var lastValue = if (initialValue.isFinite()) initialValue else 0.0
    private var hasBaseline = true
    private var positiveMagnitude = kotlin.math.abs(positiveRateLimit)
    private var negativeMagnitude = kotlin.math.abs(negativeRateLimit)

    /** Current output value of the rate limiter. */
    val value: Double get() = lastValue

    /**
     * Filters the target input signal to enforce maximum rate-of-change constraints.
     *
     * @param input Desired target input signal value.
     * @param dtSeconds Time elapsed since last update cycle in seconds ($\Delta t$).
     * @return Rate-limited output signal value.
     */
    fun calculate(input: Double, dtSeconds: Double): Double {
        if (!input.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !positiveMagnitude.isFinite() || !negativeMagnitude.isFinite()
        ) return lastValue

        if (!hasBaseline) {
            lastValue = input
            hasBaseline = true
            return input
        }

        if (input == lastValue) return lastValue
        val increasing = input > lastValue
        val magnitude = if (increasing) positiveMagnitude else negativeMagnitude
        val allowance = magnitude * dtSeconds
        val gap = if (increasing) input - lastValue else lastValue - input
        val next = if (gap.isInfinite() && allowance.isInfinite()) {
            // Both can exceed MAX_VALUE without the permitted endpoint being nonfinite.
            val halfGap = if (increasing) input * 0.5 - lastValue * 0.5 else lastValue * 0.5 - input * 0.5
            val halfAllowance = (magnitude * 0.5) * dtSeconds
            if (halfAllowance >= halfGap) input
            else (lastValue * 0.5 + if (increasing) halfAllowance else -halfAllowance) * 2.0
        } else if (allowance >= gap) input
        else lastValue + if (increasing) allowance else -allowance
        lastValue = next.coerceIn(minOf(lastValue, input), maxOf(lastValue, input))
        return lastValue
    }

    /**
     * Resets the internal state to a new baseline value and marks it as initialized.
     *
     * @param value New baseline output signal value.
     */
    fun reset(value: Double = 0.0) {
        lastValue = if (value.isFinite()) value else 0.0
        hasBaseline = true
    }

    /**
     * Clears state so the next finite input with positive finite time snaps without rate limiting.
     */
    fun clear() {
        hasBaseline = false
        lastValue = 0.0
    }

    /**
     * Dynamically updates positive and negative rate limits.
     *
     * @param positive Maximum rate of increase per second.
     * @param negative Maximum rate of decrease per second.
     */
    fun setRateLimits(positive: Double, negative: Double = -positive) {
        positiveMagnitude = kotlin.math.abs(positive)
        negativeMagnitude = kotlin.math.abs(negative)
    }
}
