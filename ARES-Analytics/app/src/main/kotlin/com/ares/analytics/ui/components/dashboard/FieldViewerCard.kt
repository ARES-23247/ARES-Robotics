package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.ui.Alignment
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.League
import com.ares.analytics.ui.components.pathplanner.FieldCanvas
import com.ares.analytics.ui.components.pathplanner.IndicatorLightRenderState
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.FieldViewerViewModel
import com.ares.analytics.viewmodel.FieldViewerIntent
import com.ares.analytics.viewmodel.LivePoseState
import com.ares.analytics.viewmodel.field.toReplayPoseState
import com.ares.analytics.viewmodel.field.loadReplayFieldTrace
import com.ares.analytics.viewmodel.field.loadRobotLightingPlacements
import androidx.compose.material.icons.filled.SwapHoriz

private fun waypointOrNull(x: Double?, y: Double?, headingRad: Double?): Waypoint? {
    return Waypoint(x ?: return null, y ?: return null, headingRad ?: return null)
}

/** Keeps the live simulator robot current even when the optional trace publishes less often. */
internal fun fieldRobotPath(
    poseHistory: List<Waypoint>,
    liveTruePose: Waypoint?,
    tracerEnabled: Boolean,
): List<Waypoint> = when {
    liveTruePose == null -> poseHistory
    !tracerEnabled -> listOf(liveTruePose)
    poseHistory.lastOrNull() == liveTruePose -> poseHistory
    else -> poseHistory + liveTruePose
}

/** Keeps the estimator overlay independent from simulator truth so EKF error remains observable. */
internal fun fieldEstimatedPose(liveState: LivePoseState): Waypoint? =
    waypointOrNull(liveState.ekfX, liveState.ekfY, liveState.ekfHeading)

@Composable
fun FieldViewerCard(
    nt4ClientService: Nt4ClientService,
    currentFrame: com.ares.analytics.service.ReplayFrame? = null,
    databaseService: DatabaseService? = null,
    replayStartTimestampMs: Long = 0L,
    league: League,
    projectPath: String? = null,
    properties: Map<String, String> = emptyMap(),
    onPropertiesChanged: (Map<String, String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(nt4ClientService) { FieldViewerViewModel(nt4ClientService, scope) }
    val state by viewModel.state.collectAsState()
    val observedLiveState by viewModel.livePose.collectAsState()
    val liveState = remember(currentFrame?.sequence, observedLiveState) {
        currentFrame?.toReplayPoseState() ?: observedLiveState
    }
    val lightingPlacements by produceState(initialValue = emptyMap(), projectPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadRobotLightingPlacements(projectPath)
        }
    }
    val replayTraceBucket = currentFrame?.playheadMs?.div(250L)
    val replayTrace by produceState<List<Waypoint>>(
        initialValue = emptyList(),
        currentFrame?.sessionId,
        replayTraceBucket,
        replayStartTimestampMs,
    ) {
        val replay = currentFrame
        val database = databaseService
        value = if (replay == null || database == null) emptyList() else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadReplayFieldTrace(
                    database = database,
                    sessionId = replay.sessionId,
                    startMs = replayStartTimestampMs,
                    endMs = replay.playheadMs,
                )
            }
        }
    }
    val liveTruePose = if (liveState.hasTruePoseData) {
        waypointOrNull(liveState.trueX, liveState.trueY, liveState.trueHeading)
    } else null
    val estimatedPose = fieldEstimatedPose(liveState)
    val odomPose = waypointOrNull(liveState.odomX, liveState.odomY, liveState.odomHeading)
    var showEkfPose by remember { mutableStateOf(true) }
    var showOdomPose by remember { mutableStateOf(true) }
    var showVisionPoses by remember { mutableStateOf(true) }
    var layersMenuExpanded by remember { mutableStateOf(false) }
    val activeVisionPoses = remember(liveState.visionPoses, liveState.visionX, liveState.visionY, liveState.visionHeading, liveState.visionHasTarget) {
        val list = mutableListOf<Waypoint>()
        if (liveState.visionHasTarget) {
            val maxIndex = liveState.visionPoses.keys.maxOrNull() ?: -1
            for (i in 0..maxIndex step 3) {
                val vx = liveState.visionPoses[i]
                val vy = liveState.visionPoses[i + 1]
                val vh = liveState.visionPoses[i + 2]
                waypointOrNull(vx, vy, vh)?.let(list::add)
            }
            if (list.isEmpty()) {
                waypointOrNull(liveState.visionX, liveState.visionY, liveState.visionHeading)
                    ?.let(list::add)
            }
        }
        list
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = AresSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    if (currentFrame == null) "Field 2D Live Tracker" else "Field 2D Replay",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    when {
                        currentFrame != null && currentFrame.playheadMs == currentFrame.timestampMs -> "Replay · sample exact"
                        currentFrame != null -> "Replay · sample ${currentFrame.playheadMs - currentFrame.timestampMs} ms old"
                        liveState.isConnected -> "Connected"
                        else -> "Offline"
                    },
                    color = if (liveState.isConnected) AresGreen else AresTextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val currentRotation = properties["rotation"]?.toFloatOrNull() ?: 0f
                    IconButton(
                        onClick = {
                            val nextRot = (currentRotation + 90f) % 360f
                            onPropertiesChanged(properties + ("rotation" to nextRot.toString()))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "Rotate",
                            tint = AresTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    val showTracer = properties["show_tracer"]?.toBoolean() ?: false
                    IconButton(
                        onClick = { onPropertiesChanged(properties + ("show_tracer" to (!showTracer).toString())) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = "Toggle Tracer",
                            tint = if (showTracer) AresCyan else AresTextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { layersMenuExpanded = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = "Robot Poses Layers",
                                tint = if (showEkfPose || showOdomPose || showVisionPoses) AresCyan else AresTextTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = layersMenuExpanded,
                            onDismissRequest = { layersMenuExpanded = false },
                            modifier = Modifier.background(AresBackground)
                        ) {
                            val allSelected = showEkfPose && showOdomPose && showVisionPoses
                            DropdownMenuItem(onClick = {
                                val target = !allSelected
                                showEkfPose = target
                                showOdomPose = target
                                showVisionPoses = target
                            }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = allSelected,
                                        onCheckedChange = { target ->
                                            showEkfPose = target
                                            showOdomPose = target
                                            showVisionPoses = target
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AresCyan,
                                            uncheckedColor = AresTextTertiary,
                                            checkmarkColor = AresBackground
                                        )
                                    )
                                    Text("Select All", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 12.sp)
                                }
                            }
                            DropdownMenuItem(onClick = { showEkfPose = !showEkfPose }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = showEkfPose,
                                        onCheckedChange = { showEkfPose = it },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AresAmber,
                                            uncheckedColor = AresTextTertiary,
                                            checkmarkColor = AresBackground
                                        )
                                    )
                                    Text(
                                        "Estimated (EKF)",
                                        color = AresTextPrimary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            DropdownMenuItem(onClick = { showOdomPose = !showOdomPose }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = showOdomPose,
                                        onCheckedChange = { showOdomPose = it },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AresGreen,
                                            uncheckedColor = AresTextTertiary,
                                            checkmarkColor = AresBackground
                                        )
                                    )
                                    Text("Pinpoint (Odom)", color = AresTextPrimary, fontSize = 12.sp)
                                }
                            }
                            DropdownMenuItem(onClick = { showVisionPoses = !showVisionPoses }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = showVisionPoses,
                                        onCheckedChange = { showVisionPoses = it },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AresGold,
                                            uncheckedColor = AresTextTertiary,
                                            checkmarkColor = AresBackground
                                        )
                                    )
                                    Text("Vision (Limelight)", color = AresTextPrimary, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = { viewModel.onIntent(FieldViewerIntent.ToggleAlliance) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Toggle Alliance",
                            tint = if (state.isRedAlliance) AresRed else AresCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.onIntent(FieldViewerIntent.ClearTrace) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Trace",
                            tint = AresTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = AresBorder)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
            ) {
                val tracerEnabled = properties["show_tracer"]?.toBoolean() == true

                FieldCanvas(
                    league = league,
                    waypoints = emptyList(),
                    actualPath = fieldRobotPath(
                        poseHistory = if (currentFrame == null) state.poseHistory else replayTrace,
                        liveTruePose = liveTruePose,
                        tracerEnabled = tracerEnabled,
                    ),
                    onWaypointsChanged = {},
                    projectPath = projectPath,
                    estimatedPose = estimatedPose,
                    odomPose = odomPose,
                    visionPoses = activeVisionPoses,
                    showTruePose = liveState.hasTruePoseData,
                    showEkfPose = showEkfPose,
                    showOdomPose = showOdomPose,
                    showVisionPoses = showVisionPoses,
                    gamePieces = liveState.liveGamePieces.values.toList(),
                    showPathControls = false,
                    showObstacleControls = false,
                    showToolbar = false,
                    initialViewRotation = properties["rotation"]?.toFloatOrNull() ?: 0f,
                    onViewRotationChanged = { newRot -> onPropertiesChanged(properties + ("rotation" to newRot.toString())) },
                    indicatorLights = liveState.indicatorLights.toSortedMap().map { (name, position) ->
                        val placement = lightingPlacements[name]
                        IndicatorLightRenderState(
                            position = position,
                            forwardFraction = placement?.forwardFraction ?: 0.0,
                            leftFraction = placement?.leftFraction ?: 0.0,
                        )
                    },
                    prismPulseWidthUs = liveState.prismLights.toSortedMap().values.firstOrNull(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
