// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldEraseTargetTest {
    @Test
    fun `locked items survive and eligible targets retain category and list priority`() {
        val point = Waypoint(0.0, 0.0)
        val piece = GamePiece("piece", "Piece", 0.0, 0.0)
        val shape = Obstacle.Circle("shape", "Shape", 0.0, 0.0, 1.0)
        val tag = AprilTagPlacement("tag", 1, 0.0, 0.0)
        val waypoint = FieldWaypoint("waypoint", "Waypoint", 0.0, 0.0, 0.0)
        fun find(p: GamePiece, s: Obstacle, t: AprilTagPlacement, w: FieldWaypoint) =
            findFieldEraseTarget(point, listOf(s), listOf(p), listOf(t), listOf(w))
        assertEquals(FieldEraseTarget.Piece(piece), find(piece, shape, tag, waypoint))
        assertEquals(FieldEraseTarget.Shape(shape), find(piece.copy(locked = true), shape, tag, waypoint))
        assertEquals(FieldEraseTarget.Tag(tag), find(piece.copy(locked = true), shape.copy(locked = true), tag, waypoint))
        assertEquals(FieldEraseTarget.NamedWaypoint(waypoint), find(piece.copy(locked = true),
            shape.copy(locked = true), tag.copy(locked = true), waypoint))
        assertNull(find(piece.copy(locked = true), shape.copy(locked = true),
            tag.copy(locked = true), waypoint.copy(locked = true)))
        assertEquals(FieldEraseTarget.Piece(piece), findFieldEraseTarget(point, emptyList(),
            listOf(piece.copy(id = "locked", locked = true), piece, piece.copy(id = "later")), emptyList(), emptyList()))
    }

    @Test
    fun `rectangle uses actual rotated edges and corners instead of center radius`() {
        val long = Obstacle.Rectangle("long", "Long", 0.0, 0.0, 10.0, 1.0, 0.0)
        assertEquals(2.5, long.distanceFromFilledShape(Waypoint(0.0, 3.0)), 1e-12)
        val square = long.copy(width = 10.0, height = 10.0)
        assertEquals(0.0, square.distanceFromFilledShape(Waypoint(4.9, 4.9)))
        assertEquals(0.5, square.distanceFromFilledShape(Waypoint(5.3, 5.4)), 1e-12)
        val rotated = long.copy(centerX = 2.0, centerY = -3.0, rotation = 90.0)
        assertEquals(0.25, rotated.distanceFromFilledShape(Waypoint(1.25, -3.0)), 1e-12)
        assertEquals(0.0, rotated.distanceFromFilledShape(Waypoint(2.0, 1.0)), 1e-12)
        assertNull(findFieldEraseTarget(Waypoint(0.0, 3.0), listOf(long), emptyList(), emptyList(), emptyList()))
        assertEquals(FieldEraseTarget.Shape(square), findFieldEraseTarget(Waypoint(4.9, 4.9),
            listOf(square), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `polygon distance follows edges and concave notches with repeated vertices`() {
        val vertices = listOf(PathPoint(0.0, 0.0), PathPoint(4.0, 0.0), PathPoint(4.0, 1.0),
            PathPoint(1.0, 1.0), PathPoint(1.0, 4.0), PathPoint(0.0, 4.0))
        for (points in listOf(vertices, vertices.reversed(), vertices + vertices.first())) {
            val polygon = Obstacle.Polygon("polygon", "Polygon", points)
            assertEquals(0.0, polygon.distanceFromFilledShape(Waypoint(2.0, 0.5)))
            assertEquals(0.25, polygon.distanceFromFilledShape(Waypoint(2.0, -0.25)), 1e-12)
            assertEquals(1.0, polygon.distanceFromFilledShape(Waypoint(2.0, 2.0)), 1e-12)
            assertEquals(FieldEraseTarget.Shape(polygon), findFieldEraseTarget(Waypoint(2.0, -0.25),
                listOf(polygon), emptyList(), emptyList(), emptyList()))
        }
    }

    @Test
    fun `eraser radii are strict and invalid geometry is ignored`() {
        val circle = Obstacle.Circle("circle", "Circle", 0.0, 0.0, 1.0)
        fun hit(x: Double, shapes: List<Obstacle>) = findFieldEraseTarget(Waypoint(x, 0.0),
            shapes, emptyList(), emptyList(), emptyList())
        assertEquals(FieldEraseTarget.Shape(circle), hit(1.499, listOf(circle)))
        assertNull(hit(1.5, listOf(circle)))
        val piece = GamePiece("piece", "Piece", 0.0, 0.0)
        assertNull(findFieldEraseTarget(Waypoint(0.3, 0.0), emptyList(), listOf(piece), emptyList(), emptyList()))
        val invalid = listOf(circle.copy(radius = -1.0), circle.copy(radius = Double.POSITIVE_INFINITY),
            circle.copy(centerX = Double.NaN), Obstacle.Rectangle("rect", "Rect", 0.0, 0.0, 0.0, 1.0, 0.0),
            Obstacle.Polygon("empty", "Empty", emptyList()),
            Obstacle.Polygon("invalid", "Invalid", listOf(PathPoint(0.0, 0.0), PathPoint(1.0, 0.0), PathPoint(Double.NaN, 1.0))))
        assertNull(hit(0.0, invalid))
        assertNull(hit(Double.NaN, listOf(circle)))
        assertEquals(FieldEraseTarget.Shape(circle), hit(0.0, invalid + circle))
    }
}
