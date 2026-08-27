package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TelemetryRow(
    val timestampMs: Long,
    val values: Map<String, Double?>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTablePanel(
    databaseService: DatabaseService,
    replayEngineService: ReplayEngineService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var availableKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    val selectedKeys = remember { mutableStateListOf<String>() }
    var keyDropdownExpanded by remember { mutableStateOf(false) }

    // Table rows cache
    var telemetryRows by remember { mutableStateOf<List<TelemetryRow>>(emptyList()) }
    var startTimestampMs by remember { mutableStateOf(0L) }
    var endTimestampMs by remember { mutableStateOf(0L) }
    var isLoading by remember { mutableStateOf(false) }

    // Scroll state and replay engine sync
    val listState = rememberLazyListState()
    val currentFrame by replayEngineService.currentFrame.collectAsState()
    val activeTimestamp = currentFrame?.timestampMs ?: 0L
    val activeWindow = if (activeTimestamp > 0L && startTimestampMs > 0L) {
        (activeTimestamp - startTimestampMs).coerceAtLeast(0L) / TABLE_WINDOW_MS
    } else {
        0L
    }

    // Fetch keys on session change
    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            isLoading = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    availableKeys = databaseService.getDistinctTelemetryKeys(sessionId)
                    val timestampRange = databaseService.getSessionTimestampRange(sessionId)
                    startTimestampMs = timestampRange?.first ?: 0L
                    endTimestampMs = timestampRange?.second ?: 0L

                    // Auto-select first few keys as default columns
                    selectedKeys.clear()
                    availableKeys.take(3).forEach { selectedKeys.add(it) }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isLoading = false
                }
            }
        } else {
            availableKeys = emptyList()
            selectedKeys.clear()
            telemetryRows = emptyList()
        }
    }

    // Align values using sample-and-hold when keys or session changes
    LaunchedEffect(sessionId, selectedKeys.toList(), activeWindow) {
        if (sessionId != null && selectedKeys.isNotEmpty()) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val center = if (activeTimestamp in startTimestampMs..endTimestampMs) activeTimestamp else startTimestampMs
                val queryStart = (center - TABLE_WINDOW_MS).coerceAtLeast(startTimestampMs)
                val queryEnd = (center + TABLE_WINDOW_MS).coerceAtMost(endTimestampMs)
                val page = databaseService.getTelemetryPageForKeys(
                    sessionId = sessionId,
                    keys = selectedKeys.toList(),
                    startMs = queryStart,
                    endMs = queryEnd,
                    limit = MAX_TABLE_FRAMES
                )
                val data = page.groupBy { it.key }

                // Gather all distinct timestamps across selected keys
                val allTimestamps = data.values.flatMap { list -> list.map { it.timestampMs } }.distinct().sorted()

                // Calculate aligned rows using sample-and-hold
                val rows = allTimestamps.map { ts ->
                    val rowValues = selectedKeys.associateWith { key ->
                        val keyFrames = data[key] ?: emptyList()
                        var idx = keyFrames.binarySearchBy(ts) { it.timestampMs }
                        if (idx < 0) {
                            idx = -idx - 2 // Sample-and-hold last known value
                        }
                        if (idx in keyFrames.indices) keyFrames[idx].value else null
                    }
                    TelemetryRow(ts, rowValues)
                }
                telemetryRows = rows
            }
        } else {
            telemetryRows = emptyList()
        }
    }

    // Historical tables intentionally query only the playhead viewport. LazyColumn virtualizes
    // rendering, while this bound also prevents an entire long match from entering heap memory.

    // Auto-scroll table to active frame row during replay playback
    LaunchedEffect(activeTimestamp) {
        if (telemetryRows.isNotEmpty()) {
            var closestIndex = telemetryRows.binarySearchBy(activeTimestamp) { it.timestampMs }
            if (closestIndex < 0) {
                closestIndex = -closestIndex - 2
            }
            closestIndex = closestIndex.coerceIn(0, telemetryRows.size - 1)

            // Check if active row is currently visible, if not scroll to it
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val isVisible = visibleInfo.any { it.index == closestIndex }
            if (!isVisible && !listState.isScrollInProgress) {
                listState.animateScrollToItem(closestIndex)
            }
        }
    }

    if (sessionId == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(AresSurface)
                .border(1.dp, AresBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = AresTextTertiary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Select a session to view tabular data", color = AresTextTertiary, fontSize = 14.sp)
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Table Controls / Columns selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DATA TABLE VIEWER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AresCyan)

            // Columns Picker
            Box {
                Button(
                    onClick = { keyDropdownExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Add, null, tint = AresBackground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Columns", color = AresBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                DropdownMenu(
                    expanded = keyDropdownExpanded,
                    onDismissRequest = { keyDropdownExpanded = false },
                    modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder).width(280.dp).heightIn(max = 400.dp)
                ) {
                    availableKeys.filter { !selectedKeys.contains(it) }.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(key, color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            onClick = {
                                selectedKeys.add(key)
                                keyDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Active Columns Tags
        if (selectedKeys.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Active Columns:", fontSize = 11.sp, color = AresTextSecondary)
                selectedKeys.forEach { key ->
                    InputChip(
                        selected = true,
                        onClick = { selectedKeys.remove(key) },
                        label = { Text(key.substringAfterLast("/"), fontSize = 10.sp, color = AresCyan) },
                        trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp), tint = AresCyan) },
                        colors = InputChipDefaults.inputChipColors(containerColor = AresCyanGlow, selectedContainerColor = AresCyanGlow)
                    )
                }
            }
        }

        // Aligned Tabular Grid
        if (telemetryRows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Select columns to display data", color = AresTextTertiary)
            }
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Table Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AresBackground)
                        .padding(vertical = 8.dp, horizontal = 12.dp)
                        .border(width = 1.dp, color = AresBorder, shape = RoundedCornerShape(4.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Timestamp Column Header
                    Text(
                        text = "Timestamp (s)",
                        modifier = Modifier.weight(1f),
                        color = AresTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Keys Column Headers
                    selectedKeys.forEach { key ->
                        Text(
                            text = key.substringAfterLast("/"),
                            modifier = Modifier.weight(1f),
                            color = AresTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Table Aligned Rows with virtual scroll
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, AresBorder, RoundedCornerShape(4.dp))
                ) {
                    itemsIndexed(telemetryRows) { index, row ->
                        val isHighlighted = abs(row.timestampMs - activeTimestamp) < 30L // Near active playhead
                        val rowBg = when {
                            isHighlighted -> AresCyanGlow
                            index % 2 == 0 -> AresSurfaceElevated
                            else -> AresSurface
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .clickable {
                                    val duration = endTimestampMs - startTimestampMs
                                    if (duration > 0) {
                                        val pct = (row.timestampMs - startTimestampMs).toDouble() / duration.toDouble()
                                        replayEngineService.scrubTo(pct)
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Timestamp column
                            Text(
                                text = String.format("%.3f", (row.timestampMs - startTimestampMs) / 1000.0),
                                modifier = Modifier.weight(1f),
                                color = if (isHighlighted) AresCyan else AresTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            // Keys columns values
                            selectedKeys.forEach { key ->
                                val valVal = row.values[key]
                                val textVal = if (valVal != null) String.format("%.4f", valVal) else "-"
                                Text(
                                    text = textVal,
                                    modifier = Modifier.weight(1f),
                                    color = if (isHighlighted) AresCyan else AresTextPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val TABLE_WINDOW_MS = 5_000L
private const val MAX_TABLE_FRAMES = 5_000
