package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.*
import com.ares.analytics.service.tuning.TuningProfileRepository
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.components.dashboard.*
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.DashboardIntent
import com.ares.analytics.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.project.requireXrpRuntimeOptions
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository

/**
 * Primary telemetry analytics dashboard screen displaying real-time robot visualization cards.
 *
 * Coordinates live telemetry streams over NT4 (`ARES/EstimatedPose`, `Drive/Pose_X`, `Drive/Pose_Y`, `Drive/Pose_Heading`),
 * rendering Mecanum drive motor currents ($A$) / powers ($-1.0 \dots 1.0$), 2D field canvas trajectories, system alerts, and AI strategy advice.
 *
 * ### Field Canvas Axis Coordinate Transform:
 * - $\text{canvasX} = \left(-\frac{\text{fieldY}}{\text{fieldWidth}} + 0.5\right) \times \text{canvasWidth}$
 * - $\text{canvasY} = \left(-\frac{\text{fieldX}}{\text{fieldHeight}} + 0.5\right) \times \text{canvasHeight}$
 * - Robot icon heading angle rotation offset: $-90^\circ$
 *
 * @param viewModel State manager [DashboardViewModel] for dashboard actions.
 * @param services Typed dependencies owned by the dashboard feature.
 * @param currentConfig Active workspace configuration settings.
 *
 * @see FieldViewerViewModel
 * @see MecanumVisualizer
 */
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.dashboard.DashboardMissionHeader
import com.ares.analytics.ui.components.dashboard.DashboardMissionSnapshot

internal data class DashboardFeatureServices(
    val widgets: DashboardWidgetServices,
    val simulator: SimulatorProcessService,
    val tuningProfiles: TuningProfileRepository,
)

@Composable
internal fun DashboardScreen(
    viewModel: DashboardViewModel,
    services: DashboardFeatureServices,
    currentConfig: WorkspaceConfig,
    isRobotLinkConnected: Boolean,
    isLocalSimulatorSelected: Boolean,
    isSimulatorLaunchPreparationRunning: Boolean,
    simulatorLaunchRequiresVerification: Boolean,
    canLaunchSimulator: Boolean,
    simulatorLaunchDisabledReason: String?,
    onLaunchSimulator: () -> Unit,
    matches: List<MatchInfo>,
    onForensicsCompleted: (ForensicsResponse) -> Unit,
    onSelectMatch: (MatchInfo, String) -> Unit,
    reloadTrigger: Int,
    onImportSuccess: () -> Unit,
    onRunRecorded: () -> Unit,
    onNavigate: (NavigationTarget) -> Unit = {},
    onOpenKeybindings: () -> Unit = {},
    onOpenRunHistory: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onMissionSnapshotChanged: (DashboardMissionSnapshot) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val liveServices = services.widgets.live
    val replayServices = services.widgets.replay
    var newLayoutName by remember { mutableStateOf("") }
    var offlineGuideDismissed by remember { mutableStateOf(false) }
    var loopTimeMs by remember { mutableStateOf<Double?>(null) }
    var batteryVoltage by remember { mutableStateOf<Double?>(null) }
    var brownoutCount by remember { mutableStateOf<Int?>(null) }
    var loopOverruns by remember { mutableStateOf<Int?>(null) }
    var lastUpdateTimestampMs by remember { mutableStateOf(-1L) }
    var lastUpdateAgeMs by remember { mutableStateOf(-1L) }
    val simulatorState by services.simulator.state.collectAsState()
    val isSimRunning = simulatorState.running
    val isLocalSimulator = isLocalSimulatorSelected
    val healthSnapshot by liveServices.dashboardHealthService.health.collectAsState()
    val frameRateHz = healthSnapshot.ingestFramesPerSecond
    val isReplayActive by liveServices.nt4ClientService.isReplayActive.collectAsState()
    val replayEngine = replayServices.replayEngineService
    val replayState by replayEngine.state.collectAsState()
    val replayFrame by replayEngine.currentFrame.collectAsState()
    val replaySessionStart by replayEngine.sessionStartTimestampMs.collectAsState()
    val isReplayMode = state.primarySessionId != null || isReplayActive
    val displayedReplayFrame = replayFrame.takeIf { isReplayMode }
    val latestReplayMode by rememberUpdatedState(isReplayMode)
    val tuningDeclarations by produceState<List<TuningParameterDeclaration>>(emptyList(), currentConfig.projectPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            services.tuningProfiles.load(currentConfig.projectPath).getOrNull()?.catalog.orEmpty()
        }
    }
    val xrpBrownoutThresholdVolts by produceState<Double?>(null, currentConfig.league, currentConfig.projectPath) {
        value = if (currentConfig.league == League.XRP) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ProjectMetadataRepository().load(currentConfig.projectPath).getOrNull()
                    ?.requireXrpRuntimeOptions()
                    ?.brownoutThresholdVolts
            }
        } else {
            null
        }
    }

    // Telemetry flow listener for health metrics and freshness tracking
    LaunchedEffect(Unit) {
        scope.launch {
            liveServices.nt4ClientService.uiTelemetryFlow.collect { frame ->
                if (latestReplayMode) return@collect
                lastUpdateTimestampMs = System.currentTimeMillis()
                val key = frame.key.lowercase()
                val value = frame.value

                when {
                    key.contains("looptime") || key.contains("loop_time") -> {
                        loopTimeMs = value
                    }
                    key.contains("batteryvoltage") || key.contains("battery_voltage") -> {
                        batteryVoltage = value
                    }
                    key.contains("brownoutcount") || key.contains("brownout_count") -> {
                        brownoutCount = value.toInt()
                    }
                    key.contains("loopoverruns") || key.contains("loop_overruns") -> {
                        loopOverruns = value.toInt()
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                lastUpdateAgeMs = if (latestReplayMode) {
                    0L
                } else if (lastUpdateTimestampMs > 0) {
                    System.currentTimeMillis() - lastUpdateTimestampMs
                } else {
                    -1L
                }
            }
        }
    }

    // Replay integration
    val selectedSessionForDisposal by rememberUpdatedState(state.primarySessionId)
    val undismissedAlerts = remember { mutableStateListOf<AlertRecord>() }

    LaunchedEffect(state.alerts, isReplayMode) {
        if (isReplayMode) {
            // Historical alerts remain available as replay markers and evidence. They must not
            // appear as live, audible/urgent dashboard alarms.
            undismissedAlerts.clear()
            return@LaunchedEffect
        }
        undismissedAlerts.removeAll { alert -> state.alerts.any { it.alertId == alert.alertId && it.resolveTimestampMs != null } }
        state.alerts.forEach { alert ->
            val isCritical = alert.ruleKey.contains("brownout", ignoreCase = true) ||
                             alert.ruleKey.contains("comms", ignoreCase = true) ||
                             alert.ruleKey.contains("can", ignoreCase = true) ||
                             alert.ruleKey.contains("battery", ignoreCase = true)

            if (isCritical && undismissedAlerts.none { it.alertId == alert.alertId }) {
                undismissedAlerts.add(alert)
            }
        }
    }

    LaunchedEffect(state.primarySessionId) {
        undismissedAlerts.clear()
    }

    // Load replay session when primarySessionId changes
    LaunchedEffect(state.primarySessionId, state.replayEvidenceTarget?.requestId) {
        val sessionId = state.primarySessionId
        if (sessionId != null) {
            liveServices.nt4ClientService.isReplayActive.value = true
            if (replayEngine.sessionInfo.value?.sessionId != sessionId || replayEngine.currentFrame.value == null) {
                replayEngine.loadSession(sessionId)
            }
            state.replayEvidenceTarget
                ?.takeIf { it.sessionId == sessionId }
                ?.let { target ->
                    replayEngine.seekToTimestamp(target.timestampMs)
                    viewModel.onIntent(DashboardIntent.ConsumeReplayEvidenceTarget(target.requestId))
                }
        } else {
            replayEngine.stop()
            if (state.sessionMode != SessionMode.LIVE_REWIND) {
                liveServices.nt4ClientService.isReplayActive.value = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Replay state survives navigation, but non-dashboard screens return to live UI data.
            // Coming back reselects the same immutable snapshot without reloading or losing place.
            if (selectedSessionForDisposal != null) {
                liveServices.nt4ClientService.isReplayActive.value = false
            }
        }
    }

    LaunchedEffect(displayedReplayFrame?.sequence) {
        displayedReplayFrame?.toReplayHealthSnapshot()?.let { replay ->
            loopTimeMs = replay.loopTimeMs
            batteryVoltage = replay.batteryVoltage
            brownoutCount = replay.brownoutCount
            loopOverruns = replay.loopOverruns
            lastUpdateTimestampMs = replayFrame?.playheadMs ?: -1L
            lastUpdateAgeMs = 0L
        }
    }

    LaunchedEffect(state.importSuccess) {
        if (state.importSuccess) {
            onImportSuccess()
            viewModel.onIntent(DashboardIntent.ClearImportSuccess)
        }
    }

    val missionSnapshot = DashboardMissionSnapshot(
        workspace = currentConfig,
        isConnected = isRobotLinkConnected,
        isLocalSimulator = isLocalSimulator,
        isSimulatorRunning = isSimRunning,
        isReplayActive = isReplayActive || isReplayMode,
        primarySessionId = state.primarySessionId,
        loopTimeMs = loopTimeMs,
        batteryVoltage = batteryVoltage,
        brownoutCount = brownoutCount,
        loopOverruns = loopOverruns,
        xrpBrownoutThresholdVolts = xrpBrownoutThresholdVolts,
        activeAlerts = state.alerts,
        frameRateHz = frameRateHz,
        lastUpdateAgeMs = lastUpdateAgeMs,
        hostIp = if (isLocalSimulator) "127.0.0.1" else currentConfig.nt4Host?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
    )

    LaunchedEffect(missionSnapshot) {
        onMissionSnapshotChanged(missionSnapshot)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

        if (isLocalSimulator) {
            if (currentConfig.league == League.XRP) XrpSimulatorControlBar(
                xrpLink = liveServices.xrpLinkService,
                keyboardDriveState = liveServices.keyboardDriveState,
                projectPath = currentConfig.projectPath,
                isConnected = isRobotLinkConnected,
                isSimulatorProcessRunning = isSimRunning,
                isLaunchPreparationRunning = isSimulatorLaunchPreparationRunning,
                canLaunchSimulator = canLaunchSimulator,
                simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
                onLaunchSimulator = onLaunchSimulator,
                modifier = Modifier.fillMaxWidth(),
            ) else LocalSimulatorControlBar(
                nt4Client = liveServices.nt4ClientService,
                keyboardDriveState = liveServices.keyboardDriveState,
                league = currentConfig.league,
                teamId = currentConfig.teamId,
                seasonId = currentConfig.seasonId,
                robotId = currentConfig.robotId,
                projectPath = currentConfig.projectPath,
                isConnected = isRobotLinkConnected,
                isSimulatorProcessRunning = isSimRunning,
                isLaunchPreparationRunning = isSimulatorLaunchPreparationRunning,
                launchRequiresVerification = simulatorLaunchRequiresVerification,
                canLaunchSimulator = canLaunchSimulator,
                simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
                onLaunchSimulator = onLaunchSimulator,
                onRecordingSaved = onRunRecorded,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (shouldShowDashboardOfflineGuide(isRobotLinkConnected, state.primarySessionId, offlineGuideDismissed)) {
            DashboardOfflineGuide(
                onOpenRunHistory = onOpenRunHistory,
                onOpenHelp = onOpenHelp,
                onDismiss = { offlineGuideDismissed = true },
            )
        }

        // Configurable widgets area
        val layout = state.currentLayout
        if (layout != null) {
            DashboardWidgetHost(
                layout = layout,
                services = services.widgets,
                workspace = currentConfig,
                isRobotLinkConnected = isRobotLinkConnected,
                xrpBrownoutThresholdVolts = xrpBrownoutThresholdVolts,
                dashboardState = state,
                replayFrame = displayedReplayFrame,
                replaySessionStartMs = replaySessionStart,
                matches = matches,
                tuningDeclarations = tuningDeclarations,
                reloadTrigger = reloadTrigger,
                onForensicsCompleted = onForensicsCompleted,
                onSelectMatch = onSelectMatch,
                onSelectPrimarySession = { viewModel.onIntent(DashboardIntent.SelectPrimarySession(it)) },
                onSelectCompareSession = { viewModel.onIntent(DashboardIntent.SelectCompareSession(it)) },
                onOpenKeybindings = onOpenKeybindings,
                onUpdateProperties = { widget, newProperties ->
                    viewModel.onIntent(DashboardIntent.UpdateLayout(layout.widgets.map {
                        if (it.id == widget.id) it.copy(properties = newProperties) else it
                    }))
                },
                onLayoutChanged = { viewModel.onIntent(DashboardIntent.UpdateLayout(it)) },
                onRemoveWidget = { viewModel.onIntent(DashboardIntent.RemoveWidget(it)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        // Timeline Scrubber Bar
        val isReplayActive by liveServices.nt4ClientService.isReplayActive.collectAsState()

        if (state.primarySessionId != null || isRobotLinkConnected) {
            ReplayTimelineScrubber(
                replayEngine = replayEngine,
                replayState = replayState,
                isLiveConnection = state.primarySessionId == null && isRobotLinkConnected,
                isReplayActive = isReplayActive,
                sessionMode = state.sessionMode,
                sessionId = state.primarySessionId,
                alerts = state.alerts,
                onSnapToRealtime = {
                    scope.launch {
                        liveServices.nt4ClientService.isReplayActive.value = false
                        viewModel.onIntent(DashboardIntent.SetSessionMode(SessionMode.LIVE_STREAMING))
                        replayEngine.stop()
                    }
                },
                onScrubLive = { pct ->
                    scope.launch {
                        liveServices.nt4ClientService.isReplayActive.value = true
                        viewModel.onIntent(DashboardIntent.SetSessionMode(SessionMode.LIVE_REWIND))
                        replayEngine.loadSession("live-telemetry")
                        replayEngine.scrubTo(pct)
                    }
                },
                onPauseLive = {
                    scope.launch {
                        liveServices.nt4ClientService.isReplayActive.value = true
                        viewModel.onIntent(DashboardIntent.SetSessionMode(SessionMode.LIVE_REWIND))
                        replayEngine.loadSession("live-telemetry")
                        replayEngine.scrubTo(1.0)
                        replayEngine.pause()
                    }
                },
                onPlayLive = {
                    scope.launch {
                        liveServices.nt4ClientService.isReplayActive.value = true
                        viewModel.onIntent(DashboardIntent.SetSessionMode(SessionMode.LIVE_REWIND))
                        replayEngine.loadSession("live-telemetry")
                        replayEngine.play()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (state.isPickerOpen) {
        WidgetPicker(
            onDismiss = { viewModel.onIntent(DashboardIntent.SetPickerOpen(false)) },
            onSelectWidget = { type ->
                viewModel.onIntent(DashboardIntent.AddWidget(type))
            }
        )
    }

    DashboardCriticalAlertStack(
        alerts = undismissedAlerts,
        onDismiss = { undismissedAlerts.remove(it) },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp),
    )
    }
}
internal fun shouldShowDashboardOfflineGuide(
    isConnected: Boolean,
    primarySessionId: String?,
    dismissed: Boolean
): Boolean = !isConnected && primarySessionId == null && !dismissed
