package com.areslib.telemetry

/**
 * Centralized topic string constants and canonical topic normalization rules for ARESLib.
 */
object TelemetryTopicConstants {
    const val DRIVE_POSE_X = "Drive/Pose_X"
    const val DRIVE_POSE_Y = "Drive/Pose_Y"
    const val DRIVE_POSE_HEADING = "Drive/Pose_Heading"

    const val DRIVE_ODOM_X = "Drive/Odom_X"
    const val DRIVE_ODOM_Y = "Drive/Odom_Y"
    const val DRIVE_ODOM_HEADING = "Drive/Odom_Heading"

    const val VISION_POSE_X = "Vision/Pose_X"
    const val VISION_POSE_Y = "Vision/Pose_Y"
    const val VISION_POSE_HEADING = "Vision/Pose_Heading"

    const val ESTIMATED_POSE_X = "ARES/EstimatedPose/0"
    const val ESTIMATED_POSE_Y = "ARES/EstimatedPose/1"
    const val ESTIMATED_POSE_HEADING = "ARES/EstimatedPose/2"

    /** Atomic v2 command: version, session, sequence, client monotonic ms, vx, vy, omega, flags. */
    const val DRIVE_INPUT_FRAME = "ARES/Input/driveFrame"

    /** Packed seven-double records; consumers must honor [GAME_PIECES_COUNT]. */
    const val GAME_PIECES = "ARES/GamePieces"
    /** Number of live records in [GAME_PIECES], including the explicit zero/removal state. */
    const val GAME_PIECES_COUNT = "ARES/GamePieces/Count"
}

/** Removes transport-only leading slashes from an ARES telemetry topic. */
object TelemetryTopicNormalizer {
    fun normalizeTopic(key: String): String = key.trimStart('/')

    /** Converts a canonical ARES key to the single-root form used in NT4 wire announcements. */
    fun toWireTopic(key: String): String = "/${normalizeTopic(key)}"
}
