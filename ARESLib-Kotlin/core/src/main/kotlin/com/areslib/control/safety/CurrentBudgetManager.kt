package com.areslib.control.safety

import com.areslib.hardware.actuator.MotorIO

/**
 * System-Level Power & Current Budget Manager for FTC and FRC Drivetrains.
 *
 * Estimates motor current using a DC motor model and applies graduated power scaling when the
 * modeled total exceeds configured thresholds. This approximate motor-current sum is not a
 * physical battery-current measurement or a guarantee against breaker/fuse trips.
 *
 * ### DC Motor Electromechanical Model Equations:
 * To avoid blocking I2C reads (~2-3ms per motor), current is estimated from bulk-cached velocity and commanded power:
 * $$R_{motor} = \frac{V_{nominal}}{I_{stall}}, \quad K_v = \frac{V_{nominal}}{\omega_{free}}$$
 * $$I_{estimated} = \left|\frac{V_{battery} \cdot \text{power} \cdot \text{scale} - K_v \cdot v_{encoder}}{R_{motor}}\right| + I_{calibrationOffset}$$
 *
 * ### Power Scaling State Machine:
 * 1. **Healthy State** ($I_{total} \le I_{warning}$): $\text{powerScale} = 1.0$
 * 2. **Warning State** ($I_{warning} < I_{total} < I_{critical}$): Linear power scaling down to $\alpha_{min}$
 * 3. **Critical State** ($I_{total} \ge I_{critical}$): $\text{powerScale} = \alpha_{min}$ (Aggressive current limiting)
 *
 * ### Physical Units & Properties:
 * - Current Thresholds ($I_{warning}, I_{critical}, I_{hysteresis}$): Amperes ($A$)
 * - Battery Voltage ($V_{battery}, V_{nominal}$): Volts ($V$)
 * - Motor Resistance ($R_{motor}$): Ohms ($\Omega$)
 * - Motor Back-EMF Constant ($K_v$): Volts per tick/sec ($V / tps$)
 * - Memory Footprint: Zero allocations in hot path [update] loops.
 *
 * @property warningCurrentAmps Total current threshold in Amps ($A$) where graduated power scaling begins.
 * @property criticalCurrentAmps Total current threshold in Amps ($A$) where maximum current limiting is enforced.
 * @property minPowerScale Minimum allowable power scaling factor at critical boundary.
 * @property hysteresisAmps Hysteresis band in Amps ($A$) to prevent state boundary oscillation.
 *
 * @see MotorIO
 * @see CurrentBudgetState
 */
class CurrentBudgetManager(
    val warningCurrentAmps: Double = 15.0,
    val criticalCurrentAmps: Double = 18.0,
    val minPowerScale: Double = 0.2,
    val hysteresisAmps: Double = 1.5
) {
    init {
        require(warningCurrentAmps.isFinite() && warningCurrentAmps >= 0.0 &&
            criticalCurrentAmps.isFinite() && criticalCurrentAmps > warningCurrentAmps) { "Current thresholds must be finite and ordered" }
        require(minPowerScale in 0.0..1.0) { "Minimum power scale must be within [0, 1]" }
        require(hysteresisAmps.isFinite() && hysteresisAmps >= 0.0) { "Current hysteresis must be finite and non-negative" }
    }

    /** Registered motor slots tracking electrical parameters and estimated current draw. */
    private val slots = ArrayList<MotorSlot>(8)
    private var calibrationIndex = 0

    /** Current computed system-wide power scale factor ($0.0 \dots 1.0$). */
    var powerScale: Double = 1.0
        private set

    /** Current current-budget state machine state ([CurrentBudgetState.HEALTHY], [CurrentBudgetState.WARNING], [CurrentBudgetState.CRITICAL]). */
    var state: CurrentBudgetState = CurrentBudgetState.HEALTHY
        private set

    /** Total estimated current draw across all registered motors in Amperes ($A$). */
    var totalEstimatedAmps: Double = 0.0
        private set

    /** Cumulative count of budget trip events since last reset. */
    var tripCount: Int = 0
        private set

    /**
     * Registers a motor with its electromechanical characteristics for current estimation.
     * Invalid/nonrepresentable electrical parameters throw before mutation. Re-registering the same
     * motor/model is a no-op; changed parameters replace its slot and clear its learned correction.
     *
     * @param motor [MotorIO] actuator interface instance to monitor.
     * @param stallCurrentAmps Motor stall current at the rated nominal voltage in Amps ($A$, from motor datasheet).
     * @param freeSpeedTps Motor free-speed rotational velocity in encoder ticks per second ($tps$).
     * @param nominalVoltage Rated nominal voltage in Volts ($V$, default: $12.0$ V).
     */
    fun register(
        motor: MotorIO,
        stallCurrentAmps: Double = 9.2,
        freeSpeedTps: Double = 2786.0,
        nominalVoltage: Double = 12.0
    ) {
        require(stallCurrentAmps.isFinite() && stallCurrentAmps > 0.0 &&
            freeSpeedTps.isFinite() && freeSpeedTps > 0.0 && nominalVoltage.isFinite() && nominalVoltage > 0.0) {
            "Motor electrical parameters must be finite and positive"
        }
        val resistance = nominalVoltage / stallCurrentAmps
        val kv = nominalVoltage / freeSpeedTps
        require(resistance.isFinite() && resistance > 0.0 && kv.isFinite() && kv > 0.0) {
            "Motor resistance and back-EMF coefficient must be representable and positive"
        }
        for (index in slots.indices) {
            val slot = slots[index]
            if (slot.motor === motor) {
                if (slot.resistance != resistance || slot.kv != kv || slot.nominalVoltage != nominalVoltage) {
                    slots[index] = MotorSlot(motor, resistance, kv, nominalVoltage)
                }
                return
            }
        }
        slots.add(MotorSlot(motor, resistance, kv, nominalVoltage))
    }

    /**
     * Updates motor current estimates and evaluates total power budget scaling.
     *
     * Call once per loop iteration. Zero heap allocations.
     *
     * @param batteryVoltage Current measured battery voltage in Volts ($V$). Missing, nonfinite or
     * <=0.1V observations invalidate the entire budget; no nominal-voltage substitution is made.
     * @param enableCalibration If `true`, consumes one cached actual motor current per cycle round-robin to calibrate the model.
     * @param additionalMeasuredCurrentAmps Non-negative measured load outside the model. Invalid
     * readings mark total current unknown and disable effort until a valid update arrives.
     */
    fun update(
        batteryVoltage: Double,
        enableCalibration: Boolean = false,
        additionalMeasuredCurrentAmps: Double = 0.0
    ) {

        val vBat = batteryVoltage
        if (!validBatteryVoltage(vBat) || !additionalMeasuredCurrentAmps.isFinite() || additionalMeasuredCurrentAmps < 0.0) {
            rejectCurrentEstimate()
            return
        }
        val safeAdditionalMeasuredAmps = additionalMeasuredCurrentAmps

        // 1. Estimate current for each motor from the DC motor model + learned calibrationOffset
        var totalAmps = safeAdditionalMeasuredAmps
        for (i in slots.indices) {
            val slot = slots[i]
            slot.appliedVoltage = sampledAppliedVoltage(slot, vBat)
            val rawEstimate = estimateCurrentAtVoltage(slot, slot.appliedVoltage)
            slot.rawEstimatedAmps = rawEstimate
            val estimatedCurrent = (rawEstimate + slot.calibrationOffset).coerceAtLeast(0.0)

            slot.estimatedAmps = estimatedCurrent
            totalAmps += estimatedCurrent
        }

        // 2. Calibrate one slot using the same model sample captured above.
        if (enableCalibration && slots.isNotEmpty()) {
            val slot = slots[calibrationIndex]
            try {
                val actualAmps = slot.motor.currentAmps
                val rawEstimate = slot.rawEstimatedAmps
                val appliedVoltage = slot.appliedVoltage
                val missingPoweredReading = actualAmps == 0.0 &&
                    (!appliedVoltage.isFinite() || kotlin.math.abs(appliedVoltage) > 0.5)
                if (actualAmps.isFinite() && actualAmps >= 0.0 && rawEstimate.isFinite() &&
                    slot.motor.isCurrentReadingValid(actualAmps) && !missingPoweredReading) {
                    val previousEstimate = slot.estimatedAmps
                    val currentError = actualAmps - rawEstimate
                    slot.calibrationOffset = slot.calibrationOffset * 0.3 + currentError * 0.7
                    slot.estimatedAmps = (rawEstimate + slot.calibrationOffset).coerceAtLeast(0.0)
                    totalAmps += slot.estimatedAmps - previousEstimate
                }
            } catch (_: Exception) {
                // Cached current unavailable: retain this frame's model estimate.
            }
            calibrationIndex = if (calibrationIndex + 1 >= slots.size) 0 else calibrationIndex + 1
        }

        if (!totalAmps.isFinite() || totalAmps < 0.0) {
            rejectCurrentEstimate()
            return
        }
        totalEstimatedAmps = totalAmps

        // 3. State machine with hysteresis
        val previousState = state
        state = when (state) {
            CurrentBudgetState.HEALTHY -> when {
                totalAmps >= criticalCurrentAmps -> CurrentBudgetState.CRITICAL
                totalAmps >= warningCurrentAmps -> CurrentBudgetState.WARNING
                else -> CurrentBudgetState.HEALTHY
            }
            CurrentBudgetState.WARNING -> when {
                totalAmps >= criticalCurrentAmps -> CurrentBudgetState.CRITICAL
                totalAmps < warningCurrentAmps - hysteresisAmps -> CurrentBudgetState.HEALTHY
                else -> CurrentBudgetState.WARNING
            }
            CurrentBudgetState.CRITICAL -> when {
                totalAmps < criticalCurrentAmps - hysteresisAmps -> CurrentBudgetState.WARNING
                else -> CurrentBudgetState.CRITICAL
            }
        }

        if (state != CurrentBudgetState.HEALTHY && previousState == CurrentBudgetState.HEALTHY) {
            tripCount++
        }

        // 4. Calculate power scale
        powerScale = when (state) {
            CurrentBudgetState.HEALTHY -> 1.0
            CurrentBudgetState.CRITICAL -> minPowerScale
            CurrentBudgetState.WARNING -> {
                val range = criticalCurrentAmps - warningCurrentAmps
                if (range <= 0.0) {
                    minPowerScale
                } else {
                    val ratio = (1.0 - ((totalAmps - warningCurrentAmps) / range)).coerceIn(0.0, 1.0)
                    minPowerScale + ratio * (1.0 - minPowerScale)
                }
            }
        }
    }

    private fun rejectCurrentEstimate() {
        if (state == CurrentBudgetState.HEALTHY) tripCount++
        state = CurrentBudgetState.CRITICAL
        totalEstimatedAmps = Double.NaN
        powerScale = 0.0
        for (index in slots.indices) {
            val slot = slots[index]
            slot.estimatedAmps = Double.NaN
            slot.rawEstimatedAmps = Double.NaN
            slot.appliedVoltage = Double.NaN
        }
    }

    private fun validBatteryVoltage(voltage: Double) = voltage.isFinite() && voltage > 0.1

    private fun sampledAppliedVoltage(slot: MotorSlot, batteryVoltage: Double): Double = try {
        effectiveAppliedVoltage(slot, batteryVoltage)
    } catch (_: Exception) { Double.NaN }

    private fun effectiveAppliedVoltage(slot: MotorSlot, batteryVoltage: Double): Double {
        if (!validBatteryVoltage(batteryVoltage)) return Double.NaN
        val power = slot.motor.power
        val scale = slot.motor.powerScale
        if (!power.isFinite() || !scale.isFinite() || scale !in 0.0..1.0) return Double.NaN
        return batteryVoltage * power.coerceIn(-1.0, 1.0) * scale.coerceIn(0.0, 1.0)
    }

    private fun estimateRawCurrent(slot: MotorSlot, batteryVoltage: Double): Double {
        return estimateCurrentAtVoltage(slot, sampledAppliedVoltage(slot, batteryVoltage))
    }

    private fun estimateCurrentAtVoltage(slot: MotorSlot, appliedVoltage: Double): Double {
        if (!appliedVoltage.isFinite()) return Double.NaN
        val velocity = try { slot.motor.velocity } catch (_: Exception) { return Double.NaN }
        if (!velocity.isFinite()) return Double.NaN
        val backEmf = slot.kv * velocity
        return kotlin.math.abs(appliedVoltage - backEmf) / slot.resistance
    }

    /**
     * Returns estimated current draw in Amperes ($A$) for a specific registered motor slot.
     *
     * @param index Zero-based index of the registered motor slot.
     * @return Estimated current draw in Amps ($A$).
     */
    fun getMotorAmps(index: Int): Double {
        return if (index in slots.indices) slots[index].estimatedAmps else 0.0
    }

    /**
     * Estimates one registered motor without mutation, using the same observation rules as [update].
     * Returns NaN for invalid observations/arithmetic and zero for an unregistered motor.
     */
    fun estimateMotorAmps(motor: MotorIO, batteryVoltage: Double): Double {
        val vBat = batteryVoltage
        for (index in slots.indices) {
            val slot = slots[index]
            if (slot.motor === motor) {
                val estimate = estimateRawCurrent(slot, vBat) + slot.calibrationOffset
                return if (estimate.isFinite()) estimate.coerceAtLeast(0.0) else Double.NaN
            }
        }
        return 0.0
    }

    /** Total number of registered motor slots. */
    val motorCount: Int get() = slots.size

    /**
     * Resets state machine baseline and trip counter.
     */
    fun reset() {
        state = CurrentBudgetState.HEALTHY
        powerScale = 1.0
        totalEstimatedAmps = 0.0
        tripCount = 0
        calibrationIndex = 0
        for (i in slots.indices) {
            val slot = slots[i]
            slot.estimatedAmps = 0.0
            slot.calibrationOffset = 0.0
        }
    }

    /**
     * Checks if a motor instance is registered with the current budget manager.
     *
     * @param motor [MotorIO] instance to check.
     * @return `true` if registered; `false` otherwise.
     */
    fun isRegistered(motor: MotorIO): Boolean {
        for (i in 0 until slots.size) {
            if (slots[i].motor === motor) return true
        }
        return false
    }

    /**
     * Clears all registered motor slots and resets internal state.
     */
    fun clear() {
        slots.clear()
        reset()
    }

    companion object {
        /**
         * Factory constructor pre-configured for the FTC 20A ATM main-battery fuse. The warning
         * band leaves transient headroom while preventing sustained load from reaching the fuse's
         * continuous rating.
         *
         * @return Pre-configured FTC [CurrentBudgetManager] instance.
         */
        fun ftcDefaults(): CurrentBudgetManager = CurrentBudgetManager(
            warningCurrentAmps = 16.0,
            criticalCurrentAmps = 20.0,
            minPowerScale = 0.30,
            hysteresisAmps = 2.0
        )
    }
}

/** Internal tracking structure for a registered motor slot. */
internal class MotorSlot(
    val motor: MotorIO,
    val resistance: Double,
    val kv: Double,
    val nominalVoltage: Double,
    var estimatedAmps: Double = 0.0,
    var calibrationOffset: Double = 0.0,
    var appliedVoltage: Double = 0.0,
    var rawEstimatedAmps: Double = 0.0
)

/** Current budget state machine states. */
enum class CurrentBudgetState {
    /** Total current is within budget ($I_{total} \le I_{warning}$) — full power allowed (1.0). */
    HEALTHY,
    /** Total current exceeds warning ($I_{warning} < I_{total} < I_{critical}$) — graduated power reduction active. */
    WARNING,
    /** Total current at or near fuse limit ($I_{total} \ge I_{critical}$) — aggressive power reduction enforced. */
    CRITICAL
}
