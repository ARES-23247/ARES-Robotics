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
        private val scratchFaults = object : ThreadLocal<IntArray>() {
            override fun initialValue() = IntArray(4)
        }
    }

    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        val curr = scratchCurrents.get()!!
        val enc = scratchEncoderPositions.get()!!
        val currentsValid = getCurrentsIfValid(curr)
        val encodersValid = getEncoderPositionsIfValid(enc)
        telemetry.putDoubleArray("$prefix/Currents", curr)
        telemetry.putBoolean("$prefix/CurrentsValid", currentsValid)
        telemetry.putDoubleArray("$prefix/EncoderPositions", enc)
        telemetry.putBoolean("$prefix/EncoderPositionsValid", encodersValid)
        val faults = scratchFaults.get()!!
        getFaults(faults)
        telemetry.putNumber("$prefix/FaultBits/FrontLeft", faults[0].toDouble())
        telemetry.putNumber("$prefix/FaultBits/FrontRight", faults[1].toDouble())
        telemetry.putNumber("$prefix/FaultBits/RearLeft", faults[2].toDouble())
        telemetry.putNumber("$prefix/FaultBits/RearRight", faults[3].toDouble())
    }

    /** Refreshes cached status signals from the hardware. */
    override fun refresh() {}

    /** Reads the drive state from the hardware. */
    fun read(): DriveState

    /**
     * Writes target speeds back to hardware with the current safety power scale applied at the
     * mutable request boundary. Implementations must not copy [driveState] in the periodic path.
     */
    fun write(driveState: DriveState, powerScale: Double)

    /** Gets measured motor supply currents. */
    fun getCurrents(out: DoubleArray) {}

    /** Whether the last hardware refresh produced a fresh current snapshot. */
    val currentMeasurementsValid: Boolean
        get() = false

    /** Checked cached-current read; invalid hardware must not be represented as a healthy zero. */
    fun getCurrentsIfValid(out: DoubleArray): Boolean {
        if (!currentMeasurementsValid) {
            out.fill(Double.NaN)
            return false
        }
        getCurrents(out)
        return true
    }

    /** Gets measured absolute encoder positions. */
    fun getEncoderPositions(out: DoubleArray) {}

    /** Whether the last hardware refresh produced a trustworthy encoder snapshot. */
    val encoderPositionsValid: Boolean
        get() = true

    /**
     * Backward-compatible checked read. Existing implementations remain valid by default; hardware
     * implementations with refresh status override [encoderPositionsValid].
     */
    fun getEncoderPositionsIfValid(out: DoubleArray): Boolean {
        if (!encoderPositionsValid) return false
        getEncoderPositions(out)
        return true
    }

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

    /**
     * Feeds an AprilTag observation into the drivetrain pose estimator.
     *
     * This is intentionally abstract: silently dropping an accepted localization
     * observation is a safety-critical integration failure.
     */
    fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double)

    /**
     * Feeds an AprilTag observation with observation-specific standard deviations.
     * Implementations without a covariance-aware vendor API still inject the pose
     * through the mandatory two-argument method.
     */
    fun addVisionMeasurement(
        pose: Pose2d,
        timestampSeconds: Double,
        stdDevXMeters: Double,
        stdDevYMeters: Double,
        stdDevHeadingRadians: Double
    ) {
        addVisionMeasurement(pose, timestampSeconds)
    }

    /**
     * Samples the authoritative estimator at an historical timestamp. Implementations write
     * X, Y, and CCW-positive heading radians into [out] and return false when history is absent.
     */
    fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean = false

    /** Resets/seeds the underlying pose estimator. */
    fun seedPose(pose: Pose2d) {}

    /** Gets any active motor fault codes (bitfields). */
    fun getFaults(out: IntArray) {}

    /** Gets the signal latency in milliseconds of the swerve sensors. */
    val signalLatencyMs: Double
        get() = Double.POSITIVE_INFINITY
}
