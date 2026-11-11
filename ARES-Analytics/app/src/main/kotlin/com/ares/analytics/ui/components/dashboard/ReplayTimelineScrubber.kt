package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.service.ReplayLoadState
import com.ares.analytics.service.ReplayState
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.SessionMode
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier,
    league: League = League.FTC,
    databaseService: DatabaseService? = null,
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
    val diagnosticFrames by produceState<List<TelemetryFrame>>(emptyList(), sessionId, loadState, databaseService) {
        val activeSession = sessionId
        value = if (!activeSession.isNullOrBlank() && databaseService != null && loadState == ReplayLoadState.READY) {
            withContext(Dispatchers.IO) {
                runCatching {
                    databaseService.getTelemetryForKeyPatterns(activeSession, REPLAY_DIAGNOSTIC_KEY_PATTERNS)
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }
    val diagnosticBookmarks = remember(sessionId, league, diagnosticFrames, alerts) {
        buildReplayDiagnosticBookmarks(
            sessionId = sessionId,
            league = league,
            frames = diagnosticFrames,
            alerts = alerts,
        )
    }
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
        Column(modifier = Modifier.fillMaxWidth()) {
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
                    "${formatReplayDuration(elapsed)} / ${formatReplayDuration(sessionDuration)}"
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
        if (diagnosticBookmarks.isNotEmpty() && (!isLiveConnection || isReplayActive)) {
            ReplayDiagnosticBookmarkBar(
                bookmarks = diagnosticBookmarks,
                sessionStartMs = sessionInfo?.startTimestampMs ?: 0L,
                onSelectBookmark = { bookmark ->
                    scope.launch { replayEngine.seekToTimestamp(bookmark.timestampMs) }
                },
            )
        }
        }
    }

    if (loadState == ReplayLoadState.ERROR && !loadError.isNullOrBlank()) {
        LaunchedEffect(loadError) {
            System.err.println("[Replay] $loadError")
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

/**
 * Categories of post-match diagnostic bookmarks surfaced on the Replay timeline.
 */
enum class ReplayDiagnosticKind(
    val shortTitle: String,
) {
    VOLTAGE_SAG("Voltage Sag"),
    LOOP_OVERRUN("Loop Overrun"),
    TRACKING_SPIKE("Tracking Spike"),
    ALERT_TRIGGER("Alert"),
}

/**
 * A single clickable diagnostic event on a recorded session timeline.
 */
data class ReplayDiagnosticBookmark(
    val id: String,
    val kind: ReplayDiagnosticKind,
    val timestampMs: Long,
    val label: String,
    val detail: String,
)

/**
 * Scans telemetry frames and recorded alerts to build a chronological list of "What Went Wrong?"
 * diagnostic bookmarks for 1-click replay scrubbing.
 */
fun buildReplayDiagnosticBookmarks(
    sessionId: String?,
    league: League,
    frames: List<TelemetryFrame>,
    alerts: List<AlertRecord>,
): List<ReplayDiagnosticBookmark> {
    val voltageSagThreshold = when (league) {
        League.FRC -> 8.5
        League.FTC -> 10.0
        League.XRP -> 4.5
    }
    val candidates = mutableListOf<ReplayDiagnosticBookmark>()

    frames.asSequence()
        .filter { sessionId == null || it.sessionId == sessionId }
        .forEach { frame ->
            val keyLower = frame.key.lowercase(Locale.ROOT)
            val value = frame.value
            if (!value.isFinite()) return@forEach

            val isBatteryKey = keyLower.contains("batteryvoltage") ||
                keyLower.contains("battery_voltage") ||
                keyLower.endsWith("/vin") ||
                keyLower == "vin" ||
                keyLower.endsWith("battery")
            if (isBatteryKey && value > 0.5 && value < voltageSagThreshold) {
                candidates += ReplayDiagnosticBookmark(
                    id = "sag-${frame.timestampMs}",
                    kind = ReplayDiagnosticKind.VOLTAGE_SAG,
                    timestampMs = frame.timestampMs,
                    label = String.format(Locale.US, "Voltage Sag (%.1f V)", value),
                    detail = String.format(
                        Locale.US,
                        "Battery dipped to %.2f V (below %.1f V %s threshold)",
                        value,
                        voltageSagThreshold,
                        league.name,
                    ),
                )
            }

            val isLoopTimeKey = keyLower.contains("looptime") || keyLower.contains("loop_time")
            if (isLoopTimeKey && value > 25.0) {
                candidates += ReplayDiagnosticBookmark(
                    id = "loop-${frame.timestampMs}",
                    kind = ReplayDiagnosticKind.LOOP_OVERRUN,
                    timestampMs = frame.timestampMs,
                    label = String.format(Locale.US, "Loop Overrun (%.1f ms)", value),
                    detail = String.format(Locale.US, "Control loop took %.1f ms (> 25.0 ms budget)", value),
                )
            }

            val isTrackingErrorKey = keyLower.contains("trackingerror") ||
                keyLower.contains("tracking_error") ||
                keyLower.contains("crosstrackerror") ||
                keyLower.contains("alongtrackerror") ||
                keyLower.contains("poseerror")
            if (isTrackingErrorKey && abs(value) > 0.15) {
                candidates += ReplayDiagnosticBookmark(
                    id = "track-${frame.timestampMs}",
                    kind = ReplayDiagnosticKind.TRACKING_SPIKE,
                    timestampMs = frame.timestampMs,
                    label = String.format(Locale.US, "Tracking Spike (%.2f m)", abs(value)),
                    detail = String.format(Locale.US, "Path tracking error reached %.2f m (> 0.15 m)", abs(value)),
                )
            }
        }

    alerts.asSequence()
        .filter { sessionId == null || it.sessionId == sessionId }
        .forEach { alert ->
            val shortRule = alert.ruleKey.trim('/').substringAfterLast('/')
            candidates += ReplayDiagnosticBookmark(
                id = "alert-${alert.alertId}-${alert.triggerTimestampMs}",
                kind = ReplayDiagnosticKind.ALERT_TRIGGER,
                timestampMs = alert.triggerTimestampMs,
                label = "Alert: $shortRule",
                detail = "${alert.ruleKey} triggered (peak ${String.format(Locale.US, "%.2f", alert.peakValue)})",
            )
        }

    if (candidates.isEmpty()) return emptyList()

    val sorted = candidates.sortedBy { it.timestampMs }
    val deduplicated = mutableListOf<ReplayDiagnosticBookmark>()
    val lastTimestampByKind = mutableMapOf<ReplayDiagnosticKind, Long>()

    for (bookmark in sorted) {
        val prevTs = lastTimestampByKind[bookmark.kind]
        if (prevTs == null || bookmark.timestampMs - prevTs >= 1_000L || bookmark.kind == ReplayDiagnosticKind.ALERT_TRIGGER) {
            deduplicated += bookmark
            lastTimestampByKind[bookmark.kind] = bookmark.timestampMs
        }
    }
    return deduplicated.take(16)
}

internal val REPLAY_DIAGNOSTIC_KEY_PATTERNS = listOf(
    "%BatteryVoltage%",
    "%battery_voltage%",
    "%LoopTime%",
    "%loop_time%",
    "%TrackingError%",
    "%tracking_error%",
    "%CrossTrackError%",
    "%AlongTrackError%",
    "%PoseError%",
)

@Composable
internal fun ReplayDiagnosticBookmarkBar(
    bookmarks: List<ReplayDiagnosticBookmark>,
    sessionStartMs: Long,
    onSelectBookmark: (ReplayDiagnosticBookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bookmarks.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "What Went Wrong? (${bookmarks.size}):",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = AresTextSecondary,
        )
        bookmarks.forEach { bookmark ->
            val color = bookmark.kind.accentColor()
            val relativeMs = if (sessionStartMs > 0L) {
                (bookmark.timestampMs - sessionStartMs).coerceAtLeast(0L)
            } else {
                0L
            }
            OutlinedButton(
                onClick = { onSelectBookmark(bookmark) },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
            ) {
                Icon(
                    imageVector = bookmark.kind.icon(),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${bookmark.label} @ ${formatReplayDuration(relativeMs)}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
            }
        }
    }
}

private fun ReplayDiagnosticKind.accentColor(): Color = when (this) {
    ReplayDiagnosticKind.VOLTAGE_SAG -> AresAmber
    ReplayDiagnosticKind.LOOP_OVERRUN -> AresGold
    ReplayDiagnosticKind.TRACKING_SPIKE -> AresCyan
    ReplayDiagnosticKind.ALERT_TRIGGER -> AresError
}

private fun ReplayDiagnosticKind.icon(): ImageVector = when (this) {
    ReplayDiagnosticKind.VOLTAGE_SAG -> Icons.Default.BatteryAlert
    ReplayDiagnosticKind.LOOP_OVERRUN -> Icons.Default.Speed
    ReplayDiagnosticKind.TRACKING_SPIKE -> Icons.Default.GpsOff
    ReplayDiagnosticKind.ALERT_TRIGGER -> Icons.Default.Flag
}
