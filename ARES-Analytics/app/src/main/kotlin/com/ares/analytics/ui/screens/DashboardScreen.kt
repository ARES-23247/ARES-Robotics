package com.ares.analytics.ui.screens

import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
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
        isConnected = state.isConnected,
        isLocalSimulator = isLocalSimulator,
        isSimulatorRunning = isSimRunning,
        isReplayActive = isReplayActive || isReplayMode,
        primarySessionId = state.primarySessionId,
        loopTimeMs = loopTimeMs,
        batteryVoltage = batteryVoltage,
        brownoutCount = brownoutCount,
        loopOverruns = loopOverruns,
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
            LocalSimulatorControlBar(
                nt4Client = liveServices.nt4ClientService,
                keyboardDriveState = liveServices.keyboardDriveState,
                league = currentConfig.league,
                teamId = currentConfig.teamId,
                seasonId = currentConfig.seasonId,
                robotId = currentConfig.robotId,
                isConnected = state.isConnected,
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

        if (shouldShowDashboardOfflineGuide(state.isConnected, state.primarySessionId, offlineGuideDismissed)) {
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
        val isConnected by liveServices.nt4ClientService.isConnected.collectAsState()
        val isReplayActive by liveServices.nt4ClientService.isReplayActive.collectAsState()

        if (state.primarySessionId != null || isConnected) {
            ReplayTimelineScrubber(
                replayEngine = replayEngine,
                replayState = replayState,
                isLiveConnection = state.primarySessionId == null,
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

@Composable
private fun DashboardOfflineGuide(
    onOpenRunHistory: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = AresCyan.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Explore, null, tint = AresCyan, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Choose where your data should come from", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "No live telemetry yet. For safe practice, select Local Sim above and press Play. To review past data, open Run History.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            OutlinedButton(onClick = onOpenHelp) { Text("Simulator guide") }
            Button(
                onClick = onOpenRunHistory,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
            ) { Text("Run History", fontWeight = FontWeight.Bold) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Dismiss dashboard guidance", tint = AresTextSecondary)
            }
        }
    }
}

@Composable
private fun ReplayTimelineScrubber(
    replayEngine: ReplayEngineService,
    replayState: ReplayState,
    isLiveConnection: Boolean,
    isReplayActive: Boolean,
    sessionMode: SessionMode,
    sessionId: String?,
    alerts: List<AlertRecord>,
    onSnapToRealtime: () -> Unit,
    onScrubLive: (Double) -> Unit,
    onPauseLive: () -> Unit,
    onPlayLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val rawProgress by replayEngine.progress.collectAsState()
    val speed by replayEngine.speed.collectAsState()
    val looping by replayEngine.looping.collectAsState()
    val loadState by replayEngine.loadState.collectAsState()
    val loadError by replayEngine.loadError.collectAsState()
    val sessionInfo by replayEngine.sessionInfo.collectAsState()
    val playheadTimestamp by replayEngine.playheadTimestampMs.collectAsState()
    val currentFrame by replayEngine.currentFrame.collectAsState()
    val isSeeking by replayEngine.isSeeking.collectAsState()
    val progress = if (isLiveConnection && !isReplayActive) 1.0 else rawProgress
    val replayReady = isLiveConnection && !isReplayActive || loadState == ReplayLoadState.READY
    // Keep these comparisons direct. A stale incremental desktop build once omitted
    // Kotlin's synthetic DashboardScreenKt$WhenMappings class and crashed as soon as
    // the replay bar rendered. Direct enum comparisons have no companion class to lose.
    val modeColor = if (sessionMode == SessionMode.LIVE_STREAMING) {
        ModeLive
    } else if (sessionMode == SessionMode.LIVE_REWIND) {
        ModeRewind
    } else {
        ModeReplay
    }
    val modeGlow = if (sessionMode == SessionMode.LIVE_STREAMING) {
        ModeLiveGlow
    } else if (sessionMode == SessionMode.LIVE_REWIND) {
        ModeRewindGlow
    } else {
        ModeReplayGlow
    }

    Surface(
        modifier = modifier
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !replayReady) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar -> {
                        if (replayState == ReplayState.PLAYING) replayEngine.pause() else replayEngine.play()
                        true
                    }
                    Key.DirectionLeft -> true.also { replayEngine.stepBackward() }
                    Key.DirectionRight -> true.also { replayEngine.stepForward() }
                    Key.MoveHome -> true.also { replayEngine.scrubTo(0.0) }
                    Key.MoveEnd -> true.also { replayEngine.scrubTo(1.0) }
                    else -> false
                }
            },
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, modeColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mode Pill
            Surface(
                color = modeGlow,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, modeColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).background(modeColor, RoundedCornerShape(3.dp)))
                    Text(
                        text = if (sessionMode == SessionMode.LIVE_STREAMING) {
                            "LIVE"
                        } else if (sessionMode == SessionMode.LIVE_REWIND) {
                            "LIVE REWIND"
                        } else {
                            "REPLAY · ${sessionInfo?.robotId ?: sessionId?.take(12)}"
                        },
                        color = modeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Play / Pause
            IconButton(
                onClick = {
                    scope.launch {
                        if (isLiveConnection && !isReplayActive) {
                            onPauseLive()
                        } else {
                            if (replayState == ReplayState.PLAYING) replayEngine.pause() else replayEngine.play()
                        }
                    }
                },
                enabled = replayReady,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = when {
                        isLiveConnection && !isReplayActive -> Icons.Default.Pause
                        replayState == ReplayState.PLAYING -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        isLiveConnection && !isReplayActive -> "Pause"
                        replayState == ReplayState.PLAYING -> "Pause"
                        else -> "Play"
                    },
                    tint = modeColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Stop / Live TV
            IconButton(
                onClick = {
                    if (isLiveConnection) {
                        onSnapToRealtime()
                    } else {
                        scope.launch { replayEngine.stop() }
                    }
                },
                enabled = replayReady,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isLiveConnection) Icons.Default.LiveTv else Icons.Default.Stop,
                    contentDescription = if (isLiveConnection) "Realtime" else "Stop",
                    tint = if (isLiveConnection && !isReplayActive) modeColor else AresTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Step backward
            IconButton(
                onClick = {
                    if (!isLiveConnection || isReplayActive) {
                        scope.launch { replayEngine.stepBackward() }
                    }
                },
                enabled = replayReady && (!isLiveConnection || isReplayActive),
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Step Back",
                    tint = if (!isLiveConnection || isReplayActive) AresTextSecondary else AresBorder,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Step forward
            IconButton(
                onClick = {
                    if (!isLiveConnection || isReplayActive) {
                        scope.launch { replayEngine.stepForward() }
                    }
                },
                enabled = replayReady && (!isLiveConnection || isReplayActive),
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Step Forward",
                    tint = if (!isLiveConnection || isReplayActive) AresTextSecondary else AresBorder,
                    modifier = Modifier.size(18.dp)
                )
            }
            var sliderDragging by remember { mutableStateOf(false) }
            var localSliderValue by remember { mutableStateOf(0f) }
            val density by replayEngine.telemetryDensity.collectAsState()
            val actions by replayEngine.sessionActions.collectAsState()
            val annotations by replayEngine.sessionAnnotations.collectAsState()
            val sessionStart by replayEngine.sessionStartTimestampMs.collectAsState()
            val sessionDuration by replayEngine.sessionDurationMs.collectAsState()

            Box(modifier = Modifier.weight(1f).height(32.dp)) {
                // Histogram Canvas
                if (density.isNotEmpty() || actions.isNotEmpty() || annotations.isNotEmpty() || alerts.isNotEmpty()) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        if (density.isNotEmpty()) {
                            val barWidth = size.width / density.size
                            density.forEachIndexed { i, value ->
                                val barHeight = size.height * value
                                val x = i * barWidth
                                val y = size.height - barHeight
                                drawRect(
                                    color = modeColor.copy(alpha = 0.3f),
                                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                                    size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, barHeight)
                                )
                            }
                        }

                        // Draw action markers
                        if (actions.isNotEmpty() && sessionDuration > 0) {
                            actions.forEach { action ->
                                val proportion = (action.timestampMs - sessionStart).toDouble() / sessionDuration.toDouble()
                                val x = (proportion * size.width).toFloat()
                                drawCircle(
                                    color = androidx.compose.ui.graphics.Color(0xFF00E5FF), // Cyan marker
                                    radius = 3f,
                                    center = androidx.compose.ui.geometry.Offset(x, size.height / 2f)
                                )
                            }
                        }

                        // Gold note markers are distinct in both color and shape from actions and alerts.
                        if (annotations.isNotEmpty() && sessionDuration > 0) {
                            annotations.forEach { annotation ->
                                val proportion = (annotation.createdAt - sessionStart).toDouble() / sessionDuration.toDouble()
                                if (proportion in 0.0..1.0) {
                                    val x = (proportion * size.width).toFloat()
                                    val diamond = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(x, size.height / 2f - 4f)
                                        lineTo(x + 4f, size.height / 2f)
                                        lineTo(x, size.height / 2f + 4f)
                                        lineTo(x - 4f, size.height / 2f)
                                        close()
                                    }
                                    drawPath(diamond, color = AresGold)
                                }
                            }
                        }

                        // Draw alert markers (Red Flags)
                        if (alerts.isNotEmpty() && sessionDuration > 0) {
                            alerts.forEach { alert ->
                                val proportion = (alert.triggerTimestampMs - sessionStart).toDouble() / sessionDuration.toDouble()
                                if (proportion in 0.0..1.0) {
                                    val x = (proportion * size.width).toFloat()
                                    // Vertical flag line
                                    drawLine(
                                        color = androidx.compose.ui.graphics.Color(0xFFFF5252).copy(alpha = 0.7f),
                                        start = androidx.compose.ui.geometry.Offset(x, 6f),
                                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                                        strokeWidth = 2f
                                    )
                                    // Downward pointing triangle flag at the top
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(x, 0f)
                                        lineTo(x - 4f, 0f)
                                        lineTo(x, 6f)
                                        lineTo(x + 4f, 0f)
                                        close()
                                    }
                                    drawPath(
                                        path = path,
                                        color = androidx.compose.ui.graphics.Color(0xFFFF5252)
                                    )
                                }
                            }
                        }
                    }
                }

                Slider(
                    value = if (sliderDragging) localSliderValue else progress.toFloat(),
                    onValueChange = { newVal ->
                        sliderDragging = true
                        localSliderValue = newVal
                    },
                    onValueChangeFinished = {
                        sliderDragging = false
                        scope.launch {
                            if (isLiveConnection && !isReplayActive) {
                                onScrubLive(localSliderValue.toDouble())
                            } else {
                                replayEngine.scrubTo(localSliderValue.toDouble())
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    colors = SliderDefaults.colors(
                        thumbColor = modeColor,
                        activeTrackColor = modeColor,
                        inactiveTrackColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }

            // Time / Live Status display
            val timeText = when {
                isLiveConnection && !isReplayActive -> "NOW"
                loadState == ReplayLoadState.LOADING -> "Loading recording…"
                loadState == ReplayLoadState.EMPTY -> "No telemetry samples"
                loadState == ReplayLoadState.ERROR -> "Replay unavailable"
                isSeeking -> "Seeking…"
                sessionInfo != null -> {
                    val elapsed = (playheadTimestamp - sessionInfo!!.startTimestampMs).coerceAtLeast(0L)
                    "${formatReplayDuration(elapsed)} / ${formatReplayDuration(sessionInfo!!.endTimestampMs - sessionInfo!!.startTimestampMs)}"
                }
                else -> "0:00.000"
            }
            Text(
                text = timeText,
                color = AresTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )

            if (currentFrame != null && !isLiveConnection) {
                val sampleAge = (currentFrame!!.playheadMs - currentFrame!!.timestampMs).coerceAtLeast(0L)
                Text(
                    text = if (sampleAge == 0L) "Exact sample" else "Held ${sampleAge} ms",
                    color = if (sampleAge == 0L) AresGreen else AresTextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }

            // Snap to Realtime button (shown only in Live Rewind mode)
            if (isLiveConnection && isReplayActive) {
                Button(
                    onClick = onSnapToRealtime,
                    colors = ButtonDefaults.buttonColors(containerColor = modeColor),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.FlashOn, contentDescription = null, tint = AresBackground, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Snap to Realtime", color = AresBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            IconButton(
                onClick = { replayEngine.setLooping(!looping) },
                enabled = replayReady && (!isLiveConnection || isReplayActive),
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = if (looping) "Disable replay loop" else "Loop replay",
                    tint = if (looping) modeColor else AresTextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Speed selector
            var speedExpanded by remember { mutableStateOf(false) }
            Box {
                TextButton(
                    onClick = { speedExpanded = !speedExpanded }
                ) {
                    Text("${speed}×", color = AresAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = speedExpanded,
                    onDismissRequest = { speedExpanded = false }
                ) {
                    listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0).forEach { s ->
                        DropdownMenuItem(
                            text = { Text("${s}×", color = AresTextPrimary) },
                            onClick = {
                                scope.launch { replayEngine.setSpeed(s) }
                                speedExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (loadState == ReplayLoadState.ERROR && !loadError.isNullOrBlank()) {
        LaunchedEffect(loadError) {
            System.err.println("[Replay] ${loadError.orEmpty()}")
        }
    }
}

internal fun formatReplayDuration(durationMs: Long): String {
    val safe = durationMs.coerceAtLeast(0L)
    val minutes = safe / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val millis = safe % 1_000L
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}
