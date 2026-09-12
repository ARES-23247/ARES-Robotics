package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose3d
import com.areslib.state.VisionMeasurement
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * Aggregates distinct [VisionIO] identities in constructor order.
 *
 * A composite is connected when any child is connected. Observations are ordered and correlated
 * using their shared RobotClock [VisionMeasurement.timestampMs] capture time. Source-specific
 * microsecond clocks need not share an epoch and are preserved only for downstream consumers.
 * Only the best-quality usable observation in each 10 ms window, anchored at the oldest
 * remaining observation, is exposed for fusion.
 * Camera mount poses retain child order. Per-source freshness and frame de-duplication remain
 * responsibilities of the child and platform tracker; this class does not read a second clock.
 *
 * Child snapshots and aggregate buffers are reused (lists can grow to a new high-water mark).
 * Outputs are borrowed until the next operation that invalidates them. Lifecycle and polling
 * share one owner thread. Failures invalidate aggregate outputs and propagate; lifecycle
 * forwarding/close attempts every child and preserves later failures as suppressed exceptions.
 */
class CompositeVisionIO(ios: List<VisionIO>) : VisionIO, AutoCloseable {
    private val children: Array<VisionIO> = distinctChildren(ios)
    private val childInputs = Array(children.size) { VisionIOInputs() }
    private val candidates = ArrayList<VisionMeasurement>((children.size * 4).coerceAtLeast(4))
    private val selected = ArrayList<VisionMeasurement>((children.size * 2).coerceAtLeast(2))
    private val aggregateCameraPoses = ArrayList<Pose3d>(children.size)
    private var currentInputs: VisionIOInputs? = null
    private var busy = false
    private var closed = false

    override val cameraPoses: List<Pose3d>
        get() = aggregateCameraPoses

    /** Polls each child once; absent/invalid child fields cannot retain the preceding sample. */
    override fun updateInputs(inputs: VisionIOInputs) {
        if (busy) {
            clearInputs(inputs)
            error("composite vision polling must not be reentrant")
        }
        invalidate()
        currentInputs = inputs
        clearInputs(inputs)
        if (closed) return
        busy = true
        try {
            var anyConnected = false
            for (index in children.indices) {
                val subInputs = childInputs[index]
                clearInputs(subInputs)
                children[index].updateInputs(subInputs)
                if (closed) return
                if (subInputs.isConnected) {
                    anyConnected = true
                    for (measurementIndex in subInputs.measurements.indices) {
                        val measurement = subInputs.measurements[measurementIndex]
                        if (hasValidVisionObservation(measurement)) candidates.add(measurement)
                    }
                }
                for (poseIndex in subInputs.cameraPoses.indices) {
                    aggregateCameraPoses.add(subInputs.cameraPoses[poseIndex])
                }
            }

            // Stable tiny-N insertion sort avoids comparator/iterator allocations in the loop.
            for (index in 1 until candidates.size) {
                val value = candidates[index]
                val valueTime = value.timestampMs
                var insertion = index - 1
                while (insertion >= 0 && candidates[insertion].timestampMs > valueTime) {
                    candidates[insertion + 1] = candidates[insertion]
                    insertion--
                }
                candidates[insertion + 1] = value
            }

            var index = 0
            while (index < candidates.size) {
                val groupStart = candidates[index].timestampMs
                var best = candidates[index]
                var next = index + 1
                while (next < candidates.size) {
                    val spread = candidates[next].timestampMs - groupStart
                    if (spread < 0L || spread > CORRELATION_WINDOW_MS) break
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
        } catch (failure: Throwable) {
            invalidate()
            throw failure
        } finally {
            busy = false
        }
    }

    /** Forwards the same orientation hint to every open child in constructor order. */
    override fun setOrientation(
        yawDegrees: Double, yawRateDegPerSec: Double,
        pitchDegrees: Double, pitchRateDegPerSec: Double,
        rollDegrees: Double, rollRateDegPerSec: Double,
        linearVelocityMps: Double
    ) {
        if (closed) return
        check(!busy) { "composite vision forwarding must not be reentrant" }
        busy = true
        var failure: Throwable? = null
        try {
            for (index in children.indices) {
                if (closed) break
                try {
                    children[index].setOrientation(yawDegrees, yawRateDegPerSec, pitchDegrees,
                        pitchRateDegPerSec, rollDegrees, rollRateDegPerSec, linearVelocityMps)
                } catch (next: Throwable) {
                    failure = accumulateFailure(failure, next)
                }
            }
            if (failure != null) invalidate()
        } finally {
            busy = false
        }
        failure?.let { throw it }
    }

    /** Forwards lifecycle IMU-mode changes to every open child. */
    override fun setImuMode(mode: Int) {
        if (closed) return
        check(!busy) { "composite vision forwarding must not be reentrant" }
        busy = true
        var failure: Throwable? = null
        try {
            for (index in children.indices) {
                if (closed) break
                try { children[index].setImuMode(mode) }
                catch (next: Throwable) { failure = accumulateFailure(failure, next) }
            }
            if (failure != null) invalidate()
        } finally {
            busy = false
        }
        failure?.let { throw it }
    }

    /** Closes each owned child once, even after failures; a closed composite cannot resume polling. */
    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        var failure: Throwable? = null
        for (index in children.indices) {
            val io = children[index]
            if (io is AutoCloseable) {
                try { io.close() }
                catch (next: Throwable) { failure = accumulateFailure(failure, next) }
            }
            clearInputs(childInputs[index])
        }
        failure?.let { throw it }
    }

    private fun invalidate() {
        candidates.clear()
        selected.clear()
        aggregateCameraPoses.clear()
        currentInputs?.let { clearInputs(it) }
    }

    private fun isHigherQuality(candidate: VisionMeasurement, current: VisionMeasurement): Boolean {
        if (candidate.tagCount != current.tagCount) return candidate.tagCount > current.tagCount
        val candidateDistance = if (candidate.averageTagDistanceMeters >= 0.0) candidate.averageTagDistanceMeters else Double.POSITIVE_INFINITY
        val currentDistance = if (current.averageTagDistanceMeters >= 0.0) current.averageTagDistanceMeters else Double.POSITIVE_INFINITY
        if (candidateDistance != currentDistance) return candidateDistance < currentDistance
        val uncertainty = compareUncertainty(candidate, current)
        if (uncertainty != 0) return uncertainty < 0
        val candidateSpan = if (candidate.tagSpanMeters >= 0.0) candidate.tagSpanMeters else -1.0
        val currentSpan = if (current.tagSpanMeters >= 0.0) current.tagSpanMeters else -1.0
        if (candidateSpan != currentSpan) return candidateSpan > currentSpan
        val candidateArea = if (candidate.averageTagAreaPercent >= 0.0) candidate.averageTagAreaPercent else -1.0
        val currentArea = if (current.averageTagAreaPercent >= 0.0) current.averageTagAreaPercent else -1.0
        return candidateArea > currentArea
    }

    private fun compareUncertainty(candidate: VisionMeasurement, current: VisionMeasurement): Int {
        val cx = candidate.stdDevXMeters
        val cy = candidate.stdDevYMeters
        val ox = current.stdDevXMeters
        val oy = current.stdDevYMeters
        val candidateKnown = cx.isFinite() && cx > 0.0 && cy.isFinite() && cy > 0.0
        val currentKnown = ox.isFinite() && ox > 0.0 && oy.isFinite() && oy > 0.0
        if (candidateKnown != currentKnown) return if (candidateKnown) -1 else 1
        if (!candidateKnown) return 0
        // A common scale preserves the ordering of x^2+y^2 without overflow or all-small underflow.
        val scale = max(max(cx, cy), max(ox, oy))
        val scx = cx / scale
        val scy = cy / scale
        val sox = ox / scale
        val soy = oy / scale
        return (scx * scx + scy * scy).compareTo(sox * sox + soy * soy)
    }

    private companion object {
        // Retain the existing JVM field and units for callers compiled against older versions.
        const val CORRELATION_WINDOW_MICROS = 10_000L
        private const val CORRELATION_WINDOW_MS = CORRELATION_WINDOW_MICROS / 1_000L

        fun clearInputs(inputs: VisionIOInputs) {
            inputs.isConnected = false
            inputs.measurements = emptyList()
            inputs.cameraPoses = emptyList()
        }

        fun accumulateFailure(first: Throwable?, next: Throwable): Throwable {
            if (first == null) return next
            if (first !== next) first.addSuppressed(next)
            return first
        }

        fun distinctChildren(ios: List<VisionIO>): Array<VisionIO> {
            val seen = IdentityHashMap<VisionIO, Boolean>(ios.size)
            val children = ArrayList<VisionIO>(ios.size)
            for (io in ios) if (seen.put(io, true) == null) children.add(io)
            return children.toTypedArray()
        }
    }
}
