package com.ares.analytics.ui.components.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.dashboard.BuiltInDashboardLayoutProfiles
import com.ares.analytics.service.dashboard.DashboardLayoutProfile
import com.ares.analytics.service.layoutProfileNameError
import com.ares.analytics.shared.RobotUnit
import com.ares.analytics.shared.UnitCategory
import com.ares.analytics.shared.UnitConversion
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.theme.AresThemeSettings
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryChartPanel(
    nt4ClientService: Nt4ClientService,
    databaseService: DatabaseService? = null,
    currentFrame: ReplayFrame? = null,
    properties: Map<String, String>,
    onPropertiesChanged: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    var parentWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasWindowBounds by remember { mutableStateOf<Rect?>(null) }
    var isTreeVisible by remember { mutableStateOf(true) }
    val treeWidth by animateDpAsState(
        targetValue = if (isTreeVisible) 260.dp else 0.dp,
        animationSpec = tween(durationMillis = 300)
    )

    // Configurable time windows (seconds)
    val timeWindows = listOf(10, 30, 60, 120)
    val initialKeys = remember(properties) {
        properties["selectedKeys"]?.split(",")?.map { it.removePrefix("/") }?.filter { it.isNotEmpty() } ?: emptyList()
    }
    val initialWindow = remember(properties) {
        properties["windowSec"]?.toIntOrNull() ?: 30
    }
    var selectedWindowSec by remember(initialWindow) { mutableStateOf(initialWindow) }
    val selectedKeys = remember(initialKeys) { mutableStateListOf<String>().apply { addAll(initialKeys) } }

    // In-memory data store for live plotting: key -> ArrayDeque of points (circular buffer)
    val telemetryData = remember { ConcurrentHashMap<String, ArrayDeque<TelemetryPoint>>() }
    var lastUpdateTick by remember { mutableStateOf(0L) }
    var serverTimeOffset by remember { mutableStateOf(0L) }
    var hasReceivedData by remember { mutableStateOf(false) }
    var liveTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(selectedKeys.toList(), selectedWindowSec, currentFrame?.sessionId) {
        val keysList = selectedKeys.toList()
        if (keysList != initialKeys || selectedWindowSec != initialWindow) {
            onPropertiesChanged(mapOf(
                "selectedKeys" to keysList.joinToString(","),
                "windowSec" to selectedWindowSec.toString()
            ))
        }

        // Initialize newly added keys with their historical values
        if (currentFrame == null) keysList.forEach { key ->
            val queue = telemetryData.getOrPut(key) { ArrayDeque() }
            synchronized(queue) {
                if (queue.isEmpty()) {
                    val history = nt4ClientService.telemetryStore.history(key)
                    if (history.isNotEmpty()) {
                        history.forEach { frame ->
                            queue.add(TelemetryPoint(frame.timestampMs, frame.value))
                        }
                    } else {
                        val latest = nt4ClientService.telemetryStore.latest(key)
                        if (latest != null) {
                            queue.add(TelemetryPoint(latest.timestampMs, latest.value))
                        }
                    }
                }
            }
        }
    }

    // Live clock ticker to keep the chart scrolling smoothly even when stationary
    LaunchedEffect(currentFrame?.sessionId) {
        while (true) {
            liveTime = currentFrame?.playheadMs ?: (System.currentTimeMillis() + serverTimeOffset)
            kotlinx.coroutines.delay(100)
        }
    }

    // Selected target unit for each key
    val targetUnits = remember { mutableStateMapOf<String, RobotUnit>() }

    val activeTopics = remember { mutableStateListOf<String>() }

    // Periodically update active topics from NT4 Service
    LaunchedEffect(currentFrame?.sessionId, currentFrame?.sequence) {
        if (currentFrame != null) {
            activeTopics.clear()
            activeTopics.addAll(currentFrame.values.keys.sorted())
            return@LaunchedEffect
        }
        while (true) {
            val topics = nt4ClientService.getActiveTopics()
            activeTopics.clear()
            activeTopics.addAll(topics)
            kotlinx.coroutines.delay(1000)
        }
    }

    // Subscribe to telemetry Flow
    LaunchedEffect(selectedKeys.toList(), currentFrame?.sessionId) {
        if (currentFrame != null) return@LaunchedEffect
        val observedKeys = selectedKeys.toSet()
        if (observedKeys.isNotEmpty()) {
            nt4ClientService.telemetryStore.observe(observedKeys).collect { frame ->
                val queue = telemetryData.getOrPut(frame.key) { ArrayDeque() }
                val now = frame.timestampMs
                val offset = now - System.currentTimeMillis()
                if (!hasReceivedData || kotlin.math.abs(serverTimeOffset - offset) > 100) {
                    serverTimeOffset = offset
                    hasReceivedData = true
                }
                val maxWindowSec = timeWindows.maxOrNull() ?: 120
                val cutoff = now - (maxWindowSec * 1000)

                synchronized(queue) {
                    queue.add(TelemetryPoint(frame.timestampMs, frame.value))
                    while (queue.size > 1 && queue[1].timestampMs < cutoff) {
                        queue.removeFirst()
                    }
                }
                lastUpdateTick = frame.timestampMs
            }
        }
    }

    // Historical charts query only the visible bounded viewport. Bucketing the logical playhead
    // limits DuckDB work while playback is running; a paused seek still refreshes immediately.
    val replayQueryBucket = currentFrame?.let { it.playheadMs / REPLAY_CHART_REFRESH_MS }
    LaunchedEffect(
        currentFrame?.sessionId,
        replayQueryBucket,
        currentFrame?.sequence.takeIf { currentFrame != null && currentFrame.playheadMs == currentFrame.timestampMs },
        selectedKeys.toList(),
        selectedWindowSec,
    ) {
        val replay = currentFrame ?: return@LaunchedEffect
        val database = databaseService ?: return@LaunchedEffect
        val keys = selectedKeys.toList()
        liveTime = replay.playheadMs
        if (keys.isEmpty()) return@LaunchedEffect
        val startMs = (replay.playheadMs - selectedWindowSec * 1_000L).coerceAtLeast(0L)
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            keys.associateWith { key ->
                database.getTelemetrySeries(
                    sessionId = replay.sessionId,
                    key = key,
                    startMs = startMs,
                    endMs = replay.playheadMs,
                    maxPoints = MAX_REPLAY_CHART_POINTS_PER_TOPIC,
                )
            }
        }
        keys.forEach { key ->
            val queue = telemetryData.getOrPut(key) { ArrayDeque() }
            synchronized(queue) {
                queue.clear()
                loaded.getValue(key).forEach { queue.add(TelemetryPoint(it.timestampMs, it.value)) }
            }
        }
        lastUpdateTick = replay.sequence
    }

    // Legend colors for up to 8 channels
    val channelColors = listOf(
        AresCyan, AresRed, AresGreen, AresAmber,
        Color(0xFFFF00FF), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFFFFFFFF)
    )

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                parentWindowOffset = coords.positionInWindow()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(AresSurface)
                .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        TelemetryChartHeaderAndToolbar(
            isReplay = currentFrame != null,
            timeWindows = timeWindows,
            selectedWindowSec = selectedWindowSec,
            onSelectWindowSec = { selectedWindowSec = it },
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            activeTopics = activeTopics.toList(),
            selectedKeys = selectedKeys.toList(),
            onAddKey = { topic ->
                val cleanKey = topic.removePrefix("/")
                if (selectedKeys.size < 8 && !selectedKeys.contains(cleanKey)) {
                    selectedKeys.add(cleanKey)
                }
            },
            onRemoveKey = { selectedKeys.remove(it) },
            isTreeVisible = isTreeVisible,
            onToggleTreeVisible = { isTreeVisible = !isTreeVisible },
            channelColors = channelColors,
            targetUnits = targetUnits.toMap(),
            onSelectCategoryUnit = { category, unit ->
                selectedKeys
                    .filter { telemetryChartCategory(it) == category }
                    .forEach { sameCategoryKey -> targetUnits[sameCategoryKey] = unit }
            },
        )
        val signalTree = remember(activeTopics.toList()) { buildSignalTree(activeTopics.toList()) }

        val selectedKeySnapshot = selectedKeys.toList()
        val targetUnitSnapshot = targetUnits.toMap()
        val pointsSnapshot = remember(selectedKeySnapshot, lastUpdateTick, liveTime) {
            selectedKeySnapshot.associateWith { key ->
                telemetryData[key]?.let { queue -> synchronized(queue) { queue.toList() } }.orEmpty()
            }
        }
        val chartSnapshot = remember(
            selectedKeySnapshot,
            targetUnitSnapshot,
            pointsSnapshot,
            liveTime,
            selectedWindowSec,
        ) {
            buildTelemetryChartSnapshot(
                selectedKeys = selectedKeySnapshot,
                pointsByKey = pointsSnapshot,
                targetUnits = targetUnitSnapshot,
                nowMs = liveTime,
                windowMs = selectedWindowSec * 1_000L,
            )
        }
        val chartPaths = remember { List(8) { Path() } }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AresSurfaceElevated)
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
        ) {
            // Signal Tree Explorer (Slide-out panel)
            Box(
                modifier = Modifier
                    .width(treeWidth)
                    .fillMaxHeight()
                    .background(AresSurface)
                    .clipToBounds()
            ) {
                if (treeWidth > 10.dp) {
                    SignalTreeExplorer(
                        rootNode = signalTree,
                        selectedKeys = selectedKeys,
                        onKeySelected = { key ->
                            val cleanKey = key.removePrefix("/")
                            if (selectedKeys.size < 8 && !selectedKeys.contains(cleanKey)) {
                                selectedKeys.add(cleanKey)
                            }
                        },
                        onDragStart = { key, offset ->
                            draggedKey = key.removePrefix("/")
                            dragOffset = offset
                        },
                        onDrag = { offset ->
                            dragOffset += offset
                        },
                        onDragEnd = {
                            val finalOffset = dragOffset
                            val bounds = canvasWindowBounds
                            if (bounds != null && bounds.contains(finalOffset)) {
                                draggedKey?.let { key ->
                                    val cleanKey = key.removePrefix("/")
                                    if (selectedKeys.size < 8 && !selectedKeys.contains(cleanKey)) {
                                        selectedKeys.add(cleanKey)
                                    }
                                }
                            }
                            draggedKey = null
                        }
                    )
                }
            }

            VerticalDivider(color = AresBorder, modifier = Modifier.fillMaxHeight())

            // Plot Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { coordinates ->
                        canvasWindowBounds = coordinates.boundsInWindow()
                    }
            ) {
                if (selectedKeys.isEmpty()) {
                    TelemetryChartEmptyState(
                        onSelectTopics = { bundleTopics ->
                            bundleTopics.forEach { topic ->
                                val cleanKey = topic.removePrefix("/")
                                if (selectedKeys.size < 8 && !selectedKeys.contains(cleanKey)) {
                                    selectedKeys.add(cleanKey)
                                }
                            }
                        },
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 12.dp).clipToBounds()) {
                        val width = size.width
                        val height = size.height

                    // 1. Draw Grid Lines
                    val gridLinesX = 5
                    val gridLinesY = 4
                    for (i in 0..gridLinesX) {
                        val x = width * i / gridLinesX
                        drawLine(color = AresBorder, start = Offset(x, 0f), end = Offset(x, height), strokeWidth = 1f)
                    }
                    val bandHeight = height / chartSnapshot.groups.size.coerceAtLeast(1)
                    chartSnapshot.groups.forEachIndexed { groupIndex, group ->
                        val bandTop = bandHeight * groupIndex
                        for (i in 0..gridLinesY) {
                            val y = bandTop + bandHeight * i / gridLinesY
                            drawLine(color = AresBorder, start = Offset(0f, y), end = Offset(width, y), strokeWidth = 1f)
                        }

                        group.series.forEach { series ->
                            val points = series.points
                            if (points.isNotEmpty()) {
                                val channelIdx = selectedKeySnapshot.indexOf(series.key).coerceAtLeast(0)
                                val color = channelColors[channelIdx % channelColors.size]
                                val now = liveTime
                                val minX = now - (selectedWindowSec * 1000)
                                val maxX = now
                                val path = chartPaths[channelIdx].apply { reset() }

                                fun getPy(value: Double): Float {
                                    val converted = convertTelemetryChartValue(value, series.sourceUnit, series.displayUnit)
                                    val yPct = ((converted - group.bounds.min) / (group.bounds.max - group.bounds.min)).toFloat()
                                    return bandTop + bandHeight - (yPct * bandHeight)
                                }
                                var isFirst = true

                                var visibleStartIndex = points.indexOfFirst { it.timestampMs >= minX }
                                if (visibleStartIndex == -1) visibleStartIndex = points.size
                                val startIndex = kotlin.math.max(0, visibleStartIndex - 1)

                                if (startIndex < visibleStartIndex && points[startIndex].timestampMs < minX) {
                                    path.moveTo(0f, getPy(points[startIndex].value))
                                    isFirst = false
                                }

                                for (i in startIndex until points.size) {
                                    val pt = points[i]
                                    val px = ((pt.timestampMs - minX).toFloat() / (maxX - minX)) * width
                                    val py = getPy(pt.value)
                                    if (isFirst) {
                                        path.moveTo(px, py)
                                        isFirst = false
                                    } else {
                                        path.lineTo(px, py)
                                    }
                                }

                                val lastPt = points.last()
                                if (lastPt.timestampMs < maxX) {
                                    val py = getPy(lastPt.value)
                                    if (isFirst) path.moveTo(width, py) else path.lineTo(width, py)
                                }

                                drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
                            }
                        }
                    }
                }

                // Overlay Y-axis labels
                Column(
                    modifier = Modifier.fillMaxHeight().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    chartSnapshot.groups.forEach { group ->
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Text(
                                text = String.format("%.2f %s", group.bounds.max, group.unitSymbol),
                                color = AresTextSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                            Text(
                                text = group.category?.name?.replace('_', ' ') ?: "RAW",
                                color = AresTextTertiary,
                                fontSize = 8.sp,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                            Text(
                                text = String.format("%.2f %s", group.bounds.min, group.unitSymbol),
                                color = AresTextSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomStart),
                            )
                        }
                    }
                }
            }
            }
        }
    }

    draggedKey?.let { key ->
            Box(
                modifier = Modifier
                    .offset { IntOffset((dragOffset.x - parentWindowOffset.x).toInt() - 20, (dragOffset.y - parentWindowOffset.y).toInt() - 20) }
                    .background(AresCyan.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                Text(
                    text = key.substringAfterLast('/'),
                    color = AresOnAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private const val REPLAY_CHART_REFRESH_MS = 200L
private const val MAX_REPLAY_CHART_POINTS_PER_TOPIC = 1_500
