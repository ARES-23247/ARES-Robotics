package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThetaStarEndpointGeometryTest {
    @Test fun `invalid normalized coordinates return no path`() {
        for (origin in listOf(Translation2d(Double.NaN, 0.0), Translation2d(-Double.MAX_VALUE, 0.0))) {
            val map = Costmap(6.0, 6.0, 1.0, origin)
            assertTrue(ThetaStarPlanner.plan(map, Translation2d(Double.MAX_VALUE, 0.0),
                Translation2d(Double.MAX_VALUE, 1.0)).isEmpty())
        }
    }

    @Test fun `exact endpoints cannot turn a clear grid shortcut into a collision`() {
        val map = Costmap(6.0, 6.0, 1.0, Translation2d())
        map.setObstacle(1, 2)
        map.inflate(0.0)
        val a = Translation2d(0.51, 1.49)
        val b = Translation2d(3.49, 2.49)
        for ((start, end) in listOf(a to b, b to a)) {
            val path = ThetaStarPlanner.plan(map, start, end)
            assertTrue(path.isNotEmpty())
            assertEquals(start, path.first())
            assertEquals(end, path.last())
            for (i in 1 until path.size) {
                assertFalse(intersects(path[i - 1], path[i], 1, 2), "Collision in $path")
            }
        }
    }

    @Test fun `endpoints touching an occupied cell boundary fail closed`() {
        val map = Costmap(6.0, 6.0, 1.0, Translation2d())
        map.setObstacle(1, 2)
        map.inflate(0.0)
        for (point in listOf(Translation2d(1.5, 2.0), Translation2d(1.5, 2.5))) {
            assertTrue(ThetaStarPlanner.plan(map, point, Translation2d(4.0, 4.0)).isEmpty())
            assertTrue(ThetaStarPlanner.plan(map, Translation2d(4.0, 4.0), point).isEmpty())
            assertTrue(ThetaStarPlanner.plan(map, point, point).isEmpty())
        }
    }

    @Test fun `fractional endpoint matrix stays clear of occupied squares`() {
        val map = Costmap(6.0, 6.0, 1.0, Translation2d())
        map.setObstacle(1, 2)
        map.setObstacle(3, 3)
        map.inflate(0.0)
        for (sx in listOf(-0.49, 0.0, 0.49)) for (sy in listOf(-0.49, 0.0, 0.49)) {
            for (ex in listOf(-0.49, 0.0, 0.49)) for (ey in listOf(-0.49, 0.0, 0.49)) {
                val start = Translation2d(1.0 + sx, 1.0 + sy)
                val end = Translation2d(4.0 + ex, 2.0 + ey)
                val path = ThetaStarPlanner.plan(map, start, end)
                assertTrue(path.isNotEmpty())
                assertEquals(start, path.first())
                assertEquals(end, path.last())
                for (i in 1 until path.size) {
                    assertFalse(intersects(path[i - 1], path[i], 1, 2))
                    assertFalse(intersects(path[i - 1], path[i], 3, 3))
                }
            }
        }
    }

    private fun intersects(a: Translation2d, b: Translation2d, x: Int, y: Int): Boolean {
        var enter = 0.0
        var exit = 1.0
        for (axis in 0..1) {
            val start = if (axis == 0) a.x else a.y
            val delta = (if (axis == 0) b.x else b.y) - start
            val center = if (axis == 0) x else y
            if (delta == 0.0) {
                if (start < center - 0.5 || start > center + 0.5) return false
            } else {
                val first = (center - 0.5 - start) / delta
                val second = (center + 0.5 - start) / delta
                enter = maxOf(enter, minOf(first, second))
                exit = minOf(exit, maxOf(first, second))
                if (enter > exit) return false
            }
        }
        return true
    }
}
