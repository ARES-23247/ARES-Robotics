package com.areslib.frc.power

import com.areslib.control.safety.BrownoutGuard
import com.areslib.control.safety.CurrentBudgetState
import com.areslib.subsystem.PowerManager

/**
 * FRC battery voltage manager and power scaling controller.
 *
 * Polls active battery voltage in volts through [batteryVoltageSupplier] (normally
 * `RobotController.getBatteryVoltage()`), advances [BrownoutGuard], and distributes its normalized
 * scale to every currently registered motor. Voltage is read exactly once per [update]; properties
 * expose cached values and perform no hardware IO.
 *
 * ### Physical Units & Limits:
 * - Battery Voltage: Volts ($V$), nominal $12.6\text{V}$.
 * - Total Current Draw: Amperes ($A$).
 * - Power Scale Factor: Normalized multiplier $[0.0, 1.0]$.
 *
 * @see PowerManager
 * @see BrownoutGuard
 */
class FrcPowerManager : PowerManager {
    private val currentSourceSampler = com.areslib.hardware.CurrentSourceSampler()
    /** FRC brownout protection guard instance. */
    val brownoutGuard = BrownoutGuard.frcDefaults()

    /** Configurable battery voltage supplier lambda. */
    var batteryVoltageSupplier: () -> Double = { 12.6 }
    /** Total battery current supplier, normally WPILib PowerDistribution.getTotalCurrent(). */
    var totalCurrentSupplier: () -> Double = { registeredCurrentFallback() }
    /** roboRIO brownout-state supplier, normally RobotController.isBrownedOut(). */
    var brownedOutSupplier: () -> Boolean = { false }

    /** Latest measured battery voltage in Volts ($V$). */
    override var batteryVoltage = 12.6
        private set
    /** Calculated global power scaling factor $[0.0, 1.0]$. */
    override var powerScale = 1.0
        private set

    /** Total current draw in Amperes ($A$) calculated across all registered motors in [com.areslib.hardware.HardwareRegistry]. */
    override var currentAmps: Double = 0.0
        private set

    /** Current-only effort scale layered with voltage brownout protection. */
    var currentPowerScale: Double = 1.0
        private set

    /** Current budget state for telemetry and post-match diagnosis. */
    var currentBudgetState: CurrentBudgetState = CurrentBudgetState.HEALTHY
        private set

    /** True when the roboRIO reports that its hardware brownout state is active. */
    var isBrownedOut: Boolean = false
        private set

    /**
     * Updates battery voltage reading, evaluates brownout protection status, and applies power scale limits to registered motors.
     *
     * @param dtSeconds Cycle delta time in seconds. Present for [PowerManager] compatibility; the
     * current implementation does not use it.
     * @param timestampMs Robot timestamp in milliseconds. Present for interface compatibility; the
     * current implementation does not use it.
     * @return Calculated global power scale factor $[0.0, 1.0]$.
     */
    override fun update(dtSeconds: Double, timestampMs: Long): Double {
        batteryVoltage = try {
            batteryVoltageSupplier()
        } catch (_: Exception) {
            0.0
        }
        val suppliedCurrent = try { totalCurrentSupplier() } catch (_: Exception) { Double.NaN }
        currentAmps = suppliedCurrent.takeIf { it.isFinite() && it >= 0.0 }
            ?: registeredCurrentFallback()
        isBrownedOut = try {
            brownedOutSupplier()
        } catch (_: Exception) {
            true
        }

        brownoutGuard.update(batteryVoltage)
        updateCurrentBudget(currentAmps)
        powerScale = if (isBrownedOut) 0.0 else minOf(brownoutGuard.powerScale, currentPowerScale)

        // Dynamically distribute powerScale to all registered motors
        val motors = com.areslib.hardware.HardwareRegistry.getRegisteredMotors()
        for (m in motors) {
            m.powerScale = powerScale
        }

        return powerScale
    }

    private fun updateCurrentBudget(totalAmps: Double) {
        currentBudgetState = when (currentBudgetState) {
            CurrentBudgetState.HEALTHY -> when {
                totalAmps >= CURRENT_CRITICAL_AMPS -> CurrentBudgetState.CRITICAL
                totalAmps >= CURRENT_WARNING_AMPS -> CurrentBudgetState.WARNING
                else -> CurrentBudgetState.HEALTHY
            }
            CurrentBudgetState.WARNING -> when {
                totalAmps >= CURRENT_CRITICAL_AMPS -> CurrentBudgetState.CRITICAL
                totalAmps < CURRENT_WARNING_AMPS - CURRENT_HYSTERESIS_AMPS -> CurrentBudgetState.HEALTHY
                else -> CurrentBudgetState.WARNING
            }
            CurrentBudgetState.CRITICAL -> when {
                totalAmps < CURRENT_CRITICAL_AMPS - CURRENT_HYSTERESIS_AMPS -> CurrentBudgetState.WARNING
                else -> CurrentBudgetState.CRITICAL
            }
        }

        currentPowerScale = when (currentBudgetState) {
            CurrentBudgetState.HEALTHY -> 1.0
            CurrentBudgetState.CRITICAL -> MIN_CURRENT_POWER_SCALE
            CurrentBudgetState.WARNING -> {
                val ratio = ((totalAmps - CURRENT_WARNING_AMPS) /
                    (CURRENT_CRITICAL_AMPS - CURRENT_WARNING_AMPS)).coerceIn(0.0, 1.0)
                1.0 - ratio * (1.0 - MIN_CURRENT_POWER_SCALE)
            }
        }
    }

    private fun registeredCurrentFallback(): Double {
        val sources = com.areslib.hardware.HardwareRegistry.getRegisteredCurrentSources()
        return currentSourceSampler.sample(sources)
    }

    private companion object {
        // FRC's 120A main breaker tolerates short acceleration transients. These thresholds are a
        // system brownout budget, while TalonFX supply/stator limits remain branch protection.
        const val CURRENT_WARNING_AMPS = 180.0
        const val CURRENT_CRITICAL_AMPS = 240.0
        const val CURRENT_HYSTERESIS_AMPS = 20.0
        const val MIN_CURRENT_POWER_SCALE = 0.40
    }
}

