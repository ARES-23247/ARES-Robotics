package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class SplineStationaryAuditTest {
    private fun data(points: List<Translation2d>) = PathPlannerJsonParser.ParsedPathData(
        listOf(PathPlannerJsonParser.WaypointData(points[0], points[0], points[1]),
            PathPlannerJsonParser.WaypointData(points[3], points[2], points[3])),
        2.0, 1.0, 0.0, null, 0.0, null, emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun `an off grid reversal is explicitly sampled and stopped`() {
        val path = SplineMotionProfiler.buildProfiledPath(data(listOf(
            Translation2d(), Translation2d(1.0, 0.0), Translation2d(1.0, 0.0), Translation2d(-2.0, 0.0))))
        val root = (sqrt(3.0) - 1.0) / 2.0
        val expectedX = 3.0 * root - 0.5
        val cusp = path.points.singleOrNull { abs(it.pose.x - expectedX) < 1e-12 }
        assertTrue(cusp != null, "stationary root is missing from the grid")
        assertEquals(0.0, cusp.velocityMps)
    }

    @Test fun `root classification survives tiny scaling and exact coordinate transforms`() {
        for (scale in listOf(1e-240, 1.0, 100.0)) {
            val controls = listOf(0.0, 1.0, 1.0, -2.0).map { Translation2d(7.0, it * scale) }
            val roots = splineStationarySamples(data(controls).waypoints)
            assertEquals(1, roots.size)
            assertEquals((sqrt(3.0) - 1.0) / 2.0, roots.single().relativePosition, 1e-15)
            assertTrue(roots.single().requiresStop)
        }
        val controls = listOf(0.0, 1.0, 1.0, -2.0).map { Translation2d(2.0 * it, -3.0 * it) }
        assertEquals(1, splineStationarySamples(data(controls).waypoints).size)
    }

    @Test fun `independent quadratic components retain only their exact common root`() {
        val controls = listOf(Translation2d(), Translation2d(1.0, 2.0), Translation2d(-1.0, -1.0), Translation2d(2.0, -1.0))
        val roots = splineStationarySamples(data(controls).waypoints)
        assertEquals(1, roots.size)
        assertEquals(0.25, roots.single().relativePosition)
        assertTrue(roots.single().requiresStop)
    }

    @Test fun `a near common root is not silently classified as a cusp`() {
        val controls = listOf(Translation2d(), Translation2d(1.0, 2.0), Translation2d(-1.0, -1.0), Translation2d(2.0, -1.0 + 1e-12))
        assertTrue(splineStationarySamples(data(controls).waypoints).isEmpty())
    }

    @Test fun `linear and quadratic components share a root in either coordinate order`() {
        val controls = listOf(Translation2d(), Translation2d(1.0, -1.0), Translation2d(-1.0, 0.0), Translation2d(2.0, 3.0))
        for (points in listOf(controls, controls.map { Translation2d(it.y, it.x) })) {
            val root = splineStationarySamples(data(points).waypoints).single()
            assertEquals(0.25, root.relativePosition)
            assertTrue(root.requiresStop)
        }
    }

    @Test fun `proportional linear derivatives find a shared reversal`() {
        val controls = listOf(0.0, 2.0, 1.0, -3.0).map { Translation2d(it, 2.0 * it) }
        val root = splineStationarySamples(data(controls).waypoints).single()
        assertEquals(1.0 / 3.0, root.relativePosition, 1e-15)
        assertTrue(root.requiresStop)
    }

    @Test fun `two distinct derivative roots remain separate stops`() {
        val pathData = data(listOf(0.0, 3.0, -2.0, 1.0).map { Translation2d(it, 0.0) })
        val roots = splineStationarySamples(pathData.waypoints)
        assertEquals(listOf(0.25, 0.75), roots.map { it.relativePosition })
        assertTrue(roots.all { it.requiresStop })
        val path = SplineMotionProfiler.buildProfiledPath(pathData)
        assertEquals(0.0, path.points[5].velocityMps)
        assertEquals(0.0, path.points[15].velocityMps)
    }

    @Test fun `a repeated off grid root preserves smooth travel and tangent`() {
        val pathData = data(listOf(0.0, 9.0, -6.0, 19.0).map { Translation2d(0.0, it) })
        val root = splineStationarySamples(pathData.waypoints).single()
        assertEquals(0.375, root.relativePosition)
        assertFalse(root.requiresStop)
        val point = SplineMotionProfiler.buildProfiledPath(pathData).points.single { abs(it.pose.y - 3.375) < 1e-12 }
        assertTrue(point.velocityMps > 0.0)
        assertEquals(PI / 2.0, point.tangentRadians, 1e-12)
    }

    @Test fun `nonreal constant monotone and endpoint roots add no interior samples`() {
        for (xs in listOf(listOf(0.0, 1.0, 0.9, 1.9), listOf(2.0, 2.0, 2.0, 2.0), listOf(0.0, 0.0, 1.0, 1.0))) {
            assertTrue(splineStationarySamples(data(xs.map { Translation2d(it, 0.0) }).waypoints).isEmpty())
        }
    }

    @Test fun `generated natural curves include their off grid overshoot stops`() {
        val path = SplineMotionProfiler.generateHermitePath(listOf(0.0, 1.0, 0.0, 1.0).map { Translation2d(it, 0.0) },
            com.areslib.math.geometry.Rotation2d(), com.areslib.math.geometry.Rotation2d(), 2.0, 1.0)
        assertTrue(path.points.any { it.pose.x > 1.0 && it.velocityMps == 0.0 })
        assertTrue(path.points.any { it.pose.x < 0.0 && it.velocityMps == 0.0 })
    }

    @Test fun `critical samples participate in the total budget`() {
        val anchors = listOf(-500.0, 500.0, -500.0, 500.0, -500.0, 499.95).map { Translation2d(it, 0.0) }
        val failure = assertFailsWith<IllegalArgumentException> {
            SplineMotionProfiler.generateHermitePath(anchors, com.areslib.math.geometry.Rotation2d(), com.areslib.math.geometry.Rotation2d(), 2.0, 1.0)
            Unit
        }
        assertTrue(failure.message!!.contains("budget"))
    }

    @Test fun `constraint and critical samples deduplicate while both semantics apply`() {
        val pathData = data(listOf(0.0, 9.0, -6.0, 19.0).map { Translation2d(0.0, it) }).copy(
            constraintZones = listOf(PathPlannerJsonParser.ParsedConstraintsZone(0.375, 0.375, 0.2, 1.0)))
        val path = SplineMotionProfiler.buildProfiledPath(pathData)
        assertEquals(382, path.points.size)
        val point = path.points.single { abs(it.pose.y - 3.375) < 1e-12 }
        assertTrue(point.velocityMps > 0.0 && point.velocityMps <= 0.2)
        assertEquals(PI / 2.0, point.tangentRadians, 1e-12)
    }

    @Test fun `an interior root unrepresentable in the grid fails explicitly`() {
        for (xs in listOf(listOf(0.0, -Double.MIN_VALUE, 1000.0, 1000.0), listOf(-2.0, -1.0, Double.MIN_VALUE, 0.0))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                splineStationarySamples(data(xs.map { Translation2d(it, 0.0) }).waypoints)
            }
            assertTrue(failure.message!!.contains("representable"))
        }
    }
}
