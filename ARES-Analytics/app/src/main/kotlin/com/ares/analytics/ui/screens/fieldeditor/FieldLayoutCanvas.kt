package com.ares.analytics.ui.screens.fieldeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.ui.components.pathplanner.drawAprilTags
import com.ares.analytics.ui.components.pathplanner.drawCoordinateAxes
import com.ares.analytics.ui.components.pathplanner.drawCustomObstacles
import com.ares.analytics.ui.components.pathplanner.drawFieldBackground
import com.ares.analytics.ui.components.pathplanner.drawFieldGrid
import com.ares.analytics.ui.components.pathplanner.drawFieldWaypoints
import com.ares.analytics.ui.components.pathplanner.drawFtcAllianceStations
import com.ares.analytics.ui.components.pathplanner.drawGamePieces
import com.ares.analytics.ui.components.pathplanner.getCanvasOffsetBase
import com.ares.analytics.ui.components.pathplanner.getDragDeltaInFieldCoords
import com.ares.analytics.ui.components.pathplanner.getRobotCoordFromScreen
import com.ares.analytics.ui.components.pathplanner.getTransformedCanvasOffset
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.field.FieldEditorLayout
import com.ares.analytics.viewmodel.field.FieldValidationIssue
import com.ares.analytics.viewmodel.field.FieldValidationSeverity
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

private enum class FieldLayoutTool { SELECT, POLYGON, CIRCLE, RECTANGLE, GAME_PIECE, APRIL_TAG, WAYPOINT }
private enum class TransformHandle { MOVE, RESIZE, ROTATE, BOX }

/** Dedicated field-authoring surface, intentionally separate from the autonomous path canvas. */
@Composable
fun FieldLayoutCanvas(
    league: League,
    fieldImage: ImageBitmap?,
    fieldImageConfig: FieldImageConfig,
    layout: FieldEditorLayout,
    gamePieceTypes: List<GamePieceType>,
    selectedIds: Set<String>,
    snapEnabled: Boolean,
    gridSpacingMeters: Double,
    validationIssues: List<FieldValidationIssue>,
    onSelectionChanged: (Set<String>, Boolean) -> Unit,
    onLayoutChanged: (FieldEditorLayout) -> Unit,
    modifier: Modifier = Modifier
) {
    var tool by remember { mutableStateOf(FieldLayoutTool.SELECT) }
    var zoomScale by remember { mutableStateOf(1f) }
    val panOffset = Offset.Zero
    val polygonDraft = remember { mutableStateListOf<PathPoint>() }
    var boxStart by remember { mutableStateOf<Offset?>(null) }
    var boxEnd by remember { mutableStateOf<Offset?>(null) }
    val currentLayout by rememberUpdatedState(layout)
    val currentSelection by rememberUpdatedState(selectedIds)
    val currentSnapEnabled by rememberUpdatedState(snapEnabled)
    val currentGridSpacing by rememberUpdatedState(gridSpacingMeters)
    val currentTool by rememberUpdatedState(tool)
    val isShiftPressed = LocalWindowInfo.current.keyboardModifiers.isShiftPressed
    val textMeasurer = rememberTextMeasurer()
    val fieldWidth = fieldImageConfig.widthMeters.takeIf { it > 0.0 } ?: if (league == League.FTC) 3.6576 else 16.541
    val fieldHeight = fieldImageConfig.heightMeters.takeIf { it > 0.0 } ?: if (league == League.FTC) 3.6576 else 8.211

    fun snap(value: Double): Double {
        val spacing = currentGridSpacing.coerceAtLeast(0.001)
        return if (currentSnapEnabled) round(value / spacing) * spacing else value
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, AresBorder, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = AresSurfaceElevated
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LayoutToolButton(FieldLayoutTool.SELECT, tool, { tool = it }, Icons.Default.PanTool, "Select, drag, or box select")
                LayoutToolButton(FieldLayoutTool.RECTANGLE, tool, { tool = it }, Icons.Default.CropSquare, "Place rectangle")
                LayoutToolButton(FieldLayoutTool.CIRCLE, tool, { tool = it }, Icons.Default.TripOrigin, "Place circle")
                LayoutToolButton(FieldLayoutTool.POLYGON, tool, { tool = it }, Icons.Default.Polyline, "Draw polygon")
                LayoutToolButton(FieldLayoutTool.GAME_PIECE, tool, { tool = it }, Icons.Default.Extension, "Place game piece")
                LayoutToolButton(FieldLayoutTool.APRIL_TAG, tool, { tool = it }, Icons.Default.QrCodeScanner, "Place AprilTag")
                LayoutToolButton(FieldLayoutTool.WAYPOINT, tool, { tool = it }, Icons.Default.LocationOn, "Place named waypoint")

                if (tool == FieldLayoutTool.POLYGON && polygonDraft.isNotEmpty()) {
                    TextButton(onClick = {
                        if (polygonDraft.size >= 3) {
                            val obstacle = Obstacle.Polygon(nextCanvasId("polygon"), "Polygon ${layout.obstacles.size + 1}", polygonDraft.toList())
                            onLayoutChanged(layout.copy(obstacles = layout.obstacles + obstacle))
                            onSelectionChanged(setOf(obstacle.id), false)
                        }
                        polygonDraft.clear()
                        tool = FieldLayoutTool.SELECT
                    }) { Text("Finish polygon", color = AresCyan, fontSize = 11.sp) }
                }

                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { zoomScale = (zoomScale + 0.15f).coerceAtMost(3f) }) {
                        Icon(Icons.Default.ZoomIn, "Zoom in", tint = AresTextSecondary)
                    }
                    IconButton(onClick = { zoomScale = (zoomScale - 0.15f).coerceAtLeast(0.5f) }) {
                        Icon(Icons.Default.ZoomOut, "Zoom out", tint = AresTextSecondary)
                    }
                    IconButton(onClick = { zoomScale = 1f }) {
                        Icon(Icons.Default.Refresh, "Reset zoom", tint = AresTextSecondary)
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .aspectRatio((fieldWidth / fieldHeight).toFloat())
                    .fillMaxSize()
                    .background(AresSurface)
                    .pointerInput(league, fieldWidth, fieldHeight, zoomScale) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startScreen = down.position
                            val widthPx = size.width.toFloat()
                            val heightPx = size.height.toFloat()
                            val startFieldRaw = getRobotCoordFromScreen(startScreen, widthPx, heightPx, fieldWidth, fieldHeight, league, zoomScale, panOffset)
                            val startField = Waypoint(snap(startFieldRaw.x), snap(startFieldRaw.y))
                            val initialLayout = currentLayout
                            val initialSelection = currentSelection
                            var selectionForGesture = initialSelection
                            var handle: TransformHandle? = null
                            var targetObstacleId: String? = null
                            var dragged = false

                            if (currentTool == FieldLayoutTool.SELECT) {
                                val selectedObstacle = initialLayout.obstacles.singleOrNull { it.id in initialSelection }
                                val transformHit = selectedObstacle?.let {
                                    hitTransformHandle(startScreen, it, widthPx, heightPx, fieldWidth, fieldHeight, league, zoomScale, panOffset)
                                }
                                if (transformHit != null && !selectedObstacle.locked) {
                                    handle = transformHit
                                    targetObstacleId = selectedObstacle.id
                                } else {
                                    val hitId = hitTest(startFieldRaw, initialLayout)
                                    if (hitId != null) {
                                        selectionForGesture = if (isShiftPressed) initialSelection + hitId else if (hitId in initialSelection) initialSelection else setOf(hitId)
                                        onSelectionChanged(setOf(hitId), isShiftPressed)
                                        handle = TransformHandle.MOVE
                                    } else {
                                        if (!isShiftPressed) onSelectionChanged(emptySet(), false)
                                        handle = TransformHandle.BOX
                                        boxStart = startScreen
                                        boxEnd = startScreen
                                    }
                                }
                            }

                            val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            if (slopChange != null) {
                                dragged = true
                                var accumulated = slopChange.position - down.position
                                drag(slopChange.id) { change ->
                                    accumulated += change.positionChange()
                                    change.consume()
                                    when (handle) {
                                        TransformHandle.BOX -> boxEnd = change.position
                                        TransformHandle.MOVE -> {
                                            val delta = getDragDeltaInFieldCoords(accumulated, widthPx, heightPx, fieldWidth, fieldHeight, league, zoomScale)
                                            onLayoutChanged(moveSelection(initialLayout, selectionForGesture, snap(delta.x), snap(delta.y)))
                                        }
                                        TransformHandle.RESIZE, TransformHandle.ROTATE -> {
                                            val obstacleId = targetObstacleId ?: return@drag
                                            val pointer = getRobotCoordFromScreen(change.position, widthPx, heightPx, fieldWidth, fieldHeight, league, zoomScale, panOffset)
                                            onLayoutChanged(initialLayout.copy(obstacles = initialLayout.obstacles.map { obstacle ->
                                                if (obstacle.id == obstacleId) transformObstacle(obstacle, handle, pointer, currentGridSpacing, currentSnapEnabled) else obstacle
                                            }))
                                        }
                                        null -> Unit
                                    }
                                }
                            }

                            if (handle == TransformHandle.BOX) {
                                val end = boxEnd ?: startScreen
                                if (dragged && hypot((end.x - startScreen.x).toDouble(), (end.y - startScreen.y).toDouble()) > 5.0) {
                                    onSelectionChanged(idsInsideBox(initialLayout, startScreen, end, widthPx, heightPx, fieldWidth, fieldHeight, league, zoomScale, panOffset), isShiftPressed)
                                }
                                boxStart = null
                                boxEnd = null
                            } else if (!dragged && currentTool != FieldLayoutTool.SELECT) {
                                when (currentTool) {
                                    FieldLayoutTool.POLYGON -> polygonDraft += PathPoint(startField.x, startField.y)
                                    FieldLayoutTool.CIRCLE -> {
                                        val obstacle = Obstacle.Circle(nextCanvasId("circle"), "Circle ${initialLayout.obstacles.size + 1}", startField.x, startField.y, 0.25)
                                        onLayoutChanged(initialLayout.copy(obstacles = initialLayout.obstacles + obstacle))
                                        onSelectionChanged(setOf(obstacle.id), false)
                                        tool = FieldLayoutTool.SELECT
                                    }
                                    FieldLayoutTool.RECTANGLE -> {
                                        val obstacle = Obstacle.Rectangle(nextCanvasId("rect"), "Rectangle ${initialLayout.obstacles.size + 1}", startField.x, startField.y, 0.5, 0.5)
                                        onLayoutChanged(initialLayout.copy(obstacles = initialLayout.obstacles + obstacle))
                                        onSelectionChanged(setOf(obstacle.id), false)
                                        tool = FieldLayoutTool.SELECT
                                    }
                                    FieldLayoutTool.GAME_PIECE -> {
                                        val type = gamePieceTypes.firstOrNull()
                                        val piece = GamePiece(
                                            id = nextCanvasId("piece"),
                                            name = type?.name ?: "Game piece",
                                            x = startField.x,
                                            y = startField.y,
                                            type = type?.name ?: "Custom",
                                            typeId = type?.id,
                                        )
                                        onLayoutChanged(initialLayout.copy(gamePieces = initialLayout.gamePieces + piece))
                                        onSelectionChanged(setOf(piece.id), false)
                                        tool = FieldLayoutTool.SELECT
                                    }
                                    FieldLayoutTool.APRIL_TAG -> {
                                        val used = initialLayout.aprilTags.mapTo(hashSetOf()) { it.tagId }
                                        val tagId = generateSequence(1) { it + 1 }.first { it !in used }
                                        val tag = AprilTagPlacement(nextCanvasId("apriltag"), tagId, startField.x, startField.y)
                                        onLayoutChanged(initialLayout.copy(aprilTags = initialLayout.aprilTags + tag))
                                        onSelectionChanged(setOf(tag.id), false)
                                        tool = FieldLayoutTool.SELECT
                                    }
                                    FieldLayoutTool.WAYPOINT -> {
                                        val waypoint = FieldWaypoint(nextCanvasId("waypoint"), "Waypoint ${initialLayout.fieldWaypoints.size + 1}", startField.x, startField.y, 0.0)
                                        onLayoutChanged(initialLayout.copy(fieldWaypoints = initialLayout.fieldWaypoints + waypoint))
                                        onSelectionChanged(setOf(waypoint.id), false)
                                        tool = FieldLayoutTool.SELECT
                                    }
                                    FieldLayoutTool.SELECT -> Unit
                                }
                            }
                        }
                    }
            ) {
                val widthPx = size.width
                val heightPx = size.height
                drawContext.canvas.save()
                drawContext.transform.translate(panOffset.x, panOffset.y)
                drawContext.transform.scale(zoomScale, zoomScale, pivot = Offset.Zero)
                drawFieldBackground(fieldImage, fieldImageConfig, widthPx, heightPx)
                drawFieldGrid(widthPx, heightPx, fieldWidth, fieldHeight, league, showCostmap = false)
                drawSnapGrid(widthPx, heightPx, fieldWidth, fieldHeight, league, gridSpacingMeters)
                drawFtcAllianceStations(widthPx, heightPx, fieldWidth, fieldHeight, league, fieldImageConfig)
                if (league == League.FTC) drawCoordinateAxes(widthPx, heightPx, fieldWidth, fieldHeight, league, textMeasurer)
                drawCustomObstacles(layout.obstacles, widthPx, heightPx, fieldWidth, fieldHeight, league, showCostmap = false)
                drawGamePieces(layout.gamePieces, widthPx, heightPx, fieldWidth, fieldHeight, league)
                drawAprilTags(layout.aprilTags, widthPx, heightPx, fieldWidth, fieldHeight, league, textMeasurer)
                drawFieldWaypoints(layout.fieldWaypoints, selectedIds.singleOrNull(), widthPx, heightPx, fieldWidth, fieldHeight, league, textMeasurer)
                drawPolygonDraft(polygonDraft, widthPx, heightPx, fieldWidth, fieldHeight, league)
                drawSelectionOverlay(layout, selectedIds, validationIssues, widthPx, heightPx, fieldWidth, fieldHeight, league)
                drawContext.canvas.restore()

                val start = boxStart
                val end = boxEnd
                if (start != null && end != null) {
                    val left = min(start.x, end.x)
                    val top = min(start.y, end.y)
                    drawRect(AresCyan.copy(alpha = 0.12f), Offset(left, top), Size(abs(end.x - start.x), abs(end.y - start.y)))
                    drawRect(AresCyan, Offset(left, top), Size(abs(end.x - start.x), abs(end.y - start.y)), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun LayoutToolButton(
    value: FieldLayoutTool,
    selected: FieldLayoutTool,
    onSelected: (FieldLayoutTool) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String
) {
    IconButton(onClick = { onSelected(value) }, modifier = Modifier.size(38.dp)) {
        Icon(icon, description, tint = if (value == selected) AresCyan else AresTextSecondary)
    }
}

private fun hitTest(point: Waypoint, layout: FieldEditorLayout): String? {
    layout.fieldWaypoints.lastOrNull { hypot(point.x - it.x, point.y - it.y) <= 0.22 }?.let { return it.id }
    layout.aprilTags.lastOrNull { hypot(point.x - it.x, point.y - it.y) <= 0.22 }?.let { return it.id }
    layout.gamePieces.lastOrNull { hypot(point.x - it.x, point.y - it.y) <= 0.18 }?.let { return it.id }
    return layout.obstacles.lastOrNull { it.contains(point.x, point.y) }?.id
}

private fun Obstacle.contains(x: Double, y: Double): Boolean = when (this) {
    is Obstacle.Circle -> hypot(x - centerX, y - centerY) <= radius
    is Obstacle.Rectangle -> {
        val radians = Math.toRadians(-rotation)
        val dx = x - centerX
        val dy = y - centerY
        abs(dx * cos(radians) - dy * sin(radians)) <= width / 2.0 &&
            abs(dx * sin(radians) + dy * cos(radians)) <= height / 2.0
    }
    is Obstacle.Polygon -> pointInPolygon(x, y, vertices)
}

private fun pointInPolygon(x: Double, y: Double, vertices: List<PathPoint>): Boolean {
    if (vertices.size < 3) return false
    var inside = false
    var previous = vertices.last()
    for (current in vertices) {
        if ((current.y > y) != (previous.y > y)) {
            val intersectionX = (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x
            if (x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}

private fun moveSelection(layout: FieldEditorLayout, ids: Set<String>, dx: Double, dy: Double): FieldEditorLayout = layout.copy(
    obstacles = layout.obstacles.map { obstacle ->
        if (obstacle.id !in ids || obstacle.locked) obstacle else when (obstacle) {
            is Obstacle.Circle -> obstacle.copy(centerX = obstacle.centerX + dx, centerY = obstacle.centerY + dy)
            is Obstacle.Rectangle -> obstacle.copy(centerX = obstacle.centerX + dx, centerY = obstacle.centerY + dy)
            is Obstacle.Polygon -> obstacle.copy(vertices = obstacle.vertices.map { PathPoint(it.x + dx, it.y + dy) })
        }
    },
    gamePieces = layout.gamePieces.map { if (it.id in ids && !it.locked) it.copy(x = it.x + dx, y = it.y + dy) else it },
    aprilTags = layout.aprilTags.map { if (it.id in ids && !it.locked) it.copy(x = it.x + dx, y = it.y + dy) else it },
    fieldWaypoints = layout.fieldWaypoints.map { if (it.id in ids && !it.locked) it.copy(x = it.x + dx, y = it.y + dy) else it }
)

private fun transformObstacle(
    obstacle: Obstacle,
    handle: TransformHandle,
    pointer: Waypoint,
    spacing: Double,
    snapEnabled: Boolean
): Obstacle {
    fun snap(value: Double): Double = if (snapEnabled) round(value / spacing.coerceAtLeast(0.001)) * spacing else value
    val center = obstacle.center()
    return when (handle) {
        TransformHandle.ROTATE -> {
            val angle = Math.toDegrees(atan2(pointer.y - center.y, pointer.x - center.x))
            val snappedAngle = if (snapEnabled) round(angle / 15.0) * 15.0 else angle
            when (obstacle) {
                is Obstacle.Rectangle -> obstacle.copy(rotation = snappedAngle)
                is Obstacle.Polygon -> {
                    val originalHandle = obstacle.resizeHandle()
                    val originalAngle = atan2(originalHandle.y - center.y, originalHandle.x - center.x)
                    val delta = Math.toRadians(snappedAngle) - originalAngle
                    obstacle.copy(vertices = obstacle.vertices.map { point -> rotatePoint(point, center, delta) })
                }
                is Obstacle.Circle -> obstacle
            }
        }
        TransformHandle.RESIZE -> when (obstacle) {
            is Obstacle.Circle -> obstacle.copy(radius = snap(hypot(pointer.x - obstacle.centerX, pointer.y - obstacle.centerY)).coerceAtLeast(0.01))
            is Obstacle.Rectangle -> {
                val radians = Math.toRadians(-obstacle.rotation)
                val dx = pointer.x - obstacle.centerX
                val dy = pointer.y - obstacle.centerY
                val localX = dx * cos(radians) - dy * sin(radians)
                val localY = dx * sin(radians) + dy * cos(radians)
                obstacle.copy(width = snap(abs(localX) * 2.0).coerceAtLeast(0.01), height = snap(abs(localY) * 2.0).coerceAtLeast(0.01))
            }
            is Obstacle.Polygon -> {
                val originalRadius = hypot(obstacle.resizeHandle().x - center.x, obstacle.resizeHandle().y - center.y).coerceAtLeast(0.001)
                val scale = hypot(pointer.x - center.x, pointer.y - center.y) / originalRadius
                obstacle.copy(vertices = obstacle.vertices.map { PathPoint(center.x + (it.x - center.x) * scale, center.y + (it.y - center.y) * scale) })
            }
        }
        else -> obstacle
    }
}

private fun hitTransformHandle(
    screen: Offset,
    obstacle: Obstacle,
    widthPx: Float,
    heightPx: Float,
    fieldWidth: Double,
    fieldHeight: Double,
    league: League,
    zoom: Float,
    pan: Offset
): TransformHandle? {
    val resize = getTransformedCanvasOffset(obstacle.resizeHandle(), widthPx, heightPx, fieldWidth, fieldHeight, league, zoom, pan)
    val rotate = getTransformedCanvasOffset(obstacle.rotateHandle(), widthPx, heightPx, fieldWidth, fieldHeight, league, zoom, pan)
    return when {
        hypot((screen.x - rotate.x).toDouble(), (screen.y - rotate.y).toDouble()) <= 14.0 -> TransformHandle.ROTATE
        hypot((screen.x - resize.x).toDouble(), (screen.y - resize.y).toDouble()) <= 14.0 -> TransformHandle.RESIZE
        else -> null
    }
}

private fun idsInsideBox(
    layout: FieldEditorLayout,
    start: Offset,
    end: Offset,
    widthPx: Float,
    heightPx: Float,
    fieldWidth: Double,
    fieldHeight: Double,
    league: League,
    zoom: Float,
    pan: Offset
): Set<String> {
    val left = min(start.x, end.x)
    val right = max(start.x, end.x)
    val top = min(start.y, end.y)
    val bottom = max(start.y, end.y)
    fun contains(point: Waypoint): Boolean {
        val screen = getTransformedCanvasOffset(point, widthPx, heightPx, fieldWidth, fieldHeight, league, zoom, pan)
        return screen.x in left..right && screen.y in top..bottom
    }
    return buildSet {
        layout.obstacles.filter { contains(it.center()) }.forEach { add(it.id) }
        layout.gamePieces.filter { contains(Waypoint(it.x, it.y)) }.forEach { add(it.id) }
        layout.aprilTags.filter { contains(Waypoint(it.x, it.y)) }.forEach { add(it.id) }
        layout.fieldWaypoints.filter { contains(Waypoint(it.x, it.y)) }.forEach { add(it.id) }
    }
}

private fun Obstacle.center(): Waypoint = when (this) {
    is Obstacle.Circle -> Waypoint(centerX, centerY)
    is Obstacle.Rectangle -> Waypoint(centerX, centerY)
    is Obstacle.Polygon -> if (vertices.isEmpty()) Waypoint(0.0, 0.0) else Waypoint(vertices.map { it.x }.average(), vertices.map { it.y }.average())
}

private fun Obstacle.resizeHandle(): Waypoint = when (this) {
    is Obstacle.Circle -> Waypoint(centerX + radius, centerY)
    is Obstacle.Rectangle -> rotateLocalPoint(width / 2.0, height / 2.0, centerX, centerY, rotation)
    is Obstacle.Polygon -> vertices.maxByOrNull { hypot(it.x - center().x, it.y - center().y) }?.let { Waypoint(it.x, it.y) } ?: center()
}

private fun Obstacle.rotateHandle(): Waypoint {
    val center = center()
    val distance = when (this) {
        is Obstacle.Circle -> radius + 0.25
        is Obstacle.Rectangle -> height / 2.0 + 0.25
        is Obstacle.Polygon -> vertices.maxOfOrNull { hypot(it.x - center.x, it.y - center.y) }?.plus(0.25) ?: 0.25
    }
    val baseAngle = when (this) {
        is Obstacle.Rectangle -> rotation + 90.0
        else -> 90.0
    }
    return Waypoint(center.x + cos(Math.toRadians(baseAngle)) * distance, center.y + sin(Math.toRadians(baseAngle)) * distance)
}

private fun rotateLocalPoint(localX: Double, localY: Double, centerX: Double, centerY: Double, degrees: Double): Waypoint {
    val radians = Math.toRadians(degrees)
    return Waypoint(centerX + localX * cos(radians) - localY * sin(radians), centerY + localX * sin(radians) + localY * cos(radians))
}

private fun rotatePoint(point: PathPoint, center: Waypoint, radians: Double): PathPoint {
    val dx = point.x - center.x
    val dy = point.y - center.y
    return PathPoint(center.x + dx * cos(radians) - dy * sin(radians), center.y + dx * sin(radians) + dy * cos(radians))
}

private fun DrawScope.drawSnapGrid(widthPx: Float, heightPx: Float, fieldWidth: Double, fieldHeight: Double, league: League, requestedSpacing: Double) {
    var spacing = requestedSpacing.coerceAtLeast(0.01)
    while (fieldWidth / spacing > 100.0 || fieldHeight / spacing > 100.0) spacing *= 2.0
    val minX = if (league == League.FTC) -fieldWidth / 2.0 else 0.0
    val maxX = if (league == League.FTC) fieldWidth / 2.0 else fieldWidth
    val minY = if (league == League.FTC) -fieldHeight / 2.0 else 0.0
    val maxY = if (league == League.FTC) fieldHeight / 2.0 else fieldHeight
    var x = kotlin.math.ceil(minX / spacing) * spacing
    while (x <= maxX + 1e-9) {
        val start = getCanvasOffsetBase(Waypoint(x, minY), widthPx, heightPx, fieldWidth, fieldHeight, league)
        val end = getCanvasOffsetBase(Waypoint(x, maxY), widthPx, heightPx, fieldWidth, fieldHeight, league)
        drawLine(Color.White.copy(alpha = 0.06f), start, end, 1f)
        x += spacing
    }
    var y = kotlin.math.ceil(minY / spacing) * spacing
    while (y <= maxY + 1e-9) {
        val start = getCanvasOffsetBase(Waypoint(minX, y), widthPx, heightPx, fieldWidth, fieldHeight, league)
        val end = getCanvasOffsetBase(Waypoint(maxX, y), widthPx, heightPx, fieldWidth, fieldHeight, league)
        drawLine(Color.White.copy(alpha = 0.06f), start, end, 1f)
        y += spacing
    }
}

private fun DrawScope.drawPolygonDraft(points: List<PathPoint>, widthPx: Float, heightPx: Float, fieldWidth: Double, fieldHeight: Double, league: League) {
    if (points.isEmpty()) return
    val offsets = points.map { getCanvasOffsetBase(Waypoint(it.x, it.y), widthPx, heightPx, fieldWidth, fieldHeight, league) }
    offsets.zipWithNext().forEach { (start, end) -> drawLine(AresCyan, start, end, 2.dp.toPx()) }
    offsets.forEach { drawCircle(AresCyan, 4.dp.toPx(), it) }
}

private fun DrawScope.drawSelectionOverlay(
    layout: FieldEditorLayout,
    selectedIds: Set<String>,
    issues: List<FieldValidationIssue>,
    widthPx: Float,
    heightPx: Float,
    fieldWidth: Double,
    fieldHeight: Double,
    league: League
) {
    val anchors = linkedMapOf<String, Waypoint>()
    layout.obstacles.forEach { anchors[it.id] = it.center() }
    layout.gamePieces.forEach { anchors[it.id] = Waypoint(it.x, it.y) }
    layout.aprilTags.forEach { anchors[it.id] = Waypoint(it.x, it.y) }
    layout.fieldWaypoints.forEach { anchors[it.id] = Waypoint(it.x, it.y) }

    val singleAnchor = selectedIds.singleOrNull()?.let(anchors::get)
    if (singleAnchor != null) {
        val offset = getCanvasOffsetBase(singleAnchor, widthPx, heightPx, fieldWidth, fieldHeight, league)
        drawLine(AresCyan.copy(alpha = 0.35f), Offset(0f, offset.y), Offset(widthPx, offset.y), 1.dp.toPx())
        drawLine(AresCyan.copy(alpha = 0.35f), Offset(offset.x, 0f), Offset(offset.x, heightPx), 1.dp.toPx())
    }

    selectedIds.forEach { id ->
        anchors[id]?.let { point ->
            drawCircle(AresCyan, 12.dp.toPx(), getCanvasOffsetBase(point, widthPx, heightPx, fieldWidth, fieldHeight, league), style = Stroke(2.dp.toPx()))
        }
    }

    if (selectedIds.size == 1) {
        layout.obstacles.firstOrNull { it.id in selectedIds && !it.locked }?.let { obstacle ->
            val center = getCanvasOffsetBase(obstacle.center(), widthPx, heightPx, fieldWidth, fieldHeight, league)
            val resize = getCanvasOffsetBase(obstacle.resizeHandle(), widthPx, heightPx, fieldWidth, fieldHeight, league)
            val rotate = getCanvasOffsetBase(obstacle.rotateHandle(), widthPx, heightPx, fieldWidth, fieldHeight, league)
            drawLine(AresCyan, center, rotate, 1.5.dp.toPx())
            drawRect(AresCyan, resize - Offset(5.dp.toPx(), 5.dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()))
            drawCircle(AresAmber, 6.dp.toPx(), rotate)
        }
    }

    issues.forEach { issue ->
        val color = if (issue.severity == FieldValidationSeverity.ERROR) AresError else AresAmber
        issue.elementIds.forEach { id ->
            anchors[id]?.let { point ->
                drawCircle(color.copy(alpha = 0.8f), 16.dp.toPx(), getCanvasOffsetBase(point, widthPx, heightPx, fieldWidth, fieldHeight, league), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

private val CANVAS_ID_SEQUENCE = AtomicLong(System.currentTimeMillis())
private fun nextCanvasId(prefix: String): String = "$prefix-${CANVAS_ID_SEQUENCE.incrementAndGet()}"
