package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PathSamplingBoundaryTest {
    @Test
    fun `tiny positive distance intervals interpolate instead of collapsing`() {
        for (length in listOf(1e-7, 1e-100, Double.MIN_VALUE * 2)) {
            val path = Path(listOf(PathPoint(Pose2d(), 0.0),
                PathPoint(Pose2d(1.0, 2.0), 2.0, length, 4.0, 1.0)))
            val out = MutablePathPoint()
            path.sampleAtDistance(length / 2, out)
            assertEquals(0.5, out.x, 1e-15)
            assertEquals(1.0, out.y, 1e-15)
            assertEquals(1.0, out.velocityMps, 1e-15)
            assertEquals(2.0, out.curvature, 1e-15)
            assertEquals(0.5, out.tangentRadians, 1e-15)
            assertEquals(out.x, path.sampleAtDistance(length / 2).pose.x)
        }
    }

    @Test
    fun `finite opposite scalar extremes and huge tangent angles remain finite`() {
        val max = Double.MAX_VALUE
        val path = Path(listOf(PathPoint(Pose2d(-max, max), -max, 0.0, -max, -max),
            PathPoint(Pose2d(max, -max), max, 2.0, max, max)))
        val out = MutablePathPoint()
        path.sampleAtDistance(1.0, out)
        assertEquals(0.0, out.x)
        assertEquals(0.0, out.y)
        assertEquals(0.0, out.velocityMps)
        assertEquals(0.0, out.curvature)
        val start = Rotation2d(-max).radians
        val expected = Rotation2d(start + Rotation2d(Rotation2d(max).radians - start).radians / 2).radians
        assertEquals(expected, Rotation2d(out.tangentRadians).radians, 1e-15)
        val value = path.sampleAtDistance(1.0)
        assertEquals(out.x, value.pose.x)
        assertEquals(out.tangentRadians, value.tangentRadians)
    }

    @Test
    fun `nonfinite queries fail before changing output including empty paths`() {
        val paths = listOf(Path(emptyList()), Path(listOf(PathPoint(Pose2d(), 1.0))))
        for (path in paths) for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val out = MutablePathPoint().apply { x = 42.0; distanceMeters = 12.0 }
            assertThrows(IllegalArgumentException::class.java) { path.sampleAtDistance(distance) }
            assertThrows(IllegalArgumentException::class.java) { path.sampleAtDistance(distance, out) }
            assertEquals(42.0, out.x)
            assertEquals(12.0, out.distanceMeters)
        }
    }

    @Test
    fun `invalid sampled fields and raw headings reject without partially writing output`() {
        val changes: List<(PathPoint) -> Unit> = listOf(
            { it.pose = Pose2d(Double.NaN, 0.0) }, { it.pose = Pose2d(0.0, Double.POSITIVE_INFINITY) },
            { it.pose = Pose2d(0.0, 0.0, Rotation2d(Double.NaN)) },
            { it.velocityMps = Double.NaN }, { it.distanceMeters = Double.NaN },
            { it.distanceMeters = -1.0 }, { it.curvature = Double.POSITIVE_INFINITY },
            { it.tangentRadians = Double.NaN })
        for (change in changes) {
            val bad = PathPoint(Pose2d(1.0, 0.0), 1.0, 1.0).also(change)
            val path = Path(listOf(PathPoint(Pose2d(), 0.0), bad))
            val out = MutablePathPoint().apply { x = 42.0; velocityMps = 12.0 }
            assertThrows(IllegalArgumentException::class.java) { path.sampleAtDistance(0.5, out) }
            assertEquals(42.0, out.x)
            assertEquals(12.0, out.velocityMps)
        }
    }

    @Test
    fun `exact knots reuse their mutable value and both empty overloads return zero state`() {
        val points = listOf(PathPoint(Pose2d(), 0.0), PathPoint(Pose2d(1.0, 0.0), 1.0, 1.0),
            PathPoint(Pose2d(2.0, 0.0), 0.0, 2.0))
        val path = Path(points)
        assertSame(points[1], path.sampleAtDistance(1.0))
        val out = MutablePathPoint()
        Path(emptyList()).sampleAtDistance(42.0, out)
        assertEquals(Path(emptyList()).sampleAtDistance(42.0), out.toPathPoint())
    }

    @Test
    fun `copying unchanged pose preserves its immutable object while updating other fields`() {
        val scratch = MutablePathPoint().apply { x = 1.0; y = 2.0; headingRad = 3.0; velocityMps = 4.0 }
        val out = scratch.toPathPoint()
        val pose = out.pose
        scratch.velocityMps = 5.0
        scratch.copyInto(out)
        assertSame(pose, out.pose)
        assertEquals(5.0, out.velocityMps)
        scratch.x = 2.0
        scratch.copyInto(out)
        assertNotSame(pose, out.pose)
        assertEquals(1.0, pose.x)
        assertEquals(2.0, out.pose.x)
    }
}
