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
    @SerializedName("pitchVelocityRadPerSec") var pitchVelocityRadPerSec: Double = 0.0,
    @SerializedName("rollVelocityRadPerSec") var rollVelocityRadPerSec: Double = 0.0,
    @SerializedName("timestampMs") var timestampMs: Long = 0L
)

/**
 * Pure abstraction for reading a gyroscope / IMU.
 */
interface ImuIO : SubsystemIO {
    /** Convenience publisher; timing-sensitive owners should retain an [ImuTelemetryPublisher]. */
    override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
        val inputs = ImuInputs()
        updateInputs(inputs)
        publishImuSnapshot(telemetry, inputs, "$prefix/HeadingRad", "$prefix/PitchRad",
            "$prefix/RollRad", "$prefix/YawVelocityRadPerSec", "$prefix/PitchVelocityRadPerSec",
            "$prefix/RollVelocityRadPerSec")
    }

    /**
     * Copies the latest coherent cached IMU signals into caller-owned storage.
     * Hardware sampling belongs to refresh/background polling; do not retain [inputs].
     */
    fun updateInputs(inputs: ImuInputs)
    
    /**
     * Reset the robot heading to 0 degrees.
     */
    fun resetHeading()
}
