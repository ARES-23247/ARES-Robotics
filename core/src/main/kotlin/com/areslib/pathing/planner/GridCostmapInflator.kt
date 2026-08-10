package com.areslib.pathing.planner

import com.areslib.pathing.Costmap

/**
 * Occupancy Grid Obstacle Inflation and Dynamic Obstacle Inserter.
 *
 * Expands raw obstacle cells outward by the robot's physical bumper radius $r_{\text{bumper}}$,
 * preventing trajectory waypoints from placing robot chassis edges into field structures.
 *
 * ### Mathematical Formulation:
 * Inflation Cell Offset Condition:
 * $$\Delta c^2 + \Delta r^2 \le r_{\text{cell}}^2, \quad r_{\text{cell}} = \left\lceil \frac{r_{\text{bumper}}}{\text{res}} \right\rceil$$
 *
 * ### Zero-GC Guarantee:
 * Mutates 1D flat boolean arrays (`inflatedGrid`) in-place with zero dynamic allocations.
 */
object GridCostmapInflator {
    /**
     * Inflates occupied obstacle cells in [grid] outward into [inflatedGrid] by [robotRadiusMeters].
     *
     * @param grid Source raw occupancy boolean array.
     * @param inflatedGrid Destination inflated occupancy boolean array.
     * @param widthCells Grid column count ($N_{\text{cols}}$).
     * @param heightCells Grid row count ($N_{\text{rows}}$).
     * @param robotRadiusMeters Robot physical bumper radius in meters ($m$).
     * @param resolutionMeters Grid cell size resolution in meters ($m$).
     */
    fun inflate(
        grid: BooleanArray,
        inflatedGrid: BooleanArray,
        widthCells: Int,
        heightCells: Int,
        robotRadiusMeters: Double,
        resolutionMeters: Double
    ) {

        inflatedGrid.fill(false)
        val cellRadius = (robotRadiusMeters / resolutionMeters).toInt().coerceAtLeast(1)

        for (cy in 0 until heightCells) {
            for (cx in 0 until widthCells) {
                if (grid[cy * widthCells + cx]) {
                    // Inflate outward in a circular radius
                    for (dy in -cellRadius..cellRadius) {
                        for (dx in -cellRadius..cellRadius) {
                            if (dx * dx + dy * dy <= cellRadius * cellRadius) {
                                val nx = cx + dx
                                val ny = cy + dy
                                if (nx in 0 until widthCells && ny in 0 until heightCells) {
                                    inflatedGrid[ny * widthCells + nx] = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    fun insertDynamicObstacle(
        inflatedGrid: BooleanArray,
        widthCells: Int,
        heightCells: Int,
        cellX: Int,
        cellY: Int,
        radiusMeters: Double,
        resolutionMeters: Double
    ) {
        val cellRadius = (radiusMeters / resolutionMeters).toInt().coerceAtLeast(1)

        for (dy in -cellRadius..cellRadius) {
            for (dx in -cellRadius..cellRadius) {
                if (dx * dx + dy * dy <= cellRadius * cellRadius) {
                    val nx = cellX + dx
                    val ny = cellY + dy
                    if (nx in 0 until widthCells && ny in 0 until heightCells) {
                        inflatedGrid[ny * widthCells + nx] = true
                    }
                }
            }
        }
    }
}
