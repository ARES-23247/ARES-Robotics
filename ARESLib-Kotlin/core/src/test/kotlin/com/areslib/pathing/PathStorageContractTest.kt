package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.LinkedList
import java.util.RandomAccess

class PathStorageContractTest {
    @Test
    fun `legacy reflection does not silently turn invalid raw heading into zero`() {
        val path = Path(listOf(PathPoint(Pose2d(1.0, 2.0, Rotation2d(Double.NaN)), 0.0)))
        assertThrows(IllegalArgumentException::class.java) { path.mirrorForBlueAlliance() }
        assertThrows(IllegalArgumentException::class.java) { path.mirrorForRedAlliance() }
    }

    @Test
    fun `late narrow projection window does not scan the full random access path`() {
        val points = object : AbstractList<PathPoint>(), RandomAccess {
            var reads = 0
            override val size = 32_768
            override fun get(index: Int): PathPoint {
                reads++
                return PathPoint(Pose2d(index.toDouble(), 0.0), 0.0, index.toDouble())
            }
        }
        assertEquals(32_000.5, Path(points).findClosestDistance(32_000.5, 1.0, 32_000.0, 32_001.0), 1e-12)
        assertTrue(points.reads < 60, "read ${points.reads} points")
    }

    @Test
    fun `linked list queries do not repeatedly index every segment`() {
        val points = object : LinkedList<PathPoint>() {
            var gets = 0
            override fun get(index: Int): PathPoint { gets++; return super.get(index) }
        }
        repeat(1_000) { points.add(PathPoint(Pose2d(it.toDouble(), 0.0), 0.0, it.toDouble())) }
        val path = Path(points)
        assertEquals(900.5, path.findClosestDistance(900.5, 1.0), 1e-12)
        assertTrue(points.gets < 10, "indexed ${points.gets} times")
        points.gets = 0
        assertEquals(900.5, path.sampleAtDistance(900.5).pose.x, 1e-12)
        assertTrue(points.gets < 10, "indexed ${points.gets} times")
    }

    @Test
    fun `caller mutations remain visible and data class copy ownership is unchanged`() {
        val points = mutableListOf(PathPoint(Pose2d(), 0.0), PathPoint(Pose2d(1.0, 0.0), 1.0, 1.0))
        val events = mutableListOf(PathEvent("one", 0.0))
        val path = Path(points, events)
        val copy = path.copy()
        assertSame(points, path.points)
        assertSame(events, copy.events)
        points[1].pose = Pose2d(3.0, 0.0)
        assertEquals(1.5, path.sampleAtDistance(0.5).pose.x)
        points.add(PathPoint(Pose2d(4.0, 0.0), 0.0, 2.0))
        assertEquals(3.5, copy.sampleAtDistance(1.5).pose.x)
        val (copiedPoints, copiedEvents) = copy
        assertSame(points, copiedPoints)
        assertSame(events, copiedEvents)
        assertEquals(path, copy)
        assertEquals(path.hashCode(), copy.hashCode())
    }

    @Test
    fun `both legacy mirrors are independent involutions sharing the event list`() {
        val point = PathPoint(Pose2d(1.0, 2.0, Rotation2d(0.7)), 3.0, 4.0, 0.5, 0.2)
        val path = Path(listOf(point), listOf(PathEvent("one", 1.0)))
        val blue = path.mirrorForBlueAlliance()
        val red = path.mirrorForRedAlliance()
        assertEquals(blue, red)
        assertEquals(path, blue.mirrorForBlueAlliance())
        assertSame(path.events, red.events)
        assertNotSame(point, blue.points.first())
        assertEquals(1.0, red.points.first().pose.x)
        assertEquals(-2.0, red.points.first().pose.y)
        assertEquals(-0.5, red.points.first().curvature)
        blue.points.first().pose = Pose2d()
        assertEquals(2.0, point.pose.y)
    }

    @Test
    fun `duplicate arc distances and zero length geometry remain valid`() {
        val path = Path(listOf(PathPoint(Pose2d(), 0.0), PathPoint(Pose2d(), 0.0, 1.0),
            PathPoint(Pose2d(1.0, 0.0), 0.0, 1.0), PathPoint(Pose2d(2.0, 0.0), 0.0, 2.0)))
        assertEquals(1.0, path.findClosestDistance(0.5, 0.0))
        assertEquals(1.5, path.findClosestDistance(1.5, 0.0), 1e-12)
        assertEquals(0.0, path.sampleAtDistance(1.0).pose.x)
        assertEquals(0.0, Path(emptyList()).findClosestDistance(0.0, 0.0))
    }
}
