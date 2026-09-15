package com.ares.analytics.service

import kotlinx.serialization.json.*

internal const val SIMULATOR_POSE_FRAME_TOPIC = "ARES/SimulatorPoseFrame"
internal const val SIMULATOR_POSE_FRAME_VALUE_COUNT = 10
internal const val DRIVE_INPUT_ACK_TOPIC = "ARES/Control/DriveInputAck"
internal const val DRIVE_INPUT_ACK_VALUE_COUNT = 9
internal const val MECANUM_MOTOR_FRAME_TOPIC = "Hardware/Motors/MecanumFrame"
internal const val MECANUM_MOTOR_FRAME_VALUE_COUNT = 13

/** One immutable, same-cycle simulator localization sample decoded from the packed NT4 topic. */
data class SimulatorPoseFrameSnapshot(
    val trueX: Double,
    val trueY: Double,
    val trueHeading: Double,
    val ekfX: Double,
    val ekfY: Double,
    val ekfHeading: Double,
    val odomX: Double,
    val odomY: Double,
    val odomHeading: Double,
    val sequence: Long,
    val timestampMs: Long,
    val timestampUs: Long,
    val targetEpoch: Long = 0L,
)

/** Latest fail-closed simulator receiver state for the desktop-owned drive-frame lease. */
data class DriveInputAcknowledgement(
    val version: Double,
    val statusCode: Int,
    val acceptedSession: Long,
    val acceptedSequence: Long,
    val leaseAgeMs: Long,
    val appliedVx: Double,
    val appliedVy: Double,
    val appliedOmega: Double,
    val rejectedFrameCount: Long,
    val timestampMs: Long,
)

internal fun decodeDriveInputAcknowledgement(value: Any?, timestampMs: Long): DriveInputAcknowledgement? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size != DRIVE_INPUT_ACK_VALUE_COUNT) return null

    fun numberAt(index: Int): Double? {
        val element = when (value) {
            is JsonArray -> value[index]
            is List<*> -> value[index]
            is DoubleArray -> value[index]
            is FloatArray -> value[index]
            is Array<*> -> value[index]
            else -> null
        }
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is Number -> element.toDouble()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    val version = numberAt(0) ?: return null
    val statusValue = numberAt(1) ?: return null
    val sessionValue = numberAt(2) ?: return null
    val sequenceValue = numberAt(3) ?: return null
    val ageValue = numberAt(4) ?: return null
    val rejectedValue = numberAt(8) ?: return null
    val statusCode = statusValue.toInt()
    val acceptedSession = sessionValue.toLong()
    val acceptedSequence = sequenceValue.toLong()
    val leaseAgeMs = ageValue.toLong()
    val rejectedFrameCount = rejectedValue.toLong()
    if (statusCode.toDouble() != statusValue || acceptedSession.toDouble() != sessionValue ||
        acceptedSequence.toDouble() != sequenceValue || leaseAgeMs.toDouble() != ageValue ||
        rejectedFrameCount.toDouble() != rejectedValue
    ) return null

    return DriveInputAcknowledgement(
        version = version,
        statusCode = statusCode,
        acceptedSession = acceptedSession,
        acceptedSequence = acceptedSequence,
        leaseAgeMs = leaseAgeMs,
        appliedVx = numberAt(5) ?: return null,
        appliedVy = numberAt(6) ?: return null,
        appliedOmega = numberAt(7) ?: return null,
        rejectedFrameCount = rejectedFrameCount,
        timestampMs = timestampMs,
    )
}

/** One complete, same-tick mecanum simulator observation in FL, FR, RL, RR order. */
data class MecanumMotorFrameSnapshot(
    val flPower: Double,
    val frPower: Double,
    val rlPower: Double,
    val rrPower: Double,
    val flVelocity: Double,
    val frVelocity: Double,
    val rlVelocity: Double,
    val rrVelocity: Double,
    val flCurrentAmps: Double,
    val frCurrentAmps: Double,
    val rlCurrentAmps: Double,
    val rrCurrentAmps: Double,
    val sequence: Long,
    val timestampMs: Long,
)

internal fun decodeMecanumMotorFrame(value: Any?, timestampMs: Long): MecanumMotorFrameSnapshot? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size != MECANUM_MOTOR_FRAME_VALUE_COUNT) return null

    fun numberAt(index: Int): Double? {
        val element = when (value) {
            is JsonArray -> value[index]
            is List<*> -> value[index]
            is DoubleArray -> value[index]
            is FloatArray -> value[index]
            is Array<*> -> value[index]
            else -> null
        }
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is Number -> element.toDouble()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    val flPower = numberAt(0) ?: return null
    val frPower = numberAt(1) ?: return null
    val rlPower = numberAt(2) ?: return null
    val rrPower = numberAt(3) ?: return null
    val flVelocity = numberAt(4) ?: return null
    val frVelocity = numberAt(5) ?: return null
    val rlVelocity = numberAt(6) ?: return null
    val rrVelocity = numberAt(7) ?: return null
    val flCurrentAmps = numberAt(8) ?: return null
    val frCurrentAmps = numberAt(9) ?: return null
    val rlCurrentAmps = numberAt(10) ?: return null
    val rrCurrentAmps = numberAt(11) ?: return null
    val sequenceValue = numberAt(12) ?: return null
    val sequence = sequenceValue.toLong()
    if (sequence < 0L || sequence.toDouble() != sequenceValue) return null
    return MecanumMotorFrameSnapshot(
        flPower = flPower, frPower = frPower, rlPower = rlPower, rrPower = rrPower,
        flVelocity = flVelocity, frVelocity = frVelocity, rlVelocity = rlVelocity, rrVelocity = rrVelocity,
        flCurrentAmps = flCurrentAmps, frCurrentAmps = frCurrentAmps,
        rlCurrentAmps = rlCurrentAmps, rrCurrentAmps = rrCurrentAmps,
        sequence = sequence,
        timestampMs = timestampMs,
    )
}

/** Decodes without retaining any producer- or MessagePack-owned array storage. */
internal fun decodeSimulatorPoseFrame(
    value: Any?,
    timestampMs: Long,
    timestampUs: Long,
    targetEpoch: Long = 0L,
): SimulatorPoseFrameSnapshot? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size != SIMULATOR_POSE_FRAME_VALUE_COUNT) return null

    fun numberAt(index: Int): Double? {
        val element = when (value) {
            is JsonArray -> value[index]
            is List<*> -> value[index]
            is DoubleArray -> value[index]
            is FloatArray -> value[index]
            is Array<*> -> value[index]
            else -> null
        }
        return when (element) {
            is JsonPrimitive -> element.takeUnless { it.isString }?.doubleOrNull
            is Number -> element.toDouble()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    val trueX = numberAt(0) ?: return null
    val trueY = numberAt(1) ?: return null
    val trueHeading = numberAt(2) ?: return null
    val ekfX = numberAt(3) ?: return null
    val ekfY = numberAt(4) ?: return null
    val ekfHeading = numberAt(5) ?: return null
    val odomX = numberAt(6) ?: return null
    val odomY = numberAt(7) ?: return null
    val odomHeading = numberAt(8) ?: return null
    val sequenceValue = numberAt(9) ?: return null
    val sequence = sequenceValue.toLong()
    if (sequence !in 0L..9_007_199_254_740_991L || sequence.toDouble() != sequenceValue) return null

    return SimulatorPoseFrameSnapshot(
        trueX = trueX,
        trueY = trueY,
        trueHeading = trueHeading,
        ekfX = ekfX,
        ekfY = ekfY,
        ekfHeading = ekfHeading,
        odomX = odomX,
        odomY = odomY,
        odomHeading = odomHeading,
        sequence = sequence,
        timestampMs = timestampMs,
        timestampUs = timestampUs,
        targetEpoch = targetEpoch,
    )
}
