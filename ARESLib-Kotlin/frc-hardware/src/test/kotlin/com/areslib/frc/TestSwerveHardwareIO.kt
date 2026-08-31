package com.areslib.frc

import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.geometry.Pose2d

/** Explicit fail-closed baseline for focused test doubles. */
abstract class TestSwerveHardwareIO : SwerveHardwareIO {
    override fun refresh() = Unit

    override fun getCurrents(out: DoubleArray) = out.fill(Double.NaN)
    override val currentMeasurementsValid: Boolean = false

    override fun getEncoderPositions(out: DoubleArray) = out.fill(Double.NaN)
    override val encoderPositionsValid: Boolean = false

    override val pitchDegrees: Double = Double.NaN
    override val rollDegrees: Double = Double.NaN
    override val rawGyroYawDegrees: Double = Double.NaN
    override val yawRateDegreesPerSecond: Double = Double.NaN

    override fun getModuleSpeeds(out: DoubleArray) = out.fill(Double.NaN)
    override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean = false
    override fun seedPose(pose: Pose2d) = Unit
    override fun getFaults(out: IntArray) = out.fill(-1)
    override val signalLatencyMs: Double = Double.POSITIVE_INFINITY
}
