package org.aresfirst.starter.frc

import com.areslib.math.wrapAngle
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldValidator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Conservative educational collision model, compiled once per field replacement.
 * Rectangles use obstacle-local expanded bounds; circles/polygons use the bumper's bounding
 * circle. Translation sweeps each resolved axis and rotation bounds the entire requested arc.
 * These conservative envelopes may stop short of exact bumper contact; this is not rigid-body
 * contact physics. Queries allocate no objects and do not loop over distance or angle magnitude.
 */
internal class StarterDriveCollision(config: RobotFieldConfig, private val halfLength: Double, private val halfWidth: Double) {
    init {
        require(RobotFieldValidator.validate(config, FieldType.FRC).isEmpty()) {
            "FRC simulation requires a valid canonical field document"
        }
    }

    private val width = config.resolvedWidthMeters
    private val height = config.resolvedHeightMeters
    private val radius = hypot(halfLength, halfWidth)
    private val obstacles = config.obstacles.filter { it.isBlocking }.map(::Obstacle).toTypedArray()

    fun isPoseFree(x: Double, y: Double, heading: Double): Boolean = isRotationFree(x, y, heading, 0.0)

    fun isRotationFree(x: Double, y: Double, heading: Double, delta: Double): Boolean {
        if (!x.isFinite() || !y.isFinite() || !heading.isFinite() || !delta.isFinite()) return false
        val xExtent = maximumExtent(halfLength, halfWidth, heading, delta)
        val yExtent = maximumExtent(halfWidth, halfLength, heading, delta)
        if (x - xExtent < 0.0 || x + xExtent > width || y - yExtent < 0.0 || y + yExtent > height) return false
        for (obstacle in obstacles) {
            when (obstacle.shape) {
                "circle" -> if (hypot(x - obstacle.x, y - obstacle.y) <= obstacle.circleRadius + radius) return false
                "polygon" -> if (polygonBlocks(obstacle, x, y, x, y)) return false
                else -> {
                    val dx = x - obstacle.x
                    val dy = y - obstacle.y
                    val localX = dx * obstacle.c + dy * obstacle.s
                    val localY = -dx * obstacle.s + dy * obstacle.c
                    if (!localX.isFinite() || !localY.isFinite()) return false
                    val relative = heading - obstacle.heading
                    val extentX = maximumExtent(halfLength, halfWidth, relative, delta)
                    val extentY = maximumExtent(halfWidth, halfLength, relative, delta)
                    if (abs(localX) <= obstacle.halfX + extentX && abs(localY) <= obstacle.halfY + extentY) return false
                }
            }
        }
        return true
    }

    fun isTranslationFree(ax: Double, ay: Double, bx: Double, by: Double, heading: Double): Boolean {
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite() || !heading.isFinite()) return false
        val c = abs(cos(heading))
        val s = abs(sin(heading))
        val xExtent = c * halfLength + s * halfWidth
        val yExtent = s * halfLength + c * halfWidth
        if (min(ax, bx) - xExtent < 0.0 || max(ax, bx) + xExtent > width ||
            min(ay, by) - yExtent < 0.0 || max(ay, by) + yExtent > height) return false
        // The field boundary is convex and heading is fixed during each axis movement.
        for (obstacle in obstacles) {
            when (obstacle.shape) {
                "circle" -> if (distanceToSegment(obstacle.x, obstacle.y, ax, ay, bx, by) <= obstacle.circleRadius + radius) return false
                "polygon" -> if (polygonBlocks(obstacle, ax, ay, bx, by)) return false
                else -> {
                    val adx = ax - obstacle.x
                    val ady = ay - obstacle.y
                    val bdx = bx - obstacle.x
                    val bdy = by - obstacle.y
                    val relative = heading - obstacle.heading
                    val c = abs(cos(relative))
                    val s = abs(sin(relative))
                    if (segmentIntersectsBox(
                        adx * obstacle.c + ady * obstacle.s, -adx * obstacle.s + ady * obstacle.c,
                        bdx * obstacle.c + bdy * obstacle.s, -bdx * obstacle.s + bdy * obstacle.c,
                        obstacle.halfX + c * halfLength + s * halfWidth,
                        obstacle.halfY + s * halfLength + c * halfWidth,
                    )) return false
                }
            }
        }
        return true
    }

    private fun polygonBlocks(obstacle: Obstacle, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val points = obstacle.points
        var inside = false
        var previous = points.lastIndex
        for (current in points.indices) {
            val p = points[previous]
            val q = points[current]
            if (segmentsIntersect(ax, ay, bx, by, p.x, p.y, q.x, q.y) ||
                distanceToSegment(ax, ay, p.x, p.y, q.x, q.y) <= radius ||
                distanceToSegment(bx, by, p.x, p.y, q.x, q.y) <= radius ||
                distanceToSegment(p.x, p.y, ax, ay, bx, by) <= radius ||
                distanceToSegment(q.x, q.y, ax, ay, bx, by) <= radius
            ) return true
            if ((p.y > ay) != (q.y > ay)) {
                val side = orientation(p.x, p.y, q.x, q.y, ax, ay)
                if (side == 0) return true // Numerically ambiguous boundary: conservatively blocked.
                if ((q.y > p.y && side > 0) || (q.y < p.y && side < 0)) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private class Obstacle(source: RobotFieldObstacle) {
        val shape = source.shape.lowercase()
        val x = source.x
        val y = source.y
        val halfX = source.width / 2.0
        val halfY = source.height / 2.0
        val circleRadius = source.width // Canonical circle width is its radius, as in FieldObstacleLoader.
        val heading = wrapAngle(Math.toRadians(source.rotation))
        val c = cos(heading)
        val s = sin(heading)
        val points = source.points.toTypedArray()
    }
}

/** Maximum of a*abs(cos(t)) + b*abs(sin(t)) over a directed angular interval. */
private fun maximumExtent(a: Double, b: Double, heading: Double, delta: Double): Double {
    val length = abs(delta)
    if (length >= PI) return hypot(a, b) // The projection has period pi.
    val initial = a * abs(cos(heading)) + b * abs(sin(heading))
    if (length == 0.0) return initial
    val end = heading + delta
    val endpoints = max(initial, a * abs(cos(end)) + b * abs(sin(end)))
    var start = min(heading, end) % PI
    if (start < 0.0) start += PI
    val peak = atan2(b, a)
    val firstOffset = (peak - start + PI) % PI
    val secondOffset = (PI - peak - start + PI) % PI
    return if (firstOffset <= length || secondOffset <= length) hypot(a, b) else endpoints
}

private fun segmentIntersectsBox(ax: Double, ay: Double, bx: Double, by: Double, halfX: Double, halfY: Double): Boolean {
    if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) return true
    val dx = bx - ax
    val dy = by - ay
    if (!dx.isFinite() || !dy.isFinite()) return true
    var enter = 0.0
    var leave = 1.0
    if (dx == 0.0) {
        if (abs(ax) > halfX) return false
    } else {
        val first = (-halfX - ax) / dx
        val second = (halfX - ax) / dx
        enter = max(enter, min(first, second))
        leave = min(leave, max(first, second))
    }
    if (dy == 0.0) {
        if (abs(ay) > halfY) return false
    } else {
        val first = (-halfY - ay) / dy
        val second = (halfY - ay) / dy
        enter = max(enter, min(first, second))
        leave = min(leave, max(first, second))
    }
    return enter <= leave
}

private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val length = hypot(dx, dy)
    if (!length.isFinite()) return 0.0 // Cannot resolve geometry: do not declare a clear sweep.
    if (length == 0.0) return hypot(px - ax, py - ay)
    val nx = dx / length
    val ny = dy / length
    val projection = (px - ax) * nx + (py - ay) * ny
    if (!projection.isFinite()) return 0.0
    val along = projection.coerceIn(0.0, length)
    return hypot(px - (ax + along * nx), py - (ay + along * ny))
}

private fun segmentsIntersect(ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
    if (max(ax, bx) < min(cx, dx) || max(cx, dx) < min(ax, bx) ||
        max(ay, by) < min(cy, dy) || max(cy, dy) < min(ay, by)) return false
    return orientation(ax, ay, bx, by, cx, cy) * orientation(ax, ay, bx, by, dx, dy) <= 0 &&
        orientation(cx, cy, dx, dy, ax, ay) * orientation(cx, cy, dx, dy, bx, by) <= 0
}

/** Filtered orientation. Uncertain results return zero, conservatively expanding contact. */
private fun orientation(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Int {
    val ux = bx - ax
    val uy = by - ay
    val vx = cx - ax
    val vy = cy - ay
    val scale = max(max(abs(ux), abs(uy)), max(abs(vx), abs(vy)))
    if (!scale.isFinite() || scale == 0.0) return 0
    val left = (ux / scale) * (vy / scale)
    val right = (uy / scale) * (vx / scale)
    val determinant = left - right
    if (abs(determinant) <= max(1e-300, (abs(left) + abs(right)) * 1e-14)) return 0
    return if (determinant > 0.0) 1 else -1
}
