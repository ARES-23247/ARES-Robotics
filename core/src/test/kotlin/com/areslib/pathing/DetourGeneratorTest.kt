package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetourGeneratorTest {

    @Test
    fun `test detour generator creates smooth transition spline to target path`() {
        // Target detour path: starts at (1, 1), ends at (4, 1)
        val points = listOf(
            PathPoint(Pose2d(1.0, 1.0, Rotation2d.fromDegrees(0.0)), 1.5, 0.0),
            PathPoint(Pose2d(2.5, 1.0, Rotation2d.fromDegrees(0.0)), 1.5, 1.5),
            PathPoint(Pose2d(4.0, 1.0, Rotation2d.fromDegrees(0.0)), 1.5, 3.0)
        )
        val events = listOf(PathEvent("DetourAction", 2.0))
        val targetPath = Path(points, events)

        // Current robot pose is off-path (e.g. at (0, 0) facing 45 degrees, velocity is 0.8 mps)
        val currentPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(45.0))
        val currentVel = 0.8

        val detourPath = DetourGenerator.generateTangentArc(
            startPose = currentPose,
            currentVelMps = currentVel,
            targetPath = targetPath,
            interceptLookaheadMeters = 1.0,
            maxVelocityMps = 2.0
        )

        // 1. Verify start pose is exactly the robot's active real-time state
        val startPoint = detourPath.points.first()
        assertEquals(0.0, startPoint.pose.x, 1e-4)
        assertEquals(0.0, startPoint.pose.y, 1e-4)
        assertEquals(45.0, Math.toDegrees(startPoint.pose.heading.radians), 1e-4)
        assertEquals(currentVel, startPoint.velocityMps, 1e-4)

        // 2. Verify transition points seamlessly blend into the target detour path
        assertTrue(detourPath.points.size > 15, "Should have transition points plus remaining detour points")

        // 3. Verify final point of detour path is target path's final point
        val endPoint = detourPath.points.last()
        assertEquals(4.0, endPoint.pose.x, 0.1)
        assertEquals(1.0, endPoint.pose.y, 0.1)
        assertEquals(0.0, Math.toDegrees(endPoint.pose.heading.radians), 0.1)

        // 4. Verify path events on the target path are preserved and shifted correctly
        val shiftedEvents = detourPath.events
        assertEquals(1, shiftedEvents.size)
        assertEquals("DetourAction", shiftedEvents.first().eventName)
        assertTrue(shiftedEvents.first().triggerDistanceMeters > 2.0, "Event distance should be shifted by transition length")
    }
}
