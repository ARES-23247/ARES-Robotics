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
 * @param positiveRateLimit Maximum allowed rate of increase per second ($r_{\text{pos}} > 0$).
 * @param negativeRateLimit Maximum allowed rate of decrease per second ($r_{\text{neg}} < 0$). Defaults to $-positiveRateLimit$.
 * @param initialValue Starting output signal value before first update (default: 0.0).
 */
class SlewRateLimiter(
    private var positiveRateLimit: Double,
    private var negativeRateLimit: Double = -positiveRateLimit,
    initialValue: Double = 0.0
) {

    private var lastValue = if (initialValue.isFinite()) initialValue else 0.0
    private var hasBeenCalled = true

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
        if (!input.isFinite() || !dtSeconds.isFinite() ||
            !positiveRateLimit.isFinite() || !negativeRateLimit.isFinite()
        ) return lastValue

        if (!hasBeenCalled) {
            lastValue = input
            hasBeenCalled = true
            return input
        }

        val dt = if (dtSeconds > 0.0) dtSeconds else 0.0
        if (dt == 0.0) return lastValue

        val change = input - lastValue
        val posLimit = kotlin.math.abs(positiveRateLimit)
        val negLimit = -kotlin.math.abs(negativeRateLimit)

        val clampedChange = change.coerceIn(negLimit * dt, posLimit * dt)
        lastValue += clampedChange
        return lastValue
    }

    /**
     * Resets the internal state to a new baseline value and marks it as initialized.
     *
     * @param value New baseline output signal value.
     */
    fun reset(value: Double = 0.0) {
        lastValue = if (value.isFinite()) value else 0.0
        hasBeenCalled = true
    }

    /**
     * Clears internal state so the next input sample snaps directly without rate limiting.
     */
    fun clear() {
        hasBeenCalled = false
        lastValue = 0.0
    }

    /**
     * Dynamically updates positive and negative rate limits.
     *
     * @param positive Maximum rate of increase per second.
     * @param negative Maximum rate of decrease per second.
     */
    fun setRateLimits(positive: Double, negative: Double = -positive) {
        positiveRateLimit = positive
        negativeRateLimit = negative
    }
}
