package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import kotlin.random.Random

class PathNumericalOracleTest {
    private val context = MathContext.DECIMAL128
    private fun bd(value: Double) = BigDecimal(value)

    @Test
    fun `overflowing scale ratios and reversed enormous axes clamp consistently`() {
        val tiny = Path(listOf(PathPoint(Pose2d(), 0.0),
            PathPoint(Pose2d(1e-308, 2e-308), 0.0, 1.0)))
        assertEquals(1.0, tiny.findClosestDistance(1e308, 1e308))
        assertEquals(0.0, tiny.findClosestDistance(-1e308, -1e308))
        val max = Double.MAX_VALUE
        val reversed = Path(listOf(PathPoint(Pose2d(max, 0.0), 0.0),
            PathPoint(Pose2d(-max, 0.0), 0.0, 1.0)))
        assertEquals(0.5, reversed.findClosestDistance(0.0, 0.0), 1e-15)
        assertEquals(0.25, reversed.findClosestDistance(max / 2, 0.0), 1e-15)
        assertEquals(1.0, reversed.findClosestDistance(-max, 0.0))
    }

    @Test
    fun `sampling matches decimal convex interpolation across finite exponent range`() {
        val random = Random(5601)
        val out = MutablePathPoint()
        repeat(2_000) {
            val a = Math.scalb(random.nextDouble(-1.0, 1.0), random.nextInt(-1000, 1024))
            val b = Math.scalb(random.nextDouble(-1.0, 1.0), random.nextInt(-1000, 1024))
            val t = random.nextDouble()
            val path = Path(listOf(PathPoint(Pose2d(a, b), a, 0.0, a),
                PathPoint(Pose2d(b, a), b, 1.0, b)))
            path.sampleAtDistance(t, out)
            val expected = bd(a).multiply(BigDecimal.ONE.subtract(bd(t))).add(bd(b).multiply(bd(t))).toDouble()
            val tolerance = 4 * Math.ulp(maxOf(kotlin.math.abs(a), kotlin.math.abs(b)))
            assertTrue(out.x.isFinite())
            assertTrue(out.x in minOf(a, b)..maxOf(a, b))
            assertEquals(expected, out.x, tolerance)
            val value = path.sampleAtDistance(t)
            assertEquals(out.x, value.pose.x)
            assertEquals(out.velocityMps, value.velocityMps)
            assertEquals(out.curvature, value.curvature)
        }
    }

    @Test
    fun `windowed projection matches independent decimal oracle under power of two scaling`() {
        val random = Random(5602)
        repeat(1_000) {
            val ax = random.nextDouble(-10.0, 10.0)
            val ay = random.nextDouble(-10.0, 10.0)
            val bx = ax + random.nextDouble(0.1, 5.0)
            val by = ay + random.nextDouble(-5.0, 5.0)
            val x = random.nextDouble(-20.0, 20.0)
            val y = random.nextDouble(-20.0, 20.0)
            val lower = random.nextDouble(0.0, 0.5)
            val upper = random.nextDouble(0.5, 1.0)
            val dx = bd(bx).subtract(bd(ax)); val dy = bd(by).subtract(bd(ay))
            val numerator = bd(x).subtract(bd(ax)).multiply(dx).add(bd(y).subtract(bd(ay)).multiply(dy))
            val denominator = dx.multiply(dx).add(dy.multiply(dy))
            val expected = numerator.divide(denominator, context).toDouble().coerceIn(lower, upper)
            for (exponent in listOf(-600, 0, 600)) {
                val path = Path(listOf(
                    PathPoint(Pose2d(Math.scalb(ax, exponent), Math.scalb(ay, exponent)), 0.0),
                    PathPoint(Pose2d(Math.scalb(bx, exponent), Math.scalb(by, exponent)), 0.0, 1.0)))
                val actual = path.findClosestDistance(Math.scalb(x, exponent), Math.scalb(y, exponent), lower, upper)
                assertEquals(expected, actual, 2e-12, "case=$it exponent=$exponent")
            }
        }
    }

    @Test
    fun `subnormal oblique geometry retains projection contributions`() {
        val tiny = Double.MIN_VALUE
        val path = Path(listOf(PathPoint(Pose2d(), 0.0), PathPoint(Pose2d(2 * tiny, tiny), 0.0, 1.0)))
        assertEquals(0.2, path.findClosestDistance(0.0, tiny), 1e-15)
    }

    @Test
    fun `overflow in an irrelevant perpendicular coordinate does not erase tiny segment direction`() {
        val tiny = Double.MIN_VALUE
        val max = Double.MAX_VALUE
        val path = Path(listOf(PathPoint(Pose2d(-max, 0.0), 0.0),
            PathPoint(Pose2d(-max, 2 * tiny), 0.0, 1.0)))
        assertEquals(1.0, path.findClosestDistance(max, 2 * tiny), 1e-15)
    }
}
