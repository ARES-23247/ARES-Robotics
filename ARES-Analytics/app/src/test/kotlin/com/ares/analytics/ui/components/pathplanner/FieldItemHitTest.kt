// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import androidx.compose.ui.geometry.Offset
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldItemHitTest {
    @Test
    fun `polygon interiors are selectable while concave notches remain empty`() {
        val vertices = listOf(PathPoint(0.0, 0.0), PathPoint(3.0, 0.0), PathPoint(3.0, 1.0),
            PathPoint(1.0, 1.0), PathPoint(1.0, 3.0), PathPoint(0.0, 3.0))
        for (winding in listOf(vertices, vertices.reversed())) {
            val polygon = Obstacle.Polygon("polygon", "Polygon", winding)
            for (league in League.entries) for (angle in listOf(0f, 90f, -37f)) {
                val pan = Offset(23f, -19f)
                fun hit(x: Double, y: Double): Pair<String, String>? {
                    val screen = getTransformedCanvasOffset(Waypoint(x, y), 800f, 800f, 8.0, 8.0,
                        league, 1.5f, pan, angle)
                    return findFieldItemAtScreen(screen, 800f, 800f, 8.0, 8.0, league,
                        1.5f, pan, angle, listOf(polygon), emptyList())
                }
                assertEquals("Obstacle" to "polygon", hit(2.0, 0.5))
                assertEquals("Obstacle" to "polygon", hit(0.5, 2.0))
                assertNull(hit(2.0, 2.0))
                // Preserve the existing vertex grab tolerance outside the filled polygon.
                assertEquals("Obstacle" to "polygon", hit(-0.1, -0.1))
            }
        }
    }

    @Test
    fun `rotated item lookup hits each rendered item kind with zoom and pan`() {
        for (league in League.entries) for (angle in listOf(0f, 90f, -37f)) {
            val point = Waypoint(1.0, 0.5)
            val pan = Offset(23f, -19f)
            val screen = getTransformedCanvasOffset(point, 800f, 400f, 4.0, 2.0, league, 1.5f, pan, angle)
            val circle = Obstacle.Circle("circle", "Circle", point.x, point.y, 0.1)
            val rectangle = Obstacle.Rectangle("rectangle", "Rectangle", point.x, point.y, 0.2, 0.1, 37.0)
            for (obstacle in listOf(circle, rectangle)) {
                assertEquals("Obstacle" to obstacle.id, findFieldItemAtScreen(screen, 800f, 400f, 4.0, 2.0,
                    league, 1.5f, pan, angle, listOf(obstacle), emptyList()))
            }
            assertEquals("AprilTag" to "tag", findFieldItemAtScreen(screen, 800f, 400f, 4.0, 2.0,
                league, 1.5f, pan, angle, emptyList(), listOf(AprilTagPlacement("tag", 1, point.x, point.y))))
            assertEquals("GamePiece" to "piece", findFieldItemAtScreen(screen, 800f, 400f, 4.0, 2.0,
                league, 1.5f, pan, angle, emptyList(), emptyList(), listOf(GamePiece("piece", "Piece", point.x, point.y))))
            assertEquals("FieldWaypoint" to "waypoint", findFieldItemAtScreen(screen, 800f, 400f, 4.0, 2.0,
                league, 1.5f, pan, angle, emptyList(), emptyList(), emptyList(),
                listOf(FieldWaypoint("waypoint", "Waypoint", point.x, point.y, 0.0))))
        }
    }

    @Test
    fun `lookup preserves obstacle priority and returns no hit away from items`() {
        val obstacle = Obstacle.Circle("circle", "Circle", 0.0, 0.0, 0.1)
        val tags = listOf(AprilTagPlacement("tag", 1, 0.0, 0.0))
        assertEquals("Obstacle" to "circle", findFieldItemAtScreen(Offset(400f, 200f), 800f, 400f,
            4.0, 2.0, League.XRP, 1f, Offset.Zero, 0f, listOf(obstacle), tags))
        assertNull(findFieldItemAtScreen(Offset.Zero, 800f, 400f, 4.0, 2.0, League.XRP,
            1f, Offset.Zero, 0f, listOf(obstacle), tags))
    }
}
