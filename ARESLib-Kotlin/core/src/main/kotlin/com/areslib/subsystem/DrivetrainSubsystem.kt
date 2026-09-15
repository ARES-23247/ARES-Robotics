package com.areslib.subsystem

import com.areslib.math.geometry.Pose2d

/**
 * Platform-independent drivetrain interface supporting generic coordinate drive calculations.
 */
interface DrivetrainSubsystem : Subsystem {
    /**
     * Commands robot-relative forward/left velocity in m/s and CCW angular velocity in rad/s.
     * Implementations own configured limits and route intent through the robot's safety pipeline.
     */
    fun setChassisSpeeds(vx: Double, vy: Double, omega: Double)

    /**
     * Retrieves the current EKF-fused absolute position coordinates.
     */
    fun getEstimatedPose(): Pose2d
}
