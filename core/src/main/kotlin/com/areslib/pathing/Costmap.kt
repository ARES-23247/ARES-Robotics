package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import kotlin.math.roundToInt

/**
 * 2D Occupancy Grid Costmap for Global Robot Navigation and Obstacle Avoidance.
 *
 * Exposes a field grid model designed for fast obstacle intersection tests and dynamic updates.
 * Features circular bumper radius obstacle inflation to prevent the robot boundary from clipping
 * walls or structural field corners. Uses a single 1D flat boolean primitive array backing internally to avoid
 * multi-dimensional index pointer dereference overhead and dynamic allocations during execution.
 *
 * ### Mathematical Formulation:
 * 1. **World $(x,y)$ to Discrete Grid $(c, r)$ Index Mapping**:
 *    $$c = \text{round}\left(\frac{x - x_{\text{origin}}}{\text{res}}\right), \quad r = \text{round}\left(\frac{y - y_{\text{origin}}}{\text{res}}\right)$$
 * 2. **Flat 1D Row-Major Indexing**:
 *    $$\text{index}(c, r) = r \cdot N_{\text{widthCells}} + c$$
 * 3. **Circular Inflation Mask Condition**:
 *    $$\Delta c^2 + \Delta r^2 \le r_{\text{cell}}^2, \quad r_{\text{cell}} = \left\lceil \frac{r_{\text{bumper}}}{\text{res}} \right\rceil$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Dimensions $(W, H)$: Field width and height in meters ($m$)
 * - Resolution ($\text{res}$): Grid cell size in meters per cell ($m/\text{cell}$)
 * - World Coordinates $(x, y)$: Meters ($m$)
 * - Inflation Radius ($r_{\text{bumper}}$): Robot bumper radius in meters ($m$)
 *
 * ### Zero-GC Guarantee:
 * Allocates 1D primitive `BooleanArray` buffers (`grid`, `inflatedGrid`) during construction.
 * Grid queries (`isTraversable`, `isCellTraversable`) operate in $O(1)$ time with zero dynamic memory allocation.
 *
 * @property widthMeters Total width of the field in meters ($m$). Defaults to $16.0$.
 * @property heightMeters Total height of the field in meters ($m$). Defaults to $8.0$.
 * @property resolutionMeters Grid cell size resolution in meters ($m$). Defaults to $0.1$ ($10\,\text{cm}$).
 * @property origin Field-relative translation coordinate mapping to grid cell $(0,0)$ in meters ($m$).
 */
class Costmap(
    val widthMeters: Double = 16.0,
    val heightMeters: Double = 8.0,
    val resolutionMeters: Double = 0.1,
    val origin: Translation2d = Translation2d(-widthMeters / 2.0, -heightMeters / 2.0)
) {

    val widthCells: Int
    val heightCells: Int

    init {
        require(widthMeters > 0.0) { "Width must be positive" }
        require(heightMeters > 0.0) { "Height must be positive" }
        val isResolutionInvalid = resolutionMeters < 0.001 || resolutionMeters.isNaN() || resolutionMeters.isInfinite()
        if (isResolutionInvalid) {
            throw IllegalArgumentException("Resolution must be at least 1mm (0.001 meters) and not NaN/Infinite")
        }

        val w = (widthMeters / resolutionMeters).toInt()
        if (w <= 0 || w > 10000) throw IllegalArgumentException("Invalid width cells: $w")
        widthCells = w

        val h = (heightMeters / resolutionMeters).toInt()
        if (h <= 0 || h > 10000) throw IllegalArgumentException("Invalid height cells: $h")
        heightCells = h

        val cells = widthCells.toLong() * heightCells.toLong()
        require(cells > 0 && cells <= 1_000_000L) { "Grid size is too large or invalid ($cells cells). Must be under 1,000,000 cells." }
    }

    // 1D backed array for cash-locality and zero-allocation updates
    private val grid = BooleanArray(widthCells * heightCells)
    private val inflatedGrid = BooleanArray(widthCells * heightCells)

    /**
     * Resets the costmap grid state.
     */
    fun clear() {
        grid.fill(false)
        inflatedGrid.fill(false)
    }

    /**
     * Marks a cell coordinate as occupied.
     */
    fun setObstacle(cellX: Int, cellY: Int, isOccupied: Boolean = true) {
        if (cellX in 0 until widthCells && cellY in 0 until heightCells) {
            grid[cellY * widthCells + cellX] = isOccupied
        }
    }

    /**
     * Checks if a field-relative coordinate is traversable.
     */
    fun isTraversable(x: Double, y: Double): Boolean {
        val cellX = ((x - origin.x) / resolutionMeters).roundToInt()
        val cellY = ((y - origin.y) / resolutionMeters).roundToInt()
        return isCellTraversable(cellX, cellY)
    }

    /**
     * Checks if a grid cell coordinate is traversable.
     */
    fun isCellTraversable(cellX: Int, cellY: Int): Boolean {
        if (cellX !in 0 until widthCells || cellY !in 0 until heightCells) {
            return false // Out-of-bounds is non-traversable
        }
        return !inflatedGrid[cellY * widthCells + cellX]
    }

    /**
     * Sets a field-relative coordinate as an obstacle.
     */
    fun setObstacle(x: Double, y: Double, isOccupied: Boolean = true) {
        val cellX = ((x - origin.x) / resolutionMeters).roundToInt()
        val cellY = ((y - origin.y) / resolutionMeters).roundToInt()
        setObstacle(cellX, cellY, isOccupied)
    }

    /**
     * Rasterizes and registers a list of static obstacles from a field layout.
     */
    fun setStaticObstacles(obstacles: List<com.areslib.state.RobotFieldObstacle>) {
        for (obs in obstacles) {
            if (!obs.isBlocking) continue // Skip non-blocking elements like ramps

            // Obstacle boundaries in meters (from centering)
            val minX = obs.x - obs.height / 2.0
            val maxX = obs.x + obs.height / 2.0
            val minY = obs.y - obs.width / 2.0
            val maxY = obs.y + obs.width / 2.0

            // Map boundaries to grid cell ranges
            val startCellX = (((minX - origin.x) / resolutionMeters).roundToInt()).coerceIn(0, widthCells - 1)
            val endCellX = (((maxX - origin.x) / resolutionMeters).roundToInt()).coerceIn(0, widthCells - 1)
            val startCellY = (((minY - origin.y) / resolutionMeters).roundToInt()).coerceIn(0, heightCells - 1)
            val endCellY = (((maxY - origin.y) / resolutionMeters).roundToInt()).coerceIn(0, heightCells - 1)

            // Mark cells as occupied
            for (cx in startCellX..endCellX) {
                for (cy in startCellY..endCellY) {
                    setObstacle(cx, cy, true)
                }
            }
        }
    }

    /**
     * Checks if a grid cell coordinate is occupied by a raw obstacle.
     */
    fun isCellOccupied(cellX: Int, cellY: Int): Boolean {
        if (cellX !in 0 until widthCells || cellY !in 0 until heightCells) {
            return true // Out-of-bounds is considered occupied
        }
        return grid[cellY * widthCells + cellX]
    }

    /**
     * Checks if a field-relative coordinate is occupied by a raw obstacle.
     */
    fun isOccupied(x: Double, y: Double): Boolean {
        val cellX = ((x - origin.x) / resolutionMeters).roundToInt()
        val cellY = ((y - origin.y) / resolutionMeters).roundToInt()
        return isCellOccupied(cellX, cellY)
    }

    /**
     * Inflates the obstacle boundaries by the robot's physical bumper radius.
     * Prevents any paths from running the chassis edges directly into structures.
     * @param robotRadiusMeters Radius of the robot bumper boundary.
     */
    fun inflate(robotRadiusMeters: Double) {
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

    /**
     * Dynamic insert of a dynamic obstacle (e.g. an opponent robot).
     */
    fun insertDynamicObstacle(x: Double, y: Double, radiusMeters: Double) {
        val cellX = ((x - origin.x) / resolutionMeters).roundToInt()
        val cellY = ((y - origin.y) / resolutionMeters).roundToInt()
        val cellRadius = (radiusMeters / resolutionMeters).roundToInt().coerceAtLeast(1)

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

    /**
     * Rasterizes and registers static field elements into the costmap.
     */
    fun setStaticElements(
        elementTypes: List<com.areslib.state.RobotFieldElementType>,
        elements: List<com.areslib.state.RobotFieldElementInstance>
    ) {
        val typesMap = elementTypes.associateBy { it.id }
        for (el in elements) {
            val type = typesMap[el.elementTypeId] ?: continue
            if (type.movable) continue // Only static elements go into the costmap

            val halfW = if (type.shape.lowercase() == "box") type.width / 2.0 else (type.diameter ?: 0.15) / 2.0
            val halfH = if (type.shape.lowercase() == "box") type.height / 2.0 else (type.diameter ?: 0.15) / 2.0

            val minX = el.x - halfH
            val maxX = el.x + halfH
            val minY = el.y - halfW
            val maxY = el.y + halfW

            val startCellX = (((minX - origin.x) / resolutionMeters).roundToInt()).coerceIn(0, widthCells - 1)
            val endCellX = (((maxX - origin.x) / resolutionMeters).roundToInt()).coerceIn(0, widthCells - 1)
            val startCellY = (((minY - origin.y) / resolutionMeters).roundToInt()).coerceIn(0, heightCells - 1)
            val endCellY = (((maxY - origin.y) / resolutionMeters).roundToInt()).coerceIn(0, heightCells - 1)

            for (cx in startCellX..endCellX) {
                for (cy in startCellY..endCellY) {
                    setObstacle(cx, cy, true)
                }
            }
        }
    }

    companion object {
        /**
         * Factory to create and inflate a Costmap directly from a RobotFieldConfig.
         */
        fun fromFieldConfig(
            config: com.areslib.state.RobotFieldConfig,
            robotRadiusMeters: Double = if (config.fieldType == com.areslib.state.FieldType.FRC) 0.60 else 0.35
        ): Costmap {
            val width = if (config.fieldType == com.areslib.state.FieldType.FRC) 16.0 else 3.66
            val height = if (config.fieldType == com.areslib.state.FieldType.FRC) 8.0 else 3.66
            val origin = if (config.fieldType == com.areslib.state.FieldType.FRC) {
                Translation2d(0.0, 0.0)
            } else {
                Translation2d(-width / 2.0, -height / 2.0)
            }
            val costmap = Costmap(width, height, 0.1, origin)
            costmap.setStaticObstacles(config.obstacles)
            costmap.setStaticElements(config.elementTypes, config.elements)
            costmap.inflate(robotRadiusMeters)
            return costmap
        }
    }
}
