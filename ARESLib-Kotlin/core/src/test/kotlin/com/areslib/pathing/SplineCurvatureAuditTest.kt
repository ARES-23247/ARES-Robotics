package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import com.areslib.math.wrapAngle

class SplineCurvatureAuditTest {
    private fun profile(p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d,
                        startVelocity: Double = 0.0, endVelocity: Double = 0.0) =
        SplineMotionProfiler.buildProfiledPath(PathPlannerJsonParser.ParsedPathData(
            listOf(PathPlannerJsonParser.WaypointData(p0, p0, p1), PathPlannerJsonParser.WaypointData(p3, p2, p3)),
            2.0, 1.0, startVelocity, null, endVelocity, null, emptyList(), emptyList(), emptyList(), emptyList()))

    @Test fun `long handles cannot hide analytical curvature at the emitted midpoint`() {
        val path = profile(Translation2d(), Translation2d(0.0, 100.0), Translation2d(1.0, 100.0), Translation2d(1.0, 0.0))
        val middle = path.points.single { abs(it.pose.x - 0.5) < 1e-12 }
        // At t=1/2: B'=(1.5,0), B''=(0,-600), hence k=-900/(1.5^3).
        val exactCurvature = -900.0 / (1.5 * 1.5 * 1.5)
        assertTrue(abs(exactCurvature) * middle.velocityMps * middle.velocityMps <= 2.0 + 1e-10,
            "emitted speed ${middle.velocityMps} exceeds the true centripetal bound")
        assertEquals(exactCurvature, middle.curvature, 1e-10)
    }

    @Test fun `interior reversal cusp forces a stop instead of averaging opposite tangents`() {
        val path = profile(Translation2d(), Translation2d(1.0, 0.0), Translation2d(1.0, 0.0), Translation2d())
        val cusp = path.points.maxBy { it.pose.x }
        assertEquals(0.75, cusp.pose.x, 1e-12)
        assertEquals(0.0, cusp.velocityMps, "a reversal has no continuous nonzero travel velocity")
        assertTrue(cusp.curvature.isFinite())
    }

    @Test fun `regular samples agree with an independent power basis derivative oracle`() {
        val random = Random(98)
        repeat(50) {
            val p = List(4) { Translation2d(random.nextDouble(-5.0, 5.0), random.nextDouble(-5.0, 5.0)) }
            fun coefficients(axis: (Translation2d) -> Double): DoubleArray {
                val v = p.map(axis)
                return doubleArrayOf(3 * (v[1] - v[0]), 3 * (v[2] - 2 * v[1] + v[0]), v[3] - 3 * v[2] + 3 * v[1] - v[0])
            }
            val x = coefficients { it.x }; val y = coefficients { it.y }
            val differential = SplineDifferential(p[0], p[1], p[2], p[3])
            for (t in listOf(0.0, 0.13, 0.5, 0.77, 1.0)) {
                val dx = x[0] + 2 * x[1] * t + 3 * x[2] * t * t
                val dy = y[0] + 2 * y[1] * t + 3 * y[2] * t * t
                val ddx = 2 * x[1] + 6 * x[2] * t
                val ddy = 2 * y[1] + 6 * y[2] * t
                val length = hypot(dx, dy)
                val curvature = (dx * ddy - dy * ddx) / (length * length * length)
                differential.evaluate(t)
                assertEquals(curvature, differential.curvature, 1e-9 * maxOf(1.0, abs(curvature)))
                assertEquals(0.0, wrapAngle(differential.headingRadians - atan2(dy, dx)), 1e-10)
                assertFalse(differential.requiresStop)
            }
        }
    }

    @Test fun `curvature scales correctly even when squared derivative norms underflow`() {
        for (scale in listOf(1e-240, 1e-120, 1e-12, 1.0, 100.0)) {
            val differential = SplineDifferential(Translation2d(), Translation2d(0.0, scale), Translation2d(scale, scale), Translation2d(scale, 0.0))
            differential.evaluate(0.5)
            assertEquals(-8.0 / 3.0, differential.curvature * scale, 1e-12)
            assertTrue(differential.curvature.isFinite())
            assertFalse(differential.requiresStop)
        }
    }

    @Test fun `reversing a regular curve reverses curvature and tangent`() {
        val p = listOf(Translation2d(), Translation2d(0.0, 2.0), Translation2d(3.0, 1.0), Translation2d(2.0, -1.0))
        val forward = SplineDifferential(p[0], p[1], p[2], p[3])
        val reverse = SplineDifferential(p[3], p[2], p[1], p[0])
        for (t in listOf(0.0, 0.2, 0.5, 0.9, 1.0)) {
            forward.evaluate(t); reverse.evaluate(1.0 - t)
            assertEquals(-forward.curvature, reverse.curvature, 1e-12)
            assertEquals(0.0, wrapAngle(reverse.headingRadians - forward.headingRadians - PI), 1e-12)
        }
    }

    @Test fun `collinear collapsed endpoints preserve finite speed ceilings`() {
        val path = profile(Translation2d(), Translation2d(), Translation2d(1.0, 0.0), Translation2d(1.0, 0.0), 1.0, 1.0)
        assertEquals(1.0, path.points.first().velocityMps)
        assertEquals(1.0, path.points.last().velocityMps)
        assertTrue(path.points.all { it.curvature == 0.0 })
    }

    @Test fun `a regular cubic stall retains its unique two sided travel direction`() {
        val path = profile(Translation2d(), Translation2d(0.0, 1.0), Translation2d(), Translation2d(0.0, 1.0))
        val middle = path.points.single { abs(it.pose.y - 0.5) < 1e-12 }
        assertEquals(PI / 2.0, middle.tangentRadians, 1e-12)
        assertEquals(0.0, middle.curvature)
        assertTrue(middle.velocityMps > 0.0)
    }

    @Test fun `divergent endpoint curvature forces zero speed without nonfinite path fields`() {
        val path = profile(Translation2d(), Translation2d(), Translation2d(0.0, 1.0), Translation2d(1.0, 1.0), 1.0, 1.0)
        assertEquals(0.0, path.points.first().velocityMps)
        assertEquals(PI / 2.0, path.points.first().tangentRadians, 1e-12)
        assertTrue(path.points.all { it.curvature.isFinite() && it.velocityMps.isFinite() })
    }

    @Test fun `unrepresentable regular curvature rejects instead of returning infinity or false zero`() {
        assertFailsWith<IllegalArgumentException> {
            profile(Translation2d(), Translation2d(0.0, 1e-310), Translation2d(1e-310, 1e-310), Translation2d(1e-310, 0.0))
            Unit
        }
        assertFailsWith<IllegalArgumentException> {
            profile(Translation2d(), Translation2d(1000.0, 0.0), Translation2d(1000.0, 1e-320), Translation2d(1000.0, 1e-320))
            Unit
        }
    }

    @Test fun `constant geometry cannot retain a requested nonzero speed`() {
        val p = Translation2d(2.0, 1.0)
        assertTrue(profile(p, p, p, p, 1.0, 1.0).points.all { it.velocityMps == 0.0 && it.curvature == 0.0 })
    }

    @Test fun `a sharp tangent change at a shared waypoint requires a stop`() {
        val points = listOf(Translation2d(), Translation2d(1.0, 0.0), Translation2d(1.0, 1.0))
        val path = SplineMotionProfiler.buildProfiledPath(PathPlannerJsonParser.ParsedPathData(
            points.map { PathPlannerJsonParser.WaypointData(it, it, it) },
            2.0, 1.0, 0.0, null, 0.0, null, emptyList(), emptyList(), emptyList(), emptyList()))
        val junction = path.points.single { it.pose.x == 1.0 && it.pose.y == 0.0 }
        assertEquals(0.0, junction.velocityMps)
    }

    @Test fun `a continuous tangent join respects the larger outgoing curvature`() {
        val p0 = Translation2d()
        val join = Translation2d(1.0, 0.0)
        val end = Translation2d(1.03, 1.0)
        val path = SplineMotionProfiler.buildProfiledPath(PathPlannerJsonParser.ParsedPathData(listOf(
            PathPlannerJsonParser.WaypointData(p0, p0, Translation2d(0.3, 0.0)),
            PathPlannerJsonParser.WaypointData(join, Translation2d(0.7, 0.0), Translation2d(1.01, 0.0)),
            PathPlannerJsonParser.WaypointData(end, Translation2d(1.02, 1.0), end)),
            2.0, 100.0, 0.0, null, 0.0, null, emptyList(), emptyList(), emptyList(), emptyList()))
        val junction = path.points.single { it.pose.x == 1.0 && it.pose.y == 0.0 }
        // The outgoing derivative is (0.03,0), second derivative (0,6).
        val outgoingCurvature = 6.0 / (0.03 * 0.03)
        assertEquals(outgoingCurvature, junction.curvature, 1e-8)
        assertTrue(outgoingCurvature * junction.velocityMps * junction.velocityMps <= 2.0 + 1e-10)
    }

    @Test fun `natural cubic continuity does not introduce an artificial junction stop`() {
        val path = SplineMotionProfiler.generateHermitePath(
            listOf(Translation2d(), Translation2d(1.0, 1.0), Translation2d(2.0, 0.0)),
            com.areslib.math.geometry.Rotation2d(), com.areslib.math.geometry.Rotation2d(), 2.0, 1.0)
        val junction = path.points.single { it.pose.x == 1.0 && it.pose.y == 1.0 }
        assertTrue(junction.velocityMps > 0.0)
        assertEquals(0.0, junction.tangentRadians, 1e-12)
    }
}
