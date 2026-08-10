package com.areslib.hardware.drive

import com.google.gson.annotations.SerializedName

/**
 * Serializable sensory inputs for a single swerve module.
 * Adheres to the Logged IO pattern.
 */
data class SwerveModuleInputs(
    @SerializedName("drivePositionRads") var drivePositionRads: Double = 0.0,
    @SerializedName("driveVelocityRadsPerSec") var driveVelocityRadsPerSec: Double = 0.0,
    @SerializedName("steerAbsolutePositionRads") var steerAbsolutePositionRads: Double = 0.0,
    @SerializedName("drivePositionValid") var drivePositionValid: Boolean = false,
    @SerializedName("driveVelocityValid") var driveVelocityValid: Boolean = false,
    @SerializedName("steerAbsoluteValid") var steerAbsoluteValid: Boolean = false,
    @SerializedName("timestampMs") var timestampMs: Long = 0L
)

/**
 * Platform-independent hardware interface for a single swerve module.
 */
interface SwerveModuleIO {
    /**
     * Poll the latest hardware signals into the inputs structure.
     */
    fun updateInputs(inputs: SwerveModuleInputs)

    /**
     * Commands motor duty-cycle powers for drive and steer actuators.
     */
    fun setDesiredPower(drivePower: Double, steerPower: Double) {}
}
