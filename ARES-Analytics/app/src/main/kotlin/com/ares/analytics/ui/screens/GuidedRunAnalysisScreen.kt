@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.GuidedRunDestination
import com.ares.analytics.service.GuidedRunFinding
import com.ares.analytics.service.GuidedRunMetric
import com.ares.analytics.service.RunEvidenceSourceKind
import com.ares.analytics.service.shortRunLabel
import com.ares.analytics.service.tuning.GuidedTuningExperimentSeed
import com.ares.analytics.service.tuning.toExperimentSeed
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
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
import com.ares.analytics.ui.components.runanalysis.RunComparisonPanel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** One novice-first evidence path; the existing dashboard and history table remain advanced tools. */
@Composable
fun GuidedRunAnalysisScreen(
    viewModel: GuidedRunAnalysisViewModel,
    onOpenImports: () -> Unit,
    onOpenDashboardReplay: (String, Long?) -> Unit,
    onOpenTuning: () -> Unit,
    onCreateTuningExperiment: (GuidedTuningExperimentSeed) -> Unit,
    onOpenAcademy: () -> Unit,
    onOpenRunHistory: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(AresBackground)) {
        val horizontalPadding = if (maxWidth < 760.dp) 12.dp else 20.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GuidedAnalysisHeader(onOpenAcademy, onOpenImports)
            }
            item {
                GuideStep(1, "Choose one run", "Only runs matching the selected team, season, and robot are listed.") {
                    SessionPicker(
                        sessions = state.sessions,
                        selectedSessionId = state.selectedSessionId,
                        loading = state.loadingSessions,
                        onSelect = viewModel::selectSession,
                        onRefresh = viewModel::refreshSessions,
                    )
                    if (!state.loadingSessions && state.sessions.isEmpty()) {
                        EmptyEvidenceState(onOpenImports, onOpenAcademy)
                    }
                }
            }
            state.error?.let { message ->
                item { AnalysisError(message, viewModel::refreshAnalysis) }
            }
            if (state.analyzing) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Reading persisted evidence without changing the run…", color = AresTextSecondary)
                    }
                }
            }
            state.report?.let { report ->
                item { SourceEvidenceStep(report) }
                item { TimelineStep(report) { onOpenDashboardReplay(report.session.sessionId, null) } }
                item {
                    RunComparisonPanel(
                        sessions = state.sessions,
                        primarySessionId = state.selectedSessionId,
                        comparisonSessionIds = state.comparisonSessionIds,
                        comparing = state.comparing,
                        comparisonError = state.comparisonError,
                        comparisonExportMessage = state.comparisonExportMessage,
                        defaultExportDirectory = state.comparisonExportDirectory?.let(::File),
                        report = state.comparisonReport,
                        onToggleSession = viewModel::toggleComparisonSession,
                        onSelectAlignment = viewModel::selectAlignment,
                        onOpenEvidence = { sessionId, timestampMs -> onOpenDashboardReplay(sessionId, timestampMs) },
                        onCreateExperiment = { finding ->
                            state.comparisonReport?.let { comparison ->
                                onCreateTuningExperiment(finding.toExperimentSeed(comparison))
                            }
                        },
                        onExport = viewModel::exportComparison,
                    )
                }
                item {
                    FindingsStep(report.findings, report.missingSignals) { timestampMs ->
                        onOpenDashboardReplay(report.session.sessionId, timestampMs)
                    }
                }
                item {
                    NextActionsStep(report) { destination ->
                        when (destination) {
                            GuidedRunDestination.IMPORTS -> onOpenImports()
                            GuidedRunDestination.DASHBOARD_REPLAY -> onOpenDashboardReplay(report.session.sessionId, null)
                            GuidedRunDestination.TUNING -> onOpenTuning()
                            GuidedRunDestination.ACADEMY -> onOpenAcademy()
                            GuidedRunDestination.RUN_HISTORY -> onOpenRunHistory()
                        }
                    }
                }
                item {
                    PreserveEvidenceStep(
                        report = report,
                        message = state.exportMessage,
                        onExport = { destination -> viewModel.export(destination) },
                        onOpenRunHistory = onOpenRunHistory,
                    )
                }
                item { LimitationsCard(report.limitations) }
            }
        }
    }
}

@Composable
private fun GuidedAnalysisHeader(onOpenAcademy: () -> Unit, onOpenImports: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Analytics, contentDescription = null, tint = AresCyan, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("Guided Run Review", color = AresTextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("Start with evidence, separate possible causes, and choose one safe next action.", color = AresTextSecondary)
                }
            }
            HorizontalDivider(color = AresBorder)
            Text(
                "Workflow: choose a run → identify its source → inspect timestamps and alerts → compare compatible runs → review hypotheses → preserve the evidence.",
                color = AresTextPrimary,
                lineHeight = 20.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenAcademy) {
                    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Learn evidence review")
                }
                OutlinedButton(onClick = onOpenImports) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import a run")
                }
            }
        }
    }
}

@Composable
private fun GuideStep(number: Int, title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = AresCyan.copy(alpha = 0.12f), shape = RoundedCornerShape(20.dp)) {
                    Text(number.toString(), color = AresTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Column(Modifier.weight(1f).semantics { heading() }) {
                    Text(title, color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            content()
        }
    }
}

@Composable
private fun SessionPicker(
    sessions: List<Session>,
    selectedSessionId: String?,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.sessionId == selectedSessionId }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = sessions.isNotEmpty() && !loading) {
                Text(selected?.displayLabel() ?: if (loading) "Loading runs…" else "Select a run", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(session.displayLabel(), color = AresTextPrimary)
                                Text(session.sessionId.take(12), color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(session.sessionId)
                        },
                    )
                }
            }
        }
        OutlinedButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Refresh runs")
        }
    }
}

@Composable
private fun EmptyEvidenceState(onOpenImports: () -> Unit, onOpenAcademy: () -> Unit) {
    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(9.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No matching runs", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text("Import a simulator log for this team, season, and robot. No physical robot is required.", color = AresTextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenImports, colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)) { Text("Open Log Imports") }
                OutlinedButton(onClick = onOpenAcademy) { Text("Show me how") }
            }
        }
    }
}

@Composable
private fun SourceEvidenceStep(report: GuidedRunAnalysisReport) {
    GuideStep(2, "Identify the data source", "The source record tells you what was captured and which claims remain limited.") {
        val source = report.source
        val complete = source.kind == RunEvidenceSourceKind.IMPORTED_FILE || source.kind == RunEvidenceSourceKind.WORKSPACE_DRIVE_OBJECT
        StatusLine(if (complete) "Source identity preserved" else "Source identity incomplete", complete)
        Text(source.kind.label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
        Text(source.explanation, color = AresTextSecondary, lineHeight = 19.sp)
        source.sourceName?.let { KeyValue("File or Drive object", it) }
        source.decoder?.let { KeyValue("Decoder", it) }
        source.sha256?.let { KeyValue("SHA-256", it) }
        KeyValue("Freshness", report.evidenceContext.freshnessStatus)
        KeyValue(
            "Interpretation confidence",
            "${report.evidenceContext.confidence.label}: ${report.evidenceContext.confidenceExplanation}",
        )
        source.acceptedRecords?.let { KeyValue("Accepted records", it.toString()) }
        source.rejectedRecords?.let { KeyValue("Rejected records", it.toString()) }
        source.warnings.forEach { WarningLine(it) }
    }
}

@Composable
private fun TimelineStep(report: GuidedRunAnalysisReport, onOpenReplay: () -> Unit) {
    GuideStep(3, "Inspect timestamps, units, and alerts", "These are persisted observations; replay provides the exact time context.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            report.metrics.forEach { metric -> MetricChip(metric) }
        }
        if (report.alerts.isEmpty()) {
            Text("No persisted alerts. That is not proof that no fault occurred.", color = AresTextSecondary)
        } else {
            Text("${report.alerts.size} persisted alert event(s)", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            report.alerts.take(5).forEach { alert ->
                Text("${alert.ruleKey} at ${alert.triggerTimestampMs} ms · peak ${alert.peakValue}", color = AresTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            if (report.alerts.size > 5) Text("${report.alerts.size - 5} more alert(s) are available in replay.", color = AresTextTertiary)
        }
        Button(onClick = onOpenReplay, colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)) {
            Icon(Icons.Default.Timeline, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open exact timeline")
        }
    }
}

@Composable
private fun MetricChip(metric: GuidedRunMetric) {
    Surface(color = AresSurfaceElevated, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metric.label, color = AresTextSecondary, fontSize = 11.sp)
            Text("${metric.value} ${metric.unit}", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(metric.evidenceSource, color = AresTextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FindingsStep(
    findings: List<GuidedRunFinding>,
    missingSignals: List<String>,
    onOpenEvidence: (Long) -> Unit,
) {
    GuideStep(5, "Separate evidence from possible causes", "ARES reports thresholds and hypotheses separately; correlation is not a diagnosis.") {
        if (findings.isEmpty()) {
            StatusLine("No configured threshold was crossed", true)
            Text("This result is not a health or safety verdict.", color = AresTextSecondary)
        }
        findings.forEach { finding -> FindingCard(finding, onOpenEvidence) }
        if (missingSignals.isNotEmpty()) {
            WarningLine("Missing from this review: ${missingSignals.joinToString()}. Missing data cannot be treated as a normal measurement.")
        }
    }
}

@Composable
private fun FindingCard(finding: GuidedRunFinding, onOpenEvidence: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(finding.title, color = AresTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(finding.statusText, color = AresAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Text("Observed evidence", color = AresGreen, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            finding.timestampSeconds?.let {
                Text("Recorded timestamp: ${"%.3f".format(it)} s", color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            if (finding.sourceTopics.isNotEmpty()) {
                Text("Topics: ${finding.sourceTopics.joinToString()}", color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Text(finding.observedEvidence, color = AresTextPrimary)
            Text("Interpretation limit", color = AresAmber, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text(finding.interpretationLimit, color = AresTextSecondary)
            Text("Possible causes to verify", color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            finding.possibleCauses.forEach { Text("• $it", color = AresTextSecondary, fontSize = 12.sp) }
            Text("Safe verification", color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            finding.safeVerificationSteps.forEach { Text("• $it", color = AresTextSecondary, fontSize = 12.sp) }
            finding.absoluteTimestampMs?.let { timestampMs ->
                OutlinedButton(onClick = { onOpenEvidence(timestampMs) }) {
                    Icon(Icons.Default.Timeline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Open replay at evidence")
                }
            }
        }
    }
}

@Composable
private fun NextActionsStep(report: GuidedRunAnalysisReport, onOpen: (GuidedRunDestination) -> Unit) {
    GuideStep(6, "Choose one safe next action", "Actions open existing tools; this screen never writes tuning, source, or hardware output.") {
        report.nextActions.forEach { action ->
            Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(action.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(action.reason, color = AresTextSecondary, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onOpen(action.destination) }) {
                        Text("Open")
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreserveEvidenceStep(
    report: GuidedRunAnalysisReport,
    message: String?,
    onExport: (File) -> Unit,
    onOpenRunHistory: () -> Unit,
) {
    GuideStep(7, "Preserve the review", "Export a checked Markdown summary while keeping the original database session unchanged.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { chooseGuidedReviewFile(report.session.sessionId)?.let(onExport) },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export evidence report")
            }
            OutlinedButton(onClick = onOpenRunHistory) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open advanced Run History")
            }
        }
        message?.let {
            val messageColor = when {
                it.startsWith("Saved") -> AresGreen
                it.startsWith("Saving") -> AresTextSecondary
                else -> AresRed
            }
            Text(it, color = messageColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LimitationsCard(limitations: List<String>) {
    Surface(color = AresAmber.copy(alpha = 0.08f), border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.65f)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Evidence boundaries", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            limitations.forEach { Text("• $it", color = AresTextSecondary, fontSize = 12.sp, lineHeight = 18.sp) }
        }
    }
}

@Composable
private fun StatusLine(text: String, positive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(
            if (positive) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (positive) AresGreen else AresAmber,
            modifier = Modifier.size(18.dp),
        )
        Text(text, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber, modifier = Modifier.size(17.dp))
        Text(text, color = AresTextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = AresTextTertiary, fontSize = 10.sp)
        Text(value, color = AresTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun AnalysisError(message: String, onRetry: () -> Unit) {
    Surface(color = AresRed.copy(alpha = 0.10f), border = BorderStroke(1.dp, AresRed.copy(alpha = 0.7f)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Default.Error, contentDescription = null, tint = AresRed)
            Column(Modifier.weight(1f)) {
                Text("Guided review is unavailable", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(message, color = AresTextSecondary)
            }
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

private fun Session.displayLabel(): String {
    val match = shortRunLabel()
    val time = if (createdAt >= 946_684_800_000L) {
        DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt))
    } else {
        "source time $createdAt ms"
    }
    return "$match · $time"
}

private fun chooseGuidedReviewFile(sessionId: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Export guided run evidence"
        selectedFile = File("ares-guided-review-${sessionId.take(24)}.md")
        fileFilter = FileNameExtensionFilter("Markdown evidence report", "md")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    return if (selected.extension.equals("md", ignoreCase = true)) selected else File(selected.parentFile, "${selected.name}.md")
}
