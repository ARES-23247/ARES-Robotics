package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.service.VisionTargetSnapshot
import com.ares.analytics.service.VisionPoseArraySnapshot
import com.ares.analytics.viewmodel.LivePoseState

/** Latched scalar vision and complete same-source-time array triples, owned by one consumer. */
internal class VisionPoseAccumulator {
    private var targetLossGeneration: Long? = null
    private var hasTarget = false
    private var targetSinceUs = Long.MIN_VALUE
    private var parentOwned = false
    private val scalar = DoubleArray(3) { Double.NaN }
    private var array: DoubleArray? = null
    private var times: LongArray? = null
    private var canonical = false
    private var publishedTime = Long.MIN_VALUE
    private var poses: Map<Int, Double> = emptyMap()

    @Synchronized fun reset() {
        targetLossGeneration = null
        hasTarget = false
        targetSinceUs = Long.MIN_VALUE
        parentOwned = false
        scalar.fill(Double.NaN)
        array = null; times = null; canonical = false
        publishedTime = Long.MIN_VALUE; poses = emptyMap()
    }

    @Synchronized fun accept(frame: VisionTargetSnapshot) {
        if (targetLossGeneration != null && targetLossGeneration != frame.lossGeneration) reset()
        accept(TelemetryFrame(frame.timestampUs / 1000, "live", "Vision/HasTarget",
            if (frame.hasTarget) 1.0 else 0.0, timestampUs = frame.timestampUs))
        targetLossGeneration = frame.lossGeneration
    }

    @Synchronized fun accept(frame: VisionPoseArraySnapshot) {
        parentOwned = true
        if (frame.timestampUs < targetSinceUs || frame.timestampUs < publishedTime) return
        array = null; times = null
        poses = frame.poses
        publishedTime = frame.timestampUs
    }

    @Synchronized fun clearParent() {
        poses = emptyMap()
        array = null; times = null
        publishedTime = Long.MIN_VALUE
    }

    @Synchronized fun accept(frame: TelemetryFrame): Boolean {
        val key = frame.key
        val value = if (frame.stringValue == null && frame.value.isFinite()) frame.value else Double.NaN
        if (key == "Vision/HasTarget") {
            if (value != 1.0) reset() else {
                if (!hasTarget) {
                    targetSinceUs = frame.timestampUs
                    if (publishedTime < targetSinceUs) clearParent()
                }
                hasTarget = true
            }
            return true
        }
        if (!hasTarget || frame.timestampUs < targetSinceUs) return false
        when (key) {
            "Vision/Pose_X" -> { scalar[0] = value; return true }
            "Vision/Pose_Y" -> { scalar[1] = value; return true }
            "Vision/Pose_Heading" -> { scalar[2] = value; return true }
        }
        if (parentOwned || frame.timestampUs < targetSinceUs) return false
        val isCanonical = key.startsWith("Vision/PoseArray/")
        val prefix = if (isCanonical) "Vision/PoseArray/" else "AdvantageScope/VisionPose/"
        if (!key.startsWith(prefix) || (!isCanonical && canonical)) return false
        val index = key.removePrefix(prefix).toIntOrNull()?.takeIf { it in 0..4095 } ?: return false
        if (isCanonical && !canonical) {
            canonical = true; array = null; times = null
            publishedTime = Long.MIN_VALUE; poses = emptyMap()
        }
        if (frame.timestampUs < publishedTime) return false
        val values = array ?: DoubleArray(4096) { Double.NaN }.also { array = it }
        val timestamps = times ?: LongArray(4096) { Long.MIN_VALUE }.also { times = it }
        values[index] = value
        timestamps[index] = frame.timestampUs
        val base = index / 3 * 3
        if (base + 2 >= values.size) return false
        val complete = (base..base + 2).all { values[it].isFinite() && timestamps[it] == frame.timestampUs }
        if (!complete) {
            if (frame.timestampUs == publishedTime && (base..base + 2).any { it in poses }) {
                poses = poses - setOf(base, base + 1, base + 2)
            }
            return true
        }
        val previous = if (frame.timestampUs == publishedTime) poses else emptyMap()
        if ((base..base + 2).all { previous[it] == values[it] }) return true
        poses = previous.toMutableMap().apply { for (slot in base..base + 2) put(slot, values[slot]) }
        publishedTime = frame.timestampUs
        return true
    }

    @Synchronized fun snapshot(current: LivePoseState): LivePoseState {
        val complete = hasTarget && scalar.all(Double::isFinite)
        val visiblePoses = if (hasTarget) poses else emptyMap()
        val x = scalar[0].takeIf { complete }
        val y = scalar[1].takeIf { complete }
        val heading = scalar[2].takeIf { complete }
        if (current.visionHasTarget == hasTarget && current.visionX == x && current.visionY == y &&
            current.visionHeading == heading && current.visionPoses == visiblePoses) return current
        return current.copy(visionHasTarget = hasTarget, visionX = x, visionY = y,
            visionHeading = heading, visionPoses = visiblePoses)
    }
}
