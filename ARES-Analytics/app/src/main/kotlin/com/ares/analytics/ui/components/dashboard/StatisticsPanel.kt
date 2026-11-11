@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.launch

enum class TimeFilter {
    FULL, AUTO, TELEOP, ENABLED
}

enum class AnalysisMode {
    SINGLE, MULTI_NORMAL, ABSOLUTE_ERROR, RELATIVE_ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsPanel(
    databaseService: DatabaseService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var availableKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedKey1 by remember { mutableStateOf<String?>(null) }
    var selectedKey2 by remember { mutableStateOf<String?>(null) }
    var timeFilter by remember { mutableStateOf(TimeFilter.FULL) }
    var analysisMode by remember { mutableStateOf(AnalysisMode.SINGLE) }

    // Dropdown expanded states
    var key1Expanded by remember { mutableStateOf(false) }
    var key2Expanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }

    // Loaded data states
    var telemetry1 by remember { mutableStateOf<List<TelemetryFrame>>(emptyList()) }
    var telemetry2 by remember { mutableStateOf<List<TelemetryFrame>>(emptyList()) }
    var stateFrames by remember { mutableStateOf<List<TelemetryFrame>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Fetch keys when session changes
    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            isLoading = true
            scope.launch {
                try {
                    availableKeys = databaseService.getDistinctTelemetryKeys(sessionId)
                    stateFrames = databaseService.getTelemetryForKeyPatterns(
                        sessionId,
                        listOf("%state%", "%mode%", "%enabled%")
                    )
                    if (selectedKey1 == null || !availableKeys.contains(selectedKey1)) {
                        selectedKey1 = availableKeys.firstOrNull()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isLoading = false
                }
            }
        } else {
            availableKeys = emptyList()
            selectedKey1 = null
            selectedKey2 = null
            telemetry1 = emptyList()
            telemetry2 = emptyList()
        }
    }

    // Fetch telemetry data for selected keys
    LaunchedEffect(sessionId, selectedKey1, selectedKey2) {
        val activeSessionId = sessionId ?: return@LaunchedEffect
        val primaryKey = selectedKey1 ?: return@LaunchedEffect
        val comparisonKey = selectedKey2
        scope.launch {
            telemetry1 = databaseService.getTelemetryForKey(activeSessionId, primaryKey)
            telemetry2 = comparisonKey?.let {
                databaseService.getTelemetryForKey(activeSessionId, it)
            } ?: emptyList()
        }
    }

    // Determine the active analysis mode
    LaunchedEffect(selectedKey2) {
        if (selectedKey2 == null) {
            analysisMode = AnalysisMode.SINGLE
        } else if (analysisMode == AnalysisMode.SINGLE) {
            analysisMode = AnalysisMode.MULTI_NORMAL
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
                Icon(Icons.Default.Analytics, contentDescription = null, tint = AresTextTertiary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Select a session to view statistics", color = AresTextTertiary, fontSize = 14.sp)
            }
        }
        return
    }

    var activeRecipe by remember(sessionId) { mutableStateOf<StudentSignalRecipe?>(null) }

    val filtered1 = remember(telemetry1, stateFrames, timeFilter) {
        filterTelemetryByPhase(telemetry1, stateFrames, timeFilter)
    }
    val filtered2 = remember(telemetry2, stateFrames, timeFilter) {
        filterTelemetryByPhase(telemetry2, stateFrames, timeFilter)
    }
    val activeValues = remember(filtered1, filtered2, analysisMode) {
        computeActiveAnalysisValues(filtered1, filtered2, analysisMode)
    }
    val stats = remember(activeValues) {
        computeDashboardSignalStats(activeValues)
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
        StudentStatisticsRecipeAndExplainerBar(
            availableKeys = availableKeys,
            activeRecipe = activeRecipe,
            onSelectRecipe = { resolved ->
                activeRecipe = resolved.recipe
                selectedKey1 = resolved.primaryKey
                selectedKey2 = resolved.secondaryKey
                analysisMode = resolved.analysisMode
            },
        )

        // Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal 1 selector
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { key1Expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresBorder))
                ) {
                    Text(selectedKey1?.substringAfterLast("/") ?: "Select Signal A", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = key1Expanded,
                    onDismissRequest = { key1Expanded = false },
                    modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder).width(240.dp)
                ) {
                    availableKeys.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(key, color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            onClick = {
                                selectedKey1 = key
                                key1Expanded = false
                            }
                        )
                    }
                }
            }

            // Signal 2 selector
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { key2Expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextSecondary),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresBorder))
                ) {
                    Text(selectedKey2?.substringAfterLast("/") ?: "Compare with B (None)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = key2Expanded,
                    onDismissRequest = { key2Expanded = false },
                    modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder).width(240.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("None (Single Mode)", color = AresTextSecondary) },
                        onClick = {
                            selectedKey2 = null
                            key2Expanded = false
                        }
                    )
                    availableKeys.filter { it != selectedKey1 }.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(key, color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            onClick = {
                                selectedKey2 = key
                                key2Expanded = false
                            }
                        )
                    }
                }
            }

            // Analysis Mode (if compare active)
            if (selectedKey2 != null) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { modeExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresCyanGlow))
                    ) {
                        Text(analysisMode.name.replace("_", " "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false },
                        modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder)
                    ) {
                        listOf(AnalysisMode.MULTI_NORMAL, AnalysisMode.ABSOLUTE_ERROR, AnalysisMode.RELATIVE_ERROR).forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.name.replace("_", " "), color = AresTextPrimary) },
                                onClick = {
                                    analysisMode = m
                                    modeExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Time Filter Selector
            Box {
                OutlinedButton(
                    onClick = { filterExpanded = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresBorder))
                ) {
                    Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(timeFilter.name)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false },
                    modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder)
                ) {
                    TimeFilter.values().forEach { filter ->
                        DropdownMenuItem(
                            text = { Text(filter.name, color = AresTextPrimary) },
                            onClick = {
                                timeFilter = filter
                                filterExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Stats Display Workspace
        if (activeValues.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No data points match filters", color = AresTextTertiary)
            }
        } else {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Table of statistics
                Card(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = AresBackground),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresBorder))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (analysisMode == AnalysisMode.SINGLE) "SIGNAL STATISTICS" else "ERROR FORENSICS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AresCyan
                        )
                        Spacer(Modifier.height(4.dp))
                        stats?.forEach { (name, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, color = AresTextSecondary, fontSize = 12.sp)
                                val formatted = if (name == "Count") value.toInt().toString() else String.format("%.5f", value)
                                Text(formatted, color = AresTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = AresBorder, thickness = 0.5.dp)
                        }
                    }
                }

                // Canvas Histogram
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(AresBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    val mean = stats?.get("Mean") ?: 0.0
                    val median = stats?.get("Median") ?: 0.0

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height

                        // Bin parameters
                        val binCount = 18
                        val minVal = activeValues.minOrNull() ?: 0.0
                        val maxVal = activeValues.maxOrNull() ?: 0.0
                        val range = maxVal - minVal
                        val binWidth = if (range == 0.0) 1.0 else range / binCount
                        val bins = IntArray(binCount)
                        for (v in activeValues) {
                            var binIdx = if (range == 0.0) 0 else ((v - minVal) / binWidth).toInt()
                            if (binIdx >= binCount) binIdx = binCount - 1
                            if (binIdx < 0) binIdx = 0
                            bins[binIdx]++
                        }
                        val maxBinCount = bins.maxOrNull()?.coerceAtLeast(1) ?: 1

                        // Draw Grid lines
                        val gridLines = 4
                        for (i in 0..gridLines) {
                            val y = height - (i * (height - 30.dp.toPx()) / gridLines) - 20.dp.toPx()
                            drawLine(
                                color = AresBorder,
                                start = Offset(40.dp.toPx(), y),
                                end = Offset(width - 10.dp.toPx(), y),
                                strokeWidth = 1f
                            )
                        }

                        // Draw Bins
                        val startX = 40.dp.toPx()
                        val endX = width - 10.dp.toPx()
                        val availableWidth = endX - startX
                        val barWidth = availableWidth / binCount

                        for (i in 0 until binCount) {
                            val binHeight = (bins[i].toFloat() / maxBinCount) * (height - 50.dp.toPx())
                            val x = startX + i * barWidth
                            val y = height - 20.dp.toPx() - binHeight

                            drawRect(
                                color = if (i % 2 == 0) AresCyan.copy(alpha = 0.85f) else AresCyanDark.copy(alpha = 0.85f),
                                topLeft = Offset(x + 2f, y),
                                size = Size(barWidth - 4f, binHeight),
                                style = androidx.compose.ui.graphics.drawscope.Fill
                            )
                        }

                        // Bottom Axis Line
                        drawLine(
                            color = AresTextSecondary,
                            start = Offset(startX, height - 20.dp.toPx()),
                            end = Offset(endX, height - 20.dp.toPx()),
                            strokeWidth = 2f
                        )

                        // Draw ticks and labels (Min, Max, Mean)
                        if (range > 0) {
                            // Mean line
                            val meanPct = (mean - minVal) / range
                            val meanX = startX + meanPct.toFloat() * availableWidth
                            if (meanX in startX..endX) {
                                drawLine(
                                    color = AresRed,
                                    start = Offset(meanX, 0f),
                                    end = Offset(meanX, height - 20.dp.toPx()),
                                    strokeWidth = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )
                            }

                            // Median line
                            val medianPct = (median - minVal) / range
                            val medianX = startX + medianPct.toFloat() * availableWidth
                            if (medianX in startX..endX) {
                                drawLine(
                                    color = AresGold,
                                    start = Offset(medianX, 0f),
                                    end = Offset(medianX, height - 20.dp.toPx()),
                                    strokeWidth = 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                                )
                            }
                        }
                    }

                    // Legend overlaid
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd).background(AresSurfaceElevated.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(10.dp, 2.dp).background(AresRed))
                            Text("Mean", color = AresTextPrimary, fontSize = 10.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(10.dp, 2.dp).background(AresGold))
                            Text("Median", color = AresTextPrimary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 1-click student telemetry recipes for the Dashboard Statistics & Forensics panel.
 * Automatically picks matching primary/secondary telemetry keys and sets the appropriate [AnalysisMode].
 */
