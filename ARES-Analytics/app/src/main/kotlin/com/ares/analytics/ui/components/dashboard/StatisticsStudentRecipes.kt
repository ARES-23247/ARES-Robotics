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

enum class StudentSignalRecipe(
    val id: String,
    val label: String,
    val shortDescription: String,
    val studentGuidance: String,
) {
    BATTERY_HEALTH(
        id = "battery_health",
        label = "Battery Sag & Health",
        shortDescription = "Inspect voltage drops under drive/mechanism load.",
        studentGuidance = "Healthy competition batteries hold Mean >= 12.0 V and Min >= 10.5 V. If 5th % drops below 10.0 V, check battery internal resistance or lower drive current limits to prevent brownouts.",
    ),
    LOOP_TIMING(
        id = "loop_timing",
        label = "Control Loop Timing",
        shortDescription = "Check control loop period consistency and P95 jitter.",
        studentGuidance = "Compare Mean vs 95th % (P95) loop time. If P95 jumps well above your target period (e.g. > 20 ms), a periodic sensor read or logging call is stalling the control thread.",
    ),
    MECHANISM_SETPOINT_ERROR(
        id = "mechanism_setpoint_error",
        label = "Mechanism Setpoint Error",
        shortDescription = "Compare measured mechanism position/velocity vs target.",
        studentGuidance = "Uses ABSOLUTE_ERROR between measured and target signals. High Mean error points to kP/feedforward under-compensation; high 95th % with low Median indicates transient lag during fast moves.",
    ),
    HEADING_POSE_TRACKING(
        id = "heading_pose_tracking",
        label = "Heading & Pose Tracking",
        shortDescription = "Compare robot heading/pose against autonomous target.",
        studentGuidance = "Checks heading or pose tracking error (CCW-positive radians/meters). Small IQR and low 95th % confirm repeatable autonomous path following.",
    ),
}

data class ResolvedSignalRecipe(
    val recipe: StudentSignalRecipe,
    val primaryKey: String?,
    val secondaryKey: String?,
    val analysisMode: AnalysisMode,
    val studentGuidance: String,
)

data class DashboardStatExplainerItem(
    val metricName: String,
    val plainEnglishMeaning: String,
    val robotDiagnosticExample: String,
)

fun studentDashboardStatExplainerItems(): List<DashboardStatExplainerItem> = listOf(
    DashboardStatExplainerItem(
        metricName = "Mean vs Median",
        plainEnglishMeaning = "Mean is the overall average; Median is the middle sample (50th %).",
        robotDiagnosticExample = "If Mean loop time is much higher than Median, a few big spikes (not every loop) are slowing the robot.",
    ),
    DashboardStatExplainerItem(
        metricName = "95th % (P95)",
        plainEnglishMeaning = "95% of all samples are at or below this value—shows worst-case behavior without single-sample flukes.",
        robotDiagnosticExample = "Use 95th % for Loop Time and Tracking Error to verify the robot stays fast and accurate under peak load.",
    ),
    DashboardStatExplainerItem(
        metricName = "Std Dev (Spread)",
        plainEnglishMeaning = "Standard deviation measures how much the signal bounces around its Mean.",
        robotDiagnosticExample = "High Std Dev on a stationary sensor or holding mechanism indicates electrical noise or PID oscillation (kP too high).",
    ),
    DashboardStatExplainerItem(
        metricName = "IQR (Middle 50% Range)",
        plainEnglishMeaning = "Interquartile Range (75th % minus 25th %) shows the typical operating band, ignoring extreme spikes.",
        robotDiagnosticExample = "A tight IQR on shooter velocity or elevator hold proves consistent repeatability across the match.",
    ),
)

fun resolveStudentSignalRecipe(
    availableKeys: List<String>,
    recipe: StudentSignalRecipe,
): ResolvedSignalRecipe {
    if (availableKeys.isEmpty()) {
        return ResolvedSignalRecipe(recipe, null, null, AnalysisMode.SINGLE, recipe.studentGuidance)
    }
    return when (recipe) {
        StudentSignalRecipe.BATTERY_HEALTH -> {
            val primary = availableKeys.firstOrNull { key ->
                key.contains("battery", ignoreCase = true) || key.contains("voltage", ignoreCase = true) || key.endsWith("/vbat", ignoreCase = true)
            } ?: availableKeys.firstOrNull()
            ResolvedSignalRecipe(recipe, primary, null, AnalysisMode.SINGLE, recipe.studentGuidance)
        }
        StudentSignalRecipe.LOOP_TIMING -> {
            val primary = availableKeys.firstOrNull { key ->
                key.contains("looptime", ignoreCase = true) || key.contains("loop_time", ignoreCase = true) ||
                    key.contains("period", ignoreCase = true) || key.contains("dt", ignoreCase = true)
            } ?: availableKeys.firstOrNull()
            ResolvedSignalRecipe(recipe, primary, null, AnalysisMode.SINGLE, recipe.studentGuidance)
        }
        StudentSignalRecipe.MECHANISM_SETPOINT_ERROR -> {
            val targetKey = availableKeys.firstOrNull { key ->
                !key.contains("pose", ignoreCase = true) && !key.contains("heading", ignoreCase = true) &&
                    (key.contains("target", ignoreCase = true) || key.contains("setpoint", ignoreCase = true) || key.contains("goal", ignoreCase = true))
            }
            val measuredKey = availableKeys.firstOrNull { key ->
                key != targetKey && !key.contains("pose", ignoreCase = true) && !key.contains("heading", ignoreCase = true) &&
                    (key.contains("position", ignoreCase = true) || key.contains("velocity", ignoreCase = true) ||
                        key.contains("measured", ignoreCase = true) || key.contains("angle", ignoreCase = true))
            } ?: availableKeys.firstOrNull { it != targetKey }
            if (measuredKey != null && targetKey != null) {
                ResolvedSignalRecipe(recipe, measuredKey, targetKey, AnalysisMode.ABSOLUTE_ERROR, recipe.studentGuidance)
            } else {
                ResolvedSignalRecipe(recipe, measuredKey ?: targetKey ?: availableKeys.firstOrNull(), null, AnalysisMode.SINGLE, recipe.studentGuidance)
            }
        }
        StudentSignalRecipe.HEADING_POSE_TRACKING -> {
            val targetKey = availableKeys.firstOrNull { key ->
                (key.contains("heading", ignoreCase = true) || key.contains("pose", ignoreCase = true) || key.contains("yaw", ignoreCase = true)) &&
                    (key.contains("target", ignoreCase = true) || key.contains("setpoint", ignoreCase = true) || key.contains("desired", ignoreCase = true))
            }
            val measuredKey = availableKeys.firstOrNull { key ->
                key != targetKey &&
                    (key.contains("heading", ignoreCase = true) || key.contains("pose", ignoreCase = true) || key.contains("yaw", ignoreCase = true))
            } ?: availableKeys.firstOrNull()
            if (measuredKey != null && targetKey != null) {
                ResolvedSignalRecipe(recipe, measuredKey, targetKey, AnalysisMode.ABSOLUTE_ERROR, recipe.studentGuidance)
            } else {
                ResolvedSignalRecipe(recipe, measuredKey, null, AnalysisMode.SINGLE, recipe.studentGuidance)
            }
        }
    }
}

internal fun interpolateSignalAtTimestamp(t: Long, times: List<Long>, values: List<Double>): Double {
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

internal fun filterTelemetryByPhase(
    raw: List<TelemetryFrame>,
    stateFrames: List<TelemetryFrame>,
    timeFilter: TimeFilter,
): List<TelemetryFrame> {
    if (raw.isEmpty()) return emptyList()
    val startMs = raw.first().timestampMs
    val enabledKey = stateFrames.firstOrNull { it.key.endsWith("enabled", ignoreCase = true) }?.key
    val modeKey = stateFrames.firstOrNull { it.key.endsWith("mode", ignoreCase = true) }?.key

    return when (timeFilter) {
        TimeFilter.FULL -> raw
        TimeFilter.AUTO -> {
            if (modeKey != null) {
                val modeValues = stateFrames.filter { it.key == modeKey }
                val times = modeValues.map { it.timestampMs }
                val vals = modeValues.map { it.value }
                raw.filter { frame -> interpolateSignalAtTimestamp(frame.timestampMs, times, vals) == 1.0 }
            } else {
                val cutoff = startMs + 15_000L
                raw.filter { it.timestampMs <= cutoff }
            }
        }
        TimeFilter.TELEOP -> {
            if (modeKey != null) {
                val modeValues = stateFrames.filter { it.key == modeKey }
                val times = modeValues.map { it.timestampMs }
                val vals = modeValues.map { it.value }
                raw.filter { frame -> interpolateSignalAtTimestamp(frame.timestampMs, times, vals) == 2.0 }
            } else {
                val cutoff = startMs + 15_000L
                raw.filter { it.timestampMs > cutoff }
            }
        }
        TimeFilter.ENABLED -> {
            if (enabledKey != null) {
                val enabledValues = stateFrames.filter { it.key == enabledKey }
                val times = enabledValues.map { it.timestampMs }
                val vals = enabledValues.map { it.value }
                raw.filter { frame -> interpolateSignalAtTimestamp(frame.timestampMs, times, vals) > 0.5 }
            } else {
                raw
            }
        }
    }
}

internal fun computeActiveAnalysisValues(
    filtered1: List<TelemetryFrame>,
    filtered2: List<TelemetryFrame>,
    analysisMode: AnalysisMode,
): List<Double> {
    if (analysisMode == AnalysisMode.SINGLE || filtered2.isEmpty()) {
        return filtered1.map { it.value }
    }
    val list = ArrayList<Double>(filtered1.size)
    val t2List = filtered2.map { it.timestampMs }
    val v2List = filtered2.map { it.value }
    for (f1 in filtered1) {
        val val1 = f1.value
        val val2 = interpolateSignalAtTimestamp(f1.timestampMs, t2List, v2List)
        when (analysisMode) {
            AnalysisMode.ABSOLUTE_ERROR -> list.add(abs(val1 - val2))
            AnalysisMode.RELATIVE_ERROR -> list.add(abs(val1 - val2) / max(abs(val2), 1e-6))
            else -> list.add(val1)
        }
    }
    return list
}

fun computeDashboardSignalStats(activeValues: List<Double>): Map<String, Double>? {
    if (activeValues.isEmpty()) return null
    val sorted = activeValues.sorted()
    val n = sorted.size
    val minVal = sorted.first()
    val maxVal = sorted.last()
    val meanVal = sorted.sum() / n
    val medianVal = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0

    val counts = mutableMapOf<Double, Int>()
    sorted.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    val modeVal = counts.maxByOrNull { it.value }?.key ?: 0.0

    val variance = sorted.fold(0.0) { acc, d -> acc + (d - meanVal).pow(2) } / n
    val stdDevVal = sqrt(variance)

    fun getPercentile(p: Double): Double {
        val index = p * (n - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(n - 1)
        val weight = index - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    val p5 = getPercentile(0.05)
    val p25 = getPercentile(0.25)
    val p75 = getPercentile(0.75)
    val p95 = getPercentile(0.95)
    val iqr = p75 - p25
    val m3 = sorted.fold(0.0) { acc, d -> acc + (d - meanVal).pow(3) } / n
    val skewnessVal = if (stdDevVal > 1e-9) m3 / stdDevVal.pow(3) else 0.0

    return linkedMapOf(
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
        "95th %" to p95,
    )
}

@Composable
internal fun StudentStatisticsRecipeAndExplainerBar(
    availableKeys: List<String>,
    activeRecipe: StudentSignalRecipe?,
    onSelectRecipe: (ResolvedSignalRecipe) -> Unit,
) {
    var showExplainer by remember { mutableStateOf(false) }
    val activeResolved = remember(availableKeys, activeRecipe) {
        activeRecipe?.let { resolveStudentSignalRecipe(availableKeys, it) }
    }
    com.ares.analytics.ui.components.core.AresCollapsibleGuideCard(
        badgeText = "RECIPES",
        title = "1-Click Student Signal Recipes",
        expanded = showExplainer,
        onToggle = { showExplainer = !showExplainer },
        expandLabel = "What Do Mean / P95 / IQR Mean?",
        collapseLabel = "Hide Mean / P95 / IQR Guide",
    ) {
        HorizontalDivider(color = AresBorder)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            studentDashboardStatExplainerItems().forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${item.metricName}: ${item.plainEnglishMeaning}", color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text("Robot tip: ${item.robotDiagnosticExample}", color = AresTextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
    com.ares.analytics.ui.components.core.AresPresetChipBar(
        items = StudentSignalRecipe.entries,
        labelFor = { it.label },
        isSelected = { activeRecipe == it },
        onSelect = { onSelectRecipe(resolveStudentSignalRecipe(availableKeys, it)) },
    )
    activeResolved?.let { resolved ->
        Text(resolved.studentGuidance, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    }
}
