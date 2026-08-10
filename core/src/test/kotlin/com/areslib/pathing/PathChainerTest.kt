package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathChainerTest {

    @Test
    fun `test path chainer stitches two linear paths smoothly`() {
        // Path A: (0,0) -> (2,0)
        val pointsA = listOf(
            PathPoint(Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0)), 1.5, 0.0),
            PathPoint(Pose2d(1.0, 0.0, Rotation2d.fromDegrees(0.0)), 1.5, 1.0),
            PathPoint(Pose2d(2.0, 0.0, Rotation2d.fromDegrees(0.0)), 1.5, 2.0)
        )
        val eventsA = listOf(PathEvent("EventA", 0.5))
        val pathA = Path(pointsA, eventsA)

        // Path B: (2,0) -> (4,2)
        val pointsB = listOf(
            PathPoint(Pose2d(2.0, 0.0, Rotation2d.fromDegrees(45.0)), 2.0, 0.0),
            PathPoint(Pose2d(3.0, 1.0, Rotation2d.fromDegrees(45.0)), 2.0, 1.414),
            PathPoint(Pose2d(4.0, 2.0, Rotation2d.fromDegrees(45.0)), 2.0, 2.828)
        )
        val eventsB = listOf(PathEvent("EventB", 1.0))
        val pathB = Path(pointsB, eventsB)

        val chained = PathChainer.chainPaths(listOf(pathA, pathB), maxVelocityMps = 2.0, maxAccelerationMps2 = 1.5)

        // 1. Verify stitching structure
        assertTrue(chained.points.size > 2, "Chained path should contain stitched points")
        assertEquals(0.0, chained.points.first().pose.x, 1e-3)
        assertEquals(4.0, chained.points.last().pose.x, 1e-3)
        assertEquals(2.0, chained.points.last().pose.y, 1e-3)

        // 2. Verify cumulative distance scaling
        val expectedTotalDist = 2.0 + 2.828
        assertEquals(expectedTotalDist, chained.points.last().distanceMeters, 0.1)

        // 3. Verify event shifting
        assertEquals(2, chained.events.size)
        assertEquals("EventA", chained.events[0].eventName)
        assertEquals(0.5, chained.events[0].triggerDistanceMeters)
        assertEquals("EventB", chained.events[1].eventName)
        assertEquals(2.0 + 1.0, chained.events[1].triggerDistanceMeters)

        // 4. Verify velocity profile is bounded and smooth (trapezoidal profiling)
        assertTrue(chained.points.first().velocityMps < 1.0, "Start velocity should ramp down for safety or start from zero")
        assertEquals(0.0, chained.points.last().velocityMps, 1e-3, "End velocity must decelerate to 0.0")

        // 5. Verify joint transition heading is blended smoothly
        val jointPoint = chained.sampleAtDistance(2.0)
        val degrees = Math.toDegrees(jointPoint.pose.heading.radians)
        assertTrue(degrees > 0.0 && degrees < 45.0, 
            "Heading should be blended smoothly at joint: $degrees")
    }
}
