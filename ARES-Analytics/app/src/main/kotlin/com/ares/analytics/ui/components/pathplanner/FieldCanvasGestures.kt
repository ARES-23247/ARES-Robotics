package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    gestureState: FieldCanvasGestureState,
    currentPolygonPoints: SnapshotStateList<PathPoint>,
    currentPenStrokePoints: SnapshotStateList<Waypoint>? = null,
): Modifier = this
    .pointerInput(autoGoalMode, gestureState) {
        var accumulatedDragPx = Offset.Zero
        var dragInitialPos = Waypoint(0.0, 0.0)
        var dragInitialVertices: List<PathPoint> = emptyList()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val s = gestureState
            val pressOffset = down.position
            var hasDragged = false
            accumulatedDragPx = Offset.Zero
            var gestureSelection = FieldCanvasGestureSelection()

            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val pressCoord = getRobotCoordFromScreen(pressOffset, w, h, s.fieldWidthM, s.fieldHeightM,
                s.league, s.zoomScale, s.panOffset, s.viewRotation)

            when (s.editorMode) {
                EditorMode.SELECT -> {
                    var hitIdx = -1
                    var hitHeading = false
                    var hitPrevHeading = false

                    val basePress = getBaseCanvasFromScreen(pressOffset, w, h, s.zoomScale, s.panOffset, s.viewRotation)
                    val hitRadiusPx = 15.dp.toPx() / s.zoomScale
                    val waypoints = s.waypoints

                    // 1. Prioritize handles of the ALREADY selected waypoint
                    if (s.selectedWaypointIndex in waypoints.indices) {
                        val wp = waypoints[s.selectedWaypointIndex]
                        val selHeading = resolveHeading(waypoints, s.selectedWaypointIndex)
                        val handleDist = if (autoGoalMode) (s.robotDimensions.lengthMeters / 2.0 + 0.08) else wp.nextControlLength
                        val headingWp = Waypoint(wp.x + handleDist * cos(selHeading), wp.y + handleDist * sin(selHeading))
                        val headingBase = getCanvasOffsetBase(headingWp, w, h, s.fieldWidthM, s.fieldHeightM, s.league)
                        if (sqrt((basePress.x - headingBase.x).pow(2) + (basePress.y - headingBase.y).pow(2)) < hitRadiusPx) {
                            hitIdx = s.selectedWaypointIndex
                            hitHeading = true
                        }

                        if (!autoGoalMode) {
                            val prevHeadingWp = Waypoint(wp.x + wp.prevControlLength * cos(selHeading + Math.PI), wp.y + wp.prevControlLength * sin(selHeading + Math.PI))
                            val prevHeadingBase = getCanvasOffsetBase(prevHeadingWp, w, h, s.fieldWidthM, s.fieldHeightM, s.league)
                            if (hitIdx == -1 && sqrt((basePress.x - prevHeadingBase.x).pow(2) + (basePress.y - prevHeadingBase.y).pow(2)) < hitRadiusPx) {
                                hitIdx = s.selectedWaypointIndex
                                hitPrevHeading = true
                            }
                        }
                    }

                    // 2. Check all waypoint center dots
                    if (hitIdx == -1) {
                        for (i in waypoints.indices) {
                            val wpBase = getCanvasOffsetBase(waypoints[i], w, h, s.fieldWidthM, s.fieldHeightM, s.league)
                            if (sqrt((basePress.x - wpBase.x).pow(2) + (basePress.y - wpBase.y).pow(2)) < hitRadiusPx) {
                                hitIdx = i
                                break
                            }
                        }
                    }

                    // 3. Check other waypoints tangent handles
                    if (hitIdx == -1) {
                        for (i in waypoints.indices) {
                            if (i == s.selectedWaypointIndex) continue
                            val wp = waypoints[i]
                            val hd = resolveHeading(waypoints, i)
                            val handleDist = if (autoGoalMode) (s.robotDimensions.lengthMeters / 2.0 + 0.08) else wp.nextControlLength
                            val headingWp = Waypoint(wp.x + handleDist * cos(hd), wp.y + handleDist * sin(hd))
                            val headingBase = getCanvasOffsetBase(headingWp, w, h, s.fieldWidthM, s.fieldHeightM, s.league)
                            if (sqrt((basePress.x - headingBase.x).pow(2) + (basePress.y - headingBase.y).pow(2)) < hitRadiusPx) {
                                hitIdx = i
                                hitHeading = true
                                break
                            }
                            if (!autoGoalMode) {
                                val prevHeadingWp = Waypoint(wp.x + wp.prevControlLength * cos(hd + Math.PI), wp.y + wp.prevControlLength * sin(hd + Math.PI))
                                val prevHeadingBase = getCanvasOffsetBase(prevHeadingWp, w, h, s.fieldWidthM, s.fieldHeightM, s.league)
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
                        for (wp in s.activeFieldWaypoints) {
                            val wpOffset = getTransformedCanvasOffset(Waypoint(wp.x, wp.y), w, h, s.fieldWidthM, s.fieldHeightM, s.league, s.zoomScale, s.panOffset, s.viewRotation)
                            val angleRad = Math.toRadians(fieldHeadingToCanvasAngleDeg(wp.headingDegrees, s.league) + s.viewRotation)
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
                            s.onItemSelected?.invoke(hitFieldWpId, "FieldWaypoint")
                            if (hitFieldWpCenter) {
                                s.activeFieldWaypoints.find { it.id == hitFieldWpId }
                                    ?.let { dragInitialPos = Waypoint(it.x, it.y) }
                            }
                        }
                        hitIdx != -1 -> {
                            // Selected waypoint
                        }
                        else -> {
                            val clickCoord = pressCoord
                            val hitObs = findObstacleForDrag(clickCoord, s.activeObstacles)
                            newObsId = hitObs?.id
                            if (newObsId != null) {
                                s.onItemSelected?.invoke(newObsId, "Obstacle")
                                when (hitObs) {
                                    is Obstacle.Circle -> dragInitialPos = Waypoint(hitObs.centerX, hitObs.centerY)
                                    is Obstacle.Rectangle -> dragInitialPos = Waypoint(hitObs.centerX, hitObs.centerY)
                                    is Obstacle.Polygon -> dragInitialVertices = hitObs.vertices.toList()
                                    else -> {}
                                }
                            } else {
                                val hitAt = s.activeAprilTags.minByOrNull { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) }?.takeIf { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.3 }
                                newAtId = hitAt?.id
                                hitAt?.let { aprilTag ->
                                    s.onItemSelected?.invoke(aprilTag.id, "AprilTag")
                                    dragInitialPos = Waypoint(aprilTag.x, aprilTag.y)
                                } ?: run {
                                    val hitGp = s.activeGamePieces.minByOrNull { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) }?.takeIf { sqrt((clickCoord.x - it.x).pow(2) + (clickCoord.y - it.y).pow(2)) < 0.2 }
                                    newGpId = hitGp?.id
                                    hitGp?.let { gamePiece ->
                                        s.onItemSelected?.invoke(gamePiece.id, "GamePiece")
                                        dragInitialPos = Waypoint(gamePiece.x, gamePiece.y)
                                    } ?: s.onItemSelected?.invoke(null, null)
                                }
                            }
                        }
                    }

                    gestureSelection = FieldCanvasGestureSelection(
                        waypointIndex = hitIdx,
                        obstacleId = newObsId,
                        aprilTagId = newAtId,
                        gamePieceId = newGpId,
                        fieldWaypointId = hitFieldWpId,
                        isDraggingHeading = hitIdx != -1 && hitHeading,
                        isDraggingPrevHeading = hitIdx != -1 && hitPrevHeading,
                        isDraggingFieldWaypoint = hitFieldWpId != null && hitFieldWpCenter,
                        isDraggingFieldWaypointHeading = hitFieldWpId != null && hitFieldWpHeading,
                    )

                    s.onSelectionChanged(
                        gestureSelection.waypointIndex,
                        gestureSelection.obstacleId,
                        gestureSelection.aprilTagId,
                        gestureSelection.gamePieceId,
                        gestureSelection.fieldWaypointId,
                        gestureSelection.isDraggingHeading,
                        gestureSelection.isDraggingPrevHeading,
                        gestureSelection.isDraggingFieldWaypoint,
                        gestureSelection.isDraggingFieldWaypointHeading,
                    )
                }
                EditorMode.PEN_PATH -> {
                    currentPenStrokePoints?.clear()
                    currentPenStrokePoints?.add(pressCoord)
                }
                else -> { /* Placement handled on release when !hasDragged */ }
            }

            // 3. Wait for touch slop then track drag
            val dragTarget = gestureSelection.dragTarget(s.waypoints.size, s.showObstacleControls)
            val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                change.consume()
            }

            if (slopChange != null) {
                accumulatedDragPx = slopChange.position - down.position
                drag(slopChange.id) { change ->
                    val live = gestureState
                    hasDragged = true
                    val dragAmount = change.positionChange()
                    accumulatedDragPx += dragAmount
                    change.consume()

                    fun snap(v: Double) = if (live.isShiftPressed) snapCoordinateMeters(v, 0.05) else v
                    val totalDelta = getDragDeltaInFieldCoords(accumulatedDragPx, w, h, live.fieldWidthM, live.fieldHeightM, live.league, live.zoomScale, live.viewRotation)

                    when (val target = dragTarget) {
                        is FieldCanvasDragTarget.Waypoint -> {
                            val liveWaypoints = live.waypoints
                            if (target.index !in liveWaypoints.indices) return@drag
                            val wp = liveWaypoints[target.index]
                            val posMeters = getRobotCoordFromScreen(change.position, w, h, live.fieldWidthM, live.fieldHeightM, live.league, live.zoomScale, live.panOffset, live.viewRotation)
                            val dx = posMeters.x - wp.x
                            val dy = posMeters.y - wp.y

                            when {
                                target.heading -> {
                                    val rawAngle = kotlin.math.atan2(dy, dx)
                                    val angle = if (live.isShiftPressed) snapHeadingRadians(rawAngle, 15.0) else rawAngle
                                    val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                                    live.onWaypointsChanged(
                                        liveWaypoints.toMutableList().apply {
                                            set(target.index, wp.copy(headingRad = angle, rotationDeg = Math.toDegrees(angle), nextControlLength = if (live.isShiftPressed) snap(mag) else mag, prevControlLength = if (live.isShiftPressed) snap(mag) else mag))
                                        }
                                    )
                                }
                                target.previousHeading -> {
                                    val angle = kotlin.math.atan2(dy, dx) - Math.PI
                                    val rawNormalized = wrapAngle(angle)
                                    val normalizedAngle = if (live.isShiftPressed) snapHeadingRadians(rawNormalized, 15.0) else rawNormalized
                                    val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                                    live.onWaypointsChanged(
                                        liveWaypoints.toMutableList().apply {
                                            set(target.index, wp.copy(headingRad = normalizedAngle, rotationDeg = Math.toDegrees(normalizedAngle), nextControlLength = if (live.isShiftPressed) snap(mag) else mag, prevControlLength = if (live.isShiftPressed) snap(mag) else mag))
                                        }
                                    )
                                }
                                else -> {
                                    val newPos = posMeters
                                    val existingWp = liveWaypoints[target.index]
                                    live.onWaypointsChanged(
                                        liveWaypoints.toMutableList().apply {
                                            set(target.index, existingWp.copy(x = snap(newPos.x), y = snap(newPos.y)))
                                        }
                                    )
                                }
                            }
                        }
                        is FieldCanvasDragTarget.Obstacle -> {
                            val targetObs = live.activeObstacles.find { it.id == target.id }
                            if (targetObs != null && !targetObs.locked) {
                                live.updateObstacles(
                                    live.activeObstacles.map { obs ->
                                        if (obs.id == target.id) {
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
                        is FieldCanvasDragTarget.AprilTag -> {
                            val targetAt = live.activeAprilTags.find { it.id == target.id }
                            if (targetAt != null && !targetAt.locked) {
                                live.updateAprilTags(
                                    live.activeAprilTags.map { at ->
                                        if (at.id == target.id) at.copy(x = snap(dragInitialPos.x + totalDelta.x), y = snap(dragInitialPos.y + totalDelta.y)) else at
                                    }
                                )
                            }
                        }
                        is FieldCanvasDragTarget.GamePiece -> {
                            val targetGp = live.activeGamePieces.find { it.id == target.id }
                            if (targetGp != null && !targetGp.locked) {
                                live.updateGamePieces(
                                    live.activeGamePieces.map { gp ->
                                        if (gp.id == target.id) gp.copy(x = snap(dragInitialPos.x + totalDelta.x), y = snap(dragInitialPos.y + totalDelta.y)) else gp
                                    }
                                )
                            }
                        }
                        is FieldCanvasDragTarget.FieldWaypoint -> {
                            val targetWp = live.activeFieldWaypoints.find { it.id == target.id }
                            if (targetWp != null && !targetWp.locked) {
                                when {
                                    target.heading -> {
                                        val posMeters = getRobotCoordFromScreen(change.position, w, h, live.fieldWidthM, live.fieldHeightM, live.league, live.zoomScale, live.panOffset, live.viewRotation)
                                        val angle = kotlin.math.atan2(posMeters.y - targetWp.y, posMeters.x - targetWp.x)
                                        val targetHeading = Math.toDegrees(angle)
                                        val normalizedHeading = ((targetHeading + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                                        val snappedHeadingDeg = if (live.isShiftPressed) {
                                            Math.toDegrees(snapHeadingRadians(Math.toRadians(normalizedHeading), 15.0))
                                        } else {
                                            normalizedHeading
                                        }
                                        live.updateFieldWaypoints(live.activeFieldWaypoints.map { wp ->
                                            if (wp.id == target.id) wp.copy(headingDegrees = snappedHeadingDeg) else wp
                                        })
                                    }
                                    target.position -> {
                                        live.updateFieldWaypoints(live.activeFieldWaypoints.map { wp ->
                                            if (wp.id == target.id) wp.copy(x = snap(dragInitialPos.x + totalDelta.x), y = snap(dragInitialPos.y + totalDelta.y)) else wp
                                        })
                                    }
                                }
                            }
                        }
                        null -> {
                            if (live.editorMode == EditorMode.PEN_PATH) {
                                val posMeters = getRobotCoordFromScreen(change.position, w, h, live.fieldWidthM, live.fieldHeightM, live.league, live.zoomScale, live.panOffset, live.viewRotation)
                                currentPenStrokePoints?.add(posMeters)
                            }
                        }
                    }
                }
            }

            val finalState = gestureState
            if (finalState.editorMode == EditorMode.PEN_PATH && hasDragged) {
                val stroke = currentPenStrokePoints?.toList().orEmpty()
                currentPenStrokePoints?.clear()
                if (stroke.size >= 2) {
                    val anchors = FreehandStrokeSplineFitter.fitStrokeToWaypoints(
                        stroke = stroke,
                        epsilonMeters = 0.08,
                        startingWaypoint = finalState.waypoints.lastOrNull(),
                    )
                    if (anchors.size >= 2) {
                        val updated = if (finalState.waypoints.isNotEmpty()) {
                            finalState.waypoints.dropLast(1) + anchors
                        } else {
                            anchors
                        }
                        finalState.onWaypointsChanged(updated)
                        finalState.onPenStrokeCompleted?.invoke(anchors)
                    }
                }
            }

            // 4. On release without drag: tool mode clicks
            if (!hasDragged) {
                when (finalState.editorMode) {
                    EditorMode.ADD_WAYPOINT, EditorMode.PEN_PATH -> {
                        currentPenStrokePoints?.clear()
                        val newWp = pressCoord
                        finalState.onWaypointsChanged(finalState.waypoints + newWp)
                    }
                    EditorMode.DRAW_POLYGON -> {
                        val newWp = pressCoord
                        currentPolygonPoints.add(PathPoint(newWp.x, newWp.y))
                    }
                    EditorMode.DRAW_CIRCLE -> {
                        val newWp = pressCoord
                        finalState.updateObstacles(finalState.activeObstacles + Obstacle.Circle("circle_${System.currentTimeMillis()}", "Circle Obstacle ${finalState.activeObstacles.size + 1}", newWp.x, newWp.y, 0.25))
                    }
                    EditorMode.DRAW_RECTANGLE -> {
                        val newWp = pressCoord
                        finalState.updateObstacles(finalState.activeObstacles + Obstacle.Rectangle("rect_${System.currentTimeMillis()}", "Rectangle Obstacle ${finalState.activeObstacles.size + 1}", newWp.x, newWp.y, 0.5, 0.5, 0.0))
                    }
                    EditorMode.PLACE_GAME_PIECE -> {
                        val newWp = pressCoord
                        val type = finalState.availableGamePieceTypes.firstOrNull { it.id == finalState.activeGamePieceType }
                            ?: finalState.availableGamePieceTypes.firstOrNull()
                        if (type != null) {
                            finalState.updateGamePieces(
                                finalState.activeGamePieces + GamePiece(
                                    id = "piece_${System.currentTimeMillis()}",
                                    name = "${type.name} ${finalState.activeGamePieces.size + 1}",
                                    x = newWp.x,
                                    y = newWp.y,
                                    type = type.name,
                                    typeId = type.id,
                                ),
                            )
                        }
                    }
                    EditorMode.PLACE_APRILTAG -> {
                        val newWp = pressCoord
                        finalState.updateAprilTags(finalState.activeAprilTags + AprilTagPlacement("apriltag_${System.currentTimeMillis()}", 11 + finalState.activeAprilTags.size, newWp.x, newWp.y, 0.5, 0.0))
                    }
                    EditorMode.PLACE_FIELD_WAYPOINT -> {
                        val newWp = pressCoord
                        finalState.updateFieldWaypoints(finalState.activeFieldWaypoints + FieldWaypoint("fieldwp_${System.currentTimeMillis()}", "Waypoint ${finalState.activeFieldWaypoints.size + 1}", newWp.x, newWp.y, 0.0))
                    }
                    EditorMode.ERASER -> {
                        val hitIdx = finalState.waypoints.indexOfFirst {
                            val screen = getTransformedCanvasOffset(it, w, h, finalState.fieldWidthM, finalState.fieldHeightM,
                                finalState.league, finalState.zoomScale, finalState.panOffset, finalState.viewRotation)
                            (pressOffset - screen).getDistance() < 25f
                        }
                        if (hitIdx != -1) {
                            finalState.onWaypointsChanged(finalState.waypoints.toMutableList().apply { removeAt(hitIdx) })
                        } else {
                            when (val target = findFieldEraseTarget(pressCoord, finalState.activeObstacles,
                                finalState.activeGamePieces, finalState.activeAprilTags, finalState.activeFieldWaypoints)) {
                                is FieldEraseTarget.Piece -> finalState.updateGamePieces(finalState.activeGamePieces - target.item)
                                is FieldEraseTarget.Shape -> finalState.updateObstacles(finalState.activeObstacles - target.item)
                                is FieldEraseTarget.Tag -> finalState.updateAprilTags(finalState.activeAprilTags - target.item)
                                is FieldEraseTarget.NamedWaypoint -> finalState.updateFieldWaypoints(finalState.activeFieldWaypoints - target.item)
                                null -> Unit
                            }
                        }
                    }
                    else -> {}
                }
            }

            // Reset selection state on gesture completion
            finalState.onSelectionChanged(-1, null, null, null, null, false, false, false, false)
        }
    }
    .fieldCanvasItemGestures(gestureState)
