package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO

/**
 * Pure abstraction for reading/writing to a physical motor.
 * Keeps the :core module decoupled from Qualcomm SDK.
 */
interface MotorIO : SubsystemIO {
    override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/Power", power * powerScale)
        telemetry.putNumber("$prefix/Position", position)
        telemetry.putNumber("$prefix/Velocity", velocity)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
    }

    override fun safe() {
        power = 0.0
    }

    /**
     * Motor power percentage (-1.0 to 1.0)
     */
    var power: Double

    /**
     * Optional scaling factor applied to all motor power writes (0.0 to 1.0).
     * Defaults to 1.0 (no scaling). Useful for automatic current limiters or speed scaling.
     */
    var powerScale: Double
        get() = 1.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /**
     * Sets the motor output in absolute volts, automatically compensating for battery sag.
     * @param volts The target voltage (e.g., up to 12.0V).
     * @param batteryVolts The current measured battery voltage (e.g., from the voltage sensor).
     */
    fun setVoltage(volts: Double, batteryVolts: Double) {
        this.power = if (batteryVolts > 0.1) volts / batteryVolts else 0.0
    }

    /**
     * Estimated or measured current draw of this motor in Amperes (Amps).
     * Defaults to 0.0 in simulations or when current monitoring is unsupported.
     */
    val currentAmps: Double
        get() = 0.0
    
    /**
     * Current motor velocity in ticks per second
     */
    val velocity: Double
    
    /**
     * Current encoder position in ticks
     */
    val position: Double
    
    /**
     * Reset the encoder position to zero
     */
    fun resetEncoder()
}

/**
 * Defines hardware boundaries for calculating absolute position from analog or PWM sensors.
 * E.g., The REV Through Bore Encoder in absolute mode outputs a pulse width between
 * 1 and 1024 microseconds. V2 specifics can be adjusted here when known.
 */
enum class RevEncoderVersion(val minPulseUs: Double, val maxPulseUs: Double, val maxVoltage: Double) {
    V1(1.0, 1024.0, 3.3),
    V2(1.0, 1024.0, 3.3) // Same defaults for now, adjust when V2 specs are confirmed
}
