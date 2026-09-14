package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.LivePoseState

/** Latched scalar vision and complete same-source-time array triples, owned by one consumer. */
internal class VisionPoseAccumulator {
    private var hasTarget = false
    private val scalar = DoubleArray(3) { Double.NaN }
    private var array: DoubleArray? = null
    private var times: LongArray? = null
    private var canonical = false
    private var publishedTime = Long.MIN_VALUE
    private var poses: Map<Int, Double> = emptyMap()

    @Synchronized fun reset() {
        hasTarget = false
        scalar.fill(Double.NaN)
        array = null; times = null; canonical = false
        publishedTime = Long.MIN_VALUE; poses = emptyMap()
    }

    @Synchronized fun accept(frame: TelemetryFrame): Boolean {
        val key = frame.key
        val value = if (frame.stringValue == null && frame.value.isFinite()) frame.value else Double.NaN
        if (key == "Vision/HasTarget") {
            if (value != 1.0) reset() else hasTarget = true
            return true
        }
        if (!hasTarget) return false
        when (key) {
            "Vision/Pose_X" -> { scalar[0] = value; return true }
            "Vision/Pose_Y" -> { scalar[1] = value; return true }
            "Vision/Pose_Heading" -> { scalar[2] = value; return true }
        }
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
        val x = scalar[0].takeIf { complete }
        val y = scalar[1].takeIf { complete }
        val heading = scalar[2].takeIf { complete }
        if (current.visionHasTarget == hasTarget && current.visionX == x && current.visionY == y &&
            current.visionHeading == heading && current.visionPoses == poses) return current
        return current.copy(visionHasTarget = hasTarget, visionX = x, visionY = y,
            visionHeading = heading, visionPoses = poses)
    }
}
