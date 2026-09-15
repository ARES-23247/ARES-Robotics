package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import com.areslib.state.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.Path2D

class StaticCostmapGeometryTest {
    private fun map() = Costmap(6.0, 6.0, 0.1, Translation2d())

    @Test fun `rotated rectangles match independent area intersections`() {
        for (degrees in listOf(17.0, 43.0, 112.0, -71.0)) {
            val map = map()
            val obstacle = RobotFieldObstacle(x = 3.17, y = 3.29, width = 2.3, height = 0.37, rotation = degrees)
            map.setStaticObstacles(listOf(obstacle))
            val shape = Area(Rectangle2D.Double(-1.15, -0.185, 2.3, 0.37))
            val transform = AffineTransform.getTranslateInstance(obstacle.x, obstacle.y)
            transform.rotate(Math.toRadians(degrees))
            shape.transform(transform)
            for (y in 0 until 60) for (x in 0 until 60) {
                val clipped = Area(shape)
                clipped.intersect(Area(Rectangle2D.Double(x * 0.1 - 0.05, y * 0.1 - 0.05, 0.1, 0.1)))
                assertEquals(!clipped.isEmpty, map.isCellOccupied(x, y), "angle $degrees cell $x,$y")
            }
        }
    }

    @Test fun `circle width is radius and bounding square corners remain free`() {
        val map = map()
        map.setStaticObstacles(listOf(RobotFieldObstacle(shape = "circle", x = 3.0, y = 3.0, width = 1.0)))
        assertTrue(map.isOccupied(3.8, 3.0))
        assertFalse(map.isOccupied(3.9, 3.9))
    }

    @Test fun `polygon uses absolute vertices and keeps a concave notch free`() {
        val map = map()
        val points = listOf(2.0 to 2.0, 4.0 to 2.0, 4.0 to 2.5, 2.5 to 2.5, 2.5 to 4.0, 2.0 to 4.0)
            .map { RobotFieldPoint(it.first, it.second) }
        map.setStaticObstacles(listOf(RobotFieldObstacle(shape = "polygon", x = -100.0, y = -100.0,
            rotation = 90.0, points = points)))
        assertTrue(map.isOccupied(3.5, 2.2))
        assertTrue(map.isOccupied(2.2, 3.5))
        assertFalse(map.isOccupied(3.5, 3.5))
    }

    @Test fun `static elements honor rotation and round width fallback`() {
        val map = map()
        val box = RobotFieldElementType(id = "box", width = 2.0, height = 0.2)
        val circle = RobotFieldElementType(id = "round", shape = "cylinder", width = 2.0)
        map.setStaticElements(listOf(box, circle), listOf(
            RobotFieldElementInstance(elementTypeId = "box", x = 1.0, y = 2.0, rotation = 90.0),
            RobotFieldElementInstance(elementTypeId = "round", x = 4.0, y = 4.0)))
        assertTrue(map.isOccupied(1.0, 2.8))
        assertFalse(map.isOccupied(1.8, 2.0))
        assertTrue(map.isOccupied(4.8, 4.0))
        assertFalse(map.isOccupied(4.9, 4.9))
    }

    @Test fun `closed cell tangency is included on both sides of a boundary`() {
        val map = Costmap(6.0, 6.0, 1.0, Translation2d())
        map.setStaticObstacles(listOf(RobotFieldObstacle(x = 2.0, y = 2.0, width = 1.0, height = 1.0)))
        assertTrue(map.isCellOccupied(1, 2))
        assertTrue(map.isCellOccupied(3, 2))
        assertTrue(map.isCellOccupied(1, 1))
    }

    @Test fun `concave polygon mask matches independent clipping and includes thin edge crossings`() {
        val map = map()
        val points = listOf(2.17 to 2.29, 4.17 to 2.29, 4.17 to 2.79, 2.67 to 2.79, 2.67 to 4.29, 2.17 to 4.29)
            .map { RobotFieldPoint(it.first, it.second) }
        map.setStaticObstacles(listOf(RobotFieldObstacle(shape = "polygon", points = points)))
        val path = Path2D.Double().apply {
            moveTo(points[0].x, points[0].y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
            closePath()
        }
        val shape = Area(path)
        for (y in 0 until 60) for (x in 0 until 60) {
            val clipped = Area(shape)
            clipped.intersect(Area(Rectangle2D.Double(x * 0.1 - 0.05, y * 0.1 - 0.05, 0.1, 0.1)))
            assertEquals(!clipped.isEmpty, map.isCellOccupied(x, y), "polygon cell $x,$y")
        }
        val coarse = Costmap(4.0, 4.0, 1.0, Translation2d())
        coarse.setStaticObstacles(listOf(RobotFieldObstacle(shape = "polygon", points = listOf(
            RobotFieldPoint(0.49, 0.49), RobotFieldPoint(0.51, 0.49), RobotFieldPoint(0.5, 0.51)))))
        for (y in 0..1) for (x in 0..1) assertTrue(coarse.isCellOccupied(x, y))
        assertFalse(coarse.isCellOccupied(2, 0))
    }

    @Test fun `invalid shapes fail and excluded obstacles leave the grid clear`() {
        val map = map()
        for (obstacle in listOf(RobotFieldObstacle(width = -1.0), RobotFieldObstacle(rotation = Double.NaN),
            RobotFieldObstacle(shape = "polygon", points = emptyList()), RobotFieldObstacle(shape = "unknown"),
            RobotFieldObstacle(shape = "circle", width = Double.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { map.setStaticObstacles(listOf(obstacle)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            map.setStaticElements(emptyList(), listOf(RobotFieldElementInstance(elementTypeId = "missing")))
        }
        val type = RobotFieldElementType(id = "duplicate")
        assertThrows(IllegalArgumentException::class.java) { map.setStaticElements(listOf(type, type), emptyList()) }
        map.setStaticObstacles(listOf(RobotFieldObstacle(shape = "unknown", isBlocking = false)))
        map.setStaticElements(listOf(RobotFieldElementType(id = "moving", movable = true)),
            listOf(RobotFieldElementInstance(elementTypeId = "moving", x = 3.0, y = 3.0)))
        for (y in 0 until 60) for (x in 0 until 60) assertFalse(map.isCellOccupied(x, y))
    }

    @Test fun `clipping handles off grid shapes without visiting unbounded coordinates`() {
        assertTimeout(java.time.Duration.ofSeconds(2)) {
            val map = map()
            map.setStaticObstacles(listOf(RobotFieldObstacle(x = 1e12, y = 1e12, width = 2.0, height = 2.0)))
            assertFalse(map.isOccupied(3.0, 3.0))
            map.setStaticObstacles(listOf(RobotFieldObstacle(shape = "circle", x = 0.0, y = 0.0, width = 1e12)))
            for (y in 0 until 60) for (x in 0 until 60) assertTrue(map.isCellOccupied(x, y))
        }
    }

    @Test fun `all round element aliases honor explicit diameter and width fallback`() {
        for (shape in listOf("circle", "CYLINDER", "sphere")) for (diameter in listOf(null, 1.0)) {
            val map = map()
            map.setStaticElements(listOf(RobotFieldElementType(id = "round", shape = shape, width = 2.0, diameter = diameter)),
                listOf(RobotFieldElementInstance(elementTypeId = "round", x = 3.0, y = 3.0)))
            assertTrue(map.isOccupied(3.4, 3.0))
            assertEquals(diameter == null, map.isOccupied(3.8, 3.0))
        }
    }

    @Test fun `quarter turns preserve exact boundary contact on long rectangles`() {
        for (degrees in listOf(90.0, -90.0, 270.0, 450.0)) {
            val map = Costmap(40.0, 40.0, 1.0, Translation2d())
            map.setStaticObstacles(listOf(RobotFieldObstacle(x = 20.0, y = 20.0, width = 1.0, height = 30.0, rotation = degrees)))
            for (x in 5..35) {
                assertTrue(map.isCellOccupied(x, 19), "lower edge $degrees at $x")
                assertTrue(map.isCellOccupied(x, 21), "upper edge $degrees at $x")
            }
            assertFalse(map.isCellOccupied(20, 18))
        }
    }

    @Test fun `raster primitives avoid per cell geometry allocations`() {
        val map = map()
        val points = listOf(RobotFieldPoint(1.0, 1.0), RobotFieldPoint(3.0, 1.0), RobotFieldPoint(2.0, 3.0))
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        repeat(3000) {
            StaticCostmapRasterizer.rectangle(map, 2.0, 2.0, 1.3, 0.7, (it % 90).toDouble())
            StaticCostmapRasterizer.circle(map, 2.0, 2.0, 0.7)
            StaticCostmapRasterizer.polygon(map, points)
        }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(1000) {
            StaticCostmapRasterizer.rectangle(map, 2.0, 2.0, 1.3, 0.7, (it % 90).toDouble())
            StaticCostmapRasterizer.circle(map, 2.0, 2.0, 0.7)
            StaticCostmapRasterizer.polygon(map, points)
        }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(map.isOccupied(2.0, 2.0))
        assertTrue(allocated <= 1024L, "Allocated $allocated bytes in 3000 rasterizations")
    }
}
