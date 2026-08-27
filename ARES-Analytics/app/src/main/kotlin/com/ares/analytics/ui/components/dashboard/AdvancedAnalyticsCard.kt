package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AdvancedAnalyticsReport
import com.ares.analytics.service.AdvancedAnalyticsService
import com.ares.analytics.service.DiagnosticInsight
import com.ares.analytics.service.DriverPerformanceScore
import com.ares.analytics.service.InsightSeverity
import com.ares.analytics.service.OperationResult
import com.ares.analytics.service.PathHeatmapCell
import com.ares.analytics.service.RegressionSignal
import com.ares.analytics.service.SignalCorrelation
import com.ares.analytics.service.TuningSuggestion
import com.ares.analytics.ui.components.core.AnalyticsCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.abs
import kotlin.math.max

private enum class AnalyticsSection(val label: String) {
    OVERVIEW("Overview"),
    REGRESSIONS("Regressions"),
    HEATMAP("Path heatmap"),
    CORRELATIONS("Correlations"),
    RECOMMENDATIONS("Actions")
}

@Composable
fun AdvancedAnalyticsCard(
    analyticsService: AdvancedAnalyticsService,
    sessionId: String?,
    compareSessionId: String?,
    modifier: Modifier = Modifier
) {
    var result by remember(sessionId, compareSessionId) { mutableStateOf<OperationResult<AdvancedAnalyticsReport>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var selectedSection by remember { mutableStateOf(AnalyticsSection.OVERVIEW) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId, compareSessionId, refreshKey) {
        result = null
        exportMessage = null
        result = if (sessionId == null) {
            OperationResult.Unavailable("NO_SESSION", "Select a recorded session to start an evidence-backed analysis.")
        } else if (compareSessionId != null) {
            analyticsService.analyzeSafely(sessionId, listOf(compareSessionId))
        } else {
            analyticsService.analyzeAgainstRecent(sessionId)
        }
    }

    val report = (result as? OperationResult.Success)?.value
    AnalyticsCard(modifier = modifier.fillMaxSize(), backgroundColor = AresSurfaceElevated) {
        CardHeader(
            title = "Advanced Analytics",
            icon = Icons.Default.Insights,
            iconTint = AresCyan,
            statusText = report?.let { if (compareSessionId == null) "RECENT BASELINE" else "1:1 COMPARE" },
            statusColor = AresCyan,
            trailingContent = {
                IconButton(onClick = { refreshKey++ }, enabled = sessionId != null) {
                    Icon(Icons.Default.Refresh, "Refresh analytics", tint = AresTextSecondary)
                }
                IconButton(
                    onClick = {
                        report?.let { value ->
                            val target = chooseAnalyticsExportFile(value.sessionId)
                            if (target != null) scope.launch {
                                exportMessage = runCatching {
                                    withContext(Dispatchers.IO) { target.writeText(analyticsService.renderDiagnosticMarkdown(value)) }
                                    "Saved ${target.name}"
                                }.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
                            }
                        }
                    },
                    enabled = report != null
                ) {
                    Icon(Icons.Default.Download, "Export analytics report", tint = if (report == null) AresTextTertiary else AresCyan)
                }
            }
        )

        when (val current = result) {
            null -> LoadingAnalytics()
            is OperationResult.Unavailable -> AnalyticsEmptyState(current.message)
            is OperationResult.Failure -> AnalyticsFailure(current.message) { refreshKey++ }
            is OperationResult.Success -> {
                AnalyticsTabs(selectedSection) { selectedSection = it }
                exportMessage?.let {
                    Text(it, color = if (it.startsWith("Saved")) AresGreen else AresError, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp)) {
                    when (selectedSection) {
                        AnalyticsSection.OVERVIEW -> OverviewSection(current.value)
                        AnalyticsSection.REGRESSIONS -> RegressionSection(current.value.regressions)
                        AnalyticsSection.HEATMAP -> HeatmapSection(current.value.pathHeatmap)
                        AnalyticsSection.CORRELATIONS -> CorrelationSection(current.value.correlations)
                        AnalyticsSection.RECOMMENDATIONS -> RecommendationSection(current.value.diagnostics, current.value.tuningSuggestions)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsTabs(selected: AnalyticsSection, onSelect: (AnalyticsSection) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnalyticsSection.entries.forEach { section ->
            val active = section == selected
            TextButton(
                onClick = { onSelect(section) },
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) AresCyan.copy(alpha = 0.16f) else Color.Transparent)
            ) {
                Text(section.label, color = if (active) AresCyan else AresTextSecondary, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LoadingAnalytics() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Text("Analyzing bounded telemetry samples…", color = AresTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun AnalyticsEmptyState(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Insights, null, tint = AresTextTertiary, modifier = Modifier.size(36.dp))
        Text(message, color = AresTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun AnalyticsFailure(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Analytics could not be completed", color = AresError, fontWeight = FontWeight.Bold)
        Text(message, color = AresTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onRetry) { Text("Try again", color = AresCyan) }
    }
}

@Composable
private fun OverviewSection(report: AdvancedAnalyticsReport) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Driver", report.driverScore?.total?.let { "%.0f".format(it) } ?: "--", report.driverScore?.let(::scoreColor) ?: AresTextTertiary, Modifier.weight(1f))
            MetricTile("Regressions", report.regressions.size.toString(), if (report.regressions.isEmpty()) AresGreen else AresAmber, Modifier.weight(1f))
            MetricTile("Path cells", report.pathHeatmap.size.toString(), AresCyan, Modifier.weight(1f))
            MetricTile("Actions", report.tuningSuggestions.size.toString(), AresCyan, Modifier.weight(1f))
        }
        report.driverScore?.let { DriverScoreBreakdown(it) }
        SectionTitle("What needs attention")
        if (report.diagnostics.isEmpty()) {
            EmptyRow("No actionable diagnostics were found in this session.")
        } else {
            report.diagnostics.take(4).forEach { DiagnosticRow(it) }
        }
        val baselineCount = report.comparison?.baselineSessionIds?.size ?: 0
        Text(
            if (baselineCount == 0) "No compatible baseline metrics were available." else "Compared against $baselineCount baseline session${if (baselineCount == 1) "" else "s"}.",
            color = AresTextTertiary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(AresSurface).padding(10.dp)) {
        Text(label.uppercase(), color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DriverScoreBreakdown(score: DriverPerformanceScore) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Driver input quality · ${score.samples} samples")
        ScoreBar("Smoothness", score.smoothness)
        ScoreBar("Decisiveness", score.decisiveness)
        ScoreBar("Consistency", score.consistency)
    }
}

@Composable
private fun ScoreBar(label: String, value: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AresTextSecondary, fontSize = 11.sp, modifier = Modifier.width(90.dp))
        Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(AresBorder.copy(alpha = 0.45f))) {
            Box(Modifier.fillMaxWidth((value / 100.0).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(scoreColor(value)))
        }
        Text("%.0f".format(value), color = AresTextPrimary, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RegressionSection(regressions: List<RegressionSignal>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (regressions.isEmpty()) {
            EmptyRow("No material regressions against the selected baseline.", AresGreen)
        } else regressions.forEach { regression ->
            val color = severityColor(regression.severity)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AresSurface).padding(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(regression.metric, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("+%.1f%%".format(regression.percentRegression), color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text("Current %.3f · baseline %.3f".format(regression.current, regression.baseline), color = AresTextTertiary, fontSize = 11.sp)
                Box(Modifier.fillMaxWidth().padding(top = 7.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(AresBorder.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxWidth((regression.percentRegression / 100.0).toFloat().coerceIn(0.04f, 1f)).fillMaxHeight().background(color))
                }
            }
        }
    }
}

@Composable
private fun HeatmapSection(cells: List<PathHeatmapCell>) {
    if (cells.isEmpty()) {
        EmptyRow("Pose topics were not present, so a path heatmap could not be built.")
        return
    }
    val minX = cells.minOf { it.xIndex }
    val maxX = cells.maxOf { it.xIndex }
    val minY = cells.minOf { it.yIndex }
    val maxY = cells.maxOf { it.yIndex }
    val maxVisits = max(1, cells.maxOf { it.visits })
    val cyan = AresCyan
    val border = AresBorder
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Occupancy by 0.5 m field cell", color = AresTextSecondary, fontSize = 11.sp)
            Text("Peak $maxVisits visits", color = AresCyan, fontSize = 11.sp)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            val columns = maxX - minX + 1
            val rows = maxY - minY + 1
            val cellWidth = size.width / columns.coerceAtLeast(1)
            val cellHeight = size.height / rows.coerceAtLeast(1)
            cells.forEach { cell ->
                val intensity = (cell.visits.toFloat() / maxVisits).coerceIn(0f, 1f)
                val left = (cell.xIndex - minX) * cellWidth
                val top = (maxY - cell.yIndex) * cellHeight
                drawRect(cyan.copy(alpha = 0.12f + intensity * 0.78f), androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellWidth, cellHeight))
                drawRect(border.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellWidth, cellHeight), style = Stroke(1f))
            }
        }
        Text("Darker cells indicate more time spent in that area. Use this to spot congestion, hesitation, or route drift.", color = AresTextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CorrelationSection(correlations: List<SignalCorrelation>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (correlations.isEmpty()) {
            EmptyRow("Not enough aligned motor, voltage, and velocity samples for reliable correlations.")
        } else correlations.forEach { correlation ->
            val strength = abs(correlation.coefficient)
            val color = when { strength >= 0.8 -> AresAmber; strength >= 0.6 -> AresCyan; else -> AresTextSecondary }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AresSurface).padding(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("r = %.2f".format(correlation.coefficient), color = color, fontWeight = FontWeight.Bold)
                    Text("n = ${correlation.samples}", color = AresTextTertiary, fontSize = 11.sp)
                }
                Text(shortTopic(correlation.leftTopic), color = AresTextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("↔ ${shortTopic(correlation.rightTopic)}", color = AresTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecommendationSection(diagnostics: List<DiagnosticInsight>, suggestions: List<TuningSuggestion>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Diagnostics")
        if (diagnostics.isEmpty()) EmptyRow("No actionable diagnostic findings.", AresGreen) else diagnostics.forEach { DiagnosticRow(it) }
        SectionTitle("Evidence-backed tuning")
        if (suggestions.isEmpty()) EmptyRow("No tuning change is justified by the available evidence.", AresGreen) else suggestions.forEach { SuggestionRow(it) }
    }
}

@Composable
private fun DiagnosticRow(insight: DiagnosticInsight) {
    val color = severityColor(insight.severity)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AresSurface).padding(10.dp)) {
        Box(Modifier.padding(top = 4.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Column(Modifier.padding(start = 9.dp)) {
            Text(insight.message, color = AresTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("${insight.category.uppercase()} · ${insight.evidence}", color = AresTextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: TuningSuggestion) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AresSurface).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(suggestion.parameter, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("${(suggestion.confidence * 100).toInt()}% confidence", color = AresTextSecondary, fontSize = 11.sp)
        }
        Text(suggestion.recommendation, color = AresTextPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        Text("${suggestion.rationale} · n=${suggestion.evidenceSamples}", color = AresTextTertiary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun EmptyRow(message: String, color: Color = AresTextSecondary) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AresSurface).padding(14.dp)) {
        Text(message, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title.uppercase(), color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

private fun scoreColor(score: DriverPerformanceScore): Color = scoreColor(score.total)

private fun scoreColor(score: Double): Color = when {
    score >= 80.0 -> AresGreen
    score >= 60.0 -> AresAmber
    else -> AresError
}

private fun severityColor(severity: InsightSeverity): Color = when (severity) {
    InsightSeverity.INFO -> AresCyan
    InsightSeverity.WARNING -> AresAmber
    InsightSeverity.CRITICAL -> AresError
}

private fun shortTopic(topic: String): String = topic.split('/').takeLast(3).joinToString("/")

private fun chooseAnalyticsExportFile(sessionId: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Export analytics report"
        selectedFile = File("ares-analytics-${sessionId.take(24)}.md")
        fileFilter = FileNameExtensionFilter("Markdown report", "md")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    return if (selected.extension.equals("md", ignoreCase = true)) selected else File(selected.parentFile, "${selected.name}.md")
}
