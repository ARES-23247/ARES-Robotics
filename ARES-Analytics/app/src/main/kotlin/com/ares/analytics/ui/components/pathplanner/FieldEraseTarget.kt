// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.Obstacle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal sealed interface FieldEraseTarget {
    data class Piece(val item: GamePiece) : FieldEraseTarget
    data class Shape(val item: Obstacle) : FieldEraseTarget
    data class Tag(val item: AprilTagPlacement) : FieldEraseTarget
    data class NamedWaypoint(val item: FieldWaypoint) : FieldEraseTarget
}

/** Path waypoints are handled first by the caller in screen space. */
internal fun findFieldEraseTarget(
    point: Waypoint,
    obstacles: List<Obstacle>,
    gamePieces: List<GamePiece>,
    aprilTags: List<AprilTagPlacement>,
    fieldWaypoints: List<FieldWaypoint>,
): FieldEraseTarget? {
    if (!point.x.isFinite() || !point.y.isFinite()) return null
    gamePieces.firstOrNull { !it.locked && point.distanceTo(it.x, it.y) < 0.3 }
        ?.let { return FieldEraseTarget.Piece(it) }
    obstacles.firstOrNull { !it.locked && it.distanceFromFilledShape(point) < 0.5 }
        ?.let { return FieldEraseTarget.Shape(it) }
    aprilTags.firstOrNull { !it.locked && point.distanceTo(it.x, it.y) < 0.3 }
        ?.let { return FieldEraseTarget.Tag(it) }
    return fieldWaypoints.firstOrNull { !it.locked && point.distanceTo(it.x, it.y) < 0.3 }
        ?.let { FieldEraseTarget.NamedWaypoint(it) }
}

/** Distance in meters to the filled shape; invalid geometry is not an erase target. */
internal fun Obstacle.distanceFromFilledShape(point: Waypoint): Double {
    if (!point.x.isFinite() || !point.y.isFinite()) return Double.POSITIVE_INFINITY
    return when (this) {
        is Obstacle.Circle -> {
            if (!centerX.isFinite() || !centerY.isFinite() || !radius.isFinite() || radius <= 0.0)
                Double.POSITIVE_INFINITY
            else (point.distanceTo(centerX, centerY) - radius).coerceAtLeast(0.0)
        }
        is Obstacle.Rectangle -> {
            if (!centerX.isFinite() || !centerY.isFinite() || !rotation.isFinite() ||
                !width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0)
                Double.POSITIVE_INFINITY
            else {
                val radians = Math.toRadians(rotation)
                val c = cos(radians)
                val s = sin(radians)
                val dx = point.x - centerX
                val dy = point.y - centerY
                val outsideX = (abs(dx * c + dy * s) - width / 2.0).coerceAtLeast(0.0)
                val outsideY = (abs(-dx * s + dy * c) - height / 2.0).coerceAtLeast(0.0)
                hypot(outsideX, outsideY)
            }
        }
        is Obstacle.Polygon -> {
            if (vertices.size < 3 || vertices.any { !it.x.isFinite() || !it.y.isFinite() })
                Double.POSITIVE_INFINITY
            else if (pointInPolygon(point.x, point.y, vertices)) 0.0
            else {
                var nearest = Double.POSITIVE_INFINITY
                var previous = vertices.last()
                for (current in vertices) {
                    val dx = current.x - previous.x
                    val dy = current.y - previous.y
                    val length = hypot(dx, dy)
                    val distance = if (length == 0.0) point.distanceTo(current.x, current.y) else {
                        val ux = dx / length
                        val uy = dy / length
                        val along = ((point.x - previous.x) * ux + (point.y - previous.y) * uy)
                            .coerceIn(0.0, length)
                        point.distanceTo(previous.x + along * ux, previous.y + along * uy)
                    }
                    nearest = minOf(nearest, distance)
                    previous = current
                }
                nearest
            }
        }
    }
}
