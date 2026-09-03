package com.ares.analytics.ui.components.pathplanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldCanvasGesturesTest {
    @Test
    fun `newly hit waypoint remains the drag target for the current gesture`() {
        val selection = FieldCanvasGestureSelection(
            waypointIndex = 2,
            isDraggingHeading = true,
        )

        assertEquals(
            FieldCanvasDragTarget.Waypoint(index = 2, heading = true, previousHeading = false),
            selection.dragTarget(waypointCount = 3, obstacleControlsEnabled = true),
        )
    }

    @Test
    fun `invalid waypoint cannot index the path and falls through to the hit object`() {
        val selection = FieldCanvasGestureSelection(
            waypointIndex = 4,
            obstacleId = "center-stage",
        )

        assertEquals(
            FieldCanvasDragTarget.Obstacle("center-stage"),
            selection.dragTarget(waypointCount = 2, obstacleControlsEnabled = true),
        )
    }

    @Test
    fun `field object dragging stays disabled outside field editing mode`() {
        val selection = FieldCanvasGestureSelection(aprilTagId = "tag-7")

        assertNull(selection.dragTarget(waypointCount = 0, obstacleControlsEnabled = false))
    }

    @Test
    fun `every field object selection resolves to its typed drag target`() {
        assertEquals(
            FieldCanvasDragTarget.AprilTag("tag-7"),
            FieldCanvasGestureSelection(aprilTagId = "tag-7")
                .dragTarget(waypointCount = 0, obstacleControlsEnabled = true),
        )
        assertEquals(
            FieldCanvasDragTarget.GamePiece("note-1"),
            FieldCanvasGestureSelection(gamePieceId = "note-1")
                .dragTarget(waypointCount = 0, obstacleControlsEnabled = true),
        )
        assertEquals(
            FieldCanvasDragTarget.FieldWaypoint("start", heading = false, position = true),
            FieldCanvasGestureSelection(fieldWaypointId = "start", isDraggingFieldWaypoint = true)
                .dragTarget(waypointCount = 0, obstacleControlsEnabled = true),
        )
    }
}
