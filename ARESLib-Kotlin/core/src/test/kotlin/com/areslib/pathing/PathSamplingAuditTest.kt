package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import kotlin.test.*

class PathSamplingAuditTest {
    @Test
    fun `natural spline starts along its derivative rather than the waypoint chord`() {
        val path = SplineMotionProfiler.generateHermitePath(
            listOf(Translation2d(0.0, 0.0), Translation2d(1.0, 1.0), Translation2d(2.0, 0.0)),
            Rotation2d(), Rotation2d(), 2.0, 2.0)
        assertEquals(kotlin.math.atan2(1.5, 1.0), path.points.first().tangentRadians, 1e-12)
    }

    @Test
    fun `late path sampling requires logarithmic point reads`() {
        val points = object : AbstractList<PathPoint>() {
            var reads = 0
            override val size = 32768
            override fun get(index: Int): PathPoint {
                reads++
                return PathPoint(Pose2d(index.toDouble(), 2.0 * index), 1.0, index.toDouble())
            }
        }
        val path = Path(points)
        val output = MutablePathPoint()
        path.sampleAtDistance(32000.5, output)
        assertEquals(32000.5, output.x)
        assertEquals(64001.0, output.y)
        assertTrue(points.reads < 40, "Read ${points.reads} points")
        points.reads = 0
        assertEquals(output.x, path.sampleAtDistance(32000.5).pose.x)
        assertTrue(points.reads < 40, "Read ${points.reads} points")
    }

    @Test
    fun `both samplers agree across duplicate distances angle wrap and bounds`() {
        val path = Path(listOf(
            PathPoint(Pose2d(0.0, 0.0, Rotation2d(3.0)), 0.0, 0.0),
            PathPoint(Pose2d(1.0, 2.0, Rotation2d(-3.0)), 1.0, 1.0),
            PathPoint(Pose2d(1.0, 2.0, Rotation2d(-3.0)), 1.0, 1.0),
            PathPoint(Pose2d(3.0, 4.0, Rotation2d(-2.0)), 0.0, 2.0)))
        val out = MutablePathPoint()
        for (distance in listOf(-1.0, 0.0, 0.5, 1.0, 1.5, 2.0, 3.0)) {
            val value = path.sampleAtDistance(distance)
            path.sampleAtDistance(distance, out)
            assertEquals(value.pose.x, out.x)
            assertEquals(value.pose.y, out.y)
            assertEquals(value.pose.heading.radians, Rotation2d(out.headingRad).radians, 1e-12)
            assertEquals(value.velocityMps, out.velocityMps)
        }
    }
}
