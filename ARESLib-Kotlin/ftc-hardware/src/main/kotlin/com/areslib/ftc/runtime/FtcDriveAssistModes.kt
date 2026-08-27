package com.areslib.ftc.runtime

import com.areslib.state.RobotState

/**
 * OpMode-scoped drive-assist policy shared by generated FTC projects.
 *
 * Mutable switches belong to the lifecycle host, while the observed robot state remains immutable.
 * Position hold fails closed whenever estimator motion feedback is invalid, missing, rewound, or
 * older than the reviewed drivetrain freshness contract.
 */
class FtcDriveAssistModes {
    @Volatile
    var headingLockEnabled: Boolean = true

    @Volatile
    var positionHoldEnabled: Boolean = false

    fun positionHoldAllowed(
        state: RobotState,
        nowMs: Long,
        staleFeedbackTimeoutMs: Long,
    ): Boolean {
        require(staleFeedbackTimeoutMs >= 0L) { "Feedback freshness timeout must be non-negative" }
        if (!positionHoldEnabled || !state.drive.measuredMotionValid) return false
        val observationMs = state.drive.poseEstimator.lastObservationTimestampMs
        if (observationMs < 0L || nowMs < observationMs) return false
        return nowMs - observationMs <= staleFeedbackTimeoutMs
    }
}
