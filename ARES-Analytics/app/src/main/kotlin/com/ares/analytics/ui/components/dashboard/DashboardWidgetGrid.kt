package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.WidgetConfig
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlin.math.roundToInt

internal fun isDashboardLayoutEditingSupported(columns: Int): Boolean =
    columns == DashboardLayoutEngine.EXPANDED_COLUMNS

@Composable
fun DashboardWidgetGrid(
    widgets: List<WidgetConfig>,
    isEditing: Boolean,
    onLayoutChanged: (List<WidgetConfig>) -> Unit,
    onRemoveWidget: (String) -> Unit,
    widgetBuilders: Map<String, @Composable (WidgetConfig, Modifier) -> Unit>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = DashboardLayoutEngine.columnsForWidth(maxWidth.value)
        val effectiveEditing = isEditing && isDashboardLayoutEditingSupported(columns)
        val displayWidgets = remember(widgets, columns) { DashboardLayoutEngine.reflow(widgets, columns) }
        val spacing = 12.dp
        val colWidth = (maxWidth - spacing * (columns - 1)) / columns
        val rowHeight = if (maxWidth < 900.dp) 72.dp else 80.dp
        val maxRow = displayWidgets.maxOfOrNull { it.row + it.rowSpan } ?: 1
        val gridHeight = rowHeight * maxRow + spacing * (maxRow - 1).coerceAtLeast(0) + 24.dp
        val density = LocalDensity.current.density
        val currentWidgets by rememberUpdatedState(displayWidgets)

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(gridHeight)) {
                for (widget in displayWidgets) {
                    val builder = widgetBuilders[widget.type]
                    key(widget.id) {
                        var offsetX by remember { mutableStateOf(0f) }
                        var offsetY by remember { mutableStateOf(0f) }
                        var resizeX by remember { mutableStateOf(0f) }
                        var resizeY by remember { mutableStateOf(0f) }
                        var active by remember { mutableStateOf(false) }
                        val width = colWidth * widget.colSpan + spacing * (widget.colSpan - 1)
                        val height = rowHeight * widget.rowSpan + spacing * (widget.rowSpan - 1)
                        val x = colWidth * widget.col + spacing * widget.col
                        val y = rowHeight * widget.row + spacing * widget.row

                        Box(
                            Modifier
                                .offset { IntOffset(x.roundToPx() + offsetX.roundToInt(), y.roundToPx() + offsetY.roundToInt()) }
                                .layout { measurable, _ ->
                                    val targetWidth = (width.toPx() + resizeX).roundToInt().coerceAtLeast(1)
                                    val targetHeight = (height.toPx() + resizeY).roundToInt().coerceAtLeast(1)
                                    val placeable = measurable.measure(Constraints.fixed(targetWidth, targetHeight))
                                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (effectiveEditing) AresSurface else androidx.compose.ui.graphics.Color.Transparent)
                                .then(
                                    if (effectiveEditing) Modifier.border(
                                        if (active) 2.dp else 1.dp,
                                        if (active) AresCyan else AresBorder,
                                        RoundedCornerShape(12.dp)
                                    ) else Modifier
                                )
                        ) {
                            if (effectiveEditing) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .pointerInput(widget, columns) {
                                            if (!widget.isLocked) detectDragGestures(
                                                onDragStart = { active = true },
                                                onDragCancel = { active = false; offsetX = 0f; offsetY = 0f },
                                                onDragEnd = {
                                                    active = false
                                                    val colDelta = ((offsetX / density) / colWidth.value).roundToInt()
                                                    val rowDelta = ((offsetY / density) / rowHeight.value).roundToInt()
                                                    offsetX = 0f; offsetY = 0f
                                                    val moved = currentWidgets.map {
                                                        if (it.id == widget.id) it.copy(
                                                            col = (widget.col + colDelta).coerceIn(0, columns - widget.colSpan),
                                                            row = (widget.row + rowDelta).coerceAtLeast(0)
                                                        ) else it
                                                    }
                                                    onLayoutChanged(DashboardLayoutEngine.resolveMove(moved, widget.id, columns))
                                                }
                                            ) { change, amount -> change.consume(); offsetX += amount.x; offsetY += amount.y }
                                        }
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.DragIndicator, "Drag widget", tint = AresTextTertiary)
                                    Box(Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            onLayoutChanged(currentWidgets.map { if (it.id == widget.id) it.copy(isLocked = !it.isLocked) else it })
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            if (widget.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                            if (widget.isLocked) "Unlock widget" else "Lock widget",
                                            tint = if (widget.isLocked) AresCyan else AresTextTertiary
                                        )
                                    }
                                    IconButton(onClick = { onRemoveWidget(widget.id) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Close, "Remove widget", tint = AresError)
                                    }
                                }
                            }

                            Box(Modifier.fillMaxSize().padding(top = if (effectiveEditing) 40.dp else 0.dp)) {
                                if (builder != null) {
                                    builder(widget, Modifier.fillMaxSize())
                                } else {
                                    UnknownDashboardWidget(widget, Modifier.fillMaxSize())
                                }
                            }

                            if (effectiveEditing && !widget.isLocked) {
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .align(Alignment.BottomEnd)
                                        .pointerInput(widget, columns) {
                                            detectDragGestures(
                                                onDragStart = { active = true },
                                                onDragCancel = { active = false; resizeX = 0f; resizeY = 0f },
                                                onDragEnd = {
                                                    active = false
                                                    val spanX = ((resizeX / density) / colWidth.value).roundToInt()
                                                    val spanY = ((resizeY / density) / rowHeight.value).roundToInt()
                                                    resizeX = 0f; resizeY = 0f
                                                    val resized = currentWidgets.map {
                                                        if (it.id == widget.id) it.copy(
                                                            colSpan = (widget.colSpan + spanX).coerceIn(1, columns - widget.col),
                                                            rowSpan = (widget.rowSpan + spanY).coerceIn(2, 12)
                                                        ) else it
                                                    }
                                                    onLayoutChanged(DashboardLayoutEngine.resolveMove(resized, widget.id, columns))
                                                }
                                            ) { change, amount -> change.consume(); resizeX += amount.x; resizeY += amount.y }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.AspectRatio, "Resize widget", tint = if (active) AresCyan else AresTextTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isEditing && !effectiveEditing) {
            Text(
                text = "Expand the dashboard to edit its 12-column layout",
                color = AresTextTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .background(AresSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun UnknownDashboardWidget(widget: WidgetConfig, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(AresSurfaceElevated)
            .border(1.dp, AresError, RoundedCornerShape(12.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Unavailable widget: ${widget.type}\nEdit this layout to remove it.",
            color = AresTextPrimary,
            fontSize = 12.sp,
        )
    }
}
