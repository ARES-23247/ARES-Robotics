package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO

/**
 * Pure abstraction for reading/writing to a physical servo.
 * Keeps the :core module decoupled from Qualcomm SDK.
 */
interface ServoIO : SubsystemIO {
    override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/Position", position)
    }

    /**
     * Servo position (0.0 to 1.0)
     */
    var position: Double
}
