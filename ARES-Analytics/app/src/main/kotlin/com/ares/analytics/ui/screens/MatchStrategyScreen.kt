package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.analysis.*
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Legacy coaching card value type retained for KDoc and external compatibility.
 */
data class DriverCoachingCard(
    val title: String,
    val detail: String,
    val category: String,
    val severity: String,
)

private data class LoadedMatchStrategySnapshot(
    val session: Session,
    val summary: SessionSummary?,
    val sessionStartMs: Long,
    val heatmap: SpatialFieldHeatmap,
    val insights: List<MatchCoachingInsight>,
    val alerts: List<AlertRecord>,
    val annotations: List<SessionAnnotation>,
)

/**
 * Session-backed Match Strategy, Spatial Field Heatmap, Multi-Match Event Progression, and Pit Debrief screen.
 *
 * Replaces the former developer-preview sample layout with deterministic analysis backed by DuckDB
 * `Session`, `SessionSummary`, `ReplayPoseRepository`, `TelemetryFrame`, `AlertRecord`, and `SessionAnnotation` records.
 */
@Composable
fun MatchStrategyScreen(
    databaseService: DatabaseService? = null,
    exportService: ExportService? = null,
    notebookCaptureService: com.ares.analytics.service.integration.EngineeringNotebookCaptureService? = null,
    workspace: WorkspaceConfig? = null,
    reloadTrigger: Int = 0,
    matches: List<MatchInfo> = emptyList(),
    onOpenReplay: (String, Long?) -> Unit = { _, _ -> },
    onOpenImports: () -> Unit = {},
    onOpenNotebookEntry: ((String) -> Unit)? = null,
    onOpenPitOps: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val league = workspace?.league ?: League.FTC
    val resolvedExportService = remember(exportService, databaseService) {
        exportService ?: databaseService?.let(::ExportService)
    }

    var manualRefreshNonce by remember { mutableStateOf(0) }
    var selectedSessionId by remember(workspace?.id) { mutableStateOf<String?>(null) }
    var heatmapMode by remember { mutableStateOf(StrategyHeatmapMode.OCCUPANCY) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var lastLoggedNotebookEntryId by remember { mutableStateOf<String?>(null) }

    val workspaceSessionsAndSummaries by produceState<Pair<List<Session>, List<SessionSummary>>>(
        initialValue = emptyList<Session>() to emptyList(),
        databaseService,
        workspace?.teamId,
        workspace?.seasonId,
        workspace?.robotId,
        reloadTrigger,
        manualRefreshNonce,
    ) {
        if (databaseService == null) {
            value = emptyList<Session>() to emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val sessions = if (workspace != null && workspace.teamId.isNotBlank()) {
                databaseService.getSessionsForWorkspace(workspace.teamId, workspace.seasonId, workspace.robotId)
                    .ifEmpty { databaseService.getSessions() }
            } else {
                databaseService.getSessions()
            }.sortedByDescending { it.createdAt }

            val sessionIds = sessions.mapTo(HashSet()) { it.sessionId }
            val summaries = databaseService.getAllSessionSummaries()
                .filter { it.sessionId in sessionIds }
            sessions to summaries
        }
    }

    val sessions = workspaceSessionsAndSummaries.first
    val summaries = workspaceSessionsAndSummaries.second

    LaunchedEffect(sessions) {
        if (sessions.isNotEmpty() && (selectedSessionId == null || sessions.none { it.sessionId == selectedSessionId })) {
            selectedSessionId = sessions.first().sessionId
        } else if (sessions.isEmpty()) {
            selectedSessionId = null
        }
    }

    val progressionSeries = remember(sessions, summaries) {
        MatchStrategyAnalyzer.buildEventProgressionSummary(sessions, summaries)
    }

    val activeSnapshot by produceState<LoadedMatchStrategySnapshot?>(
        initialValue = null,
        databaseService,
        selectedSessionId,
        league,
        reloadTrigger,
        manualRefreshNonce,
    ) {
        val db = databaseService
        val sessionId = selectedSessionId
        val session = sessions.firstOrNull { it.sessionId == sessionId }
        if (db == null || sessionId.isNullOrBlank() || session == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val summary = db.getSessionSummary(sessionId)
            val range = db.getSessionTimestampRange(sessionId)
            val startMs = range?.first ?: 0L
            val endMs = range?.second ?: (startMs + session.durationMs.coerceAtLeast(1L))
            val poseSamples = if (range != null && endMs >= startMs) {
                runCatching {
                    db.getReplayPoseTrace(sessionId, startMs, endMs, maxPoints = 1_200)
                        .map { StrategyPosePoint(it.x, it.y, it.headingRadians, it.timestampMs) }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val telemetryFrames = runCatching {
                db.getTelemetryForKeyPatterns(sessionId, MatchStrategyAnalyzer.STRATEGY_TELEMETRY_KEY_PATTERNS)
            }.getOrDefault(emptyList())
            val alerts = runCatching { db.getAlerts(sessionId) }.getOrDefault(emptyList())
            val annotations = runCatching { db.getAnnotations(sessionId) }.getOrDefault(emptyList())
            val actions = runCatching { db.getActionsForSession(sessionId) }.getOrDefault(emptyList())

            val heatmap = MatchStrategyAnalyzer.computeSpatialFieldHeatmap(
                league = league,
                poses = poseSamples,
                telemetryFrames = telemetryFrames,
            )
            val insights = MatchStrategyAnalyzer.analyzeMatchCoachingInsights(
                league = league,
                summary = summary,
                telemetryFrames = telemetryFrames,
                alerts = alerts,
                actions = actions,
            )
            LoadedMatchStrategySnapshot(
                session = session,
                summary = summary,
                sessionStartMs = startMs,
                heatmap = heatmap,
                insights = insights,
                alerts = alerts,
                annotations = annotations,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AresBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header + Session Selector + Debrief Export Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = AresCyan, modifier = Modifier.size(26.dp))
                        Column {
                            Text(
                                text = "Match Strategy & Pit Debrief",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = AresTextPrimary,
                            )
                            Text(
                                text = "Session-backed spatial field heatmaps, multi-match trends, and deterministic replay-linked coaching",
                                fontSize = 12.sp,
                                color = AresTextSecondary,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (sessions.isNotEmpty()) {
                            SessionSelectorDropdown(
                                sessions = sessions,
                                selectedSessionId = selectedSessionId,
                                onSelect = { selectedSessionId = it },
                            )
                        }

                        val snap = activeSnapshot
                        if (snap != null && resolvedExportService != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val md = MatchDebriefReportBuilder.buildSessionMarkdownDebrief(
                                            league = league,
                                            session = snap.session,
                                            summary = snap.summary,
                                            insights = snap.insights,
                                            heatmap = snap.heatmap,
                                            alerts = snap.alerts,
                                            annotations = snap.annotations,
                                            sessionStartMs = snap.sessionStartMs,
                                        )
                                        val dest = withContext(Dispatchers.IO) {
                                            DesktopFileChoosers.chooseSaveFile(
                                                dialogTitle = "Export Match Debrief (.md)",
                                                defaultFileName = "pit_debrief_${snap.session.sessionId.take(12)}.md",
                                                filterDescription = "Markdown Report (*.md)",
                                                extensions = listOf("md"),
                                            )
                                        } ?: return@launch
                                        runCatching { resolvedExportService.writeTextReportAtomically(dest, md) }
                                            .onSuccess { statusBanner = "Saved Markdown debrief: ${dest.name}" }
                                            .onFailure { statusBanner = "Export failed: ${it.message}" }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.65f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = AresCyan, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export .md", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AresCyan)
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val html = MatchDebriefReportBuilder.buildSessionHtmlDebrief(
                                            league = league,
                                            session = snap.session,
                                            summary = snap.summary,
                                            insights = snap.insights,
                                            heatmap = snap.heatmap,
                                            alerts = snap.alerts,
                                            annotations = snap.annotations,
                                            sessionStartMs = snap.sessionStartMs,
                                        )
                                        val dest = withContext(Dispatchers.IO) {
                                            DesktopFileChoosers.chooseSaveFile(
                                                dialogTitle = "Export Match Debrief (.html)",
                                                defaultFileName = "pit_debrief_${snap.session.sessionId.take(12)}.html",
                                                filterDescription = "HTML Report (*.html)",
                                                extensions = listOf("html"),
                                            )
                                        } ?: return@launch
                                        runCatching { resolvedExportService.writeTextReportAtomically(dest, html) }
                                            .onSuccess { statusBanner = "Saved HTML debrief: ${dest.name}" }
                                            .onFailure { statusBanner = "Export failed: ${it.message}" }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.65f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Default.Code, contentDescription = null, tint = AresGreen, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export .html", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AresGreen)
                            }

                            OutlinedButton(
                                onClick = {
                                    val md = MatchDebriefReportBuilder.buildSessionMarkdownDebrief(
                                        league = league,
                                        session = snap.session,
                                        summary = snap.summary,
                                        insights = snap.insights,
                                        heatmap = snap.heatmap,
                                        alerts = snap.alerts,
                                        annotations = snap.annotations,
                                        sessionStartMs = snap.sessionStartMs,
                                    )
                                    runCatching {
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(md), null)
                                    }
                                    statusBanner = "Copied Markdown debrief to clipboard."
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AresBorder),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AresTextSecondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", fontSize = 11.sp, color = AresTextPrimary)
                            }

                            if (workspace != null && notebookCaptureService != null) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                notebookCaptureService.captureMatchDebrief(
                                                    workspace = workspace,
                                                    session = snap.session,
                                                    summary = snap.summary,
                                                    insights = snap.insights,
                                                    heatmap = snap.heatmap,
                                                    alerts = snap.alerts,
                                                    annotations = snap.annotations,
                                                )
                                            }.onSuccess { saved ->
                                                lastLoggedNotebookEntryId = saved.entryId
                                                statusBanner = "Logged ${saved.entryId} r${saved.revision} to Engineering Notebook."
                                            }.onFailure { err ->
                                                statusBanner = "Notebook capture failed: ${err.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Log to Notebook", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        IconButton(onClick = { manualRefreshNonce += 1 }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh strategy analysis", tint = AresTextSecondary)
                        }
                    }
                }

                statusBanner?.let { msg ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(msg, fontSize = 11.sp, color = AresGreen, fontWeight = FontWeight.SemiBold)
                        val entryId = lastLoggedNotebookEntryId
                        if (entryId != null && onOpenNotebookEntry != null) {
                            OutlinedButton(
                                onClick = { onOpenNotebookEntry(entryId) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text("Open in Notebook", fontSize = 10.sp, color = AresCyan)
                            }
                        }
                    }
                }
            }
        }

        if (sessions.isEmpty()) {
            if (matches.isNotEmpty()) {
                TournamentScheduleContextCard(
                    matches = matches,
                    teamId = workspace?.teamId.orEmpty(),
                    sessions = sessions,
                    selectedSession = null,
                    onOpenPitOps = onOpenPitOps,
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null, tint = AresCyan, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No Recorded Match or Practice Sessions Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AresTextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Import a robot log (.wpilog, .hoot, .dslog, .parquet, .csv) or record a live NT4/simulator run to inspect spatial heatmaps, multi-match trends, and replay-linked coaching.",
                        fontSize = 12.sp,
                        color = AresTextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenImports,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Text("Open Import Center", color = AresOnAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@Column
        }

        val snap = activeSnapshot
        if (matches.isNotEmpty()) {
            TournamentScheduleContextCard(
                matches = matches,
                teamId = workspace?.teamId.orEmpty(),
                sessions = sessions,
                selectedSession = snap?.session,
                onOpenPitOps = onOpenPitOps,
            )
        }

        EventProgressionStrip(
            seriesList = progressionSeries,
            selectedSessionId = selectedSessionId,
            onSelectSession = { selectedSessionId = it },
        )

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SpatialFieldHeatmapCard(
                heatmap = snap?.heatmap,
                mode = heatmapMode,
                onModeChange = { heatmapMode = it },
                modifier = Modifier.weight(1.22f).fillMaxHeight(),
            )

            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = AresGold, modifier = Modifier.size(18.dp))
                            Text(
                                text = "EVIDENCE-BACKED COACHING & PIT NOTES",
                                style = MaterialTheme.typography.labelMedium,
                                color = AresGold,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (snap != null) {
                            TextButton(onClick = { onOpenReplay(snap.session.sessionId, null) }) {
                                Text("Open Full Replay →", fontSize = 11.sp, color = AresCyan, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    if (snap == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(snap.insights, key = MatchCoachingInsight::id) { insight ->
                                CoachingInsightCardItem(
                                    insight = insight,
                                    sessionStartMs = snap.sessionStartMs,
                                    onJumpToReplay = { ts -> onOpenReplay(snap.session.sessionId, ts) },
                                )
                            }

                            if (snap.annotations.isNotEmpty()) {
                                item(key = "pinned-notes-header") {
                                    Text(
                                        text = "PINNED REPLAY NOTES (${snap.annotations.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AresTextSecondary,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                                items(snap.annotations, key = SessionAnnotation::annotationId) { note ->
                                    val relSec = ((note.effectiveTimestampMs() - snap.sessionStartMs).coerceAtLeast(0L)) / 1000.0
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = AresSurfaceElevated,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, AresGold.copy(alpha = 0.45f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = String.format(Locale.US, "◆ +%.3fs", relSec),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = AresGold,
                                                )
                                                Text(note.text, fontSize = 12.sp, color = AresTextPrimary)
                                            }
                                            TextButton(onClick = { onOpenReplay(snap.session.sessionId, note.effectiveTimestampMs()) }) {
                                                Text("Seek", fontSize = 11.sp, color = AresCyan)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSelectorDropdown(
    sessions: List<Session>,
    selectedSessionId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = sessions.firstOrNull { it.sessionId == selectedSessionId } ?: sessions.first()

    fun formatSessionLabel(s: Session): String {
        val matchPrefix = s.matchNumber?.let { "Match #$it · " }.orEmpty()
        val alliance = s.allianceColor?.takeIf(String::isNotBlank)?.uppercase(Locale.US)?.let { "[$it] " }.orEmpty()
        val durSec = s.durationMs / 1000
        return "$matchPrefix$alliance${s.robotId.ifBlank { s.sessionId.take(10) }} (${durSec}s)"
    }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(formatSessionLabel(active), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AresTextPrimary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AresTextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sessions.take(24).forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(formatSessionLabel(candidate), color = AresTextPrimary, fontSize = 12.sp) },
                    onClick = {
                        onSelect(candidate.sessionId)
                        expanded = false
                    },
                )
            }
        }
    }
}
