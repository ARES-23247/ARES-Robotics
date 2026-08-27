package com.areslib.subsystem

import com.areslib.math.geometry.Pose2d

/**
 * Platform-independent drivetrain interface supporting generic coordinate drive calculations.
 */
interface DrivetrainSubsystem : Subsystem {
    /**
     * Commands the drivetrain to execute specific Cartesian linear and angular speed targets.
     */
    fun setChassisSpeeds(vx: Double, vy: Double, omega: Double)

    /**
     * Retrieves the current EKF-fused absolute position coordinates.
     */
    fun getEstimatedPose(): Pose2d
}
