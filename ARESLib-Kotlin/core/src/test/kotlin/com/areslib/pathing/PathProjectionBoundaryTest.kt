package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PathProjectionBoundaryTest {
    private fun line(length: Double) = Path(listOf(PathPoint(Pose2d(), 0.0),
        PathPoint(Pose2d(length, 0.0), 0.0, 1.0)))

    @Test
    fun `projection handles tiny and huge segment magnitudes`() {
        for (length in listOf(1e-7, 1e-200, 1e200, Double.MAX_VALUE, Double.MIN_VALUE * 2)) {
            assertEquals(0.5, line(length).findClosestDistance(length / 2, 0.0), 1e-14, "length=$length")
        }
        val max = Double.MAX_VALUE
        val path = Path(listOf(PathPoint(Pose2d(-max, -max), 0.0),
            PathPoint(Pose2d(max, max), 0.0, 1.0)))
        assertEquals(0.5, path.findClosestDistance(0.0, 0.0), 1e-14)
    }

    @Test
    fun `tiny arc windows restrict projection instead of being ignored`() {
        val path = Path(listOf(PathPoint(Pose2d(), 0.0),
            PathPoint(Pose2d(1.0, 0.0), 0.0, 1e-7)))
        assertEquals(0.75e-7, path.findClosestDistance(1.0, 0.0, 0.25e-7, 0.75e-7), 1e-21)
        assertEquals(0.25e-7, path.findClosestDistance(0.0, 0.0, 0.25e-7, 0.75e-7), 1e-21)
    }

    @Test
    fun `out of range windows clamp to actual path endpoints`() {
        val path = line(1.0)
        assertEquals(1.0, path.findClosestDistance(0.5, 0.0, 10.0, 20.0))
        assertEquals(0.0, path.findClosestDistance(0.5, 0.0, -20.0, -10.0))
        assertEquals(0.5, path.findClosestDistance(0.5, 0.0, -20.0, 20.0))
    }

    @Test
    fun `nonfinite queries and inverted windows reject even for empty or singleton paths`() {
        for (path in listOf(Path(emptyList()), Path(listOf(PathPoint(Pose2d(), 0.0))), line(1.0))) {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) { path.findClosestDistance(bad, 0.0) }
                assertThrows(IllegalArgumentException::class.java) { path.findClosestDistance(0.0, bad) }
                assertThrows(IllegalArgumentException::class.java) { path.findClosestDistance(0.0, 0.0, bad, 1.0) }
                assertThrows(IllegalArgumentException::class.java) { path.findClosestDistance(0.0, 0.0, 0.0, bad) }
            }
            assertThrows(IllegalArgumentException::class.java) { path.findClosestDistance(0.0, 0.0, 2.0, 1.0) }
        }
    }

    @Test
    fun `nearest candidate is selected even when every true separation exceeds maximum double`() {
        val max = Double.MAX_VALUE
        val path = Path(listOf(PathPoint(Pose2d(-max, 0.0), 0.0),
            PathPoint(Pose2d(-max, max), 0.0, 1.0), PathPoint(Pose2d(0.0, max), 0.0, 2.0)))
        assertEquals(2.0, path.findClosestDistance(max, 0.0))
    }

    @Test
    fun `invalid geometry or distance in a visited segment is rejected`() {
        for (point in listOf(PathPoint(Pose2d(Double.NaN, 0.0), 0.0, 1.0),
            PathPoint(Pose2d(1.0, 0.0), 0.0, Double.NaN),
            PathPoint(Pose2d(1.0, 0.0), 0.0, -1.0))) {
            val path = Path(listOf(PathPoint(Pose2d(), 0.0), point))
            assertThrows(IllegalArgumentException::class.java) { path.findClosestDistance(0.5, 0.0) }
        }
    }
}
