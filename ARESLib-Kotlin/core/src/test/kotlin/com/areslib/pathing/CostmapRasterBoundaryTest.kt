package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CostmapRasterBoundaryTest {
    private fun map() = Costmap(7.0, 7.0, 1.0, Translation2d())

    @Test fun `zero inflation copies occupancy without adding a one cell halo`() {
        val map = map()
        map.setObstacle(3, 3)
        map.inflate(0.0)
        for (y in 0..6) for (x in 0..6) {
            assertEquals(x != 3 || y != 3, map.isCellTraversable(x, y))
        }
    }

    @Test fun `invalid inflation cannot replace the previous safe layer`() {
        val map = map()
        map.setObstacle(3, 3)
        map.inflate(2.0)
        for (radius in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { map.inflate(radius) }
            assertFalse(map.isCellTraversable(5, 3))
        }
    }

    @Test fun `expiry handles overflowing elapsed time without expiring future observations`() {
        val map = map()
        map.insertDynamicObstacle(3.0, 3.0, 1.0, Long.MIN_VALUE)
        map.expireDynamicObstacles(Long.MAX_VALUE, Long.MAX_VALUE)
        assertTrue(map.isCellTraversable(3, 3))
        map.insertDynamicObstacle(3.0, 3.0, 1.0, Long.MAX_VALUE)
        map.expireDynamicObstacles(Long.MIN_VALUE, 0L)
        assertFalse(map.isCellTraversable(3, 3))
    }

    @Test fun `large radii are bounded by map size and expire the same mask`() {
        assertTimeout(java.time.Duration.ofSeconds(2)) {
            val map = map()
            map.setObstacle(3, 3)
            map.inflate(Double.MAX_VALUE)
            for (y in 0..6) for (x in 0..6) assertFalse(map.isCellTraversable(x, y))
            map.clear()
            map.insertDynamicObstacle(3.0, 3.0, 50_000.0, 0L)
            for (y in 0..6) for (x in 0..6) assertFalse(map.isCellTraversable(x, y))
            map.expireDynamicObstacles(2L, 1L)
            for (y in 0..6) for (x in 0..6) assertTrue(map.isCellTraversable(x, y))
        }
    }

    @Test fun `clipped circles match independent distance masks and preserve overlapping layers`() {
        for (cx in listOf(-2, 3, 9)) for (cy in listOf(-2, 3, 9)) for (r in listOf(1, 2, 8)) {
            val map = map()
            map.setObstacle(6, 6)
            map.inflate(0.0)
            map.insertDynamicObstacle(cx.toDouble(), cy.toDouble(), r.toDouble(), 0L)
            map.insertDynamicObstacle(cx.toDouble(), cy.toDouble(), r.toDouble(), 10L)
            for (time in listOf(0L, 6L)) {
                map.expireDynamicObstacles(time, 5L)
                for (y in 0..6) for (x in 0..6) {
                    val blocked = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r ||
                        (x == 6 && y == 6)
                    assertEquals(!blocked, map.isCellTraversable(x, y), "center $cx,$cy radius $r at $x,$y")
                }
            }
            map.expireDynamicObstacles(16L, 5L)
            for (y in 0..6) for (x in 0..6) assertEquals(x != 6 || y != 6, map.isCellTraversable(x, y))
        }
    }

    @Test fun `extreme quantized circles retain exact tangent cells without integer overflow`() {
        val map = map()
        map.insertDynamicObstacle(Int.MAX_VALUE.toDouble(), 0.0, Int.MAX_VALUE.toDouble(), 0L)
        assertFalse(map.isCellTraversable(0, 0)) // Exact tangency.
        assertTrue(map.isCellTraversable(0, 1)) // One unit squared beyond the circle.
        assertFalse(map.isCellTraversable(1, 1))
        map.expireDynamicObstacles(1L, 0L)
        assertTrue(map.isCellTraversable(0, 0))
        map.insertDynamicObstacle(Int.MIN_VALUE.toDouble(), 0.0, Int.MAX_VALUE.toDouble(), 2L)
        for (y in 0..6) for (x in 0..6) assertTrue(map.isCellTraversable(x, y))
    }

    @Test fun `dynamic insertion and expiry allocate no per update buffers`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val map = map()
        repeat(20_000) {
            map.insertDynamicObstacle(3.0, 3.0, 2.0, 0L)
            map.expireDynamicObstacles(2L, 1L)
        }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(10_000) {
            map.insertDynamicObstacle(3.0, 3.0, 2.0, 0L)
            map.expireDynamicObstacles(2L, 1L)
        }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(map.isCellTraversable(3, 3))
        assertTrue(allocated <= 1024L, "Allocated $allocated bytes in 10,000 insert/expire pairs")
    }
}
