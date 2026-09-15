package com.areslib.pathing

import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SplineBoundaryAuditTest {
    // Discard returned paths so a failed assertion never prints thousands of path samples.
    private fun rejects(block: () -> Unit) { assertFailsWith<IllegalArgumentException>(block = block) }
    private fun data(anchors: List<Translation2d> = listOf(Translation2d(), Translation2d(1.0, 0.0))) =
        PathPlannerJsonParser.ParsedPathData(anchors.map { PathPlannerJsonParser.WaypointData(it, it, it) },
            2.0, 1.0, 0.0, null, 0.0, null, emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun `parsed spline budget includes the initial sample across all segments`() {
        val anchors = List(6) { Translation2d(if (it % 2 == 0) -500.0 else 500.0, 0.0) }
        rejects { SplineMotionProfiler.buildProfiledPath(data(anchors)) }
    }

    @Test fun `Hermite spline rejects cumulative sample budget overflow`() {
        val anchors = List(4) { Translation2d(if (it % 2 == 0) -1000.0 else 1000.0, if (it % 2 == 0) -1000.0 else 1000.0) }
        rejects {
            SplineMotionProfiler.generateHermitePath(anchors, Rotation2d(), Rotation2d(), 2.0, 1.0)
        }
    }

    @Test fun `oversized direct waypoint containers fail before element access`() {
        val anchors = object : AbstractList<Translation2d>() {
            override val size = 513
            override fun get(index: Int): Translation2d = error("must check count first")
        }
        rejects {
            SplineMotionProfiler.generateHermitePath(anchors, Rotation2d(), Rotation2d(), 2.0, 1.0)
        }
    }

    @Test fun `direct Hermite calls reject invalid motion limits coordinates and headings`() {
        val points = listOf(Translation2d(), Translation2d(1.0, 0.0))
        for (limit in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            rejects { SplineMotionProfiler.generateHermitePath(points, Rotation2d(), Rotation2d(), limit, 1.0) }
            rejects { SplineMotionProfiler.generateHermitePath(points, Rotation2d(), Rotation2d(), 1.0, limit) }
        }
        rejects { SplineMotionProfiler.generateHermitePath(points, Rotation2d(Double.NaN), Rotation2d(), 1.0, 1.0) }
        rejects {
            SplineMotionProfiler.generateHermitePath(listOf(Translation2d(Double.NaN, 0.0), points[1]), Rotation2d(), Rotation2d(), 1.0, 1.0)
        }
    }

    @Test fun `direct parsed calls reject invalid controls speed and metadata`() {
        val base = data()
        val invalid = listOf(
            base.copy(defaultMaxVel = Double.NaN), base.copy(defaultMaxAccel = -1.0),
            base.copy(startVel = -1.0), base.copy(endRotDeg = Double.NaN),
            base.copy(waypoints = listOf(base.waypoints[0].copy(nextControl = Translation2d(Double.NaN, 0.0)), base.waypoints[1])),
            base.copy(rotationTargets = listOf(PathPlannerJsonParser.ParsedRotationTarget(2.0, 0.0))),
            base.copy(constraintZones = listOf(PathPlannerJsonParser.ParsedConstraintsZone(0.8, 0.2, 1.0, 1.0))),
            base.copy(pointTowardsZones = listOf(PathPlannerJsonParser.ParsedPointTowardsZone(0.0, 1.0, Double.NaN, 0.0, 0.0))),
            base.copy(eventMarkers = listOf(PathPlannerJsonParser.ParsedEventMarker(-1.0, "event")))
        )
        for (path in invalid) rejects { SplineMotionProfiler.buildProfiledPath(path) }
    }

    @Test fun `ordinary and degenerate inputs preserve existing result conventions`() {
        assertTrue(SplineMotionProfiler.buildProfiledPath(data(emptyList())).points.isEmpty())
        assertTrue(SplineMotionProfiler.generateHermitePath(emptyList(), Rotation2d(), Rotation2d(), 1.0, 1.0).points.isEmpty())
        assertEquals(1, SplineMotionProfiler.buildProfiledPath(data(listOf(Translation2d()))).points.size)
        val path = SplineMotionProfiler.buildProfiledPath(data())
        assertEquals(21, path.points.size)
        assertEquals(1.0, path.points.last().distanceMeters, 1e-12)
        val constant = SplineMotionProfiler.generateHermitePath(List(3) { Translation2d(2.0, 1.0) }, Rotation2d(), Rotation2d(), 1.0, 1.0)
        assertTrue(constant.points.all { it.velocityMps in 0.0..1e-6 && it.distanceMeters in 0.0..1e-12 })
    }

    @Test fun `nested named command data from PathPlanner is supported`() {
        val json = """{"waypoints":[{"anchor":{"x":0,"y":0}},{"anchor":{"x":1,"y":0}}],"eventMarkers":[{"waypointRelativePos":0.5,"command":{"type":"named","data":{"name":"intake"}}}]}"""
        val path = PathPlannerParser.parsePath(json)
        assertEquals("intake", path.events.single().eventName)
        assertEquals(0.5, path.events.single().triggerDistanceMeters, 1e-9)
    }

    @Test fun `generated path facade rejects raw invalid headings before normalization`() {
        val points = listOf(Translation2d(), Translation2d(1.0, 0.0))
        for (angle in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejects { PathPlannerParser.generatePath(points, Rotation2d(angle), Rotation2d()) }
            rejects { PathPlannerParser.generatePath(points, Rotation2d(), Rotation2d(angle)) }
        }
    }

    @Test fun `sample plan checks its exact budget without materializing samples`() {
        val plan = splineSamplePlan(2) { Translation2d(it * (99999.25 * 0.05), 0.0) }
        assertEquals(100000, plan.totalSamples)
        assertEquals(99999, plan.steps.single())
        rejects { splineSamplePlan(2) { Translation2d(it * 5000.0, 0.0) } }
        rejects { splineSamplePlan(2) { Translation2d(if (it == 0) 0.0 else Double.MAX_VALUE, 0.0) } }
    }

    @Test fun `metadata bounds are checked before entries and direct coordinates stay bounded`() {
        val oversized = object : AbstractList<PathPlannerJsonParser.ParsedRotationTarget>() {
            override val size = 2049
            override fun get(index: Int): PathPlannerJsonParser.ParsedRotationTarget = error("must check metadata count first")
        }
        rejects { SplineMotionProfiler.buildProfiledPath(data().copy(rotationTargets = oversized)) }
        rejects { SplineMotionProfiler.buildProfiledPath(data(listOf(Translation2d(), Translation2d(1001.0, 0.0)))) }
        rejects { SplineMotionProfiler.buildProfiledPath(data().copy(eventMarkers = listOf(PathPlannerJsonParser.ParsedEventMarker(0.0, " ")))) }
    }

    @Test fun `direct endpoint speed ceilings may exceed the local speed cap`() {
        val path = SplineMotionProfiler.buildProfiledPath(data().copy(startVel = 10.0, endVel = 10.0))
        assertEquals(2.0, path.points.first().velocityMps)
        assertEquals(2.0, path.points.last().velocityMps)
        assertTrue(path.points.all { it.velocityMps in 0.0..2.0 })
    }

    @Test fun `nested command data does not fall back to a conflicting or malformed legacy name`() {
        fun parse(command: String) = PathPlannerParser.parsePath(
            """{"waypoints":[{"anchor":{"x":0,"y":0}},{"anchor":{"x":1,"y":0}}],"eventMarkers":[{"waypointRelativePos":0.5,"command":$command}]}""")
        for (command in listOf(
            """{"type":"named","name":"old","data":{"name":"new"}}""",
            """{"type":"named","name":"old","data":{}}""",
            """{"type":"named","data":null}""",
            """{"type":"named","data":{"name":4}}""",
            """{"type":"sequential","data":{"commands":[]}}"""
        )) rejects { parse(command) }
        assertEquals("same", parse("""{"type":"named","name":"same","data":{"name":"same"}}""").events.single().eventName)
    }
}
