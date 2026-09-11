package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.planner.LineOfSightChecker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LineOfSightCheckerTest {
    @Test
    fun `repeated traversal has no per query allocation`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val map = Costmap(100.0, 100.0, 1.0, Translation2d())
        var visible = 0
        repeat(20_000) { if (LineOfSightChecker.lineOfSight(map, 0, 0, 99, it % 100)) visible++ }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(10_000) { if (LineOfSightChecker.lineOfSight(map, 0, 0, 99, it % 100)) visible++ }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(30_000, visible)
        assertTrue(allocated <= 1024L, "Allocated $allocated bytes in 10,000 queries")
    }

    @Test
    fun `grid traversal matches closed cell intersection in every direction`() {
        val map = Costmap(5.0, 5.0, 1.0, Translation2d())
        // Isolate traversal from inflation: each fixture has exactly one blocked cell.
        val field = Costmap::class.java.getDeclaredField("inflatedGrid").apply { isAccessible = true }
        val grid = field.get(map) as BooleanArray
        for (blockedY in 0..4) for (blockedX in 0..4) {
            grid.fill(false)
            grid[blockedY * 5 + blockedX] = true
            for (y0 in 0..4) for (x0 in 0..4) for (y1 in 0..4) for (x1 in 0..4) {
                val expected = !intersects(x0, y0, x1, y1, blockedX, blockedY)
                assertEquals(expected, LineOfSightChecker.lineOfSight(map, x0, y0, x1, y1),
                    "($x0,$y0)->($x1,$y1), blocked ($blockedX,$blockedY)")
            }
        }
    }

    // Independent slab intersection of the segment with the closed square centered on a cell.
    private fun intersects(x0: Int, y0: Int, x1: Int, y1: Int, bx: Int, by: Int): Boolean {
        var enter = 0.0
        var exit = 1.0
        for (axis in 0..1) {
            val start = if (axis == 0) x0 else y0
            val delta = (if (axis == 0) x1 - x0 else y1 - y0).toDouble()
            val center = if (axis == 0) bx else by
            if (delta == 0.0) {
                if (start < center - 0.5 || start > center + 0.5) return false
            } else {
                val a = (center - 0.5 - start) / delta
                val b = (center + 0.5 - start) / delta
                enter = maxOf(enter, minOf(a, b))
                exit = minOf(exit, maxOf(a, b))
                if (enter > exit) return false
            }
        }
        return true
    }

    @Test
    fun `out of bounds endpoints fail closed including extreme integers`() {
        val map = Costmap(5.0, 5.0, 1.0, Translation2d())
        for (bad in listOf(-1, 5, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse(LineOfSightChecker.lineOfSight(map, bad, 0, 2, 2))
            assertFalse(LineOfSightChecker.lineOfSight(map, 2, 2, bad, 0))
            assertFalse(LineOfSightChecker.lineOfSight(map, 0, bad, 2, 2))
            assertFalse(LineOfSightChecker.lineOfSight(map, 2, 2, 0, bad))
        }
    }
}
