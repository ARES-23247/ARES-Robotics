package com.areslib.hardware.drive

import com.areslib.state.DriveState
import com.areslib.telemetry.ITelemetry
import com.areslib.hardware.SubsystemIO
import com.areslib.math.geometry.Pose2d

/**
 * Interface representing the hardware input/output for the swerve drivetrain.
 *
 * This allows clean decoupling of the swerve drivetrain logic from CTRE/REV hardware,
 * facilitating unit testing, simulation, and future cross-platform (FTC/FRC) swerve support.
 */
interface SwerveHardwareIO : SubsystemIO {
    companion object {
        private val scratchCurrents = object : ThreadLocal<DoubleArray>() {
            override fun initialValue() = DoubleArray(4)
        }
        private val scratchEncoderPositions = object : ThreadLocal<DoubleArray>() {
            override fun initialValue() = DoubleArray(4)
        }
    }

    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        val curr = scratchCurrents.get()!!
        val enc = scratchEncoderPositions.get()!!
        getCurrents(curr)
        getEncoderPositions(enc)
        telemetry.putDoubleArray("$prefix/Currents", curr)
        telemetry.putDoubleArray("$prefix/EncoderPositions", enc)
    }

    /** Refreshes cached status signals from the hardware. */
    override fun refresh() {}

    /** Reads the drive state from the hardware. */
    fun read(): DriveState

    /** Writes target speeds back to the hardware. */
    fun write(driveState: DriveState)

    /** Gets measured motor supply currents. */
    fun getCurrents(out: DoubleArray) {}

    /** Gets measured absolute encoder positions. */
    fun getEncoderPositions(out: DoubleArray) {}

    /** Gets gyro absolute pitch degrees. */
    val pitchDegrees: Double
        get() = 0.0

    /** Gets gyro absolute roll degrees. */
    val rollDegrees: Double
        get() = 0.0

    /** Gets raw gyro yaw degrees (unfused, for MegaTag2). */
    val rawGyroYawDegrees: Double
        get() = 0.0

    /** Gets raw gyro yaw rate in degrees per second (for MegaTag2). */
    val yawRateDegreesPerSecond: Double
        get() = 0.0

    /** Gets measured module linear velocities. */
    fun getModuleSpeeds(out: DoubleArray) {}

    /** Feeds AprilTag measurements into drivetrain's pose estimator. */
    fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) {}

    /** Resets/seeds the underlying pose estimator. */
    fun seedPose(pose: Pose2d) {}

    /** Gets any active motor fault codes (bitfields). */
    fun getFaults(out: IntArray) {}

    /** Gets the signal latency in milliseconds of the swerve sensors. */
    val signalLatencyMs: Double
        get() = 0.0
}
