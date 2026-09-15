package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import com.areslib.math.wrapAngle
import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.test.*

class SplineProfileAuditTest {
    private fun straight(length: Double = 1.0) = PathPlannerJsonParser.ParsedPathData(
        waypoints = listOf(
            PathPlannerJsonParser.WaypointData(Translation2d(0.0, 0.0), Translation2d(0.0, 0.0), Translation2d(length / 3.0, 0.0)),
            PathPlannerJsonParser.WaypointData(Translation2d(length, 0.0), Translation2d(2.0 * length / 3.0, 0.0), Translation2d(length, 0.0))
        ),
        defaultMaxVel = 4.0, defaultMaxAccel = 1.0,
        startVel = 0.0, startRotDeg = 170.0, endVel = 0.0, endRotDeg = -170.0,
        rotationTargets = emptyList(), constraintZones = emptyList(),
        pointTowardsZones = emptyList(), eventMarkers = emptyList()
    )

    @Test
    fun `requested endpoint speed cannot undo forward acceleration limits`() {
        val path = SplineMotionProfiler.buildProfiledPath(straight(0.1).copy(endVel = 3.0))
        for ((a, b) in path.points.zipWithNext()) {
            assertTrue(b.velocityMps.pow(2) - a.velocityMps.pow(2) <= 2.0 * (b.distanceMeters - a.distanceMeters) + 1e-10)
        }
        assertTrue(path.points.last().velocityMps <= sqrt(0.2) + 1e-10)
    }

    @Test
    fun `endpoint obeys its local speed zone`() {
        val data = straight().copy(endVel = 3.0, constraintZones = listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.8, 1.0, 0.2, 1.0)))
        assertTrue(SplineMotionProfiler.buildProfiledPath(data).points.last().velocityMps <= 0.2)
    }

    @Test
    fun `leaving an acceleration zone respects both ends of the edge`() {
        val data = straight().copy(defaultMaxAccel = 10.0, constraintZones = listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.0, 0.5, 4.0, 0.1)))
        val points = SplineMotionProfiler.buildProfiledPath(data).points
        val before = points[10]
        val after = points[11]
        val acceleration = (after.velocityMps.pow(2) - before.velocityMps.pow(2)) /
            (2.0 * (after.distanceMeters - before.distanceMeters))
        assertTrue(acceleration <= 0.1 + 1e-9, "Boundary acceleration was $acceleration")
    }

    @Test
    fun `tight curves retain analytical curvature instead of understating it`() {
        val data = straight(0.01)
        val first = data.waypoints.first().copy(nextControl = Translation2d(0.0, 0.01))
        val last = data.waypoints.last().copy(prevControl = Translation2d(0.01, 0.01))
        val points = SplineMotionProfiler.buildProfiledPath(data.copy(waypoints = listOf(first, last))).points
        // Symmetric cubic at t=1/2: x'=1.5L, y'=0, y''=-6L, k=-8/(3L).
        val expected = -8.0 / (3.0 * 0.01)
        assertTrue(abs(expected) > 100.0)
        assertEquals(expected, points[5].curvature, 1e-9)
    }

    @Test
    fun `rotation interpolation preserves shortest arc across wrap`() {
        val points = SplineMotionProfiler.buildProfiledPath(straight()).points
        for (point in points) {
            val expected = Math.toRadians(170.0) + Math.toRadians(20.0) * (1.0 - cos(PI * point.distanceMeters)) / 2.0
            assertEquals(0.0, wrapAngle(point.pose.heading.radians - expected), 1e-10)
        }
    }

    @Test
    fun `rotation targets preserve earlier sample ties and point towards priority`() {
        val data = straight().copy(rotationTargets = listOf(
            PathPlannerJsonParser.ParsedRotationTarget(0.125, 45.0),
            PathPlannerJsonParser.ParsedRotationTarget(0.5, 90.0)),
            pointTowardsZones = listOf(PathPlannerJsonParser.ParsedPointTowardsZone(0.5, 0.5, 0.0, 1.5, 0.0)))
        val points = SplineMotionProfiler.buildProfiledPath(data).points
        assertEquals(PI / 4.0, points[2].pose.heading.radians, 1e-10)
        assertEquals(0.0, points[10].pose.heading.radians, 1e-10)
    }

    @Test
    fun `dense spline construction benchmark preserves heading and endpoint`() {
        val data = straight(400.0)
        repeat(5) { SplineMotionProfiler.buildProfiledPath(straight(5.0)) }
        val durations = LongArray(3)
        repeat(3) { index ->
            val start = System.nanoTime()
            val path = SplineMotionProfiler.buildProfiledPath(data)
            durations[index] = System.nanoTime() - start
            assertEquals(8_001, path.points.size)
            assertEquals(400.0, path.points.last().pose.x)
            assertEquals(0.0, wrapAngle(path.points[4_000].pose.heading.radians - PI), 1e-9)
        }
        durations.sort()
        println("Spline audit: 8,001 samples median=${durations[1] / 1e6} ms (desktop construction, not robot-loop latency)")
    }
}
