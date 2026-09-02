package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.service.ReplayLoadState
import com.ares.analytics.service.ReplayState
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.SessionMode
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun ReplayTimelineScrubber(
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
    // Kotlin synthetic DashboardScreenKt class and crashed as soon as
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
        border = BorderStroke(1.dp, modeColor.copy(alpha = 0.5f))
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
                border = BorderStroke(1.dp, modeColor)
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
                            "REPLAY · "
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
                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        if (density.isNotEmpty()) {
                            val barWidth = size.width / density.size
                            density.forEachIndexed { i, value ->
                                val barHeight = size.height * value
                                val x = i * barWidth
                                val y = size.height - barHeight
                                drawRect(
                                    color = modeColor.copy(alpha = 0.3f),
                                    topLeft = Offset(x, y),
                                    size = Size(barWidth * 0.8f, barHeight)
                                )
                            }
                        }

                        // Draw action markers
                        if (actions.isNotEmpty() && sessionDuration > 0) {
                            actions.forEach { action ->
                                val proportion = (action.timestampMs - sessionStart).toDouble() / sessionDuration.toDouble()
                                val x = (proportion * size.width).toFloat()
                                drawCircle(
                                    color = Color(0xFF00E5FF), // Cyan marker
                                    radius = 3f,
                                    center = Offset(x, size.height / 2f)
                                )
                            }
                        }

                        // Gold note markers are distinct in both color and shape from actions and alerts.
                        if (annotations.isNotEmpty() && sessionDuration > 0) {
                            annotations.forEach { annotation ->
                                val proportion = (annotation.createdAt - sessionStart).toDouble() / sessionDuration.toDouble()
                                if (proportion in 0.0..1.0) {
                                    val x = (proportion * size.width).toFloat()
                                    val diamond = Path().apply {
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
                                        color = Color(0xFFFF5252).copy(alpha = 0.7f),
                                        start = Offset(x, 6f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 2f
                                    )
                                    // Downward pointing triangle flag at the top
                                    val path = Path().apply {
                                        moveTo(x, 0f)
                                        lineTo(x - 4f, 0f)
                                        lineTo(x, 6f)
                                        lineTo(x + 4f, 0f)
                                        close()
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(0xFFFF5252)
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
                        inactiveTrackColor = Color.Transparent
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
                    " / "
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
                    text = if (sampleAge == 0L) "Exact sample" else "Held  ms",
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
                    Text("×", color = AresAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = speedExpanded,
                    onDismissRequest = { speedExpanded = false }
                ) {
                    listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0).forEach { s ->
                        DropdownMenuItem(
                            text = { Text("×", color = AresTextPrimary) },
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
            System.err.println("[Replay] ")
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
