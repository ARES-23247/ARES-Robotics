package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.theme.*

@Composable
fun FieldCanvasContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset,
    targetType: String?,
    targetIndex: Int,
    targetId: String?,
    waypoints: List<Waypoint>,
    obstacles: List<Obstacle>,
    aprilTags: List<AprilTagPlacement>,
    gamePieces: List<GamePiece>,
    fieldWaypoints: List<FieldWaypoint>,
    onWaypointsChanged: (List<Waypoint>) -> Unit,
    updateObstacles: (List<Obstacle>) -> Unit,
    updateAprilTags: (List<AprilTagPlacement>) -> Unit,
    updateGamePieces: (List<GamePiece>) -> Unit,
    updateFieldWaypoints: (List<FieldWaypoint>) -> Unit,
    onClearSelected: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder, RoundedCornerShape(4.dp))
    ) {
        when {
            targetType == "Waypoint" && targetIndex in waypoints.indices -> {
                DropdownMenuItem(onClick = onDismissRequest) { Text("Edit Waypoint...", color = AresTextPrimary) }
                if (waypoints[targetIndex].rotationDeg != null) {
                    DropdownMenuItem(onClick = {
                        onWaypointsChanged(waypoints.toMutableList().apply {
                            set(targetIndex, this[targetIndex].copy(rotationDeg = null))
                        })
                        onDismissRequest()
                    }) { Text("Clear Rotation", color = AresTextPrimary) }
                }
                if (waypoints[targetIndex].headingRad != null) {
                    DropdownMenuItem(onClick = {
                        onWaypointsChanged(waypoints.toMutableList().apply {
                            set(targetIndex, this[targetIndex].copy(headingRad = null))
                        })
                        onDismissRequest()
                    }) { Text("Reset to Auto Heading", color = AresTextPrimary) }
                }
                DropdownMenuItem(onClick = {
                    onWaypointsChanged(waypoints.toMutableList().apply { removeAt(targetIndex) })
                    onDismissRequest()
                    onClearSelected()
                }) { Text("Delete Waypoint", color = AresRed) }
            }
            targetType == "Obstacle" && targetId != null -> {
                DropdownMenuItem(onClick = {
                    updateObstacles(obstacles.filter { it.id != targetId })
                    onDismissRequest()
                    onClearSelected()
                }) { Text("Delete Obstacle", color = AresRed) }
            }
            targetType == "AprilTag" && targetId != null -> {
                DropdownMenuItem(onClick = {
                    updateAprilTags(aprilTags.filter { it.id != targetId })
                    onDismissRequest()
                    onClearSelected()
                }) { Text("Delete AprilTag", color = AresRed) }
            }
            targetType == "GamePiece" && targetId != null -> {
                val gp = gamePieces.find { it.id == targetId }
                if (gp != null) {
                    DropdownMenuItem(onClick = {
                        val nextType = if (gp.type == "Sample") "Specimen" else "Sample"
                        updateGamePieces(gamePieces.map { if (it.id == targetId) it.copy(type = nextType) else it })
                        onDismissRequest()
                    }) { Text("Toggle Type: ${if (gp.type == "Sample") "Specimen" else "Sample"}", color = AresTextPrimary) }
                }
                DropdownMenuItem(onClick = {
                    updateGamePieces(gamePieces.filter { it.id != targetId })
                    onDismissRequest()
                    onClearSelected()
                }) { Text("Delete Game Piece", color = AresRed) }
            }
            targetType == "FieldWaypoint" && targetId != null -> {
                DropdownMenuItem(onClick = {
                    updateFieldWaypoints(fieldWaypoints.filter { it.id != targetId })
                    onDismissRequest()
                    onClearSelected()
                }) { Text("Delete Field Waypoint", color = AresRed) }
            }
            else -> {
                DropdownMenuItem(onClick = onDismissRequest) { Text("Cancel", color = AresTextSecondary) }
            }
        }
    }
}
