package com.areslib.pathing

import com.areslib.state.RobotFieldPoint
import kotlin.math.*

/** Closed-shape/closed-cell intersections during static field setup; bounds are clipped to the grid. */
internal object StaticCostmapRasterizer {
    fun rectangle(map: Costmap, x: Double, y: Double, width: Double, height: Double, degrees: Double) {
        require(x.isFinite() && y.isFinite() && degrees.isFinite() &&
            width.isFinite() && width > 0.0 && height.isFinite() && height > 0.0) { "Invalid static rectangle" }
        val normalized = degrees % 360.0
        val angle = Math.toRadians(normalized)
        // Exact quarter turns must not lose tangent cells to residual cos(PI / 2).
        val c = when (normalized) {
            0.0 -> 1.0
            90.0, -90.0, 270.0, -270.0 -> 0.0
            180.0, -180.0 -> -1.0
            else -> cos(angle)
        }
        val s = when (normalized) {
            0.0, 180.0, -180.0 -> 0.0
            90.0, -270.0 -> 1.0
            -90.0, 270.0 -> -1.0
            else -> sin(angle)
        }
        val ac = abs(c)
        val asn = abs(s)
        val halfW = width / 2.0
        val halfH = height / 2.0
        val extentX = ac * halfW + asn * halfH
        val extentY = asn * halfW + ac * halfH
        // Compute the four separating-axis bounds once. Scaling avoids overflow in dot products.
        val scale = maxOf(halfW, halfH, map.resolutionMeters / 2.0)
        val cell = map.resolutionMeters / 2.0 / scale
        val w = halfW / scale
        val h = halfH / scale
        val boundX = ac * w + asn * h + cell
        val boundY = asn * w + ac * h + cell
        val localX = w + cell * (ac + asn)
        val localY = h + cell * (ac + asn)
        rasterize(map, x - extentX, y - extentY, x + extentX, y + extentY) { px, py, _ ->
            val nx = (px - x) / scale
            val ny = (py - y) / scale
            abs(nx) <= boundX && abs(ny) <= boundY &&
                abs(c * nx + s * ny) <= localX && abs(-s * nx + c * ny) <= localY
        }
    }

    fun circle(map: Costmap, x: Double, y: Double, radius: Double) {
        require(x.isFinite() && y.isFinite() && radius.isFinite() && radius > 0.0) { "Invalid static circle" }
        rasterize(map, x - radius, y - radius, x + radius, y + radius) { px, py, half ->
            val dx = maxOf(abs(px - x) - half, 0.0)
            val dy = maxOf(abs(py - y) - half, 0.0)
            hypot(dx, dy) <= radius
        }
    }

    /** Polygon vertices are absolute field coordinates, as in the editor and field physics loader. */
    fun polygon(map: Costmap, points: List<RobotFieldPoint>) {
        require(points.size >= 3) { "Invalid static polygon" }
        var minX = points[0].x
        var maxX = minX
        var minY = points[0].y
        var maxY = minY
        for (i in points.indices) {
            val point = points[i]
            require(point.x.isFinite() && point.y.isFinite()) { "Invalid static polygon" }
            minX = minOf(minX, point.x)
            maxX = maxOf(maxX, point.x)
            minY = minOf(minY, point.y)
            maxY = maxOf(maxY, point.y)
        }
        require((maxX - minX).isFinite() && (maxY - minY).isFinite()) { "Polygon span is not representable" }
        rasterize(map, minX, minY, maxX, maxY) { x, y, half ->
            intersectsPolygon(points, x, y, half)
        }
    }

    private fun intersectsPolygon(points: List<RobotFieldPoint>, x: Double, y: Double, half: Double): Boolean {
        var inside = false
        var previous = points.last()
        for (i in points.indices) {
            val point = points[i]
            if (intersectsSegment(previous.x, previous.y, point.x, point.y,
                    x - half, y - half, x + half, y + half)) return true
            if ((previous.y > y) != (point.y > y)) {
                val crossing = previous.x + (y - previous.y) / (point.y - previous.y) * (point.x - previous.x)
                if (x < crossing) inside = !inside
            }
            previous = point
        }
        return inside
    }

    private fun intersectsSegment(ax: Double, ay: Double, bx: Double, by: Double,
                                  minX: Double, minY: Double, maxX: Double, maxY: Double): Boolean {
        var enter = 0.0
        var exit = 1.0
        for (axis in 0..1) {
            val start = if (axis == 0) ax else ay
            val delta = if (axis == 0) bx - ax else by - ay
            val min = if (axis == 0) minX else minY
            val max = if (axis == 0) maxX else maxY
            if (delta == 0.0) {
                if (start < min || start > max) return false
            } else {
                val first = (min - start) / delta
                val second = (max - start) / delta
                enter = maxOf(enter, minOf(first, second))
                exit = minOf(exit, maxOf(first, second))
                if (enter > exit) return false
            }
        }
        return true
    }

    private inline fun rasterize(map: Costmap, minX: Double, minY: Double, maxX: Double, maxY: Double,
                                 intersects: (Double, Double, Double) -> Boolean) {
        val resolution = map.resolutionMeters
        val half = resolution / 2.0
        require(minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) { "Shape bounds are not representable" }
        require((map.origin.x - half).isFinite() && (map.origin.y - half).isFinite() &&
            (map.origin.x + (map.widthCells - 1) * resolution + half).isFinite() &&
            (map.origin.y + (map.heightCells - 1) * resolution + half).isFinite()) { "Grid bounds are not representable" }
        // Include cells whose boundary touches the shape, including the lower side of exact ties.
        val firstX = ceil((minX - map.origin.x) / resolution - 0.5).toInt().coerceAtLeast(0)
        val lastX = floor((maxX - map.origin.x) / resolution + 0.5).toInt().coerceAtMost(map.widthCells - 1)
        val firstY = ceil((minY - map.origin.y) / resolution - 0.5).toInt().coerceAtLeast(0)
        val lastY = floor((maxY - map.origin.y) / resolution + 0.5).toInt().coerceAtMost(map.heightCells - 1)
        for (cy in firstY..lastY) {
            val y = map.origin.y + cy * resolution
            for (cx in firstX..lastX) {
                val x = map.origin.x + cx * resolution
                if (intersects(x, y, half)) map.setObstacle(cx, cy)
            }
        }
    }
}
