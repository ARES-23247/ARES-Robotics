package com.areslib.control.safety

import com.areslib.hardware.actuator.MotorIO

/**
 * System-Level Power & Current Budget Manager for FTC and FRC Drivetrains.
 *
 * Prevents main breaker/fuse trips (e.g. 20A main fuse) by estimating real-time current draw across all registered motors
 * using the electromechanical DC motor model and applying graduated power scaling when total current consumption exceeds safety thresholds.
 *
 * ### DC Motor Electromechanical Model Equations:
 * To avoid blocking I2C reads (~2-3ms per motor), current is estimated from bulk-cached velocity and commanded power:
 * $$R_{motor} = \frac{V_{nominal}}{I_{stall}}, \quad K_v = \frac{V_{nominal}}{\omega_{free}}$$
 * $$I_{estimated} = \max\left(0, \frac{V_{battery} \cdot |\text{power}| \cdot \text{scale} - K_v \cdot |v_{encoder}|}{R_{motor}}\right) + I_{calibrationOffset}$$
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
     *
     * @param motor [MotorIO] actuator interface instance to monitor.
     * @param stallCurrentAmps Motor stall current at 12V in Amps ($A$, from motor datasheet).
     * @param freeSpeedTps Motor free-speed rotational velocity in encoder ticks per second ($tps$).
     * @param nominalVoltage Rated nominal voltage in Volts ($V$, default: $12.0$ V).
     */
    fun register(
        motor: MotorIO,
        stallCurrentAmps: Double = 9.2,
        freeSpeedTps: Double = 2786.0,
        nominalVoltage: Double = 12.0
    ) {
        val stall = if (stallCurrentAmps > 0.0 && stallCurrentAmps.isFinite()) stallCurrentAmps else 9.2
        val speed = if (freeSpeedTps > 0.0 && freeSpeedTps.isFinite()) freeSpeedTps else 2786.0
        val volt = if (nominalVoltage > 0.0 && nominalVoltage.isFinite()) nominalVoltage else 12.0

        val resistance = volt / stall
        val kv = volt / speed
        slots.add(MotorSlot(motor, resistance, kv, volt))
    }

    /**
     * Updates motor current estimates and evaluates total power budget scaling.
     *
     * Call once per loop iteration. Zero heap allocations.
     *
     * @param batteryVoltage Current measured battery voltage in Volts ($V$).
     * @param enableCalibration If `true`, reads one actual motor current per cycle round-robin to calibrate the model (~2ms per cycle).
     */
    fun update(batteryVoltage: Double, enableCalibration: Boolean = false) {
        if (slots.isEmpty()) return

        val vBat = if (batteryVoltage > 0.1) batteryVoltage else 12.0

        // 1. Estimate current for each motor from the DC motor model + learned calibrationOffset
        var totalAmps = 0.0
        for (i in slots.indices) {
            val slot = slots[i]
            val motor = slot.motor
            val appliedVoltage = vBat * kotlin.math.abs(motor.power * slot.motor.powerScale)
            val backEmf = slot.kv * kotlin.math.abs(motor.velocity)
            val rawEstimate = if (kotlin.math.abs(slot.motor.velocity) < 1e-3 && appliedVoltage > 0.1) {
                // Motor starting from standstill: acceleration transient (~2.0A per motor), not stalled
                2.0
            } else {
                ((appliedVoltage - backEmf) / slot.resistance).coerceAtLeast(0.0)
            }
            val estimatedCurrent = (rawEstimate + slot.calibrationOffset).coerceAtLeast(0.0)

            slot.estimatedAmps = estimatedCurrent
            totalAmps += estimatedCurrent
        }

        // 2. Optional staggered calibration: read ONE motor's actual current per cycle
        if (enableCalibration && slots.isNotEmpty()) {
            val idx = calibrationIndex % slots.size
            val slot = slots[idx]
            try {
                val actualAmps = slot.motor.currentAmps
                if (actualAmps.isFinite() && actualAmps >= 0.0) {
                    val motor = slot.motor
                    val appliedVoltage = vBat * kotlin.math.abs(motor.power * slot.motor.powerScale)
                    
                    // Skip calibration if sensor returns exactly 0.0 while voltage is applied, 
                    // indicating missing/un-polled sensor hardware
                    if (actualAmps == 0.0 && appliedVoltage > 0.5) {
                        // Skip updating calibrationOffset, keep using rawEstimate or last valid estimate
                        slot.estimatedAmps = slot.estimatedAmps
                    } else {
                        slot.lastCalibratedAmps = actualAmps
                        val backEmf = slot.kv * kotlin.math.abs(motor.velocity)
                        val rawEstimate = ((appliedVoltage - backEmf) / slot.resistance).coerceAtLeast(0.0)
                        
                        // Blend error offset: difference between actual and raw model estimate
                        val currentError = actualAmps - rawEstimate
                        slot.calibrationOffset = (slot.calibrationOffset * 0.3 + currentError * 0.7)
                        
                        // Recalculate this slot's estimate and the total
                        slot.estimatedAmps = (rawEstimate + slot.calibrationOffset).coerceAtLeast(0.0)
                    }
                    
                    totalAmps = 0.0
                    for (i in slots.indices) totalAmps += slots[i].estimatedAmps
                }
            } catch (_: Exception) {
                // Current read failed — stick with estimate
            }
            calibrationIndex++
        }

        totalEstimatedAmps = totalAmps

        // 3. State machine with hysteresis
        val previousState = state
        state = when (state) {
            CurrentBudgetState.HEALTHY -> when {
                totalAmps > criticalCurrentAmps -> CurrentBudgetState.CRITICAL
                totalAmps > warningCurrentAmps -> CurrentBudgetState.WARNING
                else -> CurrentBudgetState.HEALTHY
            }
            CurrentBudgetState.WARNING -> when {
                totalAmps > criticalCurrentAmps -> CurrentBudgetState.CRITICAL
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
                    val ratio = 1.0 - ((totalAmps - warningCurrentAmps) / range)
                    minPowerScale + ratio * (1.0 - minPowerScale)
                }
            }
        }
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
            slot.lastCalibratedAmps = 0.0
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
         * Factory constructor pre-configured with standard FTC defaults (20A main battery fuse).
         *
         * @return Pre-configured FTC [CurrentBudgetManager] instance.
         */
        fun ftcDefaults(): CurrentBudgetManager = CurrentBudgetManager(
            warningCurrentAmps = 45.0,
            criticalCurrentAmps = 60.0,
            minPowerScale = 0.5,
            hysteresisAmps = 3.0
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
    var lastCalibratedAmps: Double = 0.0,
    var calibrationOffset: Double = 0.0
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
