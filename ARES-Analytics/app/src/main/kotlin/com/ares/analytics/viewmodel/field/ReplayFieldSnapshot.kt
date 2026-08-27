package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.robotLightingTelemetry
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.LivePoseState

/** Reconstructs field layers from one atomic replay snapshot without inventing missing sources. */
internal fun ReplayFrame.toReplayPoseState(): LivePoseState {
    val frameValues = values
    val packed = (0..9).map { values["ARES/SimulatorPoseFrame/$it"] }
    val hasPackedPose = packed.all { it != null }
    val hasTrueScalars = (0..2).all { values["ARES/TruePose/$it"] != null }
    val visionHasTarget = (values["Vision/HasTarget"] ?: 0.0) > 0.5
    val visionPoses = values.entries.mapNotNull { (key, value) ->
        when {
            key.startsWith("Vision/PoseArray/") || key.startsWith("AdvantageScope/VisionPose/") ->
                key.substringAfterLast('/').toIntOrNull()?.let { it to value }
            else -> null
        }
    }.toMap()
    val lighting = robotLightingTelemetry(frameValues)
    val pieceIndices = values.keys.asSequence()
        .filter { it.startsWith("ARES/GamePieces/") }
        .mapNotNull { it.substringAfterLast('/').toIntOrNull()?.div(7) }
        .distinct()
        .toList()
    val gamePieces = pieceIndices.associateWith { index ->
        GamePiece(
            id = index.toString(),
            name = "Piece $index",
            x = values["ARES/GamePieces/${index * 7}"] ?: 0.0,
            y = values["ARES/GamePieces/${index * 7 + 1}"] ?: 0.0,
            type = "Game piece",
        )
    }

    return LivePoseState(
        trueX = when {
            hasPackedPose -> requireNotNull(packed[0])
            hasTrueScalars -> requireNotNull(values["ARES/TruePose/0"])
            else -> 0.0
        },
        trueY = when {
            hasPackedPose -> requireNotNull(packed[1])
            hasTrueScalars -> requireNotNull(values["ARES/TruePose/1"])
            else -> 0.0
        },
        trueHeading = when {
            hasPackedPose -> requireNotNull(packed[2])
            hasTrueScalars -> requireNotNull(values["ARES/TruePose/2"])
            else -> 0.0
        },
        simHeading = packed[2],
        hasTruePoseData = hasPackedPose || hasTrueScalars,
        ekfX = if (hasPackedPose) packed[3] else values["ARES/EstimatedPose/0"] ?: values["Drive/Pose_X"],
        ekfY = if (hasPackedPose) packed[4] else values["ARES/EstimatedPose/1"] ?: values["Drive/Pose_Y"],
        ekfHeading = if (hasPackedPose) packed[5] else values["ARES/EstimatedPose/2"]
            ?: values["Drive/Pose_Heading"] ?: values["Drive/Drive_Heading"],
        odomX = if (hasPackedPose) packed[6] else values["Drive/Odom_X"],
        odomY = if (hasPackedPose) packed[7] else values["Drive/Odom_Y"],
        odomHeading = if (hasPackedPose) packed[8] else values["Drive/Odom_Heading"],
        visionX = values["Vision/Pose_X"].takeIf { visionHasTarget },
        visionY = values["Vision/Pose_Y"].takeIf { visionHasTarget },
        visionHeading = values["Vision/Pose_Heading"].takeIf { visionHasTarget },
        visionPoses = if (visionHasTarget) visionPoses else emptyMap(),
        visionHasTarget = visionHasTarget,
        liveGamePieces = gamePieces,
        isConnected = true,
        indicatorLights = lighting.indicatorOutputs,
        prismLights = lighting.prismOutputs,
    )
}

/** Loads a bounded, source-consistent field trace ending at the replay playhead. */
internal suspend fun loadReplayFieldTrace(
    database: DatabaseService,
    sessionId: String,
    startMs: Long,
    endMs: Long,
    maxPoints: Int = 300,
): List<Waypoint> {
    if (sessionId.isBlank() || endMs < startMs) return emptyList()
    val sourceGroups = listOf(
        Triple("ARES/SimulatorPoseFrame/0", "ARES/SimulatorPoseFrame/1", "ARES/SimulatorPoseFrame/2"),
        Triple("ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2"),
        Triple("ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2"),
        Triple("Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading"),
    )
    for ((xKey, yKey, headingKey) in sourceGroups) {
        val x = database.getTelemetrySeries(sessionId, xKey, startMs, endMs, maxPoints)
        if (x.isEmpty()) continue
        val yByTimestamp = database.getTelemetrySeries(sessionId, yKey, startMs, endMs, maxPoints)
            .associateBy { it.timestampUs to it.sampleOrder }
        val headingByTimestamp = database.getTelemetrySeries(sessionId, headingKey, startMs, endMs, maxPoints)
            .associateBy { it.timestampUs to it.sampleOrder }
        val exact = x.mapNotNull { xFrame ->
            val identity = xFrame.timestampUs to xFrame.sampleOrder
            val yFrame = yByTimestamp[identity] ?: return@mapNotNull null
            val headingFrame = headingByTimestamp[identity] ?: return@mapNotNull null
            Waypoint(xFrame.value, yFrame.value, headingFrame.value)
        }
        if (exact.isNotEmpty()) return exact

        // Many producers assign a distinct sampleOrder to each scalar within one atomic source
        // timestamp. Joining by timestamp_us remains deterministic and never crosses instants.
        val yByTime = yByTimestamp.values.associateBy { it.timestampUs }
        val headingByTime = headingByTimestamp.values.associateBy { it.timestampUs }
        val bySourceTime = x.mapNotNull { xFrame ->
            val yFrame = yByTime[xFrame.timestampUs] ?: return@mapNotNull null
            val headingFrame = headingByTime[xFrame.timestampUs] ?: return@mapNotNull null
            Waypoint(xFrame.value, yFrame.value, headingFrame.value)
        }
        if (bySourceTime.isNotEmpty()) return bySourceTime
    }
    return emptyList()
}
