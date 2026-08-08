package com.areslib.util

import com.areslib.math.geometry.Pose2d

/**
 * Global static storage for persisting robot pose between Autonomous and TeleOp.
 */
object PoseStorage {
    @JvmStatic
    var currentPose: Pose2d = Pose2d()

    @JvmStatic
    var hasValidPose: Boolean = false

    @JvmStatic
    var alliance: com.areslib.state.Alliance = com.areslib.state.Alliance.RED

    @JvmStatic
    fun clear() {
        currentPose = Pose2d()
        hasValidPose = false
    }
}
