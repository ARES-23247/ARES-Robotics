package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SplineIntervalCurvatureAuditTest {
    private fun data(p: List<Translation2d>) = PathPlannerJsonParser.ParsedPathData(
        listOf(PathPlannerJsonParser.WaypointData(p[0], p[0], p[1]), PathPlannerJsonParser.WaypointData(p[3], p[2], p[3])),
        2.0, 1.0, 0.0, null, 0.0, null, emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun `a regular curvature peak between samples respects the speed ceiling`() {
        val path = SplineMotionProfiler.buildProfiledPath(data(listOf(Translation2d(), Translation2d(0.0, 100.0), Translation2d(1.0, 80.0), Translation2d(1.0, 0.0))))
        val t = 2.0 - sqrt(21.0) / 3.0
        val x = 3 * t * t - 2 * t * t * t
        val dx = 6 * t * (1 - t)
        val curvature = abs(-720 + 360 * t) / (dx * dx)
        val before = path.points.indexOfLast { it.pose.x <= x }.coerceAtMost(path.points.lastIndex - 1)
        val a = path.points[before]; val b = path.points[before + 1]
        val fraction = (x - a.pose.x) / (b.pose.x - a.pose.x)
        val distance = a.distanceMeters + fraction * (b.distanceMeters - a.distanceMeters)
        val speed = path.sampleAtDistance(distance).velocityMps
        assertTrue(curvature * speed * speed <= 2.0 + 1e-9, "hidden curvature $curvature at speed $speed")
    }

    @Test fun `tiny finite curvature does not overflow the centripetal speed calculation`() {
        val path = SplineMotionProfiler.buildProfiledPath(data(listOf(Translation2d(), Translation2d(1.0, 0.0), Translation2d(2.0, 1e-314), Translation2d(3.0, 1e-314))).copy(
            defaultMaxVel = Double.MAX_VALUE, defaultMaxAccel = Double.MAX_VALUE, startVel = Double.MAX_VALUE, endVel = Double.MAX_VALUE))
        val point = path.points.first()
        val ceiling = sqrt(2.0) / sqrt(abs(point.curvature))
        assertTrue(ceiling.isFinite() && ceiling > 0.0)
        assertTrue(point.velocityMps <= ceiling * (1.0 + 1e-12))
    }

    @Test fun `distinct roots rounding to one position retain stop classification`() {
        val e = Double.MIN_VALUE
        val pathData = data(listOf(e, 1.0, -e, 1.0).map { Translation2d(it, 0.0) })
        val roots = splineStationarySamples(pathData.waypoints)
        assertEquals(2, roots.size)
        assertTrue(roots.all { it.relativePosition == 0.5 && it.requiresStop })
        val point = SplineMotionProfiler.buildProfiledPath(pathData).points.single { it.pose.x == 0.5 }
        assertEquals(0.0, point.velocityMps)
    }

    @Test fun `regular interval bounds dominate an independent dense power basis oracle`() {
        val random = Random(100)
        var checked = 0
        repeat(40) {
            val p = List(4) { Translation2d(random.nextDouble(-3.0, 3.0), random.nextDouble(-3.0, 3.0)) }
            val bound = SplineCurvatureBound(p[0], p[1], p[2], p[3])
            fun coefficients(axis: (Translation2d) -> Double): DoubleArray {
                val v = p.map(axis)
                return doubleArrayOf(3 * (v[1] - v[0]), 3 * (v[2] - 2 * v[1] + v[0]), v[3] - 3 * v[2] + 3 * v[1] - v[0])
            }
            val x = coefficients { it.x }; val y = coefficients { it.y }
            for (step in 0..8) {
                val lo = step / 10.0; val hi = lo + 0.1
                val ceiling = bound.speedCeiling(lo, hi) ?: continue
                assertTrue(ceiling.isFinite() && ceiling > 0.0)
                for (j in 0..40) {
                    val t = lo + (hi - lo) * j / 40.0
                    val dx = x[0] + 2 * x[1] * t + 3 * x[2] * t * t
                    val dy = y[0] + 2 * y[1] * t + 3 * y[2] * t * t
                    val ddx = 2 * x[1] + 6 * x[2] * t
                    val ddy = 2 * y[1] + 6 * y[2] * t
                    val length = hypot(dx, dy)
                    val curvature = abs(dx * ddy - dy * ddx) / (length * length * length)
                    assertTrue(curvature * ceiling * ceiling <= 2.0 + 1e-9)
                    checked++
                }
            }
        }
        assertTrue(checked > 10000)
    }

    @Test fun `interval ceilings retain square root scaling for tiny geometry`() {
        fun ceiling(scale: Double) = SplineCurvatureBound(Translation2d(), Translation2d(0.0, scale), Translation2d(scale, scale), Translation2d(scale, 0.0)).speedCeiling(0.2, 0.8)!!
        val unit = ceiling(1.0)
        for (scale in listOf(1e-240, 1e-120, 1e-12, 100.0)) {
            assertEquals(unit, ceiling(scale) / sqrt(scale), 1e-12)
        }
    }

    @Test fun `regular near stationary curves refine rather than become false cusps`() {
        val pathData = data(listOf(Translation2d(), Translation2d(1.0, 2.0), Translation2d(-1.0, -1.0), Translation2d(2.0, -1.0 + 1e-6)))
        val roots = splineStationarySamples(pathData.waypoints)
        assertTrue(roots.isEmpty())
        val plan = splineSamplePlan(2) { pathData.waypoints[it].anchor }
        val original = splineRelativePositions(pathData, plan)
        val bounded = boundSplineIntervals(pathData.waypoints, original, roots)
        assertTrue(bounded.positions.size > original.size)
        assertTrue(bounded.positions.zipWithNext().all { (a, b) -> a < b })
        assertTrue(bounded.edgeSpeedCeilings.all { it.isFinite() && it > 0.0 })
        val path = SplineMotionProfiler.buildProfiledPath(pathData)
        assertTrue(path.points.subList(1, path.points.lastIndex).all { it.velocityMps > 0.0 })
    }

    @Test fun `refinement shares the total budget before allocating path geometry`() {
        val anchors = listOf(-500.0, 500.0, -500.0, 500.0, -500.0, 499.95).map { Translation2d(it, 0.0) }
        val waypoints = anchors.map { PathPlannerJsonParser.WaypointData(it, it, it) }.toMutableList()
        waypoints[0] = waypoints[0].copy(nextControl = Translation2d(0.0, 2.5 - 0.5e-6))
        waypoints[1] = waypoints[1].copy(prevControl = Translation2d(-1000.0, -1.5 + 0.5e-6))
        val failure = assertFailsWith<IllegalArgumentException> {
            SplineMotionProfiler.buildProfiledPath(data(listOf(Translation2d(), Translation2d(), Translation2d(1.0, 0.0), Translation2d(1.0, 0.0))).copy(waypoints = waypoints))
            Unit
        }
        assertTrue(failure.message!!.contains("budget"))
    }

    @Test fun `straight geometry keeps its original grid and finite speed ceilings`() {
        val p = listOf(Translation2d(), Translation2d(1.0, 1.0), Translation2d(2.0, 2.0), Translation2d(3.0, 3.0))
        val pathData = data(p)
        val original = splineRelativePositions(pathData, splineSamplePlan(2) { pathData.waypoints[it].anchor })
        val bounded = boundSplineIntervals(pathData.waypoints, original, emptyList())
        assertEquals(original, bounded.positions)
        assertTrue(bounded.edgeSpeedCeilings.all { it == Double.MAX_VALUE })
    }

    @Test fun `singular endpoint intervals are explicitly left to pointwise handling`() {
        val bound = SplineCurvatureBound(Translation2d(), Translation2d(), Translation2d(0.0, 1.0), Translation2d(1.0, 1.0))
        assertEquals(Double.MAX_VALUE, bound.speedCeiling(0.0, 0.1))
        assertTrue(bound.speedCeiling(0.1, 0.2)!! < Double.MAX_VALUE)
    }
}
