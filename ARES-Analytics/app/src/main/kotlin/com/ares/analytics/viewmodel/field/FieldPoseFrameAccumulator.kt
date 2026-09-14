package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.SimulatorPoseFrameSnapshot
import com.ares.analytics.viewmodel.LivePoseState

/**
 * Scalar topics are latched independently within their own source because NT4 may suppress
 * unchanged scalars. Packed frames require a fresh contiguous payload and commit only at slot9.
 */
internal class FieldPoseFrameAccumulator {
    private val truth = DoubleArray(3) { Double.NaN }
    private val estimate = DoubleArray(3) { Double.NaN }
    private val drive = DoubleArray(3) { Double.NaN }
    private val odometry = DoubleArray(3) { Double.NaN }
    private val staging = DoubleArray(10)
    private val packed = DoubleArray(10)
    private var nextIndex = 0
    private var packedOwned = false
    private var atomicOwned = false
    private var packedReady = false
    private var sequence: Long? = null

    @Synchronized fun reset() {
        truth.fill(Double.NaN); estimate.fill(Double.NaN)
        drive.fill(Double.NaN); odometry.fill(Double.NaN)
        nextIndex = 0; packedOwned = false; atomicOwned = false; packedReady = false; sequence = null
    }

    /** Invalid parents leave the last valid sample unchanged. */
    @Synchronized fun accept(frame: SimulatorPoseFrameSnapshot): Boolean {
        if (!frame.isValidPose()) return false
        packed[0] = frame.trueX; packed[1] = frame.trueY; packed[2] = frame.trueHeading
        packed[3] = frame.ekfX; packed[4] = frame.ekfY; packed[5] = frame.ekfHeading
        packed[6] = frame.odomX; packed[7] = frame.odomY; packed[8] = frame.odomHeading
        packed[9] = frame.sequence.toDouble()
        packedOwned = true; atomicOwned = true; packedReady = true
        nextIndex = 0; sequence = frame.sequence
        return true
    }

    @Synchronized fun accept(key: String, value: Double): Boolean {
        if (key.startsWith(PREFIX)) {
            if (atomicOwned) return false
            packedOwned = true
            val index = key.removePrefix(PREFIX).toIntOrNull()
            if (index == 0) {
                nextIndex = if (value.isFinite()) 1 else 0
                staging[0] = value
                return false
            }
            if (index == null || index != nextIndex || nextIndex == 0 || !value.isFinite()) {
                nextIndex = 0
                return false
            }
            if (index == 9) {
                nextIndex = 0
                if (value !in 0.0..MAX_SEQUENCE.toDouble() || value != kotlin.math.floor(value) ||
                    sequence == value.toLong()) return false
                staging[9] = value
                staging.copyInto(packed)
                sequence = value.toLong(); packedReady = true
                return true
            }
            staging[index] = value
            nextIndex++
            return false
        }
        if (packedOwned) return false
        when (key) {
            "ARES/TruePose/0" -> truth[0] = value
            "ARES/TruePose/1" -> truth[1] = value
            "ARES/TruePose/2" -> truth[2] = value
            "ARES/EstimatedPose/0" -> estimate[0] = value
            "ARES/EstimatedPose/1" -> estimate[1] = value
            "ARES/EstimatedPose/2" -> estimate[2] = value
            "Drive/Pose_X" -> drive[0] = value
            "Drive/Pose_Y" -> drive[1] = value
            "Drive/Pose_Heading", "Drive/Drive_Heading" -> drive[2] = value
            "Drive/Odom_X" -> odometry[0] = value
            "Drive/Odom_Y" -> odometry[1] = value
            "Drive/Odom_Heading" -> odometry[2] = value
            else -> return false
        }
        return true
    }

    @Synchronized fun snapshot(current: LivePoseState): LivePoseState {
        val hasPacked = packedOwned && packedReady
        val trueValues = if (packedOwned) null else truth.takeIf { it.all(Double::isFinite) }
        val estimateValues = if (packedOwned) null else (estimate.takeIf { it.all(Double::isFinite) }
            ?: drive.takeIf { it.all(Double::isFinite) })
        val odomValues = if (packedOwned) null else odometry.takeIf { it.all(Double::isFinite) }
        val tx = if (hasPacked) packed[0] else trueValues?.get(0) ?: 0.0
        val ty = if (hasPacked) packed[1] else trueValues?.get(1) ?: 0.0
        val th = if (hasPacked) packed[2] else trueValues?.get(2) ?: 0.0
        val simHeading = if (hasPacked) packed[2] else trueValues?.get(2)
        val hasTruth = hasPacked || trueValues != null
        val ex = if (hasPacked) packed[3] else estimateValues?.get(0)
        val ey = if (hasPacked) packed[4] else estimateValues?.get(1)
        val eh = if (hasPacked) packed[5] else estimateValues?.get(2)
        val ox = if (hasPacked) packed[6] else odomValues?.get(0)
        val oy = if (hasPacked) packed[7] else odomValues?.get(1)
        val oh = if (hasPacked) packed[8] else odomValues?.get(2)
        if (current.trueX == tx && current.trueY == ty && current.trueHeading == th &&
            current.simHeading == simHeading && current.hasTruePoseData == hasTruth &&
            current.ekfX == ex && current.ekfY == ey && current.ekfHeading == eh &&
            current.odomX == ox && current.odomY == oy && current.odomHeading == oh) return current
        return current.copy(trueX = tx, trueY = ty, trueHeading = th, simHeading = simHeading,
            hasTruePoseData = hasTruth, ekfX = ex, ekfY = ey, ekfHeading = eh,
            odomX = ox, odomY = oy, odomHeading = oh)
    }

    private companion object {
        const val PREFIX = "ARES/SimulatorPoseFrame/"
        const val MAX_SEQUENCE = 9_007_199_254_740_991L
    }
}

internal fun SimulatorPoseFrameSnapshot.isValidPose(): Boolean =
    trueX.isFinite() && trueY.isFinite() && trueHeading.isFinite() &&
        ekfX.isFinite() && ekfY.isFinite() && ekfHeading.isFinite() &&
        odomX.isFinite() && odomY.isFinite() && odomHeading.isFinite() &&
        sequence in 0L..9_007_199_254_740_991L

/** Current parent identity is checked again at each delayed consumer, after target/mode changes. */
internal fun com.ares.analytics.service.Nt4ClientService.currentFieldPoseFrame(): SimulatorPoseFrameSnapshot? =
    simulatorPoseFrame.value?.takeIf { !isReplayActive.value && it.targetEpoch == telemetryStore.currentTargetEpoch() }

internal fun com.ares.analytics.service.Nt4ClientService.isCurrentFieldUpdate(
    frame: com.ares.analytics.shared.models.TelemetryFrame,
): Boolean = !isReplayActive.value && telemetryStore.isCurrentNotifiedFrame(frame) &&
    !(hasReceivedSimulatorPoseFrame && isFieldPoseTopic(frame.key))
