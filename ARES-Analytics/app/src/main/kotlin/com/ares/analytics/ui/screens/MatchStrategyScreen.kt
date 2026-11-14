package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.analysis.*
import com.ares.analytics.domain.navigation.MatchStrategySubTab
import com.ares.analytics.domain.navigation.StrategyDeepLinkRequest
import com.ares.analytics.domain.strategy.*
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.EventApiService
import com.ares.analytics.service.EventScoutingStore
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.service.toMatchInfo
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.components.strategy.LoadedMatchStrategySnapshot
import com.ares.analytics.ui.components.strategy.MatchStrategySessionDebriefTab
import com.ares.analytics.ui.components.strategy.StrategyAlliancePicklistTab
import com.ares.analytics.ui.components.strategy.StrategyMatchupBriefingTab
import com.ares.analytics.ui.components.strategy.StrategyOprAndScoutingTab
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Legacy coaching card value type retained for KDoc and external compatibility. */
data class DriverCoachingCard(
    val title: String,
    val detail: String,
    val category: String,
    val severity: String,
)

/**
 * 4-Tab Competition Strategy Hub inside Studio:
 * `0: Session Debrief`, `1: OPR & Scouting`, `2: Matchup Briefing`, and `3: Alliance Picklist`.
 */
@Composable
fun MatchStrategyScreen(
    databaseService: DatabaseService? = null,
    exportService: ExportService? = null,
    eventApiService: EventApiService? = null,
    eventScoutingStore: EventScoutingStore? = null,
    notebookCaptureService: EngineeringNotebookCaptureService? = null,
    workspace: WorkspaceConfig? = null,
    reloadTrigger: Int = 0,
    matches: List<MatchInfo> = emptyList(),
    strategyDeepLink: StrategyDeepLinkRequest = StrategyDeepLinkRequest(),
    onMatchesUpdated: (List<MatchInfo>) -> Unit = {},
    onOpenReplay: (String, Long?) -> Unit = { _, _ -> },
    onOpenImports: () -> Unit = {},
    onOpenNotebookEntry: ((String) -> Unit)? = null,
    onOpenPitOps: (() -> Unit)? = null,
    onSubContextChanged: ((String?) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val league = workspace?.league ?: League.FTC
    val resolvedExportService = remember(exportService, databaseService) {
        exportService ?: databaseService?.let(::ExportService)
    }

    var activeTab by remember { mutableStateOf(strategyDeepLink.tab) }
    var highlightedTeamNumber by remember(strategyDeepLink.nonce) {
        mutableStateOf(strategyDeepLink.highlightedTeamNumber?.let(::normalizeTeamNumber)?.takeIf(String::isNotBlank))
    }
    var selectedBriefingMatch by remember(strategyDeepLink.nonce) {
        mutableStateOf(strategyDeepLink.highlightedMatchNumber)
    }
    LaunchedEffect(strategyDeepLink.nonce) {
        if (strategyDeepLink.nonce > 0) {
            activeTab = strategyDeepLink.tab
        }
    }

    var manualRefreshNonce by remember { mutableStateOf(0) }
    var scoutingRefreshNonce by remember { mutableStateOf(0) }
    var syncInProgress by remember { mutableStateOf(false) }
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
            val summaries = databaseService.getAllSessionSummaries().filter { it.sessionId in sessionIds }
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

    LaunchedEffect(activeTab, selectedSessionId, sessions, highlightedTeamNumber, selectedBriefingMatch) {
        val label = when (activeTab) {
            MatchStrategySubTab.SESSION_DEBRIEF -> {
                val selected = sessions.firstOrNull { it.sessionId == selectedSessionId }
                selected?.let { s -> s.matchNumber?.let { "Match #$it" } ?: "Run ${s.sessionId.take(8)}" }
            }
            MatchStrategySubTab.OPR_SCOUTING -> highlightedTeamNumber?.let { "OPR & Scouting · Team #$it" }
                ?: MatchStrategySubTab.OPR_SCOUTING.label
            MatchStrategySubTab.MATCHUP_BRIEFING -> selectedBriefingMatch?.let { "Matchup Briefing · Match #$it" }
                ?: MatchStrategySubTab.MATCHUP_BRIEFING.label
            MatchStrategySubTab.ALLIANCE_PICKLIST -> MatchStrategySubTab.ALLIANCE_PICKLIST.label
        }
        onSubContextChanged?.invoke(label)
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
            val heatmap = MatchStrategyAnalyzer.computeSpatialFieldHeatmap(league, poseSamples, telemetryFrames)
            val insights = MatchStrategyAnalyzer.analyzeMatchCoachingInsights(league, summary, telemetryFrames, alerts, actions)
            LoadedMatchStrategySnapshot(session, summary, startMs, heatmap, insights, alerts, annotations)
        }
    }

    var scoutingBundle by remember(workspace?.id, workspace?.eventCode) {
        mutableStateOf(EventScoutingBundle(eventCode = workspace?.eventCode.orEmpty(), league = league))
    }
    LaunchedEffect(eventScoutingStore, workspace?.id, workspace?.eventCode, scoutingRefreshNonce, manualRefreshNonce) {
        val store = eventScoutingStore
        val ws = workspace
        if (store != null && ws != null) {
            scoutingBundle = store.loadBundle(ws)
        }
    }

    val oprSnapshot = remember(league, scoutingBundle.matchResults) {
        OprAndSynergyAnalyzer.solveOprMetrics(league, scoutingBundle.matchResults)
    }
    val standSummaries = remember(scoutingBundle.standObservations) {
        OprAndSynergyAnalyzer.summarizeStandObservations(scoutingBundle.standObservations)
    }
    val pitByTeam = remember(scoutingBundle.pitProfiles) {
        scoutingBundle.pitProfiles.associateBy { normalizeTeamNumber(it.teamNumber) }
    }
    val overridesByTeam = remember(scoutingBundle.picklistOverrides) {
        scoutingBundle.picklistOverrides.associateBy { normalizeTeamNumber(it.teamNumber) }
    }
    val scheduledTeams = remember(matches) {
        matches.flatMap { it.redAlliance + it.blueAlliance }.map(::normalizeTeamNumber).filter(String::isNotBlank).toSet()
    }
    val ourTeam = workspace?.teamId?.let(::normalizeTeamNumber).orEmpty()
    val rankedPicklist = remember(
        ourTeam,
        pitByTeam,
        oprSnapshot,
        standSummaries,
        overridesByTeam,
        scoutingBundle.selectedPreset,
        scheduledTeams,
    ) {
        OprAndSynergyAnalyzer.rankAlliancePicklist(
            ourTeamNumber = ourTeam,
            ourPitProfile = pitByTeam[ourTeam],
            oprSnapshot = oprSnapshot,
            pitProfiles = pitByTeam,
            standSummaries = standSummaries,
            overrides = overridesByTeam,
            preset = scoutingBundle.selectedPreset,
            scheduledTeams = scheduledTeams,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AresBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = AresCyan, modifier = Modifier.size(26.dp))
                        Column {
                            Text(
                                text = "Match Strategy & Competition Hub",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = AresTextPrimary,
                            )
                            Text(
                                text = "Session heatmaps, OPR/NP-OPR/ccWM analytics, pre-match briefings & live alliance picklists",
                                fontSize = 12.sp,
                                color = AresTextSecondary,
                            )
                        }
                    }

                    IconButton(onClick = { manualRefreshNonce += 1 }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh strategy analysis", tint = AresTextSecondary)
                    }
                }

                PrimaryTabRow(
                    selectedTabIndex = activeTab.index,
                    containerColor = AresSurfaceElevated,
                    contentColor = AresCyan,
                ) {
                    MatchStrategySubTab.entries.forEach { tab ->
                        Tab(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            text = {
                                Text(
                                    text = tab.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Medium,
                                )
                            },
                        )
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

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (activeTab) {
                MatchStrategySubTab.SESSION_DEBRIEF -> MatchStrategySessionDebriefTab(
                    league = league,
                    workspace = workspace,
                    sessions = sessions,
                    selectedSessionId = selectedSessionId,
                    onSelectSession = { selectedSessionId = it },
                    activeSnapshot = activeSnapshot,
                    progressionSeries = progressionSeries,
                    heatmapMode = heatmapMode,
                    onHeatmapModeChange = { heatmapMode = it },
                    matches = matches,
                    exportService = resolvedExportService,
                    notebookCaptureService = notebookCaptureService,
                    onOpenReplay = onOpenReplay,
                    onOpenImports = onOpenImports,
                    onOpenPitOps = onOpenPitOps,
                    onStatusMessage = { statusBanner = it },
                    onLoggedNotebookEntry = { lastLoggedNotebookEntryId = it },
                )

                MatchStrategySubTab.OPR_SCOUTING -> StrategyOprAndScoutingTab(
                    workspace = workspace,
                    bundle = scoutingBundle,
                    oprSnapshot = oprSnapshot,
                    standSummaries = standSummaries,
                    rankedRows = rankedPicklist,
                    highlightedTeamNumber = highlightedTeamNumber,
                    syncInProgress = syncInProgress,
                    onSyncEventScores = {
                        val api = eventApiService
                        val store = eventScoutingStore
                        val ws = workspace
                        if (api == null || store == null || ws == null) {
                            statusBanner = "Configure Workspace Event Code and API Key in Settings first."
                        } else {
                            scope.launch {
                                syncInProgress = true
                                runCatching { api.fetchWorkspaceEventMatchResults(ws) }
                                    .onSuccess { fetched ->
                                        if (fetched.isEmpty()) {
                                            statusBanner = "No match scores returned for ${ws.eventCode ?: "event"}; check Event Code / API Key."
                                        } else {
                                            scoutingBundle = store.upsertMatchResults(ws, fetched)
                                            scoutingRefreshNonce += 1
                                            if (matches.isEmpty()) onMatchesUpdated(fetched.map(EventMatchResult::toMatchInfo))
                                            statusBanner = "Synced ${fetched.size} match record(s) into offline OPR store."
                                        }
                                    }
                                    .onFailure { err -> statusBanner = "Score sync failed: ${err.message}" }
                                syncInProgress = false
                            }
                        }
                    },
                    onImportAndMergeBundle = {
                        val store = eventScoutingStore
                        val ws = workspace
                        if (store == null || ws == null) {
                            statusBanner = "Select an active workspace first."
                        } else {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    DesktopFileChoosers.chooseOpenFile(
                                        dialogTitle = "Import / Merge Scouting Bundle (.json / .csv)",
                                        filterDescription = "Scouting Bundle or Scores (*.json, *.csv)",
                                        extensions = listOf("json", "csv"),
                                    )
                                } ?: return@launch
                                runCatching { store.importAndMergeFile(ws, file) }
                                    .onSuccess { (merged, summary) ->
                                        scoutingBundle = merged
                                        scoutingRefreshNonce += 1
                                        if (matches.isEmpty() && merged.matchResults.isNotEmpty()) {
                                            onMatchesUpdated(merged.matchResults.map(EventMatchResult::toMatchInfo))
                                        }
                                        statusBanner = "Merged ${summary.mergedMatches} matches, ${summary.mergedPitProfiles} pit profiles, ${summary.mergedStandObservations} stand logs."
                                    }
                                    .onFailure { err -> statusBanner = "Import failed: ${err.message}" }
                            }
                        }
                    },
                    onExportBundle = {
                        val store = eventScoutingStore
                        val ws = workspace
                        if (store != null && ws != null) {
                            scope.launch {
                                val dest = withContext(Dispatchers.IO) {
                                    DesktopFileChoosers.chooseSaveFile(
                                        dialogTitle = "Export Scouting Bundle (.json)",
                                        defaultFileName = "scouting_bundle_${ws.eventCode?.ifBlank { "event" } ?: "event"}.json",
                                        filterDescription = "JSON Bundle (*.json)",
                                        extensions = listOf("json"),
                                    )
                                } ?: return@launch
                                runCatching { store.exportBundleFile(ws, dest) }
                                    .onSuccess { statusBanner = "Exported scouting bundle: ${dest.name}" }
                                    .onFailure { err -> statusBanner = "Export failed: ${err.message}" }
                            }
                        }
                    },
                    onSavePitProfile = { profile ->
                        val store = eventScoutingStore ?: return@StrategyOprAndScoutingTab
                        val ws = workspace ?: return@StrategyOprAndScoutingTab
                        scope.launch {
                            scoutingBundle = store.upsertPitProfile(ws, profile)
                            scoutingRefreshNonce += 1
                            statusBanner = "Saved pit profile for Team #${profile.teamNumber}."
                        }
                    },
                    onSaveStandObservation = { obs ->
                        val store = eventScoutingStore ?: return@StrategyOprAndScoutingTab
                        val ws = workspace ?: return@StrategyOprAndScoutingTab
                        scope.launch {
                            scoutingBundle = store.upsertStandObservation(ws, obs)
                            scoutingRefreshNonce += 1
                            statusBanner = "Logged stand observation for Team #${obs.teamNumber} Match #${obs.matchNumber}."
                        }
                    },
                    onSaveManualMatchResult = { res ->
                        val store = eventScoutingStore ?: return@StrategyOprAndScoutingTab
                        val ws = workspace ?: return@StrategyOprAndScoutingTab
                        scope.launch {
                            scoutingBundle = store.upsertMatchResults(ws, listOf(res))
                            scoutingRefreshNonce += 1
                            statusBanner = "Recorded score for ${res.compLevel} #${res.matchNumber}."
                        }
                    },
                    onJumpToPicklistTeam = { team ->
                        highlightedTeamNumber = team
                        activeTab = MatchStrategySubTab.ALLIANCE_PICKLIST
                    },
                )

                MatchStrategySubTab.MATCHUP_BRIEFING -> StrategyMatchupBriefingTab(
                    workspace = workspace,
                    matches = matches,
                    bundle = scoutingBundle,
                    oprSnapshot = oprSnapshot,
                    standSummaries = standSummaries,
                    selectedMatchNumber = selectedBriefingMatch,
                    onSelectMatchNumber = { selectedBriefingMatch = it },
                    exportService = resolvedExportService,
                    notebookCaptureService = notebookCaptureService,
                    onScoutTeam = { team ->
                        highlightedTeamNumber = normalizeTeamNumber(team)
                        activeTab = MatchStrategySubTab.OPR_SCOUTING
                    },
                    onStatusMessage = { statusBanner = it },
                    onLoggedNotebookEntry = { lastLoggedNotebookEntryId = it },
                )

                MatchStrategySubTab.ALLIANCE_PICKLIST -> StrategyAlliancePicklistTab(
                    workspace = workspace,
                    bundle = scoutingBundle,
                    oprSnapshot = oprSnapshot,
                    rankedRows = rankedPicklist,
                    highlightedTeamNumber = highlightedTeamNumber,
                    exportService = resolvedExportService,
                    notebookCaptureService = notebookCaptureService,
                    onChangePreset = { preset ->
                        val store = eventScoutingStore ?: return@StrategyAlliancePicklistTab
                        val ws = workspace ?: return@StrategyAlliancePicklistTab
                        scope.launch {
                            scoutingBundle = store.updateWeightPreset(ws, preset)
                            scoutingRefreshNonce += 1
                        }
                    },
                    onSaveOverride = { override ->
                        val store = eventScoutingStore ?: return@StrategyAlliancePicklistTab
                        val ws = workspace ?: return@StrategyAlliancePicklistTab
                        scope.launch {
                            scoutingBundle = store.upsertPicklistOverride(ws, override)
                            scoutingRefreshNonce += 1
                        }
                    },
                    onStatusMessage = { statusBanner = it },
                    onLoggedNotebookEntry = { lastLoggedNotebookEntryId = it },
                )
            }
        }
    }
}
