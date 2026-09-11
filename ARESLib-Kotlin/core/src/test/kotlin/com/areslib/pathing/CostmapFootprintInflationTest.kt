package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class CostmapFootprintInflationTest {
    @Test fun `free diagonal cell must not admit a colliding off center robot`() {
        val map = Costmap(6.0, 6.0, 1.0, Translation2d())
        StaticCostmapRasterizer.circle(map, 2.0, 2.0, 0.01)
        map.inflate(1.0)
        assertTrue(hypot(2.51 - 2.0, 2.51 - 2.0) < 1.01)
        assertFalse(map.isTraversable(2.51, 2.51))
        assertTrue(map.isCellTraversable(5, 5))
    }

    @Test fun `inflation matches independent closed rectangle distance oracle`() {
        for (resolution in listOf(0.125, 0.5, 1.0)) {
            for (radiusCells in listOf(0.01, 0.5, 1.0, 1.25, 2.0, 3.5, 20.0)) {
                for (source in listOf(0 to 0, 3 to 3, 6 to 6)) {
                    val map = Costmap(7 * resolution, 7 * resolution, resolution, Translation2d())
                    map.setObstacle(source.first, source.second)
                    val radius = radiusCells * resolution
                    map.inflate(radius)
                    // Distance between closed squares from their four corner-to-rectangle distances.
                    val sourceBox = java.awt.geom.Rectangle2D.Double(
                        (source.first - 0.5) * resolution, (source.second - 0.5) * resolution,
                        resolution, resolution)
                    for (y in 0..6) for (x in 0..6) {
                        var distance = Double.POSITIVE_INFINITY
                        for (ox in listOf(-0.5, 0.5)) for (oy in listOf(-0.5, 0.5)) {
                            val px = (x + ox) * resolution
                            val py = (y + oy) * resolution
                            distance = minOf(distance, hypot(
                                px - px.coerceIn(sourceBox.minX, sourceBox.maxX),
                                py - py.coerceIn(sourceBox.minY, sourceBox.maxY)))
                        }
                        assertEquals(distance > radius, map.isCellTraversable(x, y),
                            "resolution=$resolution radius=$radius source=$source cell=$x,$y distance=$distance")
                    }
                }
            }
        }
    }

    @Test fun `reinflation shrinks the mask and preserves raw and dynamic layers`() {
        val map = Costmap(7.0, 7.0, 1.0, Translation2d())
        map.setObstacle(3, 3)
        map.insertDynamicObstacle(0.0, 0.0, 1.0, 0L)
        map.inflate(Double.MAX_VALUE)
        map.inflate(0.01)
        assertFalse(map.isCellTraversable(4, 4))
        assertTrue(map.isCellTraversable(5, 5))
        assertFalse(map.isCellTraversable(0, 0))
        assertFalse(map.isCellOccupied(4, 4))
        map.inflate(0.0)
        assertTrue(map.isCellTraversable(4, 4))
        assertFalse(map.isCellTraversable(3, 3))
        map.expireDynamicObstacles(1L, 0L)
        assertTrue(map.isCellTraversable(0, 0))
        map.clear()
        map.inflate(Double.MAX_VALUE)
        assertTrue(map.isCellTraversable(3, 3))
    }

    @Test fun `repeated inflation allocates no per obstacle buffers`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val map = Costmap(7.0, 7.0, 1.0, Translation2d())
        map.setObstacle(3, 3)
        repeat(20_000) { map.inflate(1.25) }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(10_000) { map.inflate(1.25) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(allocated <= 1024L, "Allocated $allocated bytes across 10,000 inflations")
    }
}
