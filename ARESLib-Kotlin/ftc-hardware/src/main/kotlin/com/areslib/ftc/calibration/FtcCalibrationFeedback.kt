package com.areslib.ftc.calibration

/** Cached motion validity shared by calibration control and telemetry. */
internal object FtcCalibrationFeedback {
    fun validDriveFeedback(drive: com.areslib.state.DriveState, timestamp: Long): Boolean {
        val pose = drive.poseEstimator
        val observedAt = pose.lastObservationTimestampMs
        val ageMs = timestamp - observedAt
        return drive.measuredMotionValid && observedAt >= 0L && timestamp >= observedAt &&
            ageMs >= 0L && ageMs <= MAX_SYSID_MOTION_AGE_MS && drive.odometryHeading.isFinite() &&
            drive.measuredFieldXVelocityMetersPerSecond.isFinite() &&
            drive.measuredFieldYVelocityMetersPerSecond.isFinite() &&
            drive.measuredAngularVelocityRadiansPerSecond.isFinite() &&
            pose.estimatedPoseX.isFinite() && pose.estimatedPoseY.isFinite() && pose.estimatedPoseHeading.isFinite()
    }

    private const val MAX_SYSID_MOTION_AGE_MS = 100L
}
