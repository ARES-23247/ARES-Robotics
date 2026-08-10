package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d

/**
 * Aggregates several [VisionIO] sources in constructor order.
 *
 * A composite is connected when any child is connected. Measurements and camera mount poses are
 * concatenated without sorting, de-duplication, or timestamp normalization. One child exception is
 * allowed to propagate from update/orientation calls; shutdown is best-effort and continues closing
 * the remaining children after an individual close failure.
 *
 * This implementation creates aggregate snapshots and is not intended for a strict zero-allocation
 * loop. It is not thread-safe; lifecycle and update calls should share one owner thread.
 */
class CompositeVisionIO(private val ios: List<VisionIO>) : VisionIO, AutoCloseable {

    override val cameraPoses: List<Pose3d>
        get() = ios.flatMap { it.cameraPoses }

    /** Polls every child once and replaces [inputs] with their concatenated snapshot. */
    override fun updateInputs(inputs: VisionIOInputs) {
        val allMeasurements = mutableListOf<VisionMeasurement>()
        var anyConnected = false
        
        for (io in ios) {
            val subInputs = VisionIOInputs()
            io.updateInputs(subInputs)
            allMeasurements.addAll(subInputs.measurements)
            if (subInputs.isConnected) {
                anyConnected = true
            }
        }
        
        inputs.isConnected = anyConnected
        inputs.measurements = allMeasurements
        inputs.cameraPoses = cameraPoses
    }

    /** Forwards the same orientation hint to every child in constructor order. */
    override fun setOrientation(
        yawDegrees: Double, yawRateDegPerSec: Double,
        pitchDegrees: Double, pitchRateDegPerSec: Double,
        rollDegrees: Double, rollRateDegPerSec: Double,
        linearVelocityMps: Double
    ) {
        for (io in ios) {
            io.setOrientation(
                yawDegrees, yawRateDegPerSec,
                pitchDegrees, pitchRateDegPerSec,
                rollDegrees, rollRateDegPerSec,
                linearVelocityMps
            )
        }
    }

    /** Closes every child that implements [AutoCloseable], suppressing individual failures. */
    override fun close() {
        for (io in ios) {
            if (io is AutoCloseable) {
                try {
                    io.close()
                } catch (_: Exception) {}
            }
        }
    }
}
