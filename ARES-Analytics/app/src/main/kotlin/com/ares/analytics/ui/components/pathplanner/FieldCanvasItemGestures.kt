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
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.models.League
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Secondary field-item gestures kept separate from drag and placement state. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.fieldCanvasItemGestures(
    editorMode: EditorMode,
    zoomScale: Float,
    panOffset: Offset,
    viewRotation: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    currentWaypoints: List<Waypoint>,
    currentActiveObstacles: List<Obstacle>,
    currentActiveGamePieces: List<GamePiece>,
    currentActiveAprilTags: List<AprilTagPlacement>,
    currentActiveFieldWaypoints: List<FieldWaypoint>,
    onItemDoubleTapped: ((String, String) -> Unit)?,
    onOpenContextMenu: (offset: Offset, targetType: String?, targetIndex: Int, targetId: String?) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = { offset ->
                if (editorMode != EditorMode.SELECT) return@detectTapGestures

                findFieldItemAtScreen(
                    offset, size.width.toFloat(), size.height.toFloat(), fieldWidthM, fieldHeightM,
                    league, zoomScale, panOffset, viewRotation, currentActiveObstacles, currentActiveAprilTags,
                )?.let { (type, id) -> onItemDoubleTapped?.invoke(id, type) }
            },
        )
    }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press || event.button != PointerButton.Secondary) continue

                val offset = event.changes.first().position
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                val hitWaypointIndex = currentWaypoints.indexOfFirst {
                    val point = getTransformedCanvasOffset(
                        it,
                        width,
                        height,
                        fieldWidthM,
                        fieldHeightM,
                        league,
                        zoomScale,
                        panOffset,
                        viewRotation,
                    )
                    sqrt((offset.x - point.x).pow(2) + (offset.y - point.y).pow(2)) < 20.dp.toPx()
                }
                if (hitWaypointIndex != -1) {
                    onOpenContextMenu(offset, "Waypoint", hitWaypointIndex, null)
                    continue
                }

                val target = findFieldItemAtScreen(
                    offset, width, height, fieldWidthM, fieldHeightM, league, zoomScale, panOffset,
                    viewRotation, currentActiveObstacles, currentActiveAprilTags, currentActiveGamePieces,
                    currentActiveFieldWaypoints,
                )

                target?.let { (type, id) -> onOpenContextMenu(offset, type, -1, id) }
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
    return obstacles.find { it.contains(point) }?.let { "Obstacle" to it.id }
        ?: aprilTags.find { point.distanceTo(it.x, it.y) < 0.3 }?.let { "AprilTag" to it.id }
        ?: gamePieces.find { point.distanceTo(it.x, it.y) < 0.2 }?.let { "GamePiece" to it.id }
        ?: fieldWaypoints.find { point.distanceTo(it.x, it.y) < 0.3 }?.let { "FieldWaypoint" to it.id }
}

private fun Waypoint.distanceTo(x: Double, y: Double): Double =
    sqrt((this.x - x).pow(2) + (this.y - y).pow(2))

private fun Obstacle.contains(point: Waypoint): Boolean = when (this) {
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
