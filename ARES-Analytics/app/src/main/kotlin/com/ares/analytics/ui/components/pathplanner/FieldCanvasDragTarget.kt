package com.ares.analytics.ui.components.pathplanner

internal data class FieldCanvasGestureSelection(
    val waypointIndex: Int = -1,
    val obstacleId: String? = null,
    val aprilTagId: String? = null,
    val gamePieceId: String? = null,
    val fieldWaypointId: String? = null,
    val isDraggingHeading: Boolean = false,
    val isDraggingPrevHeading: Boolean = false,
    val isDraggingFieldWaypoint: Boolean = false,
    val isDraggingFieldWaypointHeading: Boolean = false,
)

internal sealed interface FieldCanvasDragTarget {
    data class Waypoint(val index: Int, val heading: Boolean, val previousHeading: Boolean) : FieldCanvasDragTarget
    data class Obstacle(val id: String) : FieldCanvasDragTarget
    data class AprilTag(val id: String) : FieldCanvasDragTarget
    data class GamePiece(val id: String) : FieldCanvasDragTarget
    data class FieldWaypoint(val id: String, val heading: Boolean, val position: Boolean) : FieldCanvasDragTarget
}

internal fun FieldCanvasGestureSelection.dragTarget(
    waypointCount: Int,
    obstacleControlsEnabled: Boolean,
): FieldCanvasDragTarget? = when {
    waypointIndex in 0 until waypointCount -> FieldCanvasDragTarget.Waypoint(
        index = waypointIndex,
        heading = isDraggingHeading,
        previousHeading = isDraggingPrevHeading,
    )
    !obstacleControlsEnabled -> null
    obstacleId != null -> FieldCanvasDragTarget.Obstacle(obstacleId)
    aprilTagId != null -> FieldCanvasDragTarget.AprilTag(aprilTagId)
    gamePieceId != null -> FieldCanvasDragTarget.GamePiece(gamePieceId)
    fieldWaypointId != null -> FieldCanvasDragTarget.FieldWaypoint(
        id = fieldWaypointId,
        heading = isDraggingFieldWaypointHeading,
        position = isDraggingFieldWaypoint,
    )
    else -> null
}
