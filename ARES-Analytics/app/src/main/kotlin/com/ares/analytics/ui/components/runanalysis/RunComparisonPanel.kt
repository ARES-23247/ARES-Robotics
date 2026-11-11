@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.ares.analytics.ui.components.runanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ComparisonClaimKind
import com.ares.analytics.service.GuidedComparisonFinding
import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.RunComparisonMetric
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.RunTrajectoryOverlay
import com.ares.analytics.service.shortRunLabel
import com.ares.analytics.shared.models.isSimulationSessionTags
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.io.File
import kotlin.math.max
import kotlin.math.min

@Composable
fun RunComparisonPanel(
    sessions: List<Session>,
    primarySessionId: String?,
    comparisonSessionIds: List<String>,
    comparing: Boolean,
    comparisonError: String?,
    comparisonExportMessage: String?,
    defaultExportDirectory: File?,
    report: RunComparisonReport?,
    onToggleSession: (String) -> Unit,
    onSelectAlignment: (String) -> Unit,
    onOpenEvidence: (String, Long) -> Unit,
    onCreateExperiment: (GuidedComparisonFinding) -> Unit,
    onExport: (File) -> Unit,
) {
    var runMenuExpanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("4 · Compare two or more runs", color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Choose compatible runs, align one shared event, then inspect synchronized evidence. The selected run remains the primary reference.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
            }
            val candidates = sessions.filter { it.sessionId != primarySessionId }
            if (candidates.isEmpty()) {
                Text("Import another run from this robot to enable comparison.", color = AresTextSecondary)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.filter { it.sessionId in comparisonSessionIds }.forEach { session ->
                        FilterChip(
                            selected = true,
                            onClick = { onToggleSession(session.sessionId) },
                            label = { Text(session.comparisonLabel()) },
                            leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AresCyan.copy(alpha = 0.18f),
                                selectedLabelColor = AresTextPrimary,
                            ),
                        )
                    }
                    Box {
                        OutlinedButton(
                            onClick = { runMenuExpanded = true },
                            enabled = comparisonSessionIds.size < 5 && candidates.any { it.sessionId !in comparisonSessionIds },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Add comparison run")
                        }
                        DropdownMenu(expanded = runMenuExpanded, onDismissRequest = { runMenuExpanded = false }) {
                            candidates.filter { it.sessionId !in comparisonSessionIds }.forEach { session ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(session.comparisonLabel(), color = AresTextPrimary)
                                            Text(session.sessionId.take(12), color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                        }
                                    },
                                    onClick = {
                                        runMenuExpanded = false
                                        onToggleSession(session.sessionId)
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    if (comparisonSessionIds.isEmpty()) "Select at least one comparison run."
                    else "Comparing ${comparisonSessionIds.size + 1} runs · up to 6 runs are supported.",
                    color = if (comparisonSessionIds.isEmpty()) AresAmber else AresTextTertiary,
                    fontSize = 11.sp,
                )
            }

            if (comparing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(21.dp))
                    Text("Aligning persisted timestamps and building evidence…", color = AresTextSecondary)
                }
            }
            comparisonError?.let { message ->
                Surface(
                    color = AresRed.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, AresRed.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Text(message, color = AresTextPrimary, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            report?.let { comparison ->
                HorizontalDivider(color = AresBorder)
                AlignmentPicker(comparison, onSelectAlignment)
                AlignmentAnchors(comparison)
                if (comparison.trajectories.size >= 2) TrajectoryComparison(comparison.trajectories)
                comparison.metrics.forEach { metric -> MetricComparison(metric) }
                FaultComparison(comparison)
                GuidedFindings(comparison.findings, onOpenEvidence, onCreateExperiment)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            chooseComparisonReportFile(comparison.primarySessionId, defaultExportDirectory)?.let(onExport)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export mentor/student report")
                    }
                    comparisonExportMessage?.let { message ->
                        Text(
                            message,
                            color = if (message.startsWith("Saved")) AresGreen else AresTextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
                Surface(
                    color = AresAmber.copy(alpha = 0.07f),
                    border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Evidence boundaries", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        comparison.limitations.forEach { limitation ->
                            Text("• $limitation", color = AresTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignmentPicker(report: RunComparisonReport, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Alignment", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(report.selectedAlignment.label)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                report.availableAlignments.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label, color = AresTextPrimary)
                                Text(option.kind.label, color = AresTextTertiary, fontSize = 10.sp)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.id)
                        },
                    )
                }
            }
        }
        Text(report.selectedAlignment.explanation, color = AresTextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun AlignmentAnchors(report: RunComparisonReport) {
    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(9.dp)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Original timestamps preserved", color = AresGreen, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            report.anchors.forEach { anchor ->
                val label = report.sessions.first { it.sessionId == anchor.sessionId }.comparisonLabel()
                Text("$label · ${anchor.absoluteTimestampMs} ms · ${anchor.label}", color = AresTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TrajectoryComparison(trajectories: List<RunTrajectoryOverlay>) {
    EvidenceCard("Trajectory overlay", "Meters in one shared field coordinate system; timestamps are aligned but X/Y values are not normalized.") {
        val description = trajectories.joinToString { "${it.runLabel}, ${it.points.size} points" }
        Canvas(
            Modifier.fillMaxWidth().height(250.dp).background(AresSurfaceElevated, RoundedCornerShape(8.dp))
                .semantics { contentDescription = "Aligned trajectory comparison: $description" },
        ) {
            val points = trajectories.flatMap(RunTrajectoryOverlay::points)
            if (points.isEmpty()) return@Canvas
            val minX = points.minOf { it.xMeters }
            val maxX = points.maxOf { it.xMeters }
            val minY = points.minOf { it.yMeters }
            val maxY = points.maxOf { it.yMeters }
            val spanX = max(0.01, maxX - minX)
            val spanY = max(0.01, maxY - minY)
            val margin = 18f
            val scale = min((size.width - margin * 2f) / spanX.toFloat(), (size.height - margin * 2f) / spanY.toFloat())
            val usedWidth = spanX.toFloat() * scale
            val usedHeight = spanY.toFloat() * scale
            val offsetX = (size.width - usedWidth) / 2f
            val offsetY = (size.height - usedHeight) / 2f
            trajectories.forEachIndexed { index, run ->
                val path = Path()
                run.points.forEachIndexed { pointIndex, point ->
                    val x = offsetX + ((point.xMeters - minX).toFloat() * scale)
                    val y = size.height - offsetY - ((point.yMeters - minY).toFloat() * scale)
                    if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    color = RUN_COLORS[index % RUN_COLORS.size],
                    style = Stroke(width = 3f, pathEffect = runPathEffect(index)),
                )
            }
        }
        RunLegend(trajectories.map(RunTrajectoryOverlay::runLabel))
    }
}

@Composable
private fun MetricComparison(metric: RunComparisonMetric) {
    EvidenceCard(metric.label, "${metric.explanation} Unit: ${metric.unit}.") {
        val allSamples = metric.series.flatMap { it.samples }
        if (allSamples.isNotEmpty()) {
            Text(
                "Shared range: ${allSamples.minOf { it.value }.formatComparisonUi()} to ${allSamples.maxOf { it.value }.formatComparisonUi()} ${metric.unit} · aligned ${allSamples.minOf { it.alignedTimeMs }} to ${allSamples.maxOf { it.alignedTimeMs }} ms",
                color = AresTextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Canvas(
                Modifier.fillMaxWidth().height(170.dp).background(AresSurfaceElevated, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Aligned ${metric.label} chart in ${metric.unit}" },
            ) {
                val minTime = allSamples.minOf { it.alignedTimeMs }
                val maxTime = allSamples.maxOf { it.alignedTimeMs }
                val minValue = allSamples.minOf { it.value }
                val maxValue = allSamples.maxOf { it.value }
                val timeSpan = max(1L, maxTime - minTime).toDouble()
                val valueSpan = max(1e-9, maxValue - minValue)
                val left = 10f
                val right = size.width - 10f
                val top = 10f
                val bottom = size.height - 10f
                drawLine(AresBorder, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1f)
                drawLine(AresBorder, Offset(left, top), Offset(left, bottom), strokeWidth = 1f)
                metric.series.forEachIndexed { index, series ->
                    val path = Path()
                    series.samples.sortedBy { it.alignedTimeMs }.forEachIndexed { pointIndex, sample ->
                        val x = left + ((sample.alignedTimeMs - minTime) / timeSpan).toFloat() * (right - left)
                        val y = bottom - ((sample.value - minValue) / valueSpan).toFloat() * (bottom - top)
                        if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path,
                        color = RUN_COLORS[index % RUN_COLORS.size],
                        style = Stroke(width = 2.4f, pathEffect = runPathEffect(index)),
                    )
                }
            }
        }
        RunLegend(metric.series.map { it.runLabel })
        metric.series.forEach { series ->
            Text(
                "${series.runLabel}: avg ${series.summary.average.formatComparisonUi()} · p95 ${series.summary.p95.formatComparisonUi()} · ${series.summary.sampleCount} samples",
                color = AresTextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Text("Topics: ${series.sourceTopics.joinToString()}", color = AresTextTertiary, fontSize = 9.sp)
        }
    }
}

@Composable
private fun FaultComparison(report: RunComparisonReport) {
    EvidenceCard("Faults and alerts", "Persisted alert records only; no alert is synthesized from missing data.") {
        report.faults.forEach { fault ->
            Text(
                "${fault.runLabel}: ${fault.alertCount} alert(s)${fault.alertKeys.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ").orEmpty()}",
                color = if (fault.alertCount > 0) AresAmber else AresTextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun GuidedFindings(
    findings: List<GuidedComparisonFinding>,
    onOpenEvidence: (String, Long) -> Unit,
    onCreateExperiment: (GuidedComparisonFinding) -> Unit,
) {
    EvidenceCard("Guided diagnosis", "Every statement is labeled as an observation or correlation; neither is a proven root cause.") {
        if (findings.isEmpty()) {
            Text("No configured material difference was found. This does not prove the runs are equivalent.", color = AresTextSecondary)
        }
        findings.forEach { finding ->
            val accent = when (finding.kind) {
                ComparisonClaimKind.OBSERVATION -> AresGreen
                ComparisonClaimKind.CORRELATION -> AresAmber
                ComparisonClaimKind.LIMITATION -> AresTextSecondary
            }
            Surface(color = AresSurfaceElevated, border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (finding.kind != ComparisonClaimKind.OBSERVATION) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        }
                        Text(finding.kind.label, color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    Text(finding.title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(finding.explanation, color = AresTextSecondary, fontSize = 11.sp)
                    Text(
                        "${finding.evidence.absoluteTimestampMs} ms · aligned ${finding.evidence.alignedTimeMs} ms · ${finding.evidence.topics.joinToString()}",
                        color = AresTextTertiary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenEvidence(finding.evidence.sessionId, finding.evidence.absoluteTimestampMs) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Open replay at evidence")
                        }
                        Button(
                            onClick = { onCreateExperiment(finding) },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Create one-change experiment")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceCard(title: String, explanation: String, content: @Composable () -> Unit) {
    AresCard(backgroundColor = AresSurface, cornerRadius = 10.dp, contentPadding = 12.dp, contentSpacing = 8.dp) {
        Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Text(explanation, color = AresTextSecondary, fontSize = 11.sp)
        content()
    }
}

@Composable
private fun RunLegend(labels: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Canvas(Modifier.width(28.dp).height(10.dp)) {
                    drawLine(
                        RUN_COLORS[index % RUN_COLORS.size],
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        strokeWidth = 3f,
                        pathEffect = runPathEffect(index),
                    )
                }
                Text("${runStyleLabel(index)} · $label", color = AresTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

private fun Session.comparisonLabel(): String = shortRunLabel()

private fun chooseComparisonReportFile(primarySessionId: String, defaultDirectory: File?): File? =
    DesktopFileChoosers.chooseSaveFile(
        initialDirectory = defaultDirectory?.takeIf(File::isDirectory),
        dialogTitle = "Export mentor/student run comparison",
        defaultFileName = "ares-run-comparison-${primarySessionId.take(12)}.md",
        filterDescription = "Markdown report",
        extensions = listOf("md")
    )

private fun Double.formatComparisonUi(): String = "%.3f".format(this)

private fun runPathEffect(index: Int): PathEffect? = when (index % RUN_COLORS.size) {
    0 -> null
    1 -> PathEffect.dashPathEffect(floatArrayOf(14f, 8f))
    2 -> PathEffect.dashPathEffect(floatArrayOf(3f, 6f))
    3 -> PathEffect.dashPathEffect(floatArrayOf(14f, 6f, 3f, 6f))
    4 -> PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
    else -> PathEffect.dashPathEffect(floatArrayOf(2f, 4f, 10f, 4f))
}

private fun runStyleLabel(index: Int): String = when (index % RUN_COLORS.size) {
    0 -> "Solid"
    1 -> "Long dash"
    2 -> "Dot"
    3 -> "Dash-dot"
    4 -> "Short dash"
    else -> "Dot-dash"
}

private val RUN_COLORS = listOf(
    AresCyan,
    AresAmber,
    AresGreen,
    Color(0xFFB39DDB),
    Color(0xFFFF8A80),
    Color(0xFF80CBC4),
)

/**
 * 1-click run comparison presets for students reviewing post-match or practice logs.
 */
enum class StudentRunComparisonPreset(
    val id: String,
    val label: String,
    val helperHint: String,
) {
    LATEST_VS_PREVIOUS(
        id = "latest_vs_previous",
        label = "Latest vs Previous Run",
        helperHint = "Compare your newest run directly against the run recorded right before it.",
    ),
    LATEST_VS_LONGEST_COMPLETED(
        id = "latest_vs_longest_completed",
        label = "Latest vs Longest Completed Run",
        helperHint = "Compare your newest run against the longest full match or practice session in this workspace.",
    ),
    LATEST_VS_SIMULATOR_BASELINE(
        id = "latest_vs_simulator_baseline",
        label = "Latest vs Simulator Baseline",
        helperHint = "Compare your newest run against the most recent local simulator baseline run.",
    ),
}

data class ResolvedRunComparisonPreset(
    val preset: StudentRunComparisonPreset,
    val primarySessionId: String,
    val comparisonSessionIds: List<String>,
    val summaryLabel: String,
)

fun resolveStudentComparisonPreset(
    sessions: List<Session>,
    preset: StudentRunComparisonPreset,
): ResolvedRunComparisonPreset? {
    if (sessions.isEmpty()) return null
    val sortedByTimeDesc = sessions.sortedByDescending { it.createdAt }
    val latest = sortedByTimeDesc.first()
    val candidates = sortedByTimeDesc.drop(1)
    if (candidates.isEmpty()) return null

    val targetComparison: Session = when (preset) {
        StudentRunComparisonPreset.LATEST_VS_PREVIOUS -> candidates.first()
        StudentRunComparisonPreset.LATEST_VS_LONGEST_COMPLETED -> {
            candidates.maxWithOrNull(compareBy<Session> { it.durationMs }.thenBy { it.createdAt }) ?: candidates.first()
        }
        StudentRunComparisonPreset.LATEST_VS_SIMULATOR_BASELINE -> {
            candidates.firstOrNull { it.tags.isSimulationSessionTags() } ?: candidates.first()
        }
    }

    return ResolvedRunComparisonPreset(
        preset = preset,
        primarySessionId = latest.sessionId,
        comparisonSessionIds = listOf(targetComparison.sessionId),
        summaryLabel = "${latest.shortRunLabel()} vs ${targetComparison.shortRunLabel()}",
    )
}

/**
 * Builds a structured FTC/FRC Engineering Notebook markdown entry from a [GuidedRunAnalysisReport]
 * and optional [RunComparisonReport] so students can paste evidence-backed notes with 1 click.
 */
fun formatStudentEngineeringNotebookEntry(
    report: GuidedRunAnalysisReport,
    comparisonReport: RunComparisonReport? = null,
): String = buildString {
    appendLine("# Engineering Notebook Run Review")
    appendLine()
    appendLine("## 1. Run Identity & Evidence Source")
    appendLine("- **Run**: ${report.session.shortRunLabel()} (`${report.session.sessionId}`)")
    appendLine("- **Team / Robot**: Team ${report.session.teamId} · Robot `${report.session.robotId}` (${report.session.seasonId})")
    appendLine("- **Source**: ${report.source.kind.label} — ${report.source.explanation}")
    appendLine("- **Confidence**: ${report.evidenceContext.confidence.label} (${report.evidenceContext.confidenceExplanation})")
    appendLine()
    appendLine("## 2. Key Metrics")
    if (report.metrics.isEmpty()) {
        appendLine("- No summary metrics recorded for this session.")
    } else {
        report.metrics.forEach { metric ->
            appendLine("- **${metric.label}**: ${metric.value} ${metric.unit} _(from `${metric.evidenceSource}`)_")
        }
    }
    appendLine()
    appendLine("## 3. Observed Findings & Hypotheses")
    if (report.findings.isEmpty()) {
        appendLine("- No configured fault threshold was crossed during this run.")
    } else {
        report.findings.forEach { finding ->
            appendLine("### ${finding.title} (${finding.statusText})")
            appendLine("- **Observed Evidence**: ${finding.observedEvidence}")
            if (finding.possibleCauses.isNotEmpty()) {
                appendLine("- **Hypotheses to Test**: ${finding.possibleCauses.joinToString("; ")}")
            }
            if (finding.safeVerificationSteps.isNotEmpty()) {
                appendLine("- **Safe Verification**: ${finding.safeVerificationSteps.joinToString("; ")}")
            }
        }
    }
    appendLine()
    appendLine("## 4. Multi-Run Comparison")
    if (comparisonReport == null || comparisonReport.findings.isEmpty() && comparisonReport.metrics.isEmpty()) {
        appendLine("- Single-run review (no multi-run comparison active).")
    } else {
        appendLine("- **Alignment**: ${comparisonReport.selectedAlignment.label}")
        comparisonReport.findings.forEach { finding ->
            appendLine("- **${finding.title}** (${finding.kind.label}): ${finding.explanation}")
        }
        comparisonReport.metrics.take(4).forEach { metric ->
            val summaryText = metric.series.joinToString(" vs ") { series ->
                "${series.runLabel}: avg ${"%.2f".format(series.summary.average)} ${metric.unit}, P95 ${"%.2f".format(series.summary.p95)} ${metric.unit}"
            }
            appendLine("- **${metric.label}**: $summaryText")
        }
    }
    appendLine()
    appendLine("## 5. Safe Next Action")
    if (report.nextActions.isEmpty()) {
        appendLine("- Review timeline in Dashboard Replay before making hardware or tuning changes.")
    } else {
        report.nextActions.forEach { action ->
            appendLine("- **${action.title}**: ${action.reason}")
        }
    }
}.trimEnd()

fun copyEngineeringNotebookToSystemClipboard(markdown: String): Boolean =
    com.ares.analytics.ui.util.copyTextToSystemClipboard(markdown)

@Composable
fun StudentRunComparisonPresetBar(
    sessions: List<Session>,
    onApplyPreset: (StudentRunComparisonPreset) -> Unit,
) {
    if (sessions.size < 2) return
    com.ares.analytics.ui.components.core.AresCoachBanner(
        badgeText = "COMPARE",
        title = "1-Click Student Comparison Presets",
        subtitle = "Quickly pair your newest run against a reference baseline without manually searching session IDs:",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudentRunComparisonPreset.entries.forEach { preset ->
                val resolved = remember(sessions, preset) { resolveStudentComparisonPreset(sessions, preset) }
                OutlinedButton(
                    onClick = { onApplyPreset(preset) },
                    enabled = resolved != null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Column {
                        Text(preset.label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        resolved?.let { Text(it.summaryLabel, color = AresCyan, fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
fun StudentEngineeringNotebookCopyButton(
    report: GuidedRunAnalysisReport,
    comparisonReport: RunComparisonReport?,
    onCopiedNotice: (String) -> Unit,
) {
    OutlinedButton(
        onClick = {
            val markdown = formatStudentEngineeringNotebookEntry(report, comparisonReport)
            if (copyEngineeringNotebookToSystemClipboard(markdown)) {
                onCopiedNotice("Saved Engineering Notebook Markdown summary to clipboard (${markdown.lines().size} lines).")
            } else {
                onCopiedNotice("Prepared Engineering Notebook summary (${markdown.lines().size} lines) — clipboard unavailable in headless mode.")
            }
        },
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("Copy Engineering Notebook Entry")
    }
}
