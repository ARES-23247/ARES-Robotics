package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.field.FieldDocumentMapper

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FieldCanvasToolbar(
    showPathControls: Boolean,
    showObstacleControls: Boolean,
    league: League,
    editorMode: EditorMode,
    onEditorModeChanged: (EditorMode) -> Unit,
    activeGamePieceType: String,
    onActiveGamePieceTypeChanged: (String) -> Unit,
    currentPolygonPoints: List<PathPoint>,
    onCurrentPolygonPointsCleared: () -> Unit,
    activeObstacles: List<Obstacle>,
    updateObstacles: (List<Obstacle>) -> Unit,
    zoomScale: Float,
    onZoomScaleChanged: (Float) -> Unit,
    onResetZoomPan: () -> Unit,
    showHeatmap: Boolean,
    onShowHeatmapChanged: (Boolean) -> Unit,
    showCostmap: Boolean,
    onShowCostmapChanged: (Boolean) -> Unit,
    viewRotation: Float,
    onViewRotationChanged: (Float) -> Unit,
    gamePieceTypes: List<GamePieceType> = emptyList(),
    onGamePieceTypesChanged: ((List<GamePieceType>) -> Unit)? = null,
) {
    var showCatalogDialog by remember { mutableStateOf(false) }

    if (showCatalogDialog && onGamePieceTypesChanged != null) {
        GamePieceCatalogDialog(
            gamePieceTypes = if (gamePieceTypes.isNotEmpty()) gamePieceTypes else FieldDocumentMapper.defaultGamePieceTypes(league),
            onTypesChanged = onGamePieceTypesChanged,
            onDismiss = { showCatalogDialog = false },
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = AresSurfaceElevated
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showPathControls || showObstacleControls) {
                // Select Mode
                TooltipButton(
                    tooltipText = "Select & Drag Mode",
                    onClick = { onEditorModeChanged(EditorMode.SELECT) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.SELECT) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.PanTool, contentDescription = "Select & Drag Mode")
                }
            }

            // Add Waypoint Mode
            if (showPathControls) {
                TooltipButton(
                    tooltipText = "Add Path Waypoint Mode",
                    onClick = { onEditorModeChanged(EditorMode.ADD_WAYPOINT) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.ADD_WAYPOINT) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.AddLocation, contentDescription = "Add Path Waypoint Mode")
                }
            }

            // Draw Polygon Obstacle
            if (showObstacleControls) {
                TooltipButton(
                    tooltipText = "Draw Free-form Obstacle (Polygon)",
                    onClick = { onEditorModeChanged(EditorMode.DRAW_POLYGON) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.DRAW_POLYGON) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.Polyline, contentDescription = "Draw Free Form Obstacle")
                }
            }

            if (showObstacleControls && editorMode == EditorMode.DRAW_POLYGON && currentPolygonPoints.isNotEmpty()) {
                TextButton(
                    onClick = {
                        if (currentPolygonPoints.size >= 3) {
                            val newPoly = Obstacle.Polygon(
                                id = "poly_${System.currentTimeMillis()}",
                                name = "Polygon Obstacle ${activeObstacles.size + 1}",
                                vertices = currentPolygonPoints.toList()
                            )
                            updateObstacles(activeObstacles + newPoly)
                        }
                        onCurrentPolygonPointsCleared()
                    }
                ) {
                    Text("Finish Poly", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Draw Circle Obstacle
            if (showObstacleControls) {
                TooltipButton(
                    tooltipText = "Draw Circle Obstacle",
                    onClick = { onEditorModeChanged(EditorMode.DRAW_CIRCLE) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.DRAW_CIRCLE) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.TripOrigin, contentDescription = "Draw Circle Obstacle")
                }
            }

            // Draw Rectangle Obstacle
            if (showObstacleControls) {
                TooltipButton(
                    tooltipText = "Draw Rectangle Obstacle",
                    onClick = { onEditorModeChanged(EditorMode.DRAW_RECTANGLE) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.DRAW_RECTANGLE) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.CropSquare, contentDescription = "Draw Rect Obstacle")
                }
            }

            // Place Game Piece Mode
            if (showObstacleControls) {
                TooltipButton(
                    tooltipText = "Place Game Piece Mode",
                    onClick = { onEditorModeChanged(EditorMode.PLACE_GAME_PIECE) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.PLACE_GAME_PIECE) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.Extension, contentDescription = "Place Game Piece")
                }
            }

            if (showObstacleControls && editorMode == EditorMode.PLACE_GAME_PIECE) {
                Box(modifier = Modifier.height(24.dp).width(1.dp).background(AresBorder))
                val availableTypes = if (gamePieceTypes.isNotEmpty()) gamePieceTypes else FieldDocumentMapper.defaultGamePieceTypes(league)
                availableTypes.forEach { type ->
                    val isSel = activeGamePieceType == type.id
                    val parsedColor = remember(type.colorHex) {
                        val clean = type.colorHex.removePrefix("#").trim()
                        val fullHex = if (clean.length == 6) "FF$clean" else clean
                        val intVal = fullHex.toLongOrNull(16) ?: 0xFFFFD700
                        Color(intVal)
                    }
                    TextButton(
                        onClick = { onActiveGamePieceTypeChanged(type.id) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).background(parsedColor, CircleShape))
                            Text(
                                text = type.name.take(12),
                                color = if (isSel) parsedColor else AresTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (onGamePieceTypesChanged != null) {
                    TooltipButton(
                        tooltipText = "Manage Game Piece Types",
                        onClick = { showCatalogDialog = true },
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Manage Types", modifier = Modifier.size(14.dp), tint = AresCyan)
                    }
                }
            }

            // Place AprilTag Mode
            if (showObstacleControls && league == League.FTC) {
                TooltipButton(
                    tooltipText = "Place AprilTag Mode",
                    onClick = { onEditorModeChanged(EditorMode.PLACE_APRILTAG) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.PLACE_APRILTAG) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Place AprilTag")
                }
            }

            // Place Field Waypoint Mode
            if (showObstacleControls) {
                TooltipButton(
                    tooltipText = "Place Named Waypoint Mode",
                    onClick = { onEditorModeChanged(EditorMode.PLACE_FIELD_WAYPOINT) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.PLACE_FIELD_WAYPOINT) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Place Waypoint")
                }
            }

            // Eraser Mode
            if (showPathControls || showObstacleControls) {
                TooltipButton(
                    tooltipText = "Eraser Mode (Delete Elements)",
                    onClick = { onEditorModeChanged(EditorMode.ERASER) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (editorMode == EditorMode.ERASER) AresCyan else AresTextTertiary)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Eraser")
                }
            }

            if (showPathControls || showObstacleControls) {
                Box(modifier = Modifier.height(24.dp).width(1.dp).background(AresBorder))
            }

            // Zoom controls
            TooltipButton(
                tooltipText = "Zoom In",
                onClick = { onZoomScaleChanged((zoomScale + 0.1f).coerceAtMost(3f)) }
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = AresTextSecondary)
            }
            TooltipButton(
                tooltipText = "Zoom Out",
                onClick = { onZoomScaleChanged((zoomScale - 0.1f).coerceAtLeast(0.5f)) }
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = AresTextSecondary)
            }
            TooltipButton(
                tooltipText = "Reset Zoom and Pan",
                onClick = { onResetZoomPan() }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset Zoom", tint = AresTextSecondary)
            }
            TooltipButton(
                tooltipText = "Toggle Heatmap View",
                onClick = { onShowHeatmapChanged(!showHeatmap) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = if (showHeatmap) AresCyan else AresTextTertiary)
            ) {
                Icon(Icons.Default.Whatshot, contentDescription = "Toggle Heatmap")
            }
            TooltipButton(
                tooltipText = "Toggle Bumper Clearance Boundaries",
                onClick = { onShowCostmapChanged(!showCostmap) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = if (showCostmap) AresCyan else AresTextTertiary)
            ) {
                Icon(Icons.Default.GridOn, contentDescription = "Toggle Costmap clearance visualizer")
            }
            TooltipButton(
                tooltipText = "Rotate Field View 90°",
                onClick = { onViewRotationChanged((viewRotation - 90f + 360f) % 360f) }
            ) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate View", tint = AresTextSecondary)
            }
        }
    }
}

// rememberPlainTooltipPositionProvider: the recommended
// rememberTooltipPositionProvider is not shipped by this Compose
// version; migrate at the next Compose bump.
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipButton(
    tooltipText: String,
    onClick: () -> Unit,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltipText) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, colors = colors, modifier = modifier, content = content)
    }
}
