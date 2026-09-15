package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.robotLightingTelemetry
import com.ares.analytics.service.LegacyGamePieceTelemetry
import com.ares.analytics.service.VisionPoseArrayTelemetry
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.LivePoseState

private data class ReplayPose(val x: Double, val y: Double, val headingRad: Double)

/** Reconstructs field layers from one atomic replay snapshot without inventing missing sources. */
internal fun ReplayFrame.toReplayPoseState(): LivePoseState {
    fun number(key: String): Double? =
        values[key]?.takeIf { key !in stringValues && it.isFinite() }
    fun pose(x: String, y: String, heading: String): ReplayPose? {
        return ReplayPose(number(x) ?: return null, number(y) ?: return null, number(heading) ?: return null)
    }

    val packedValues = (0..9).map { number("ARES/SimulatorPoseFrame/$it") }
    val sequence = packedValues[9]
    val packed = if (packedValues.all { it != null } && sequence != null &&
        sequence in 0.0..9_007_199_254_740_991.0 && sequence == kotlin.math.floor(sequence)) {
        packedValues.map { requireNotNull(it) }
    } else null
    val truth = packed?.let { ReplayPose(it[0], it[1], it[2]) }
        ?: pose("ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2")
    val estimate = packed?.let { ReplayPose(it[3], it[4], it[5]) }
        ?: pose("ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2")
        ?: pose("Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading")
        ?: pose("Drive/Pose_X", "Drive/Pose_Y", "Drive/Drive_Heading")
    val odometry = packed?.let { ReplayPose(it[6], it[7], it[8]) }
        ?: pose("Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading")
    val visionHasTarget = number("Vision/HasTarget") == 1.0
    val vision = if (visionHasTarget) pose("Vision/Pose_X", "Vision/Pose_Y", "Vision/Pose_Heading") else null
    val visionPoses = if (visionHasTarget) VisionPoseArrayTelemetry.decodeSnapshot(values, stringValues) else emptyMap()
    val lighting = robotLightingTelemetry(if (stringValues.isEmpty()) values else values.filterKeys { it !in stringValues })

    return LivePoseState(
        trueX = truth?.x ?: 0.0,
        trueY = truth?.y ?: 0.0,
        trueHeading = truth?.headingRad ?: 0.0,
        simHeading = packed?.get(2),
        hasTruePoseData = truth != null,
        ekfX = estimate?.x, ekfY = estimate?.y, ekfHeading = estimate?.headingRad,
        odomX = odometry?.x, odomY = odometry?.y, odomHeading = odometry?.headingRad,
        visionX = vision?.x, visionY = vision?.y, visionHeading = vision?.headingRad,
        visionPoses = visionPoses,
        visionHasTarget = visionHasTarget,
        liveGamePieces = replayGamePieces(values, stringValues),
        biobuzz = org.ares.biobuzz.BiobuzzTelemetry.decode(stringValues[org.ares.biobuzz.BiobuzzTelemetry.TOPIC]),
        isConnected = true,
        indicatorLights = lighting.indicatorOutputs,
        prismLights = lighting.prismOutputs,
    )
}

private fun replayGamePieces(values: Map<String, Double>, strings: Map<String, String>): Map<Int, GamePiece> {
    // A typed frame owns this layer even when empty or incomplete; stale legacy arrays must not win.
    if (values.keys.any { it.startsWith("ARES/GamePiecesFrame/") }) {
        return GamePieceFrameAccumulator.decodeSnapshot(values, strings).orEmpty()
    }
    return LegacyGamePieceTelemetry.decodeSnapshot(values, strings)
}

/** Loads complete recorded poses before bounded sampling, ending at the replay playhead. */
internal suspend fun loadReplayFieldTrace(
    database: DatabaseService,
    sessionId: String,
    startMs: Long,
    endMs: Long,
    maxPoints: Int = 300,
): List<Waypoint> {
    if (sessionId.isBlank() || endMs < startMs) return emptyList()
    return database.getReplayPoseTrace(sessionId, startMs, endMs, maxPoints)
        .map { Waypoint(it.x, it.y, it.headingRadians) }
}
