package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.math.wrapAngle
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Encapsulates pointer gestures, drag tracking, tool-mode placement, and context menu hit-testing
 * for the PathPlanner field canvas.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.fieldCanvasGestures(
    autoGoalMode: Boolean,
    editorMode: EditorMode,
    zoomScale: Float,
    panOffset: Offset,
    viewRotation: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    robotDimensions: RobotDimensions,
    currentWaypoints: List<Waypoint>,
    currentActiveObstacles: List<Obstacle>,
    currentActiveGamePieces: List<GamePiece>,
    currentActiveAprilTags: List<AprilTagPlacement>,
    currentActiveFieldWaypoints: List<FieldWaypoint>,
    currentPolygonPoints: SnapshotStateList<PathPoint>,
    availableGamePieceTypes: List<com.ares.analytics.shared.GamePieceType>,
    activeGamePieceType: String,
    showObstacleControls: Boolean,
    isShiftPressed: Boolean,
    selectedWaypointIndex: Int,
    selectedObstacleId: String?,
    selectedAprilTagId: String?,
    selectedGamePieceId: String?,
    selectedFieldWaypointId: String?,
    onWaypointsChanged: (List<Waypoint>) -> Unit,
    updateObstacles: (List<Obstacle>) -> Unit,
    updateGamePieces: (List<GamePiece>) -> Unit,
    updateAprilTags: (List<AprilTagPlacement>) -> Unit,
    updateFieldWaypoints: (List<FieldWaypoint>) -> Unit,
    onItemSelected: ((String?, String?) -> Unit)?,
    onItemDoubleTapped: ((String, String) -> Unit)?,
    onOpenContextMenu: (offset: Offset, targetType: String?, targetIndex: Int, targetId: String?) -> Unit,
    onSelectionChanged: (
        waypointIndex: Int,
        obstacleId: String?,
        aprilTagId: String?,
        gamePieceId: String?,
        fieldWaypointId: String?,
        isDraggingHeading: Boolean,
        isDraggingPrevHeading: Boolean,
        isDraggingFieldWaypoint: Boolean,
        isDraggingFieldWaypointHeading: Boolean,
    ) -> Unit,
): Modifier = this
    .pointerInput(autoGoalMode) {
        var accumulatedDragPx = Offset.Zero
        var dragInitialPos = Waypoint(0.0, 0.0)
        var dragInitialVertices: List<PathPoint> = emptyList()
        var isDraggingHeading = false
        var isDraggingPrevHeading = false
        var isDraggingFieldWaypoint = false
        var isDraggingFieldWaypointHeading = false

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pressOffset = down.position
            var hasDragged = false
            accumulatedDragPx = Offset.Zero

            val w = size.width.toFloat()
            val h = size.height.toFloat()

            when (editorMode) {
                EditorMode.SELECT -> {
                    var hitIdx = -1
                    var hitHeading = false
                    var hitPrevHeading = false

                    val basePress = getBaseCanvasFromScreen(pressOffset, w, h, zoomScale, panOffset, viewRotation)
                    val hitRadiusPx = 15.dp.toPx() / zoomScale

                    // 1. Prioritize handles of the ALREADY selected waypoint
                    if (selectedWaypointIndex in currentWaypoints.indices) {
                        val wp = currentWaypoints[selectedWaypointIndex]
                        val selHeading = resolveHeading(currentWaypoints, selectedWaypointIndex)
                        val handleDist = if (autoGoalMode) (robotDimensions.lengthMeters / 2.0 + 0.08) else wp.nextControlLength
                        val headingWp = Waypoint(wp.x + handleDist * cos(selHeading), wp.y + handleDist * sin(selHeading))
                        val headingBase = getCanvasOffsetBase(headingWp, w, h, fieldWidthM, fieldHeightM, league)
                        if (sqrt((basePress.x - headingBase.x).pow(2) + (basePress.y - headingBase.y).pow(2)) < hitRadiusPx) {
                            hitIdx = selectedWaypointIndex
                            hitHeading = true
                        }

                        if (!autoGoalMode) {
                            val prevHeadingWp = Waypoint(wp.x + wp.prevControlLength * cos(selHeading + Math.PI), wp.y + wp.prevControlLength * sin(selHeading + Math.PI))
                            val prevHeadingBase = getCanvasOffsetBase(prevHeadingWp, w, h, fieldWidthM, fieldHeightM, league)
                            if (hitIdx == -1 && sqrt((basePress.x - prevHeadingBase.x).pow(2) + (basePress.y - prevHeadingBase.y).pow(2)) < hitRadiusPx) {
                                hitIdx = selectedWaypointIndex
                                hitPrevHeading = true
                            }
                        }
                    }

                    // 2. Check all waypoint center dots
                    if (hitIdx == -1) {
                        for (i in currentWaypoints.indices) {
                            val wpBase = getCanvasOffsetBase(currentWaypoints[i], w, h, fieldWidthM, fieldHeightM, league)
                            if (sqrt((basePress.x - wpBase.x).pow(2) + (basePress.y - wpBase.y).pow(2)) < hitRadiusPx) {
                                hitIdx = i
                                break
                            }
                        }
                    }

                    // 3. Check other waypoints tangent handles
                    if (hitIdx == -1) {
                        for (i in currentWaypoints.indices) {
                            if (i == selectedWaypointIndex) continue
                            val wp = currentWaypoints[i]
                            val hd = resolveHeading(currentWaypoints, i)
                            val handleDist = if (autoGoalMode) (robotDimensions.lengthMeters / 2.0 + 0.08) else wp.nextControlLength
                            val headingWp = Waypoint(wp.x + handleDist * cos(hd), wp.y + handleDist * sin(hd))
                            val headingBase = getCanvasOffsetBase(headingWp, w, h, fieldWidthM, fieldHeightM, league)
                            if (sqrt((basePress.x - headingBase.x).pow(2) + (basePress.y - headingBase.y).pow(2)) < hitRadiusPx) {
                                hitIdx = i
                                hitHeading = true
                                break
                            }
                            if (!autoGoalMode) {
                                val prevHeadingWp = Waypoint(wp.x + wp.prevControlLength * cos(hd + Math.PI), wp.y + wp.prevControlLength * sin(hd + Math.PI))
                                val prevHeadingBase = getCanvasOffsetBase(prevHeadingWp, w, h, fieldWidthM, fieldHeightM, league)
                                if (sqrt((basePress.x - prevHeadingBase.x).pow(2) + (basePress.y - prevHeadingBase.y).pow(2)) < hitRadiusPx) {
                                    hitIdx = i
                                    hitPrevHeading = true
                                    break
                                }
                            }
                        }
                    }

                    var hitFieldWpId: String? = null
                    var hitFieldWpHeading = false
                    var hitFieldWpCenter = false
                    if (hitIdx == -1) {
                        for (wp in currentActiveFieldWaypoints) {
                            val wpOffset = getTransformedCanvasOffset(Waypoint(wp.x, wp.y), w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset, viewRotation)
                            val angleRad = Math.toRadians(-wp.headingDegrees - 90.0)
                            val pointerLen = 22.dp.toPx()
                            val handleOffset = Offset(
                                (wpOffset.x + pointerLen * cos(angleRad)).toFloat(),
                                (wpOffset.y + pointerLen * sin(angleRad)).toFloat()
                            )
                            if (sqrt((pressOffset.x - handleOffset.x).pow(2) + (pressOffset.y - handleOffset.y).pow(2)) < 15.dp.toPx()) {
                                hitFieldWpId = wp.id
                                hitFieldWpHeading = true
                                break
                            }
                            if (sqrt((pressOffset.x - wpOffset.x).pow(2) + (pressOffset.y - wpOffset.y).pow(2)) < 15.dp.toPx()) {
                                hitFieldWpId = wp.id
                                hitFieldWpCenter = true
                                break
                            }
                        }
                    }

                    var newObsId: String? = null
                    var newAtId: String? = null
                    var newGpId: String? = null

                    when {
                        hitFieldWpId != null -> {
                            onItemSelected?.invoke(hitFieldWpId, "FieldWaypoint")
                            if (hitFieldWpCenter) {
                                currentActiveFieldWaypoints.find { it.id == hitFieldWpId }
                                    ?.let { dragInitialPos = Waypoint(it.x, it.y) }
                            }
                        }
                        hitIdx != -1 -> {
                            // Selected waypoint
                        }
                        else -> {
                            val clickCoord = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                            val hitObs = currentActiveObstacles.minByOrNull { obs ->
                                when (obs) {
                                    is Obstacle.Circle -> sqrt((clickCoord.x - obs.centerX).pow(2) + (clickCoord.y - obs.centerY).pow(2)) - obs.radius
                                    is Obstacle.Rectangle -> {
                                        val dx = clickCoord.x - obs.centerX
                                        val dy = clickCoord.y - obs.centerY
                                        sqrt(dx * dx + dy * dy)
                                    }
                                    is Obstacle.Polygon -> obs.vertices.minOf { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) }
                                }
                            }?.takeIf { obs ->
                                when (obs) {
                                    is Obstacle.Circle -> sqrt((clickCoord.x - obs.centerX).pow(2) + (clickCoord.y - obs.centerY).pow(2)) <= obs.radius
                                    is Obstacle.Rectangle -> {
                                        val dx = clickCoord.x - obs.centerX
                                        val dy = clickCoord.y - obs.centerY
                                        val rad = Math.toRadians(-obs.rotation)
                                        kotlin.math.abs(dx * cos(rad) - dy * sin(rad)) <= obs.width / 2.0 && kotlin.math.abs(dx * sin(rad) + dy * cos(rad)) <= obs.height / 2.0
                                    }
                                    is Obstacle.Polygon -> obs.vertices.any { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                                }
                            }
                            newObsId = hitObs?.id
                            if (newObsId != null) {
                                onItemSelected?.invoke(newObsId, "Obstacle")
                                when (hitObs) {
                                    is Obstacle.Circle -> dragInitialPos = Waypoint(hitObs.centerX, hitObs.centerY)
                                    is Obstacle.Rectangle -> dragInitialPos = Waypoint(hitObs.centerX, hitObs.centerY)
                                    is Obstacle.Polygon -> dragInitialVertices = hitObs.vertices.toList()
                                    else -> {}
                                }
                            } else {
                                val hitAt = currentActiveAprilTags.minByOrNull { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) }?.takeIf { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                                newAtId = hitAt?.id
                                hitAt?.let { aprilTag ->
                                    onItemSelected?.invoke(aprilTag.id, "AprilTag")
                                    dragInitialPos = Waypoint(aprilTag.x, aprilTag.y)
                                } ?: run {
                                    val hitGp = currentActiveGamePieces.minByOrNull { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) }?.takeIf { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.2 }
                                    newGpId = hitGp?.id
                                    hitGp?.let { gamePiece ->
                                        onItemSelected?.invoke(gamePiece.id, "GamePiece")
                                        dragInitialPos = Waypoint(gamePiece.x, gamePiece.y)
                                    } ?: onItemSelected?.invoke(null, null)
                                }
                            }
                        }
                    }

                    isDraggingHeading = hitIdx != -1 && hitHeading
                    isDraggingPrevHeading = hitIdx != -1 && hitPrevHeading
                    isDraggingFieldWaypoint = hitFieldWpId != null && hitFieldWpCenter
                    isDraggingFieldWaypointHeading = hitFieldWpId != null && hitFieldWpHeading

                    onSelectionChanged(
                        hitIdx,
                        newObsId,
                        newAtId,
                        newGpId,
                        hitFieldWpId,
                        isDraggingHeading,
                        isDraggingPrevHeading,
                        isDraggingFieldWaypoint,
                        isDraggingFieldWaypointHeading,
                    )
                }
                else -> { /* Placement handled on release when !hasDragged */ }
            }

            // 3. Wait for touch slop then track drag
            val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                change.consume()
            }

            if (slopChange != null) {
                accumulatedDragPx = slopChange.position - down.position
                drag(slopChange.id) { change ->
                    hasDragged = true
                    val dragAmount = change.positionChange()
                    accumulatedDragPx += dragAmount
                    change.consume()

                    fun snap(v: Double) = if (isShiftPressed) kotlin.math.round(v * 10.0) / 10.0 else v
                    val totalDelta = getDragDeltaInFieldCoords(accumulatedDragPx, w, h, fieldWidthM, fieldHeightM, league, zoomScale)

                    when {
                        selectedWaypointIndex != -1 -> {
                            val isDraggingHeadingActive = selectedWaypointIndex in currentWaypoints.indices
                            val wp = currentWaypoints[selectedWaypointIndex]
                            val posMeters = getRobotCoordFromScreen(change.position, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                            val dx = posMeters.x - wp.x
                            val dy = posMeters.y - wp.y

                            when {
                                isDraggingHeading && isDraggingHeadingActive -> {
                                    val angle = kotlin.math.atan2(dy, dx)
                                    val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                                    onWaypointsChanged(
                                        currentWaypoints.toMutableList().apply {
                                            set(selectedWaypointIndex, wp.copy(headingRad = angle, rotationDeg = Math.toDegrees(angle), nextControlLength = if (isShiftPressed) snap(mag) else mag, prevControlLength = if (isShiftPressed) snap(mag) else mag))
                                        }
                                    )
                                }
                                isDraggingPrevHeading && isDraggingHeadingActive -> {
                                    val angle = kotlin.math.atan2(dy, dx) - Math.PI
                                    val normalizedAngle = wrapAngle(angle)
                                    val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                                    onWaypointsChanged(
                                        currentWaypoints.toMutableList().apply {
                                            set(selectedWaypointIndex, wp.copy(headingRad = normalizedAngle, nextControlLength = if (isShiftPressed) snap(mag) else mag, prevControlLength = if (isShiftPressed) snap(mag) else mag))
                                        }
                                    )
                                }
                                else -> {
                                    val newPos = getRobotCoordFromScreen(change.position, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                                    val existingWp = currentWaypoints[selectedWaypointIndex]
                                    onWaypointsChanged(
                                        currentWaypoints.toMutableList().apply {
                                            set(selectedWaypointIndex, existingWp.copy(x = snap(newPos.x), y = snap(newPos.y)))
                                        }
                                    )
                                }
                            }
                        }
                        selectedObstacleId != null && showObstacleControls -> {
                            val targetObs = currentActiveObstacles.find { it.id == selectedObstacleId }
                            if (targetObs != null && !targetObs.locked) {
                                updateObstacles(
                                    currentActiveObstacles.map { obs ->
                                        if (obs.id == selectedObstacleId) {
                                            when (obs) {
                                                is Obstacle.Circle -> obs.copy(centerX = snap(dragInitialPos.x + totalDelta.x), centerY = snap(dragInitialPos.y + totalDelta.y))
                                                is Obstacle.Rectangle -> obs.copy(centerX = snap(dragInitialPos.x + totalDelta.x), centerY = snap(dragInitialPos.y + totalDelta.y))
                                                is Obstacle.Polygon -> obs.copy(vertices = dragInitialVertices.map { v -> PathPoint(snap(v.x + totalDelta.x), snap(v.y + totalDelta.y)) })
                                            }
                                        } else obs
                                    }
                                )
                            }
                        }
                        selectedAprilTagId != null && showObstacleControls -> {
                            val targetAt = currentActiveAprilTags.find { it.id == selectedAprilTagId }
                            if (targetAt != null && !targetAt.locked) {
                                updateAprilTags(
                                    currentActiveAprilTags.map { at ->
                                        if (at.id == selectedAprilTagId) at.copy(x = snap(dragInitialPos.x + totalDelta.x), y = snap(dragInitialPos.y + totalDelta.y)) else at
                                    }
                                )
                            }
                        }
                        selectedGamePieceId != null && showObstacleControls -> {
                            val targetGp = currentActiveGamePieces.find { it.id == selectedGamePieceId }
                            if (targetGp != null && !targetGp.locked) {
                                updateGamePieces(
                                    currentActiveGamePieces.map { gp ->
                                        if (gp.id == selectedGamePieceId) gp.copy(x = snap(dragInitialPos.x + totalDelta.x), y = snap(dragInitialPos.y + totalDelta.y)) else gp
                                    }
                                )
                            }
                        }
                        selectedFieldWaypointId != null && showObstacleControls -> {
                            val targetWp = currentActiveFieldWaypoints.find { it.id == selectedFieldWaypointId }
                            if (targetWp != null && !targetWp.locked) {
                                when {
                                    isDraggingFieldWaypointHeading -> {
                                        val posMeters = getRobotCoordFromScreen(change.position, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                                        val angle = kotlin.math.atan2(posMeters.y - targetWp.y, posMeters.x - targetWp.x)
                                        val degrees = Math.toDegrees(angle)
                                        val targetHeading = -degrees - 90.0
                                        val normalizedHeading = ((targetHeading + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                                        updateFieldWaypoints(currentActiveFieldWaypoints.map { wp ->
                                            if (wp.id == selectedFieldWaypointId) wp.copy(headingDegrees = if (isShiftPressed) snap(normalizedHeading) else normalizedHeading) else wp
                                        })
                                    }
                                    isDraggingFieldWaypoint -> {
                                        updateFieldWaypoints(currentActiveFieldWaypoints.map { wp ->
                                            if (wp.id == selectedFieldWaypointId) wp.copy(x = snap(dragInitialPos.x + totalDelta.x), y = snap(dragInitialPos.y + totalDelta.y)) else wp
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. On release without drag: tool mode clicks
            if (!hasDragged) {
                when (editorMode) {
                    EditorMode.ADD_WAYPOINT -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        onWaypointsChanged(currentWaypoints + newWp)
                    }
                    EditorMode.DRAW_POLYGON -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        currentPolygonPoints.add(PathPoint(newWp.x, newWp.y))
                    }
                    EditorMode.DRAW_CIRCLE -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        updateObstacles(currentActiveObstacles + Obstacle.Circle("circle_${System.currentTimeMillis()}", "Circle Obstacle ${currentActiveObstacles.size + 1}", newWp.x, newWp.y, 0.25))
                    }
                    EditorMode.DRAW_RECTANGLE -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        updateObstacles(currentActiveObstacles + Obstacle.Rectangle("rect_${System.currentTimeMillis()}", "Rectangle Obstacle ${currentActiveObstacles.size + 1}", newWp.x, newWp.y, 0.5, 0.5, 0.0))
                    }
                    EditorMode.PLACE_GAME_PIECE -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        val type = availableGamePieceTypes.firstOrNull { it.id == activeGamePieceType }
                            ?: availableGamePieceTypes.firstOrNull()
                        if (type != null) {
                            updateGamePieces(
                                currentActiveGamePieces + GamePiece(
                                    id = "piece_${System.currentTimeMillis()}",
                                    name = "${type.name} ${currentActiveGamePieces.size + 1}",
                                    x = newWp.x,
                                    y = newWp.y,
                                    type = type.name,
                                    typeId = type.id,
                                ),
                            )
                        }
                    }
                    EditorMode.PLACE_APRILTAG -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        updateAprilTags(currentActiveAprilTags + AprilTagPlacement("apriltag_${System.currentTimeMillis()}", 11 + currentActiveAprilTags.size, newWp.x, newWp.y, 0.5, 0.0))
                    }
                    EditorMode.PLACE_FIELD_WAYPOINT -> {
                        val newWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                        updateFieldWaypoints(currentActiveFieldWaypoints + FieldWaypoint("fieldwp_${System.currentTimeMillis()}", "Waypoint ${currentActiveFieldWaypoints.size + 1}", newWp.x, newWp.y, 0.0))
                    }
                    EditorMode.ERASER -> {
                        val hitIdx = currentWaypoints.indexOfFirst {
                            sqrt((pressOffset.x - getTransformedCanvasOffset(it, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset, viewRotation).x).pow(2) + (pressOffset.y - getTransformedCanvasOffset(it, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset, viewRotation).y).pow(2)) < 25f
                        }
                        if (hitIdx != -1) {
                            onWaypointsChanged(currentWaypoints.toMutableList().apply { removeAt(hitIdx) })
                        } else {
                            val robotWp = getRobotCoordFromScreen(pressOffset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                            val hitGp = currentActiveGamePieces.find { sqrt((robotWp.x - it.x).pow(2) + (robotWp.y - it.y).pow(2)) < 0.3 }
                            if (hitGp != null) {
                                updateGamePieces(currentActiveGamePieces - hitGp)
                            } else {
                                val hitObs = currentActiveObstacles.find { obs ->
                                    when (obs) {
                                        is Obstacle.Circle -> sqrt((robotWp.x - obs.centerX).pow(2) + (robotWp.y - obs.centerY).pow(2)) - obs.radius < 0.5
                                        is Obstacle.Rectangle -> sqrt((robotWp.x - obs.centerX).pow(2) + (robotWp.y - obs.centerY).pow(2)) - maxOf(obs.width, obs.height) / 2.0 < 0.5
                                        is Obstacle.Polygon -> obs.vertices.any { sqrt((robotWp.x - it.x).pow(2) + (robotWp.y - it.y).pow(2)) < 0.5 }
                                    }
                                }
                                if (hitObs != null) {
                                    updateObstacles(currentActiveObstacles - hitObs)
                                } else {
                                    val hitAt = currentActiveAprilTags.find { sqrt((robotWp.x - it.x).pow(2) + (robotWp.y - it.y).pow(2)) < 0.3 }
                                    if (hitAt != null) {
                                        updateAprilTags(currentActiveAprilTags - hitAt)
                                    } else {
                                        val hitFwp = currentActiveFieldWaypoints.find { sqrt((robotWp.x - it.x).pow(2) + (robotWp.y - it.y).pow(2)) < 0.3 }
                                        if (hitFwp != null) updateFieldWaypoints(currentActiveFieldWaypoints - hitFwp)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }

            // Reset selection state on gesture completion
            onSelectionChanged(-1, null, null, null, null, false, false, false, false)
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = { offset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                if (editorMode == EditorMode.SELECT) {
                    val clickCoord = getRobotCoordFromScreen(offset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                    val hitObs = currentActiveObstacles.find { obs ->
                        when (obs) {
                            is Obstacle.Circle -> sqrt((clickCoord.x - obs.centerX).pow(2) + (clickCoord.y - obs.centerY).pow(2)) <= obs.radius
                            is Obstacle.Rectangle -> {
                                val dx = clickCoord.x - obs.centerX
                                val dy = clickCoord.y - obs.centerY
                                val rad = Math.toRadians(-obs.rotation)
                                kotlin.math.abs(dx * cos(rad) - dy * sin(rad)) <= obs.width / 2.0 && kotlin.math.abs(dx * sin(rad) + dy * cos(rad)) <= obs.height / 2.0
                            }
                            is Obstacle.Polygon -> obs.vertices.any { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                        }
                    }
                    if (hitObs != null) {
                        onItemDoubleTapped?.invoke(hitObs.id, "Obstacle")
                    } else {
                        val hitAt = currentActiveAprilTags.find { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                        if (hitAt != null) onItemDoubleTapped?.invoke(hitAt.id, "AprilTag")
                    }
                }
            }
        )
    }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                    val offset = event.changes.first().position
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val hitWpIdx = currentWaypoints.indexOfFirst {
                        sqrt((offset.x - getTransformedCanvasOffset(it, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset, viewRotation).x).pow(2) + (offset.y - getTransformedCanvasOffset(it, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset, viewRotation).y).pow(2)) < 20.dp.toPx()
                    }
                    if (hitWpIdx != -1) {
                        onOpenContextMenu(offset, "Waypoint", hitWpIdx, null)
                        continue
                    }
                    val clickCoord = getRobotCoordFromScreen(offset, w, h, fieldWidthM, fieldHeightM, league, zoomScale, panOffset)
                    val hitObs = currentActiveObstacles.find { obs ->
                        when (obs) {
                            is Obstacle.Circle -> sqrt((clickCoord.x - obs.centerX).pow(2) + (clickCoord.y - obs.centerY).pow(2)) <= obs.radius
                            is Obstacle.Rectangle -> {
                                val dx = clickCoord.x - obs.centerX
                                val dy = clickCoord.y - obs.centerY
                                val rad = Math.toRadians(-obs.rotation)
                                kotlin.math.abs(dx * cos(rad) - dy * sin(rad)) <= obs.width / 2.0 && kotlin.math.abs(dx * sin(rad) + dy * cos(rad)) <= obs.height / 2.0
                            }
                            is Obstacle.Polygon -> obs.vertices.any { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                        }
                    }
                    if (hitObs != null) {
                        onOpenContextMenu(offset, "Obstacle", -1, hitObs.id)
                        continue
                    }
                    val hitAt = currentActiveAprilTags.find { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                    if (hitAt != null) {
                        onOpenContextMenu(offset, "AprilTag", -1, hitAt.id)
                        continue
                    }
                    val hitGp = currentActiveGamePieces.find { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.2 }
                    if (hitGp != null) {
                        onOpenContextMenu(offset, "GamePiece", -1, hitGp.id)
                        continue
                    }
                    val hitFwp = currentActiveFieldWaypoints.find { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                    if (hitFwp != null) {
                        onOpenContextMenu(offset, "FieldWaypoint", -1, hitFwp.id)
                        continue
                    }
                }
            }
        }
    }
