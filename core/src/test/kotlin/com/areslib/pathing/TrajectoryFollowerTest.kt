package com.areslib.pathing

import com.areslib.control.drivetrain.HolonomicDriveController
import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrajectoryFollowerTest {

    @Test
    fun testClosedLoopTrajectoryTracking() {
        // 1. Create a mock PathPlanner path (Start at 2.0, 2.0, end at 8.0, 6.0)
        val mockJson = """
            {
              "waypoints": [
                {"anchor": {"x": 2.0, "y": 2.0}},
                {"anchor": {"x": 8.0, "y": 6.0}}
              ]
            }
        """.trimIndent()

        val path = PathPlannerParser.parsePath(mockJson)
        assertNotNull(path)
        assertTrue(path.points.size > 0)

        // 2. Initialize our HolonomicDriveController
        val driveController = HolonomicDriveController(
            PIDController(4.0, 0.0, 0.1),
            PIDController(4.0, 0.0, 0.1),
            PIDController(3.0, 0.0, 0.0)
        )

        // 3. Current pose: starting point (2.0, 2.0)
        val currentPose = Pose2d(2.0, 2.0, Rotation2d.fromDegrees(0.0))

        // 4. Target point along the path (e.g. halfway, which is closer to 5.0, 4.0)
        val targetPoint = path.sampleAtDistance(path.points.last().distanceMeters / 2.0)
        assertTrue(targetPoint.pose.x > 2.0)
        assertTrue(targetPoint.pose.y > 2.0)

        // 5. Calculate velocities
        val speeds = driveController.calculate(
            currentPose = currentPose,
            targetPose = targetPoint.pose,
            targetVelocityMps = targetPoint.velocityMps,
            targetHeading = targetPoint.pose.heading,
            dtSeconds = 0.02
        )

        // 6. Verify that closed-loop PID is producing corrective velocity output towards the target coordinates
        assertTrue(speeds.vxMetersPerSecond > 0.0, "vx should be positive to drive forward towards target: ${speeds.vxMetersPerSecond}")
        assertTrue(speeds.vyMetersPerSecond > 0.0, "vy should be positive to drive upward towards target: ${speeds.vyMetersPerSecond}")
    }
}
