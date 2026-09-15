// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldObstacleSelectionTest {
    @Test
    fun `nearby missed rectangle cannot hide a containing rectangle`() {
        val miss = Obstacle.Rectangle("miss", "Miss", 0.0, 0.2, 0.1, 0.1, 0.0)
        val hit = Obstacle.Rectangle("hit", "Hit", 2.0, 0.0, 6.0, 2.0, 0.0)
        for (order in listOf(listOf(miss, hit), listOf(hit, miss))) {
            assertEquals(hit, findObstacleForDrag(Waypoint(0.0, 0.0), order))
        }
    }

    @Test
    fun `empty polygon does not abort lookup and polygon interior is selectable`() {
        val empty = Obstacle.Polygon("empty", "Empty", emptyList())
        val polygon = Obstacle.Polygon("polygon", "Polygon", listOf(PathPoint(-2.0, -2.0),
            PathPoint(2.0, -2.0), PathPoint(0.0, 2.0)))
        assertEquals(polygon, findObstacleForDrag(Waypoint(0.0, 0.0), listOf(empty, polygon)))
        assertNull(findObstacleForDrag(Waypoint(5.0, 5.0), listOf(empty, polygon)))
    }

    @Test
    fun `overlapping hits retain existing distance priority and stable ties`() {
        val circle = Obstacle.Circle("circle", "Circle", 0.0, 0.0, 0.5)
        val rectangle = Obstacle.Rectangle("rectangle", "Rectangle", 0.0, 0.0, 2.0, 2.0, 30.0)
        assertEquals(circle, findObstacleForDrag(Waypoint(0.0, 0.0), listOf(rectangle, circle)))
        val other = circle.copy(id = "other")
        assertEquals(other, findObstacleForDrag(Waypoint(0.0, 0.0), listOf(other, circle)))
        assertNull(findObstacleForDrag(Waypoint(0.0, 0.0), emptyList()))
    }
}
