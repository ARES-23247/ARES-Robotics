package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.random.Random
import kotlin.math.roundToInt

class SplineConstraintAuditTest {
    private fun straight(zones: List<PathPlannerJsonParser.ParsedConstraintsZone>) =
        PathPlannerJsonParser.ParsedPathData(
            listOf(
                PathPlannerJsonParser.WaypointData(Translation2d(), Translation2d(), Translation2d(1.0 / 3.0, 0.0)),
                PathPlannerJsonParser.WaypointData(Translation2d(1.0, 0.0), Translation2d(2.0 / 3.0, 0.0), Translation2d(1.0, 0.0))
            ), 2.0, 1.0, 2.0, null, 2.0, null, emptyList(), zones, emptyList(), emptyList())

    @Test fun `speed zone between regular samples constrains the entire interval`() {
        val path = SplineMotionProfiler.buildProfiledPath(straight(listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.501, 0.509, 0.1, 1.0))))
        for (distance in listOf(0.501, 0.503, 0.505, 0.509)) {
            assertTrue(path.sampleAtDistance(distance).velocityMps <= 0.1 + 1e-10, "missed speed zone at $distance")
        }
    }

    @Test fun `narrow acceleration zone has explicit edges obeying its energy bound`() {
        val zone = PathPlannerJsonParser.ParsedConstraintsZone(0.501, 0.509, 4.0, 0.1)
        val path = SplineMotionProfiler.buildProfiledPath(straight(listOf(zone)).copy(
            defaultMaxVel = 4.0, defaultMaxAccel = 10.0, startVel = 0.0, endVel = 4.0))
        val inside = path.points.filter { it.pose.x in 0.501 - 1e-12..0.509 + 1e-12 }
        assertTrue(inside.size >= 2, "zone needs both boundary samples")
        for ((a, b) in inside.zipWithNext()) {
            val deltaEnergy = b.velocityMps * b.velocityMps - a.velocityMps * a.velocityMps
            assertTrue(deltaEnergy <= 2.0 * 0.1 * (b.distanceMeters - a.distanceMeters) + 1e-10)
        }
    }

    @Test fun `zero width speed constraint is retained between regular samples`() {
        val path = SplineMotionProfiler.buildProfiledPath(straight(listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.503, 0.503, 0.2, 1.0))))
        assertTrue(path.sampleAtDistance(0.503).velocityMps <= 0.2 + 1e-10)
    }

    @Test fun `aligned boundaries and duplicate zones preserve regular sample layout`() {
        val zone = PathPlannerJsonParser.ParsedConstraintsZone(0.5, 0.75, 0.3, 1.0)
        val regular = SplineMotionProfiler.buildProfiledPath(straight(emptyList()))
        val constrained = SplineMotionProfiler.buildProfiledPath(straight(listOf(zone, zone)))
        assertEquals(21, constrained.points.size)
        assertEquals(regular.points.map { it.pose.x }, constrained.points.map { it.pose.x })
    }

    @Test fun `overlap priority transitions constrain both sides of the connecting edge`() {
        val zones = listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.501, 0.509, 3.0, 1e9),
            PathPlannerJsonParser.ParsedConstraintsZone(0.503, 0.52, 0.1, 1e9))
        val path = SplineMotionProfiler.buildProfiledPath(straight(zones).copy(
            defaultMaxVel = 4.0, defaultMaxAccel = 1e9, startVel = 4.0, endVel = 4.0))
        for (distance in listOf(0.51, 0.515, 0.519)) {
            assertTrue(path.sampleAtDistance(distance).velocityMps <= 0.1 + 1e-10, "missed overlap transition at $distance")
        }
    }

    @Test fun `leaving a faster zone restores the global cap across the next edge`() {
        val path = SplineMotionProfiler.buildProfiledPath(straight(listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.4, 0.509, 4.0, 1e9))).copy(defaultMaxAccel = 1e9))
        for (distance in listOf(0.51, 0.52, 0.54)) {
            assertTrue(path.sampleAtDistance(distance).velocityMps <= 2.0 + 1e-10, "missed global cap at $distance")
        }
    }

    @Test fun `zone sweep agrees with first match oracle for unsorted overlapping intervals`() {
        val random = Random(97)
        repeat(25) {
            val zones = List(40) {
                val start = random.nextInt(101) / 100.0
                val end = start + random.nextInt(101 - (start * 100).roundToInt()) / 100.0
                PathPlannerJsonParser.ParsedConstraintsZone(start, end, 1.0, 1.0)
            }
            val cursor = SplineConstraintCursor(java.util.LinkedList(zones))
            val queries = (List(401) { it / 400.0 } + zones.flatMap { listOf(it.minWaypointRelativePos, it.maxWaypointRelativePos) }).distinct().sorted()
            for (position in queries) {
                assertSame(zones.firstOrNull { position >= it.minWaypointRelativePos && position <= it.maxWaypointRelativePos }, cursor.at(position))
                assertSame(zones.firstOrNull { position >= it.minWaypointRelativePos && position < it.maxWaypointRelativePos }, cursor.at(position, after = true))
            }
        }
    }

    @Test fun `zone sweep expires hidden intervals and rejects backward traversal`() {
        val a = PathPlannerJsonParser.ParsedConstraintsZone(0.1, 0.8, 1.0, 1.0)
        val b = PathPlannerJsonParser.ParsedConstraintsZone(0.2, 0.3, 1.0, 1.0)
        val c = PathPlannerJsonParser.ParsedConstraintsZone(0.4, 0.9, 1.0, 1.0)
        val cursor = SplineConstraintCursor(listOf(a, b, c))
        assertEquals(null, cursor.at(0.0))
        assertSame(a, cursor.at(0.5))
        assertSame(a, cursor.at(0.8))
        assertSame(c, cursor.at(0.85))
        assertEquals(null, cursor.at(1.0))
        assertFailsWith<IllegalArgumentException> { cursor.at(0.5) }
    }

    @Test fun `constraint boundary additions share the total sample budget`() {
        val anchors = listOf(-500.0, 500.0, -500.0, 500.0, -500.0, 499.95)
        val data = straight(emptyList()).copy(waypoints = anchors.map {
            val point = Translation2d(it, 0.0)
            PathPlannerJsonParser.WaypointData(point, point, point)
        })
        val plan = splineSamplePlan(anchors.size) { data.waypoints[it].anchor }
        assertEquals(100000, plan.totalSamples)
        val aligned = data.copy(constraintZones = listOf(PathPlannerJsonParser.ParsedConstraintsZone(0.5, 0.5, 1.0, 1.0)))
        assertEquals(100000, splineRelativePositions(aligned, plan).size)
        assertFailsWith<IllegalArgumentException> {
            SplineMotionProfiler.buildProfiledPath(data.copy(constraintZones = listOf(
                PathPlannerJsonParser.ParsedConstraintsZone(0.00001, 0.00001, 1.0, 1.0))))
            Unit
        }
    }

    @Test fun `multiple segment boundaries are sorted unique and keep incoming knot geometry`() {
        val base = straight(emptyList())
        val end = Translation2d(2.0, 0.0)
        val data = base.copy(waypoints = base.waypoints + PathPlannerJsonParser.WaypointData(end, end, end),
            constraintZones = listOf(
                PathPlannerJsonParser.ParsedConstraintsZone(1.013, 1.017, 0.2, 1.0),
                PathPlannerJsonParser.ParsedConstraintsZone(1.0, 1.013, 0.3, 1.0)))
        val positions = splineRelativePositions(data, splineSamplePlan(3) { data.waypoints[it].anchor })
        assertEquals(43, positions.size)
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        assertTrue(listOf(0.0, 1.0, 1.013, 1.017, 2.0).all { it in positions })
        val path = SplineMotionProfiler.buildProfiledPath(data)
        assertEquals(1, path.points.count { it.pose.x == 1.0 })
        assertEquals(2.0, path.points.last().pose.x)
    }

    @Test fun `sampled speeds satisfy an independent dense first match zone oracle`() {
        val random = Random(197)
        repeat(20) {
            val zones = List(30) {
                val start = random.nextInt(1000) / 1000.0
                val end = minOf(1.0, start + random.nextInt(1, 90) / 1000.0)
                PathPlannerJsonParser.ParsedConstraintsZone(start, end, random.nextDouble(0.05, 4.0), random.nextDouble(0.1, 10.0))
            }
            val path = SplineMotionProfiler.buildProfiledPath(straight(zones))
            val queries = List(1001) { it / 1000.0 } + zones.flatMap {
                listOf(it.minWaypointRelativePos, it.maxWaypointRelativePos, (it.minWaypointRelativePos + it.maxWaypointRelativePos) / 2.0)
            }
            for (position in queries) {
                val limit = zones.firstOrNull { position >= it.minWaypointRelativePos && position <= it.maxWaypointRelativePos }?.maxVelocity ?: 2.0
                assertTrue(path.sampleAtDistance(position).velocityMps <= limit + 1e-8, "speed exceeds $limit at $position")
            }
        }
    }

    @Test fun `adjacent floating point boundaries still apply the intervening global cap`() {
        val a = 0.5
        val b = Math.nextUp(a)
        val zones = listOf(
            PathPlannerJsonParser.ParsedConstraintsZone(0.4, a, 4.0, 1e9),
            PathPlannerJsonParser.ParsedConstraintsZone(b, 0.6, 4.0, 1e9))
        val path = SplineMotionProfiler.buildProfiledPath(straight(zones).copy(defaultMaxAccel = 1e9))
        val boundaries = path.points.filter { it.pose.x in a..b }
        assertTrue(boundaries.size >= 2)
        assertTrue(boundaries.all { it.velocityMps <= 2.0 }, "the real interval between adjacent doubles still has the global limit")
    }
}
