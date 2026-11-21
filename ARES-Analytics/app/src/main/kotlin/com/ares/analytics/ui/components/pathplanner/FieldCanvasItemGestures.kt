package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mutable snapshot holder updated on every recomposition so long-lived `pointerInput` blocks
 * read fresh editor mode, zoom/pan/rotation, field items, and callbacks without cancelling drags.
 */
internal class FieldCanvasGestureState {
    var editorMode: EditorMode = EditorMode.SELECT
    var zoomScale: Float = 1f
    var panOffset: Offset = Offset.Zero
    var viewRotation: Float = 0f
    var fieldWidthM: Double = 3.6576
    var fieldHeightM: Double = 3.6576
    var league: League = League.FTC
    var robotDimensions: RobotDimensions = RobotDimensions(0.4572, 0.4572)
    var waypoints: List<Waypoint> = emptyList()
    var activeObstacles: List<Obstacle> = emptyList()
    var activeGamePieces: List<GamePiece> = emptyList()
    var activeAprilTags: List<AprilTagPlacement> = emptyList()
    var activeFieldWaypoints: List<FieldWaypoint> = emptyList()
    var availableGamePieceTypes: List<GamePieceType> = emptyList()
    var activeGamePieceType: String = ""
    var showObstacleControls: Boolean = false
    var isShiftPressed: Boolean = false
    var selectedWaypointIndex: Int = -1
    var onWaypointsChanged: (List<Waypoint>) -> Unit = {}
    var updateObstacles: (List<Obstacle>) -> Unit = {}
    var updateGamePieces: (List<GamePiece>) -> Unit = {}
    var updateAprilTags: (List<AprilTagPlacement>) -> Unit = {}
    var updateFieldWaypoints: (List<FieldWaypoint>) -> Unit = {}
    var onPenStrokeCompleted: ((List<Waypoint>) -> Unit)? = null
    var onItemSelected: ((String?, String?) -> Unit)? = null
    var onItemDoubleTapped: ((String, String) -> Unit)? = null
    var onOpenContextMenu: (offset: Offset, targetType: String?, targetIndex: Int, targetId: String?) -> Unit =
        { _, _, _, _ -> }
    var onSelectionChanged: (
        waypointIndex: Int,
        obstacleId: String?,
        aprilTagId: String?,
        gamePieceId: String?,
        fieldWaypointId: String?,
        isDraggingHeading: Boolean,
        isDraggingPrevHeading: Boolean,
        isDraggingFieldWaypoint: Boolean,
        isDraggingFieldWaypointHeading: Boolean,
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> }
}

/** Secondary field-item gestures kept separate from drag and placement state. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.fieldCanvasItemGestures(
    gestureState: FieldCanvasGestureState,
): Modifier = this
    .pointerInput(gestureState) {
        detectTapGestures(
            onDoubleTap = { offset ->
                val s = gestureState
                if (s.editorMode != EditorMode.SELECT) return@detectTapGestures

                findFieldItemAtScreen(
                    offset, size.width.toFloat(), size.height.toFloat(), s.fieldWidthM, s.fieldHeightM,
                    s.league, s.zoomScale, s.panOffset, s.viewRotation, s.activeObstacles, s.activeAprilTags,
                )?.let { (type, id) -> s.onItemDoubleTapped?.invoke(id, type) }
            },
        )
    }
    .pointerInput(gestureState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press || event.button != PointerButton.Secondary) continue

                val s = gestureState
                val offset = event.changes.first().position
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                val hitWaypointIndex = s.waypoints.indexOfFirst {
                    val point = getTransformedCanvasOffset(
                        it,
                        width,
                        height,
                        s.fieldWidthM,
                        s.fieldHeightM,
                        s.league,
                        s.zoomScale,
                        s.panOffset,
                        s.viewRotation,
                    )
                    sqrt((offset.x - point.x).pow(2) + (offset.y - point.y).pow(2)) < 20.dp.toPx()
                }
                if (hitWaypointIndex != -1) {
                    s.onOpenContextMenu(offset, "Waypoint", hitWaypointIndex, null)
                    continue
                }

                val target = findFieldItemAtScreen(
                    offset, width, height, s.fieldWidthM, s.fieldHeightM, s.league, s.zoomScale, s.panOffset,
                    s.viewRotation, s.activeObstacles, s.activeAprilTags, s.activeGamePieces,
                    s.activeFieldWaypoints,
                )

                target?.let { (type, id) -> s.onOpenContextMenu(offset, type, -1, id) }
            }
        }
    }

/** Shared screen-space item lookup for double-click and context-menu gestures. */
internal fun findFieldItemAtScreen(
    offset: Offset,
    width: Float,
    height: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    zoomScale: Float,
    panOffset: Offset,
    viewRotation: Float,
    obstacles: List<Obstacle>,
    aprilTags: List<AprilTagPlacement>,
    gamePieces: List<GamePiece> = emptyList(),
    fieldWaypoints: List<FieldWaypoint> = emptyList(),
): Pair<String, String>? {
    val point = getRobotCoordFromScreen(offset, width, height, fieldWidthM, fieldHeightM,
        league, zoomScale, panOffset, viewRotation)
    return obstacles.find { it.containsFieldPoint(point) }?.let { "Obstacle" to it.id }
        ?: aprilTags.find { point.distanceTo(it.x, it.y) < 0.3 }?.let { "AprilTag" to it.id }
        ?: gamePieces.find { point.distanceTo(it.x, it.y) < 0.2 }?.let { "GamePiece" to it.id }
        ?: fieldWaypoints.find { point.distanceTo(it.x, it.y) < 0.3 }?.let { "FieldWaypoint" to it.id }
}
