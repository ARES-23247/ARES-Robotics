package com.areslib.hardware.drive

import com.google.gson.annotations.SerializedName

/**
 * Serializable sensory inputs for a single swerve module.
 * Adheres to the Logged IO pattern. Mutable, caller-owned storage reused by the robot loop.
 * Values use motor-shaft radians, radians/second and absolute steering radians, respectively.
 * A numeric default or retained prior value is not valid feedback: check the associated flag
 * and the producer's acquisition timestamp in RobotClock milliseconds before using it for control.
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
     * The default owns no actuators and does nothing, for sensor-only fixtures. Actuating adapters
     * must override this method and enforce their configuration, feedback and safe-output contract.
     * Calling the default is not evidence that a physical output was applied or neutralized.
     */
    fun setDesiredPower(drivePower: Double, steerPower: Double) {}
}
