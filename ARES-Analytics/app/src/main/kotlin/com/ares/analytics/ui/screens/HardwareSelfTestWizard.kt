package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.navigation.PitDeepLinkRequest
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.domain.pit.BatteryCheckinRecord
import com.ares.analytics.domain.pit.BatteryFleetAnalyzer
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.domain.pit.EventScheduleCodec
import com.ares.analytics.domain.pit.formatBatterySessionTag
import com.ares.analytics.domain.pit.normalizeBatteryId
import com.ares.analytics.service.AlertEngineService
import com.ares.analytics.service.AlertRuleConfiguration
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.EventScheduleStore
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.toScheduledMatchItem
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.pit.PitAlertThresholdsTab
import com.ares.analytics.ui.components.pit.PitBatteryFleetTab
import com.ares.analytics.ui.components.pit.PitMatchQueueTab
import com.ares.analytics.ui.components.pit.PitReadinessTabContent
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ARES_TELEMETRY_HEARTBEAT_TOPIC = "ARES/Telemetry/FrameSequence"
internal const val HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS = 1_500L

internal enum class SelfTestStatus {
    WAITING,
    OBSERVED,
    WARNING
}

internal data class SelfTestStep(
    val id: String,
    val name: String,
    val description: String,
    val status: SelfTestStatus,
    val details: String
)

/**
 * Builds an honest, read-only baseline preflight from telemetry already published by the robot.
 *
 * An observed topic proves only that the signal reached the app. It does not pulse motors,
 * infer encoder direction, or certify match readiness.
 */
internal fun evaluateHardwareReadiness(
    connected: Boolean,
    frames: Map<String, TelemetryFrame>,
    heartbeatAgeMs: Long?,
): List<SelfTestStep> {
    data class Definition(val id: String, val name: String, val description: String)
    val definitions = listOf(
        Definition("battery", "Battery voltage", "Confirms a finite robot voltage signal above the pit warning threshold."),
        Definition("motor_fl", "Front-left drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_fr", "Front-right drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_rl", "Rear-left drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_rr", "Rear-right drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("pinpoint", "Pinpoint / odometry", "Checks the published health or odometry evidence without moving the robot."),
        Definition("limelight", "Limelight / vision", "Checks published camera evidence without assuming that a target must be visible.")
    )
    if (!connected) {
        return definitions.map { definition ->
            SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, "Connect to a robot or simulator to observe this signal.")
        }
    }

    fun numeric(vararg keys: String): TelemetryFrame? = keys.firstNotNullOfOrNull { frames[it] }
    fun observed(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.OBSERVED, details)
    fun waiting(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, details)
    fun warning(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WARNING, details)

    if (heartbeatAgeMs == null || heartbeatAgeMs !in 0L..HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS) {
        val details = if (heartbeatAgeMs == null) {
            "The network socket is connected, but no ARES telemetry-loop heartbeat has arrived."
        } else {
            "The last ARES telemetry-loop heartbeat is $heartbeatAgeMs ms old. Treat displayed values as stale."
        }
        return definitions.map { definition ->
            SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, details)
        }
    }

    val results = mutableListOf<SelfTestStep>()
    val batteryDefinition = definitions[0]
    val battery = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.firstNotNullOfOrNull { frames[it] }?.value
    results += when {
        battery == null || !battery.isFinite() -> waiting(batteryDefinition, "No finite battery-voltage topic received.")
        battery < 11.5 -> warning(batteryDefinition, "${"%.2f".format(battery)} V observed; charge or inspect the battery before a match.")
        else -> observed(batteryDefinition, "${"%.2f".format(battery)} V observed.")
    }

    listOf("fl" to definitions[1], "fr" to definitions[2], "rl" to definitions[3], "rr" to definitions[4]).forEach { (name, definition) ->
        val aliases = if (name == "rl") listOf("rl", "bl") else if (name == "rr") listOf("rr", "br") else listOf(name)
        val current = aliases.firstNotNullOfOrNull { numeric("Hardware/Motors/$it/CurrentAmps", "Hardware/Motors/$it/Current") }
        val motion = aliases.firstNotNullOfOrNull { numeric("Hardware/Motors/$it/Velocity", "Hardware/Motors/$it/Power") }
        results += if (current != null || motion != null) {
            val evidence = listOfNotNull(
                current?.value?.takeIf(Double::isFinite)?.let { "current ${"%.2f".format(it)} A" },
                motion?.value?.takeIf(Double::isFinite)?.let { "motion signal ${"%.2f".format(it)}" }
            ).joinToString()
            observed(definition, if (evidence.isBlank()) "Motor topics observed." else "Telemetry observed: $evidence.")
        } else {
            waiting(definition, "No current, velocity, or power topic received for ${aliases.joinToString("/")}.")
        }
    }

    val pinpointDefinition = definitions[5]
    val pinpointStatus = frames["Drive/Pinpoint_Status"]?.stringValue?.trim().orEmpty()
    val odometryObserved = listOf("Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading")
        .all { frames[it]?.value?.isFinite() == true }
    results += when {
        pinpointStatus.contains("FAULT", true) || pinpointStatus.contains("DISCONNECT", true) ->
            warning(pinpointDefinition, "Robot reports: $pinpointStatus")
        pinpointStatus.isNotBlank() && !pinpointStatus.equals("UNKNOWN", true) ->
            observed(pinpointDefinition, "Robot reports: $pinpointStatus")
        odometryObserved -> warning(pinpointDefinition, "Odometry values are present, but the robot did not identify Pinpoint health.")
        else -> waiting(pinpointDefinition, "No Pinpoint health or complete odometry pose received.")
    }

    val visionDefinition = definitions[6]
    val fps = frames["Vision/Limelight/FPS"]?.value
    val visionTopicObserved = frames.containsKey("Vision/HasTarget") || frames.containsKey("Vision/MeasurementCount")
    results += when {
        fps != null && fps.isFinite() && fps >= 5.0 -> observed(visionDefinition, "${"%.1f".format(fps)} FPS observed.")
        fps != null && fps.isFinite() -> warning(visionDefinition, "Only ${"%.1f".format(fps)} FPS observed.")
        visionTopicObserved -> warning(visionDefinition, "Vision topics are present, but camera frame rate is not published.")
        else -> waiting(visionDefinition, "No Limelight or vision-health topic received.")
    }
    return results
}

/**
 * 4-tab Pit Operations Hub combining pre-match Readiness & Sign-Off (read-only passive NT4 checks
 * + manual crew sign-offs + Notebook capture), Battery Fleet Analytics, Offline Match Queue, and
 * Live Alert Threshold editing. This screen never commands robot hardware.
 */
@Composable
fun HardwareSelfTestWizard(
    nt4ClientService: Nt4ClientService,
    workspace: WorkspaceConfig? = null,
    projectSession: ProjectSession? = null,
    hardwareSetupService: HardwareSetupService? = null,
    exportService: ExportService? = null,
    databaseService: DatabaseService? = null,
    alertEngineService: AlertEngineService? = null,
    eventScheduleStore: EventScheduleStore? = null,
    notebookCaptureService: EngineeringNotebookCaptureService? = null,
    matches: List<MatchInfo> = emptyList(),
    reloadTrigger: Int = 0,
    onRefreshEventSchedule: () -> Unit = {},
    onMatchesUpdated: (List<MatchInfo>) -> Unit = {},
    onOpenMatchStrategy: () -> Unit = {},
    onOpenMatchupBriefing: ((Int) -> Unit)? = null,
    onOpenRunHistoryWithTag: (String) -> Unit = {},
    onOpenNotebookEntry: ((String) -> Unit)? = null,
    pitDeepLink: PitDeepLinkRequest = PitDeepLinkRequest(),
    onActiveTabChanged: (PitOperationsSubTab) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val league = workspace?.league ?: League.FTC
    val connected by nt4ClientService.isConnected.collectAsState()
    var activeTab by remember { mutableStateOf(pitDeepLink.tab.index) }
    LaunchedEffect(pitDeepLink.nonce, pitDeepLink.tab) {
        activeTab = pitDeepLink.tab.index
    }
    LaunchedEffect(activeTab) {
        onActiveTabChanged(PitOperationsSubTab.fromIndex(activeTab))
    }

    var reloadProfileNonce by remember { mutableStateOf(0) }
    val projectProfile by produceState(
        initialValue = PitProjectSignalProfile.leagueFallback(league),
        workspace?.id,
        workspace?.projectPath,
        league,
        reloadProfileNonce,
    ) {
        value = PitReadinessInspector.loadProjectProfile(
            workspace = workspace,
            projectSession = projectSession,
            hardwareSetupService = hardwareSetupService,
        )
    }

    var batteryRefreshNonce by remember { mutableStateOf(0) }
    var batterySpecs by remember { mutableStateOf<List<BatterySpecRecord>>(emptyList()) }
    var batteryCheckins by remember { mutableStateOf<List<BatteryCheckinRecord>>(emptyList()) }
    var workspaceSessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var workspaceSummaries by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }

    LaunchedEffect(
        workspace?.teamId,
        workspace?.seasonId,
        workspace?.robotId,
        reloadTrigger,
        batteryRefreshNonce,
        databaseService,
    ) {
        val db = databaseService
        if (workspace == null || db == null) {
            batterySpecs = emptyList()
            batteryCheckins = emptyList()
            workspaceSessions = emptyList()
            workspaceSummaries = emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val specs = db.listBatterySpecsForWorkspace(workspace.teamId, workspace.seasonId, workspace.robotId)
                val checkins = db.listBatteryCheckinsForWorkspace(workspace.teamId, workspace.seasonId, workspace.robotId)
                val sessions = db.getSessionsForWorkspace(workspace.teamId, workspace.seasonId, workspace.robotId)
                val ids = sessions.mapTo(HashSet(), Session::sessionId)
                val sums = if (ids.isEmpty()) emptyList() else db.getAllSessionSummaries().filter { it.sessionId in ids }
                batterySpecs = specs
                batteryCheckins = checkins
                workspaceSessions = sessions
                workspaceSummaries = sums
            }
        }
    }

    val batterySummaries = remember(league, batterySpecs, batteryCheckins, workspaceSessions, workspaceSummaries) {
        BatteryFleetAnalyzer.analyzeFleet(league, batterySpecs, batteryCheckins, workspaceSessions, workspaceSummaries)
    }
    val recommendedPack = remember(batterySummaries) {
        BatteryFleetAnalyzer.recommendNextMatchBattery(batterySummaries)
    }
    val queueRows = remember(matches, workspace?.teamId, workspaceSessions) {
        EventScheduleCodec.buildQueueRows(
            items = matches.map { it.toScheduledMatchItem("cache") },
            teamId = workspace?.teamId.orEmpty(),
            sessions = workspaceSessions,
        )
    }
    val nextScheduledRow = remember(queueRows) {
        queueRows.firstOrNull { it.isNextUnplayedForTeam } ?: queueRows.firstOrNull()
    }

    var lastHeartbeatReceipt by remember { mutableStateOf<TimeMark?>(null) }
    var streamLive by remember { mutableStateOf(false) }
    var steps by remember(projectProfile) {
        mutableStateOf(
            PitReadinessInspector.evaluateProjectAwarePitReadiness(
                connected = connected,
                frames = nt4ClientService.latestValues,
                heartbeatAgeMs = null,
                profile = projectProfile,
            )
        )
    }

    var manualChecklist by remember(workspace?.id, league) {
        mutableStateOf(PitReadinessInspector.defaultManualChecklist(league))
    }
    var matchTag by remember(workspace?.id) { mutableStateOf(PitMatchTag()) }
    var receiptStatusMessage by remember { mutableStateOf<String?>(null) }
    var lastLoggedReceiptEntryId by remember { mutableStateOf<String?>(null) }
    var scheduleStatusMessage by remember { mutableStateOf<String?>(null) }

    val configuredRules by (alertEngineService?.configuredRules?.collectAsState()
        ?: remember(league) { mutableStateOf(AlertRuleConfiguration.defaultRulesForLeague(league)) })
    val configWarning by (alertEngineService?.configurationWarningFlow?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) })

    LaunchedEffect(nt4ClientService) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            if (frame.key.removePrefix("/") == ARES_TELEMETRY_HEARTBEAT_TOPIC) {
                lastHeartbeatReceipt = TimeSource.Monotonic.markNow()
            }
        }
    }

    LaunchedEffect(connected, nt4ClientService, projectProfile) {
        if (!connected) {
            lastHeartbeatReceipt = null
            streamLive = false
        }
        while (isActive) {
            val heartbeatAgeMs = lastHeartbeatReceipt?.elapsedNow()?.inWholeMilliseconds
            streamLive = connected && heartbeatAgeMs != null && heartbeatAgeMs <= HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS
            steps = PitReadinessInspector.evaluateProjectAwarePitReadiness(
                connected = connected,
                frames = nt4ClientService.latestValues,
                heartbeatAgeMs = heartbeatAgeMs,
                profile = projectProfile,
            )
            delay(250)
        }
    }

    val stageMatchIntoReadiness: (String, Int, String?) -> Unit = { compLevel, matchNum, ourAlliance ->
        val mappedType = when {
            compLevel.startsWith("p", ignoreCase = true) -> PitMatchType.PRACTICE
            compLevel.startsWith("f", ignoreCase = true) ||
                compLevel.startsWith("s", ignoreCase = true) ||
                compLevel.startsWith("e", ignoreCase = true) -> PitMatchType.PLAYOFF
            else -> PitMatchType.QUALIFICATION
        }
        val mappedAlliance = when (ourAlliance?.lowercase()) {
            "red" -> PitAllianceColor.RED
            "blue" -> PitAllianceColor.BLUE
            else -> PitAllianceColor.UNASSIGNED
        }
        val nextBat = matchTag.batteryId.ifBlank { recommendedPack?.batteryId.orEmpty() }
        matchTag = matchTag.copy(
            matchType = mappedType,
            matchNumber = matchNum.toString(),
            alliance = mappedAlliance,
            batteryId = normalizeBatteryId(nextBat),
        )
        receiptStatusMessage = "Staged ${mappedType.label} #$matchNum" +
            (if (nextBat.isNotBlank()) " with recommended pack $nextBat." else ".")
    }

    val tabTitles = listOf("Readiness & Sign-Off", "Battery Fleet", "Match Queue", "Alert Thresholds")

    Column(
        Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.HealthAndSafety, null, tint = AresCyan, modifier = Modifier.size(24.dp))
                        Column {
                            Text("Competition Pit Operations Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                            Text("${projectProfile.projectLabel} · Passive NT4 readiness, battery fleet health, offline match queue & live thresholds", color = AresTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                PrimaryTabRow(selectedTabIndex = activeTab, containerColor = AresSurfaceElevated) {
                    tabTitles.forEachIndexed { idx, label ->
                        Tab(
                            selected = activeTab == idx,
                            onClick = { activeTab = idx },
                            text = { Text(label, fontSize = 12.sp, fontWeight = if (activeTab == idx) FontWeight.Bold else FontWeight.Medium) },
                        )
                    }
                }
            }
        }

        when (activeTab) {
            0 -> PitReadinessTabContent(
                projectLabel = projectProfile.projectLabel,
                connected = connected,
                streamLive = streamLive,
                steps = steps,
                manualChecklist = manualChecklist,
                matchTag = matchTag,
                recommendedBatteryId = recommendedPack?.batteryId,
                hasNextScheduledMatch = nextScheduledRow != null,
                receiptStatusMessage = receiptStatusMessage,
                lastLoggedEntryId = lastLoggedReceiptEntryId,
                onMatchTagChange = { matchTag = it },
                onFillNextMatch = nextScheduledRow?.let { next ->
                    { stageMatchIntoReadiness(next.item.compLevel, next.item.matchNumber, next.ourAlliance) }
                },
                onRefreshSignals = {
                    reloadProfileNonce += 1
                    val heartbeatAgeMs = lastHeartbeatReceipt?.elapsedNow()?.inWholeMilliseconds
                    streamLive = connected && heartbeatAgeMs != null && heartbeatAgeMs <= HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS
                    steps = PitReadinessInspector.evaluateProjectAwarePitReadiness(
                        connected = connected,
                        frames = nt4ClientService.latestValues,
                        heartbeatAgeMs = heartbeatAgeMs,
                        profile = projectProfile,
                    )
                },
                onToggleManualCheck = { id ->
                    manualChecklist = manualChecklist.map { cur -> if (cur.id == id) cur.copy(checked = !cur.checked) else cur }
                },
                onResetChecklist = { manualChecklist = manualChecklist.map { it.copy(checked = false) } },
                onExportMarkdown = {
                    val receipt = PitReadinessInspector.buildPitReadinessMarkdownReceipt(
                        workspace = workspace,
                        profile = projectProfile,
                        matchTag = matchTag,
                        connected = connected,
                        streamLive = streamLive,
                        signalSteps = steps,
                        manualChecklist = manualChecklist,
                    )
                    scope.launch {
                        val safeMatch = matchTag.summaryBadge().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        val dest = withContext(Dispatchers.IO) {
                            DesktopFileChoosers.chooseSaveFile(
                                dialogTitle = "Export Pit Readiness Receipt (.md)",
                                defaultFileName = "pit_receipt_${safeMatch.ifBlank { "check" }}.md",
                                filterDescription = "Markdown Receipt (*.md)",
                                extensions = listOf("md"),
                            )
                        } ?: return@launch
                        runCatching {
                            if (exportService != null) exportService.writeTextReportAtomically(dest, receipt)
                            else withContext(Dispatchers.IO) { dest.writeText(receipt, Charsets.UTF_8) }
                        }.onSuccess {
                            receiptStatusMessage = "Saved pit receipt: ${dest.name}"
                        }.onFailure {
                            receiptStatusMessage = "Export failed: ${it.message}"
                        }
                    }
                },
                onCopyMarkdown = {
                    val receipt = PitReadinessInspector.buildPitReadinessMarkdownReceipt(
                        workspace = workspace,
                        profile = projectProfile,
                        matchTag = matchTag,
                        connected = connected,
                        streamLive = streamLive,
                        signalSteps = steps,
                        manualChecklist = manualChecklist,
                    )
                    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(receipt), null) }
                    receiptStatusMessage = "Copied pit receipt (${matchTag.summaryBadge()}) to clipboard."
                },
                onLogReceiptToNotebook = if (workspace != null && notebookCaptureService != null) {
                    {
                        val receipt = PitReadinessInspector.buildPitReadinessMarkdownReceipt(
                            workspace = workspace,
                            profile = projectProfile,
                            matchTag = matchTag,
                            connected = connected,
                            streamLive = streamLive,
                            signalSteps = steps,
                            manualChecklist = manualChecklist,
                        )
                        val observed = steps.filter { it.status == SelfTestStatus.OBSERVED }.map { "${it.name}: ${it.details}" }
                        val warnings = steps.filter { it.status == SelfTestStatus.WARNING }.map { "${it.name}: ${it.details}" }
                        val checked = manualChecklist.filter { it.checked }.map { it.title }
                        val unchecked = manualChecklist.filterNot { it.checked }.map { it.title }
                        scope.launch {
                            runCatching {
                                notebookCaptureService.capturePitReadinessReceipt(
                                    workspace = workspace,
                                    projectLabel = projectProfile.projectLabel,
                                    matchBadge = matchTag.summaryBadge(),
                                    batteryId = matchTag.batteryId.takeIf(String::isNotBlank),
                                    connected = connected,
                                    streamLive = streamLive,
                                    observedSignalsSummary = observed,
                                    warningSignalsSummary = warnings,
                                    checkedCrewItems = checked,
                                    uncheckedCrewItems = unchecked,
                                    receiptMarkdown = receipt,
                                )
                            }.onSuccess { saved ->
                                lastLoggedReceiptEntryId = saved.entryId
                                receiptStatusMessage = "Logged ${saved.entryId} r${saved.revision} to Engineering Notebook."
                            }.onFailure { err ->
                                receiptStatusMessage = "Notebook capture failed: ${err.message}"
                            }
                        }
                    }
                } else null,
                onOpenNotebookEntry = onOpenNotebookEntry,
            )

            1 -> PitBatteryFleetTab(
                workspace = workspace,
                summaries = batterySummaries,
                recommendedPack = recommendedPack,
                highlightedBatteryId = pitDeepLink.highlightedBatteryId,
                onSaveBatterySpec = { spec ->
                    val db = databaseService ?: return@PitBatteryFleetTab
                    scope.launch {
                        db.upsertBatterySpec(spec)
                        batteryRefreshNonce += 1
                    }
                },
                onUpdatePoolStatus = { id, status ->
                    val ws = workspace ?: return@PitBatteryFleetTab
                    val db = databaseService ?: return@PitBatteryFleetTab
                    scope.launch {
                        db.updateBatteryPoolStatus(ws.teamId, ws.seasonId, ws.robotId, id, status, System.currentTimeMillis())
                        batteryRefreshNonce += 1
                    }
                },
                onRecordCheckin = { checkin ->
                    val db = databaseService ?: return@PitBatteryFleetTab
                    scope.launch {
                        db.recordBatteryCheckin(checkin)
                        batteryRefreshNonce += 1
                    }
                },
                onDeleteBattery = { id ->
                    val ws = workspace ?: return@PitBatteryFleetTab
                    val db = databaseService ?: return@PitBatteryFleetTab
                    scope.launch {
                        db.deleteBatterySpecFromWorkspace(ws.teamId, ws.seasonId, ws.robotId, id)
                        batteryRefreshNonce += 1
                    }
                },
                onAssignToReadiness = { id ->
                    matchTag = matchTag.copy(batteryId = normalizeBatteryId(id))
                    activeTab = 0
                    receiptStatusMessage = "Assigned pack $id to Readiness check."
                },
                onFilterRunHistoryByBattery = { tag ->
                    val cleanTag = formatBatterySessionTag(tag) ?: tag
                    onOpenRunHistoryWithTag(cleanTag)
                },
                onLogFleetToNotebook = if (workspace != null && notebookCaptureService != null) {
                    {
                        scope.launch {
                            runCatching {
                                notebookCaptureService.captureBatteryFleetSummary(
                                    workspace = workspace,
                                    summaries = batterySummaries,
                                    recommendedBatteryId = recommendedPack?.batteryId,
                                )
                            }.onSuccess { saved ->
                                receiptStatusMessage = "Logged ${saved.entryId} r${saved.revision} to Engineering Notebook."
                                activeTab = 0
                                lastLoggedReceiptEntryId = saved.entryId
                            }.onFailure { err ->
                                receiptStatusMessage = "Battery audit capture failed: ${err.message}"
                            }
                        }
                    }
                } else null,
            )

            2 -> PitMatchQueueTab(
                workspace = workspace,
                rows = queueRows,
                statusMessage = scheduleStatusMessage,
                highlightedMatchNumber = pitDeepLink.highlightedMatchNumber,
                onSyncApi = {
                    scheduleStatusMessage = "Syncing schedule for ${workspace?.eventCode ?: "workspace"}..."
                    onRefreshEventSchedule()
                },
                onImportScheduleFile = {
                    val ws = workspace
                    val store = eventScheduleStore
                    if (ws == null || store == null) {
                        scheduleStatusMessage = "Select an active workspace first."
                    } else {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                DesktopFileChoosers.chooseOpenFile(
                                    dialogTitle = "Import Match Schedule (.csv / .json)",
                                    filterDescription = "Schedule Files (*.csv, *.json)",
                                    extensions = listOf("csv", "json"),
                                )
                            } ?: return@launch
                            runCatching { store.importScheduleFile(ws, file) }
                                .onSuccess { imported ->
                                    onMatchesUpdated(imported)
                                    scheduleStatusMessage = "Imported ${imported.size} matches from ${file.name} (cached offline)."
                                }
                                .onFailure { err ->
                                    scheduleStatusMessage = "Import failed: ${err.message}"
                                }
                        }
                    }
                },
                onUpsertManualMatch = { item ->
                    val ws = workspace ?: return@PitMatchQueueTab
                    val store = eventScheduleStore ?: return@PitMatchQueueTab
                    scope.launch {
                        val updated = store.upsertManualMatch(ws, item)
                        onMatchesUpdated(updated)
                        scheduleStatusMessage = "Saved ${item.compLevel} #${item.matchNumber} to offline cache."
                    }
                },
                onRemoveMatch = { compLevel, matchNum ->
                    val ws = workspace ?: return@PitMatchQueueTab
                    val store = eventScheduleStore ?: return@PitMatchQueueTab
                    scope.launch {
                        val updated = store.removeMatch(ws, compLevel, matchNum)
                        onMatchesUpdated(updated)
                        scheduleStatusMessage = "Removed $compLevel #$matchNum from schedule."
                    }
                },
                onStageInReadiness = { row ->
                    stageMatchIntoReadiness(row.item.compLevel, row.item.matchNumber, row.ourAlliance)
                    activeTab = 0
                },
                onOpenMatchStrategy = onOpenMatchStrategy,
                onOpenMatchupBriefing = onOpenMatchupBriefing,
            )

            else -> PitAlertThresholdsTab(
                activeRules = configuredRules,
                configWarning = configWarning,
                activeLeague = league,
                onSaveHotReload = { edited ->
                    val engine = alertEngineService
                        ?: return@PitAlertThresholdsTab Result.failure(IllegalStateException("Alert engine unavailable."))
                    engine.replaceRules(edited, persistToDisk = true)
                },
            )
        }
    }
}
