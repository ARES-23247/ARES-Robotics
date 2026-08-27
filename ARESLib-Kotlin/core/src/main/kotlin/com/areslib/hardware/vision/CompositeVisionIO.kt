package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose3d
import com.areslib.state.VisionMeasurement
import kotlin.math.abs

/**
 * Aggregates several [VisionIO] sources in constructor order.
 *
 * A composite is connected when any child is connected. Measurements and camera mount poses are
 * merged by capture time. Near-simultaneous observations are correlated through the shared tag map,
 * so only the best-quality observation in each capture window is exposed for fusion. One child exception is
 * allowed to propagate from update/orientation calls; shutdown is best-effort and continues closing
 * the remaining children after an individual close failure.
 *
 * Child snapshots and aggregate buffers are pre-allocated. It is not thread-safe; lifecycle and
 * update calls should share one owner thread, and consumers must finish reading before the next poll.
 */
class CompositeVisionIO(private val ios: List<VisionIO>) : VisionIO, AutoCloseable {

    private val childInputs = Array(ios.size) { VisionIOInputs() }
    private val candidates = ArrayList<VisionMeasurement>((ios.size * 4).coerceAtLeast(4))
    private val selected = ArrayList<VisionMeasurement>((ios.size * 2).coerceAtLeast(2))
    private val aggregateCameraPoses = ArrayList<Pose3d>(ios.size)

    override val cameraPoses: List<Pose3d>
        get() = aggregateCameraPoses

    /** Polls every child once and replaces [inputs] with their concatenated snapshot. */
    override fun updateInputs(inputs: VisionIOInputs) {
        candidates.clear()
        selected.clear()
        aggregateCameraPoses.clear()
        var anyConnected = false

        for (index in ios.indices) {
            val io = ios[index]
            val subInputs = childInputs[index]
            io.updateInputs(subInputs)
            for (measurementIndex in subInputs.measurements.indices) {
                candidates.add(subInputs.measurements[measurementIndex])
            }
            for (poseIndex in subInputs.cameraPoses.indices) {
                aggregateCameraPoses.add(subInputs.cameraPoses[poseIndex])
            }
            anyConnected = anyConnected || subInputs.isConnected
        }

        // Tiny-N insertion sort avoids comparator/iterator allocations in the robot loop.
        for (index in 1 until candidates.size) {
            val value = candidates[index]
            val valueTime = captureTimeMicros(value)
            var insertion = index - 1
            while (insertion >= 0 && captureTimeMicros(candidates[insertion]) > valueTime) {
                candidates[insertion + 1] = candidates[insertion]
                insertion--
            }
            candidates[insertion + 1] = value
        }

        var index = 0
        while (index < candidates.size) {
            val groupStart = captureTimeMicros(candidates[index])
            var best = candidates[index]
            var next = index + 1
            while (next < candidates.size &&
                abs(captureTimeMicros(candidates[next]) - groupStart) <= CORRELATION_WINDOW_MICROS) {
                val candidate = candidates[next]
                if (isHigherQuality(candidate, best)) best = candidate
                next++
            }
            selected.add(best)
            index = next
        }

        inputs.isConnected = anyConnected
        inputs.measurements = selected
        inputs.cameraPoses = aggregateCameraPoses
    }

    /** Forwards the same orientation hint to every child in constructor order. */
    override fun setOrientation(
        yawDegrees: Double, yawRateDegPerSec: Double,
        pitchDegrees: Double, pitchRateDegPerSec: Double,
        rollDegrees: Double, rollRateDegPerSec: Double,
        linearVelocityMps: Double
    ) {
        for (index in ios.indices) {
            val io = ios[index]
            io.setOrientation(
                yawDegrees, yawRateDegPerSec,
                pitchDegrees, pitchRateDegPerSec,
                rollDegrees, rollRateDegPerSec,
                linearVelocityMps
            )
        }
    }

    /** Forwards lifecycle IMU-mode changes to every physical camera. */
    override fun setImuMode(mode: Int) {
        for (index in ios.indices) ios[index].setImuMode(mode)
    }

    /** Closes every child that implements [AutoCloseable], suppressing individual failures. */
    override fun close() {
        for (index in ios.indices) {
            val io = ios[index]
            if (io is AutoCloseable) {
                try {
                    io.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun captureTimeMicros(measurement: VisionMeasurement): Long =
        if (measurement.captureTimestampMicros > 0L) measurement.captureTimestampMicros
        else measurement.timestampMs * 1_000L

    private fun isHigherQuality(candidate: VisionMeasurement, current: VisionMeasurement): Boolean {
        if (candidate.tagCount != current.tagCount) return candidate.tagCount > current.tagCount
        val candidateDistance = candidate.averageTagDistanceMeters.takeIf { it.isFinite() && it >= 0.0 }
            ?: Double.POSITIVE_INFINITY
        val currentDistance = current.averageTagDistanceMeters.takeIf { it.isFinite() && it >= 0.0 }
            ?: Double.POSITIVE_INFINITY
        if (candidateDistance != currentDistance) return candidateDistance < currentDistance
        val candidateVariance = varianceScore(candidate)
        val currentVariance = varianceScore(current)
        if (candidateVariance != currentVariance) return candidateVariance < currentVariance
        if (candidate.tagSpanMeters != current.tagSpanMeters) return candidate.tagSpanMeters > current.tagSpanMeters
        return candidate.averageTagAreaPercent > current.averageTagAreaPercent
    }

    private fun varianceScore(measurement: VisionMeasurement): Double {
        val x = measurement.stdDevXMeters
        val y = measurement.stdDevYMeters
        return if (x.isFinite() && x > 0.0 && y.isFinite() && y > 0.0) x * x + y * y
        else Double.POSITIVE_INFINITY
    }

    private companion object {
        const val CORRELATION_WINDOW_MICROS = 10_000L
    }
}
