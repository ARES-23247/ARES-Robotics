package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.RobotUnit
import com.ares.analytics.shared.UnitCategory
import com.ares.analytics.shared.UnitConversion
import com.ares.analytics.ui.theme.*
import androidx.compose.material.icons.filled.Menu
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import java.util.concurrent.ConcurrentHashMap

fun buildSignalTree(keys: List<String>): SignalNode {
    val root = SignalNode("", "", false)
    for (topic in keys) {
        val parts = topic.split("/").filter { it.isNotEmpty() }
        var current = root
        var currentPath = ""
        for (i in parts.indices) {
            val part = parts[i]
            currentPath += "/$part"
            val isLeaf = (i == parts.lastIndex)
            current = current.children.getOrPut(part) {
                SignalNode(part, currentPath, isLeaf)
            }
        }
    }
    return root
}

data class TelemetryPoint(val timestampMs: Long, val value: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryChartPanel(
    nt4ClientService: Nt4ClientService,
    databaseService: DatabaseService? = null,
    currentFrame: ReplayFrame? = null,
    properties: Map<String, String>,
    onPropertiesChanged: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
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

    // Searchable dropdown state
    var dropdownExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
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
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (currentFrame == null) "Live Telemetry Viewer" else "Replay Telemetry Viewer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
                Text(
                    if (currentFrame == null) {
                        "Real-time streaming multi-channel scope"
                    } else {
                        "Bounded history ending at the selected replay instant"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AresTextTertiary
                )
            }

            // Window size selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                timeWindows.forEach { sec ->
                    FilterChip(
                        selected = selectedWindowSec == sec,
                        onClick = { selectedWindowSec = sec },
                        label = { Text("${sec}s", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AresCyan,
                            selectedLabelColor = AresOnAccent,
                            containerColor = AresSurfaceElevated,
                            labelColor = AresTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedWindowSec == sec,
                            borderColor = AresBorder
                        )
                    )
                }
            }
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        // Dropdown Search / Add Channel controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Button(
                    onClick = { dropdownExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Channel", color = AresTextPrimary, fontSize = 12.sp)
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier
                        .width(300.dp)
                        .background(AresSurfaceElevated)
                        .border(1.dp, AresBorder)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search NT4 topics...", color = AresTextTertiary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AresCyan,
                            unfocusedBorderColor = AresBorder,
                            focusedTextColor = AresTextPrimary,
                            unfocusedTextColor = AresTextPrimary
                        )
                    )
                    val filteredTopics = activeTopics.filter {
                        it.contains(searchQuery, ignoreCase = true) && !selectedKeys.contains(it)
                    }

                    if (filteredTopics.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No matching topics found", color = AresTextTertiary, fontSize = 12.sp) },
                            onClick = {}
                        )
                    } else {
                        filteredTopics.take(10).forEach { topic ->
                            DropdownMenuItem(
                                text = { Text(topic, color = AresTextPrimary, fontSize = 12.sp) },
                                onClick = {
                                    if (selectedKeys.size < 8) {
                                        selectedKeys.add(topic)
                                    }
                                    dropdownExpanded = false
                                    searchQuery = ""
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { isTreeVisible = !isTreeVisible },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTreeVisible) AresCyan else AresSurfaceElevated,
                    contentColor = if (isTreeVisible) AresOnAccent else AresTextPrimary,
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (isTreeVisible) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                    contentDescription = null,
                    tint = if (isTreeVisible) AresOnAccent else AresCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isTreeVisible) "Hide Signals" else "Show Signals",
                    color = if (isTreeVisible) AresOnAccent else AresTextPrimary,
                    fontSize = 12.sp
                )
            }

            // Legend chips
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(selectedKeys.toList()) { key ->
                    val index = selectedKeys.indexOf(key)
                    val color = channelColors[index % channelColors.size]
                    val detectedUnit = UnitConversion.detectUnitFromKey(key)
                    val targetUnit = targetUnits[key] ?: detectedUnit
                    var unitMenuExpanded by remember { mutableStateOf(false) }

                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AresSurfaceElevated)
                                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                                .clickable {
                                    if (detectedUnit != null && detectedUnit.category != UnitCategory.NONE) {
                                        unitMenuExpanded = true
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(color)
                            )
                            val label = key.split("/").last()
                            val unitSuffix = targetUnit?.let { " (${it.symbol})" } ?: ""
                            Text(
                                "$label$unitSuffix",
                                color = AresTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = AresTextTertiary,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { selectedKeys.remove(key) }
                            )
                        }

                        if (detectedUnit != null && detectedUnit.category != UnitCategory.NONE) {
                            DropdownMenu(
                                expanded = unitMenuExpanded,
                                onDismissRequest = { unitMenuExpanded = false },
                                modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder)
                            ) {
                                RobotUnit.entries.filter { it.category == detectedUnit.category }.forEach { unit ->
                                    DropdownMenuItem(
                                        text = { Text(unit.name + " (${unit.symbol})", color = AresTextPrimary, fontSize = 12.sp) },
                                        onClick = {
                                            selectedKeys
                                                .filter { telemetryChartCategory(it) == detectedUnit.category }
                                                .forEach { sameCategoryKey -> targetUnits[sameCategoryKey] = unit }
                                            unitMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (telemetryChartGroupCount(selectedKeys) > 1) {
            Text(
                text = "Mixed dimensions use separate, time-aligned Y-axis bands.",
                color = AresTextTertiary,
                fontSize = 11.sp,
            )
        }
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Drag & drop channels here or use tree (+) to plot.", color = AresTextTertiary, fontSize = 12.sp)
                    }
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
