package com.areslib.telemetry

/**
 * Centralized topic string constants and canonical topic normalization rules for ARESLib.
 */
object TelemetryTopicConstants {
    /** Monotonic per-publish heartbeat; a changing value proves the robot telemetry loop is alive. */
    const val TELEMETRY_FRAME_SEQUENCE: String = "ARES/Telemetry/FrameSequence"
    /** Selected FTC hub command transport (`STANDARD_SDK` or `ARES_PHOTON`). */
    const val FTC_HUB_COMMAND_TRANSPORT: String = "ARES/Runtime/FTC/HubCommandTransport"
    /** True only when the selected Photon path is active on at least one real REV hub. */
    const val FTC_PHOTON_ACTIVE: String = "ARES/Runtime/FTC/PhotonActive"
    /** Whether the canonical project requested the Control-Hub Limelight proxy. */
    const val FTC_LIMELIGHT_PROXY_CONFIGURED: String = "ARES/Runtime/FTC/LimelightProxyConfigured"
    /** Whether the bounded Limelight proxy currently owns its listener sockets. */
    const val FTC_LIMELIGHT_PROXY_ACTIVE: String = "ARES/Runtime/FTC/LimelightProxyActive"
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
    /** Atomic v2 frame: version, count, typed records, then a changing sequence marker. */
    const val GAME_PIECES_FRAME = "ARES/GamePiecesFrame"

    const val HARDWARE_MOTORS_PREFIX = "Hardware/Motors"
    fun motorVelocityTopic(name: String): String = "$HARDWARE_MOTORS_PREFIX/$name/Velocity"
    fun motorPowerTopic(name: String): String = "$HARDWARE_MOTORS_PREFIX/$name/Power"
    fun motorPositionTopic(name: String): String = "$HARDWARE_MOTORS_PREFIX/$name/Position"
    fun motorCurrentTopic(name: String): String = "$HARDWARE_MOTORS_PREFIX/$name/CurrentAmps"
}

/** Removes transport-only leading slashes from an ARES telemetry topic. */
object TelemetryTopicNormalizer {
    fun normalizeTopic(key: String): String = key.trimStart('/')

    /** Converts a canonical ARES key to the single-root form used in NT4 wire announcements. */
    fun toWireTopic(key: String): String = "/${normalizeTopic(key)}"
}
