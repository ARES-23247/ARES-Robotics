package com.areslib.control.safety

/**
 * Platform-Agnostic Battery Brownout Protection Guard.
 *
 * Monitors battery voltage in real-time and applies graduated power scaling to motor outputs to prevent
 * sudden brownout-induced Control Hub or RoboRIO micro-controller reboots.
 *
 * ### Voltage Protection Zones & Power Scaling Mathematics:
 * 1. **Healthy Zone** ($V \ge V_{warning}$): $\text{powerScale} = 1.0$ (Full power output)
 * 2. **Warning Zone** ($V_{critical} < V < V_{warning}$): Linear ramp from $1.0$ down to $\alpha_{min}$:
 *    $$\text{powerScale} = \alpha_{min} + \frac{V - V_{critical}}{V_{warning} - V_{critical}} \cdot \left(1.0 - \alpha_{min}\right)$$
 * 3. **Critical Zone** ($V \le V_{critical}$): $\text{powerScale} = 0.0$ (All motor outputs disabled)
 *
 * ### Hysteresis Recovery Logic:
 * Prevents rapid zone oscillations under transient voltage sags. Transition from `CRITICAL` or `WARNING` back to a healthier state requires battery recovery exceeding threshold $+ V_{hysteresis}$.
 *
 * ### Physical Units & Properties:
 * - Battery Voltages ($V_{warning}, V_{critical}, V_{nominal}$): Volts ($V$)
 * - Power Scale Factor: Dimensionless scaling factor ($0.0 \dots 1.0$)
 * - Battery Percentage: Percent ($0.0\% \dots 100.0\%$)
 * - Memory Footprint: 100% Zero-GC allocation compliance during update cycles.
 *
 * @property warningVoltage Voltage threshold below which graduated power scaling begins ($V$).
 * @property criticalVoltage Voltage threshold below which all motor outputs are shut off ($V$).
 * @property minPowerScale Minimum allowable power scaling factor at the warning-critical boundary before cutoff.
 * @property hysteresisVoltage Hysteresis band in volts ($V$) to prevent boundary oscillation.
 * @property nominalVoltage Fully charged nominal battery voltage rating ($V$).
 *
 * @see BrownoutState
 */
class BrownoutGuard(
    val warningVoltage: Double = 10.0,
    val criticalVoltage: Double = 7.5,
    val minPowerScale: Double = 0.3,
    val hysteresisVoltage: Double = 0.3,
    val nominalVoltage: Double = 13.0
) {
    /** Current computed power scale factor ($0.0 \dots 1.0$). Multiply motor commands by this factor. */
    var powerScale: Double = 1.0
        private set

    /** Current brownout state ([BrownoutState.HEALTHY], [BrownoutState.WARNING], [BrownoutState.CRITICAL]). */
    var state: BrownoutState = BrownoutState.HEALTHY
        private set

    /** Last processed battery voltage reading in volts ($V$). */
    var lastVoltage: Double = nominalVoltage
        private set

    /** Estimated remaining battery percentage ($0.0\% \dots 100.0\%$). */
    var batteryPercent: Double = 100.0
        private set

    /** Cumulative count of state transitions into `WARNING` or `CRITICAL` since last reset. */
    var tripCount: Int = 0
        private set

    /**
     * Updates the brownout guard state machine given the latest battery voltage reading.
     *
     * Call once per main robot loop iteration. Zero heap allocations.
     *
     * @param voltage Current battery voltage reading in volts ($V$).
     */
    fun update(voltage: Double) {
        if (!voltage.isFinite() || voltage < 0.0) {
            val previousState = state
            lastVoltage = 0.0
            batteryPercent = 0.0
            state = BrownoutState.CRITICAL
            powerScale = 0.0
            if (previousState == BrownoutState.HEALTHY) tripCount++
            return
        }

        lastVoltage = voltage
        val normVolt = if (nominalVoltage > 0.1) nominalVoltage else 13.0
        batteryPercent = ((voltage / normVolt) * 100.0).coerceIn(0.0, 100.0)

        val previousState = state

        // Apply hysteresis-aware state transitions
        state = when (state) {
            BrownoutState.HEALTHY -> when {
                voltage < criticalVoltage -> BrownoutState.CRITICAL
                voltage < warningVoltage -> BrownoutState.WARNING
                else -> BrownoutState.HEALTHY
            }
            BrownoutState.WARNING -> when {
                voltage < criticalVoltage -> BrownoutState.CRITICAL
                voltage > warningVoltage + hysteresisVoltage -> BrownoutState.HEALTHY
                else -> BrownoutState.WARNING
            }
            BrownoutState.CRITICAL -> when {
                // Must recover above critical + hysteresis to leave CRITICAL
                voltage > criticalVoltage + hysteresisVoltage -> BrownoutState.WARNING
                else -> BrownoutState.CRITICAL
            }
        }

        // Count state transitions into WARNING or CRITICAL
        if (state != BrownoutState.HEALTHY && previousState == BrownoutState.HEALTHY) {
            tripCount++
        }

        // Calculate power scale based on current state
        powerScale = when (state) {
            BrownoutState.HEALTHY -> 1.0
            BrownoutState.CRITICAL -> 0.0
            BrownoutState.WARNING -> {
                // Linear interpolation between warningVoltage (1.0) and criticalVoltage (minPowerScale)
                val range = warningVoltage - criticalVoltage
                if (range <= 0.0) {
                    minPowerScale
                } else {
                    val ratio = ((voltage - criticalVoltage) / range).coerceIn(0.0, 1.0)
                    minPowerScale + ratio * (1.0 - minPowerScale)
                }
            }
        }
    }

    /**
     * Resets trip counter and restores initial healthy state baseline (e.g. at match start).
     */
    fun reset() {
        tripCount = 0
        state = BrownoutState.HEALTHY
        powerScale = 1.0
        lastVoltage = nominalVoltage
        batteryPercent = 100.0
    }

    companion object {
        /**
         * Factory constructor pre-configured with standard FTC defaults (12V system, REV Hub brownout ~7.5V).
         *
         * @return Pre-configured FTC [BrownoutGuard] instance.
         */
        fun ftcDefaults(): BrownoutGuard = BrownoutGuard(
            warningVoltage = 10.0,
            criticalVoltage = 7.5,
            minPowerScale = 0.3,
            hysteresisVoltage = 0.4,
            nominalVoltage = 13.0
        )

        /**
         * Factory constructor pre-configured with standard FRC defaults (12V system, roboRIO brownout ~6.8V).
         *
         * @return Pre-configured FRC [BrownoutGuard] instance.
         */
        fun frcDefaults(): BrownoutGuard = BrownoutGuard(
            warningVoltage = 8.5,
            criticalVoltage = 6.8,
            minPowerScale = 0.25,
            hysteresisVoltage = 0.4,
            nominalVoltage = 12.6
        )
    }
}

/** Brownout protection state machine states. */
enum class BrownoutState {
    /** Battery voltage is healthy ($V \ge V_{warning}$) — full power allowed (1.0). */
    HEALTHY,
    /** Battery voltage is sagging ($V_{critical} < V < V_{warning}$) — graduated power reduction active. */
    WARNING,
    /** Battery voltage is critically low ($V \le V_{critical}$) — all motor outputs disabled (0.0). */
    CRITICAL
}
