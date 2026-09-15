package com.areslib.pathing

import com.areslib.math.geometry.*
import com.areslib.math.wrapAngle
import kotlin.math.*
import kotlin.test.*

class SplineHeadingScaleAuditTest {
    private fun straight(length: Double = 1.0) = PathPlannerJsonParser.ParsedPathData(
        waypoints = listOf(
            PathPlannerJsonParser.WaypointData(Translation2d(), Translation2d(), Translation2d(length / 3.0, 0.0)),
            PathPlannerJsonParser.WaypointData(Translation2d(length, 0.0), Translation2d(2.0 * length / 3.0, 0.0), Translation2d(length, 0.0)),
        ), defaultMaxVel = 2.0, defaultMaxAccel = 1.0, startVel = 0.0, startRotDeg = 0.0,
        endVel = 0.0, endRotDeg = 90.0, rotationTargets = emptyList(), constraintZones = emptyList(),
        pointTowardsZones = emptyList(), eventMarkers = emptyList(),
    )
    private fun generated(length: Double) = SplineMotionProfiler.generateHermitePath(
        listOf(Translation2d(), Translation2d(length, 0.0)), Rotation2d(), Rotation2d(PI / 2.0), 2.0, 1.0,
    )

    @Test fun `positive generated travel retains heading regardless of physical scale`() {
        for (length in listOf(5e-7, 1e-12, 1e-240)) {
            val path = generated(length)
            assertEquals(PI / 2.0, path.points.last().pose.heading.radians, 1e-14, "end heading at $length meters")
            assertEquals(PI / 4.0, path.points[path.points.size / 2].pose.heading.radians, 1e-14)
        }
    }

    @Test fun `parsed short path interpolates rather than deferring all rotation to its endpoint`() {
        val tiny = SplineMotionProfiler.buildProfiledPath(straight(5e-7))
        assertEquals(PI / 4.0, tiny.points[5].pose.heading.radians, 1e-14)
        val ordinary = SplineMotionProfiler.buildProfiledPath(straight(0.1))
        tiny.points.zip(ordinary.points).forEach { (small, large) ->
            assertEquals(large.pose.heading.radians, small.pose.heading.radians, 1e-14)
        }
    }

    @Test fun `early cosine ease retains small representable heading progress`() {
        val position = 1e-10
        val path = SplineMotionProfiler.buildProfiledPath(straight().copy(constraintZones = listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(position, position, 2.0, 1.0),
        )))
        val early = path.points.first { it.distanceMeters > 0.0 }
        val t = early.distanceMeters / path.points.last().distanceMeters
        assertTrue(t < 1e-9)
        // Taylor leading term (pi*t)^2/4; omitted fourth-order relative error < 1e-18.
        val expected = (PI / 2.0) * (PI * t) * (PI * t) / 4.0
        assertTrue(early.pose.heading.radians > 0.0)
        assertEquals(1.0, early.pose.heading.radians / expected, 1e-13)
    }

    @Test fun `large finite authored angle is normalized before interpolation arithmetic`() {
        val path = SplineMotionProfiler.buildProfiledPath(straight(0.1).copy(startRotDeg = 1e300, endRotDeg = 90.0))
        val start = path.points.first().pose.heading.radians
        val end = path.points.last().pose.heading.radians
        val expected = wrapAngle(start + wrapAngle(end - start) / 2.0)
        assertEquals(0.0, wrapAngle(path.points[5].pose.heading.radians - expected), 1e-14)
    }

    @Test fun `large point-towards offset does not erase the target bearing`() {
        val offset = 1e300
        val path = SplineMotionProfiler.buildProfiledPath(straight().copy(pointTowardsZones = listOf(
            PathPlannerJsonParser.ParsedPointTowardsZone(minWaypointRelativePos = 0.0, maxWaypointRelativePos = 1.0, rotationOffset = offset, x = 1.0, y = 1.0),
        )))
        val expected = wrapAngle(wrapAngle(Math.toRadians(offset)) + PI / 4.0)
        assertEquals(0.0, wrapAngle(path.points.first().pose.heading.radians - expected), 1e-14)
    }

    @Test fun `stationary generated path retains its goal orientation at every sample`() {
        val path = generated(0.0)
        assertTrue(path.points.isNotEmpty())
        path.points.forEach {
            assertEquals(0.0, it.distanceMeters)
            assertEquals(0.0, it.velocityMps)
            assertEquals(PI / 2.0, it.pose.heading.radians, 1e-14)
        }
    }

    @Test fun `shortest arc remains continuous across the principal angle boundary`() {
        val path = SplineMotionProfiler.generateHermitePath(listOf(Translation2d(), Translation2d(1e-8, 0.0)),
            Rotation2d(Math.toRadians(170.0)), Rotation2d(Math.toRadians(-170.0)), 2.0, 1.0)
        assertEquals(0.0, wrapAngle(path.points[5].pose.heading.radians - PI), 1e-14)
        assertTrue(path.points.zipWithNext().all { (a,b) -> wrapAngle(b.pose.heading.radians-a.pose.heading.radians) >= 0.0 })
    }
}
