package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

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

    // Helper: Interpolation for aligning Signal 2 to Signal 1 timestamps

    fun interpolate(t: Long, times: List<Long>, values: List<Double>): Double {
        if (times.isEmpty()) return 0.0
        val idx = times.binarySearch(t)
        if (idx >= 0) return values[idx]
        val insertIdx = -idx - 1
        if (insertIdx == 0) return values.first()
        if (insertIdx >= times.size) return values.last()
        val t0 = times[insertIdx - 1]
        val t1 = times[insertIdx]
        val v0 = values[insertIdx - 1]
        val v1 = values[insertIdx]
        if (t1 == t0) return v0
        val pct = (t - t0).toDouble() / (t1 - t0).toDouble()
        return v0 + pct * (v1 - v0)
    }

    // Filter telemetry based on active TimeFilter
    fun filterTelemetry(raw: List<TelemetryFrame>): List<TelemetryFrame> {
        if (raw.isEmpty()) return emptyList()
        val startMs = raw.first().timestampMs
        val durationMs = raw.last().timestampMs - startMs

        // Check if there is an explicit "/RobotState/Mode" or "/RobotState/Enabled" in telemetry
        val enabledKey = stateFrames.firstOrNull { it.key.endsWith("enabled", ignoreCase = true) }?.key
        val modeKey = stateFrames.firstOrNull { it.key.endsWith("mode", ignoreCase = true) }?.key

        return when (timeFilter) {
            TimeFilter.FULL -> raw
            TimeFilter.AUTO -> {
                if (modeKey != null) {
                    val modeValues = stateFrames.filter { it.key == modeKey }
                    raw.filter { frame ->
                        val activeMode = interpolate(frame.timestampMs, modeValues.map { it.timestampMs }, modeValues.map { it.value })
                        activeMode == 1.0 // Heuristic: 1.0 is Auto
                    }
                } else {
                    // Fallback to first 15 seconds
                    val cutoff = startMs + 15000L
                    raw.filter { it.timestampMs <= cutoff }
                }
            }
            TimeFilter.TELEOP -> {
                if (modeKey != null) {
                    val modeValues = stateFrames.filter { it.key == modeKey }
                    raw.filter { frame ->
                        val activeMode = interpolate(frame.timestampMs, modeValues.map { it.timestampMs }, modeValues.map { it.value })
                        activeMode == 2.0 // Heuristic: 2.0 is Teleop
                    }
                } else {
                    // Fallback to post-15 seconds
                    val cutoff = startMs + 15000L
                    raw.filter { it.timestampMs > cutoff }
                }
            }
            TimeFilter.ENABLED -> {
                if (enabledKey != null) {
                    val enabledValues = stateFrames.filter { it.key == enabledKey }
                    raw.filter { frame ->
                        val isEnabled = interpolate(frame.timestampMs, enabledValues.map { it.timestampMs }, enabledValues.map { it.value })
                        isEnabled > 0.5
                    }
                } else {
                    raw // Fallback to all if no enabled key
                }
            }
        }
    }

    // Processed primary signal values
    val filtered1 = filterTelemetry(telemetry1)
    val filtered2 = filterTelemetry(telemetry2)

    // Compute active dataset to analyze based on mode
    val activeValues = remember(filtered1, filtered2, analysisMode) {
        val list = mutableListOf<Double>()
        if (analysisMode == AnalysisMode.SINGLE || filtered2.isEmpty()) {
            list.addAll(filtered1.map { it.value })
        } else {
            val t2List = filtered2.map { it.timestampMs }
            val v2List = filtered2.map { it.value }
            for (f1 in filtered1) {
                val val1 = f1.value
                val val2 = interpolate(f1.timestampMs, t2List, v2List)
                when (analysisMode) {
                    AnalysisMode.ABSOLUTE_ERROR -> list.add(abs(val1 - val2))
                    AnalysisMode.RELATIVE_ERROR -> list.add(abs(val1 - val2) / max(abs(val2), 1e-6))
                    else -> list.add(val1) // Fallback normal comparison
                }
            }
        }
        list
    }

    // Compute Descriptive Statistics
    val stats = remember(activeValues) {
        if (activeValues.isEmpty()) null else {
            val sorted = activeValues.sorted()
            val n = sorted.size
            val minVal = sorted.first()
            val maxVal = sorted.last()
            val sumVal = sorted.sum()
            val meanVal = sumVal / n

            // Median
            val medianVal = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0

            // Mode
            val counts = mutableMapOf<Double, Int>()
            sorted.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            val modeVal = counts.maxByOrNull { it.value }?.key ?: 0.0

            // StdDev
            val variance = sorted.fold(0.0) { acc, d -> acc + (d - meanVal).pow(2) } / n
            val stdDevVal = sqrt(variance)

            // Percentiles
            fun getPercentile(p: Double): Double {
                val index = (p * (n - 1))
                val lower = index.toInt()
                val upper = (lower + 1).coerceAtMost(n - 1)
                val weight = index - lower
                return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
            }
            val p5 = getPercentile(0.05)
            val p25 = getPercentile(0.25)
            val p50 = medianVal
            val p75 = getPercentile(0.75)
            val p95 = getPercentile(0.95)
            val iqr = p75 - p25

            // Skewness
            val m3 = sorted.fold(0.0) { acc, d -> acc + (d - meanVal).pow(3) } / n
            val skewnessVal = if (stdDevVal > 1e-9) m3 / stdDevVal.pow(3) else 0.0

            mapOf(
                "Count" to n.toDouble(),
                "Min" to minVal,
                "Max" to maxVal,
                "Mean" to meanVal,
                "Median" to medianVal,
                "Mode" to modeVal,
                "Std Dev" to stdDevVal,
                "IQR" to iqr,
                "Skewness" to skewnessVal,
                "5th %" to p5,
                "25th %" to p25,
                "75th %" to p75,
                "95th %" to p95
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
