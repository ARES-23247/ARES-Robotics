package com.areslib.frc.power

import com.areslib.control.safety.BrownoutGuard
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
    /** FRC brownout protection guard instance. */
    val brownoutGuard = BrownoutGuard.frcDefaults()

    /** Configurable battery voltage supplier lambda. */
    var batteryVoltageSupplier: () -> Double = { 12.6 }

    /** Latest measured battery voltage in Volts ($V$). */
    override var batteryVoltage = 12.6
        private set
    /** Calculated global power scaling factor $[0.0, 1.0]$. */
    override var powerScale = 1.0
        private set

    /** Total current draw in Amperes ($A$) calculated across all registered motors in [com.areslib.hardware.HardwareRegistry]. */
    override val currentAmps: Double
        get() = com.areslib.hardware.HardwareRegistry.getRegisteredMotors().sumOf { motor ->
            try {
                motor.currentAmps.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            } catch (_: Exception) {
                0.0
            }
        }

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
        brownoutGuard.update(batteryVoltage)
        powerScale = brownoutGuard.powerScale

        // Dynamically distribute powerScale to all registered motors
        val motors = com.areslib.hardware.HardwareRegistry.getRegisteredMotors()
        for (m in motors) {
            m.powerScale = powerScale
        }

        return powerScale
    }
}

