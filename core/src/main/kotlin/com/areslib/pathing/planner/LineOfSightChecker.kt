package com.areslib.pathing.planner

import com.areslib.pathing.Costmap

/**
 * Integer Bresenham Line-of-Sight Ray-Casting Grid Collision Checker.
 *
 * Implements an $O(N)$ integer-arithmetic Bresenham ray-casting algorithm to test whether a straight-line
 * path segment between grid cells $(x_0, y_0)$ and $(x_1, y_1)$ crosses any inflated obstacle cells in [Costmap].
 * Essential for Theta* any-angle parent shortcut checks.
 *
 * ### Mathematical Formulation:
 * Discrete Bresenham Error Update Step:
 * $$e_{k+1} = e_k + 2 \Delta x - 2 \Delta y$$
 *
 * ### Zero-GC Guarantee:
 * Uses primitive scalar integer arithmetic with zero memory allocation.
 */
object LineOfSightChecker {
    /**
     * Checks if a straight line segment from $(x_0, y_0)$ to $(x_1, y_1)$ is 100% collision-free.
     *
     * @param costmap Inflated obstacle grid costmap [Costmap].
     * @param x0 Start cell column index.
     * @param y0 Start cell row index.
     * @param x1 End cell column index.
     * @param y1 End cell row index.
     * @return True if straight line contains zero non-traversable costmap cells.
     */
    fun lineOfSight(costmap: Costmap, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {

        var cx = x0
        var cy = y0

        val dx = kotlin.math.abs(x1 - x0)
        val dy = kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy

        while (true) {
            if (!costmap.isCellTraversable(cx, cy)) {
                return false
            }

            if (cx == x1 && cy == y1) break

            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                cx += sx
            }
            if (e2 < dx) {
                err += dx
                cy += sy
            }
        }

        return true
    }
}
