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
 * Cached module arrays contain four entries in front-left, front-right, rear-left, rear-right
 * order. Getters write those entries only, preserving any trailing caller storage. Refresh and
 * reads share one owning loop; validity flags do not establish freshness across concurrent updates.
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
    override fun refresh()

    /** Reads the drive state from the hardware. */
    fun read(): DriveState

    /**
     * Writes target speeds back to hardware with the current safety power scale applied at the
     * mutable request boundary. Implementations must not copy [driveState] in the periodic path.
     */
    fun write(driveState: DriveState, powerScale: Double)

    /** Gets four cached motor supply currents in amperes; [out] must have at least four entries. */
    fun getCurrents(out: DoubleArray)

    /** Whether the last hardware refresh produced a fresh current snapshot. */
    val currentMeasurementsValid: Boolean

    /**
     * Checks the validity flag and all four cached values. Incomplete/non-finite/invalid snapshots
     * become four NaNs. A getter failure also clears those entries before propagating unchanged.
     * Short buffers reject before mutation; trailing storage is preserved by conforming getters.
     */
    fun getCurrentsIfValid(out: DoubleArray): Boolean =
        readCheckedSwerveSnapshot(out, currentMeasurementsValid) { getCurrents(it) }

    /** Gets four cached absolute encoder positions in rotations into [out] (size at least four). */
    fun getEncoderPositions(out: DoubleArray)

    /** Whether the last hardware refresh produced a trustworthy encoder snapshot. */
    val encoderPositionsValid: Boolean

    /**
     * Checked cached read. Invalid hardware is represented as an unavailable sample, never a
     * healthy zero. Buffer, completeness and failure semantics match [getCurrentsIfValid].
     */
    fun getEncoderPositionsIfValid(out: DoubleArray): Boolean =
        readCheckedSwerveSnapshot(out, encoderPositionsValid) { getEncoderPositions(it) }

    /** Gets gyro absolute pitch degrees. */
    val pitchDegrees: Double

    /** Gets gyro absolute roll degrees. */
    val rollDegrees: Double

    /** Gets raw gyro yaw degrees (unfused, for MegaTag2). */
    val rawGyroYawDegrees: Double

    /** Gets raw gyro yaw rate in degrees per second (for MegaTag2). */
    val yawRateDegreesPerSecond: Double

    /** Gets measured module linear velocities. */
    fun getModuleSpeeds(out: DoubleArray)

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
    fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean

    /** Resets/seeds the underlying pose estimator. */
    fun seedPose(pose: Pose2d)

    /** Gets any active motor fault codes (bitfields). */
    fun getFaults(out: IntArray)

    /** Gets the signal latency in milliseconds of the swerve sensors. */
    val signalLatencyMs: Double
}

/** Inlined to avoid closures or temporary arrays in the periodic checked-read path. */
private inline fun readCheckedSwerveSnapshot(
    out: DoubleArray,
    valid: Boolean,
    read: (DoubleArray) -> Unit
): Boolean {
    require(out.size >= 4) { "Swerve output must contain four modules" }
    out.fill(Double.NaN, 0, 4)
    if (!valid) return false
    try {
        read(out)
    } catch (failure: Throwable) {
        out.fill(Double.NaN, 0, 4)
        throw failure
    }
    for (index in 0 until 4) {
        if (!out[index].isFinite()) {
            out.fill(Double.NaN, 0, 4)
            return false
        }
    }
    return true
}
