// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot

/** Rank only actual hits; a nearer miss must not mask another obstacle. */
internal fun findObstacleForDrag(point: Waypoint, obstacles: List<Obstacle>): Obstacle? {
    var nearest: Obstacle? = null
    var nearestDistance = Double.POSITIVE_INFINITY
    for (obstacle in obstacles) {
        if (!obstacle.containsFieldPoint(point)) continue
        val distance = when (obstacle) {
            is Obstacle.Circle -> point.distanceTo(obstacle.centerX, obstacle.centerY) - obstacle.radius
            is Obstacle.Rectangle -> point.distanceTo(obstacle.centerX, obstacle.centerY)
            is Obstacle.Polygon -> obstacle.vertices.minOfOrNull { point.distanceTo(it.x, it.y) }
                ?: Double.POSITIVE_INFINITY
        }
        if (distance < nearestDistance) {
            nearest = obstacle
            nearestDistance = distance
        }
    }
    return nearest
}

internal fun Waypoint.distanceTo(x: Double, y: Double): Double =
    hypot(this.x - x, this.y - y)

internal fun Obstacle.containsFieldPoint(point: Waypoint): Boolean = when (this) {
    is Obstacle.Circle -> point.distanceTo(centerX, centerY) <= radius
    is Obstacle.Rectangle -> {
        val dx = point.x - centerX
        val dy = point.y - centerY
        val radians = Math.toRadians(-rotation)
        kotlin.math.abs(dx * cos(radians) - dy * sin(radians)) <= width / 2.0 &&
            kotlin.math.abs(dx * sin(radians) + dy * cos(radians)) <= height / 2.0
    }
    is Obstacle.Polygon -> pointInPolygon(point.x, point.y, vertices) ||
        vertices.any { point.distanceTo(it.x, it.y) < 0.3 }
}

/** Even-odd containment for field polygons, including their boundary, in either winding. */
internal fun pointInPolygon(x: Double, y: Double, vertices: List<PathPoint>): Boolean {
    if (vertices.size < 3 || !x.isFinite() || !y.isFinite() ||
        vertices.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    var inside = false
    var previous = vertices.last()
    for (current in vertices) {
        val cross = (x - current.x) * (previous.y - current.y) -
            (y - current.y) * (previous.x - current.x)
        if (cross == 0.0 && x >= minOf(current.x, previous.x) && x <= maxOf(current.x, previous.x) &&
            y >= minOf(current.y, previous.y) && y <= maxOf(current.y, previous.y)) return true
        if ((current.y > y) != (previous.y > y)) {
            val intersectionX = (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x
            if (x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}
