package com.areslib.subsystem

/**
 * Platform-independent interface for managing battery voltage monitoring,
 * brownout protection, and current-based power budgeting.
 *
 * Both FTC and FRC platforms implement this interface with their respective
 * SDK-specific voltage/current acquisition methods.
 */
interface PowerManager {

    /**
     * The most recent filtered battery voltage in volts.
     * Low-pass filtered to prevent positive-feedback sag oscillations.
     */
    val batteryVoltage: Double

    /**
     * The current power scaling factor (0.0 to 1.0) applied to all actuators.
     * Computed from brownout protection and current budget thresholds.
     */
    val powerScale: Double

    /**
     * Total robot current draw in amperes.
     * May be physically measured (Floodgate sensor) or software-estimated from motor models.
     */
    val currentAmps: Double

    /**
     * Updates voltage/current readings and recalculates the power scaling factor.
     *
     * @param dtSeconds Loop cycle delta time in seconds.
     * @param timestampMs Current timestamp from [com.areslib.util.RobotClock].
     * @return The calculated power scale factor (0.0 to 1.0).
     */
    fun update(dtSeconds: Double, timestampMs: Long): Double
}
