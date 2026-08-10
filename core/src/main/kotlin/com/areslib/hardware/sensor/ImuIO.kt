package com.areslib.hardware.sensor

import com.google.gson.annotations.SerializedName
import com.areslib.hardware.SubsystemIO

/**
 * Serializable sensory inputs for an Inertial Measurement Unit (IMU).
 * Adheres to the Logged IO pattern.
 */
data class ImuInputs(
    @SerializedName("headingRadians") var headingRadians: Double = 0.0,
    @SerializedName("pitchRadians") var pitchRadians: Double = 0.0,
    @SerializedName("rollRadians") var rollRadians: Double = 0.0,
    @SerializedName("yawVelocityRadPerSec") var yawVelocityRadPerSec: Double = 0.0,
    @SerializedName("timestampMs") var timestampMs: Long = 0L
)

/**
 * Pure abstraction for reading a gyroscope / IMU.
 */
interface ImuIO : SubsystemIO {
    override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
        val inputs = ImuInputs()
        updateInputs(inputs)
        telemetry.putNumber("$prefix/HeadingRad", inputs.headingRadians)
        telemetry.putNumber("$prefix/PitchRad", inputs.pitchRadians)
        telemetry.putNumber("$prefix/RollRad", inputs.rollRadians)
        telemetry.putNumber("$prefix/YawVelocityRadPerSec", inputs.yawVelocityRadPerSec)
    }

    /**
     * Poll the latest IMU signals into the inputs structure.
     */
    fun updateInputs(inputs: ImuInputs)
    
    /**
     * Reset the robot heading to 0 degrees.
     */
    fun resetHeading()
}
