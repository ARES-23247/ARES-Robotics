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
 * 3. **Positive-radius Inflation Mask Condition** (distance between closed cell squares):
 *    $$\max(|\Delta c|-1,0)^2 + \max(|\Delta r|-1,0)^2 \le (r_{\text{bumper}}/\text{res})^2$$
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
    // Static inflation and dynamic occupancy are separate layers. This prevents expiry of
    // one dynamic obstacle from erasing a static obstacle or another overlapping obstacle.
    private val inflatedGrid = BooleanArray(widthCells * heightCells)
    private val dynamicOccupancyCounts = IntArray(widthCells * heightCells)
    
    private val maxDynamicObstacles = 100
    private val dynObsX = IntArray(maxDynamicObstacles)
    private val dynObsY = IntArray(maxDynamicObstacles)
    private val dynObsRadius = IntArray(maxDynamicObstacles)
    private val dynObsTimeMs = LongArray(maxDynamicObstacles)
    private var dynObsCount = 0

    /**
     * Resets the costmap grid state.
     */
    fun clear() {
        grid.fill(false)
        inflatedGrid.fill(false)
        dynamicOccupancyCounts.fill(0)
        dynObsCount = 0
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
        val index = cellY * widthCells + cellX
        return !inflatedGrid[index] && dynamicOccupancyCounts[index] == 0
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
        for (obstacle in obstacles) {
            if (!obstacle.isBlocking) continue
            when {
                obstacle.shape.equals("rectangle", ignoreCase = true) -> StaticCostmapRasterizer.rectangle(
                    this, obstacle.x, obstacle.y, obstacle.width, obstacle.height, obstacle.rotation)
                obstacle.shape.equals("circle", ignoreCase = true) -> StaticCostmapRasterizer.circle(
                    this, obstacle.x, obstacle.y, obstacle.width) // Canonical circle width stores radius.
                obstacle.shape.equals("polygon", ignoreCase = true) -> StaticCostmapRasterizer.polygon(this, obstacle.points)
                else -> throw IllegalArgumentException("Unsupported static obstacle shape: ${obstacle.shape}")
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
        val index = cellY * widthCells + cellX
        return grid[index] || dynamicOccupancyCounts[index] > 0
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
     * Every occupied cell is treated as a closed square. A target cell is blocked if any
     * position in it is within the bumper radius of an occupied square, including tangency.
     * This conservatively protects off-center waypoints as well as cell-center paths.
     * @param robotRadiusMeters Finite non-negative bumper radius; zero copies raw occupancy without a halo.
     */
    fun inflate(robotRadiusMeters: Double) {
        require(robotRadiusMeters.isFinite() && robotRadiusMeters >= 0.0) {
            "Inflation radius must be finite and non-negative"
        }
        if (robotRadiusMeters == 0.0) {
            grid.copyInto(inflatedGrid)
            return
        }
        val radius = robotRadiusMeters / resolutionMeters
        val maxDx = maxOf(widthCells - 2, 0).toDouble()
        val maxDy = maxOf(heightCells - 2, 0).toDouble()
        // Any occupied cell covers the entire grid at this radius. Avoid an O(cells^2) scan.
        if (radius >= kotlin.math.hypot(maxDx, maxDy)) {
            inflatedGrid.fill(grid.any { it })
            return
        }
        inflatedGrid.fill(false)
        // The grid-wide shortcut bounds radius before conversion and squaring, even if
        // finite meters divided by resolution overflowed to infinity.
        val extent = kotlin.math.ceil(radius).toInt() + 1
        val radiusSquared = radius * radius
        for (cy in 0 until heightCells) {
            for (cx in 0 until widthCells) {
                if (!grid[cy * widthCells + cx]) continue
                val minX = maxOf(0, cx - extent)
                val maxX = minOf(widthCells - 1, cx + extent)
                val minY = maxOf(0, cy - extent)
                val maxY = minOf(heightCells - 1, cy + extent)
                for (y in minY..maxY) {
                    val dy = maxOf(kotlin.math.abs(y - cy) - 1, 0)
                    val remainingSquared = radiusSquared - dy * dy
                    if (remainingSquared < 0.0) continue
                    val row = y * widthCells
                    for (x in minX..maxX) {
                        if (inflatedGrid[row + x]) continue
                        val dx = maxOf(kotlin.math.abs(x - cx) - 1, 0)
                        if (dx * dx <= remainingSquared) inflatedGrid[row + x] = true
                    }
                }
            }
        }
    }

    /**
     * Applies a quantized circle only within grid bounds. Delta +1/-1 updates dynamic
     * reference counts using exactly the same mask on insert and expiry.
     * Long differences and subtraction from radius squared avoid Int and squared-sum overflow.
     */
    private fun rasterizeCircle(cellX: Int, cellY: Int, cellRadius: Int, delta: Int) {
        val radius = cellRadius.toLong()
        val radiusSquared = radius * radius
        val minX = maxOf(0L, cellX.toLong() - radius).toInt()
        val maxX = minOf(widthCells - 1L, cellX.toLong() + radius).toInt()
        val minY = maxOf(0L, cellY.toLong() - radius).toInt()
        val maxY = minOf(heightCells - 1L, cellY.toLong() + radius).toInt()
        for (y in minY..maxY) {
            val dy = y.toLong() - cellY
            val remainingSquared = radiusSquared - dy * dy
            for (x in minX..maxX) {
                val dx = x.toLong() - cellX
                if (dx * dx <= remainingSquared) {
                    val index = y * widthCells + x
                    dynamicOccupancyCounts[index] += delta
                }
            }
        }
    }

    /**
     * Dynamic insert of a dynamic obstacle (e.g. an opponent robot).
     */
    fun insertDynamicObstacle(x: Double, y: Double, radiusMeters: Double, timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()) {
        if (!x.isFinite() || !y.isFinite() || !radiusMeters.isFinite() || radiusMeters <= 0.0) return
        // Never rasterize an obstacle that cannot be tracked and expired later.
        if (dynObsCount >= maxDynamicObstacles) return
        val cellX = ((x - origin.x) / resolutionMeters).roundToInt()
        val cellY = ((y - origin.y) / resolutionMeters).roundToInt()
        val cellRadius = kotlin.math.ceil(radiusMeters / resolutionMeters).toInt().coerceAtLeast(1)

        dynObsX[dynObsCount] = cellX
        dynObsY[dynObsCount] = cellY
        dynObsRadius[dynObsCount] = cellRadius
        dynObsTimeMs[dynObsCount] = timestampMs
        dynObsCount++

        rasterizeCircle(cellX, cellY, cellRadius, 1)
    }

    fun expireDynamicObstacles(currentTimeMs: Long, maxAgeMs: Long) {
        require(maxAgeMs >= 0L) { "maxAgeMs must be non-negative" }
        var i = 0
        while (i < dynObsCount) {
            val observedAt = dynObsTimeMs[i]
            val age = currentTimeMs - observedAt
            if (currentTimeMs >= observedAt && (age < 0L || age > maxAgeMs)) {
                rasterizeCircle(dynObsX[i], dynObsY[i], dynObsRadius[i], -1)

                dynObsCount--
                if (i < dynObsCount) {
                    dynObsX[i] = dynObsX[dynObsCount]
                    dynObsY[i] = dynObsY[dynObsCount]
                    dynObsRadius[i] = dynObsRadius[dynObsCount]
                    dynObsTimeMs[i] = dynObsTimeMs[dynObsCount]
                }
            } else {
                i++
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
        require(typesMap.size == elementTypes.size) { "Duplicate field element type IDs" }
        for (element in elements) {
            val type = requireNotNull(typesMap[element.elementTypeId]) { "Unknown field element type: ${element.elementTypeId}" }
            if (type.movable) continue
            when {
                type.shape.equals("box", ignoreCase = true) -> StaticCostmapRasterizer.rectangle(
                    this, element.x, element.y, type.width, type.height, element.rotation)
                type.shape.equals("circle", ignoreCase = true) || type.shape.equals("cylinder", ignoreCase = true) ||
                    type.shape.equals("sphere", ignoreCase = true) ->
                    StaticCostmapRasterizer.circle(this, element.x, element.y, (type.diameter ?: type.width) / 2.0)
                else -> throw IllegalArgumentException("Unsupported static element shape: ${type.shape}")
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
            val width = if (config.fieldType == com.areslib.state.FieldType.FRC) 16.0
            else com.areslib.math.coordinate.CoordinateTransformers.FTC_FIELD_SIZE
            val height = if (config.fieldType == com.areslib.state.FieldType.FRC) 8.0
            else com.areslib.math.coordinate.CoordinateTransformers.FTC_FIELD_SIZE
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
