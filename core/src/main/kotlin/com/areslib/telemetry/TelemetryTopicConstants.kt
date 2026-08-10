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
}

/**
 * Resolves legacy or non-standard telemetry keys into standard ARESLib topic paths.
 */
object TelemetryTopicNormalizer {
    fun normalizeTopic(key: String): String {
        val cleanKey = key.trimStart('/')
        return when (cleanKey) {
            "Drive/Drive_Heading" -> TelemetryTopicConstants.DRIVE_POSE_HEADING
            "pinpoint_x", "pinpoint/x" -> TelemetryTopicConstants.DRIVE_ODOM_X
            "pinpoint_y", "pinpoint/y" -> TelemetryTopicConstants.DRIVE_ODOM_Y
            "pinpoint_heading", "pinpoint/heading" -> TelemetryTopicConstants.DRIVE_ODOM_HEADING
            "Vision/Pose/X" -> TelemetryTopicConstants.VISION_POSE_X
            "Vision/Pose/Y" -> TelemetryTopicConstants.VISION_POSE_Y
            "Vision/Pose/Heading" -> TelemetryTopicConstants.VISION_POSE_HEADING
            "SysId_Data", "sysid_data" -> "SysId/Data"
            else -> cleanKey
        }
    }
}
