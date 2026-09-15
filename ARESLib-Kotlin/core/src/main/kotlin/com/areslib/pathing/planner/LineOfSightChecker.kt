package com.areslib.pathing.planner

import com.areslib.pathing.Costmap

/**
 * Allocation-free traversal of every closed grid cell touched by a segment between cell centers.
 *
 * Compares the next X/Y boundary crossing times using integer cross-products, without division.
 * Equal crossing times touch a corner: both side cells must also be traversable. This preserves
 * collision checks in every octant and gives the same result when endpoints are reversed.
 * Work is O(abs(x1 - x0) + abs(y1 - y0)); no temporary collections or geometry are created.
 */
object LineOfSightChecker {
    /** Returns false for blocked or out-of-bounds endpoints, including a zero-length segment. */
    fun lineOfSight(costmap: Costmap, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        // Validate before subtracting arbitrary caller coordinates. Valid grid dimensions are bounded.
        if (!costmap.isCellTraversable(x0, y0) || !costmap.isCellTraversable(x1, y1)) return false
        val dx = kotlin.math.abs(x1 - x0)
        val dy = kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var x = x0
        var y = y0
        var crossedX = 0
        var crossedY = 0
        while (x != x1 || y != y1) {
            val nextX = (2L * crossedX + 1L) * dy
            val nextY = (2L * crossedY + 1L) * dx
            when {
                nextX < nextY -> {
                    x += sx
                    crossedX++
                }
                nextY < nextX -> {
                    y += sy
                    crossedY++
                }
                else -> {
                    if (!costmap.isCellTraversable(x + sx, y) ||
                        !costmap.isCellTraversable(x, y + sy)) return false
                    x += sx
                    y += sy
                    crossedX++
                    crossedY++
                }
            }
            if (!costmap.isCellTraversable(x, y)) return false
        }
        return true
    }
}
