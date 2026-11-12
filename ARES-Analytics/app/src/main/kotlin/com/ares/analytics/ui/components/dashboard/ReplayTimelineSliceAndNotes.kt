package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.analysis.MatchDebriefReportBuilder
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Categories of post-match diagnostic bookmarks and auto-detected events on the Replay timeline.
 */
enum class ReplayDiagnosticKind(
    val shortTitle: String,
) {
    VOLTAGE_SAG("Voltage Sag"),
    STICK_STEP_SAG("Stick-Step Sag"),
    CURRENT_SPIKE("Current Spike"),
    PHASE_TRANSITION("Phase"),
    LOOP_OVERRUN("Loop Overrun"),
    TRACKING_SPIKE("Tracking Spike"),
    ALERT_TRIGGER("Alert"),
}

/**
 * A single clickable diagnostic or phase event on a recorded session timeline.
 */
data class ReplayDiagnosticBookmark(
    val id: String,
    val kind: ReplayDiagnosticKind,
    val timestampMs: Long,
    val label: String,
    val detail: String,
)

internal val REPLAY_DIAGNOSTIC_KEY_PATTERNS = listOf(
    "%BatteryVoltage%",
    "%battery_voltage%",
    "%Vin%",
    "%LoopTime%",
    "%loop_time%",
    "%TrackingError%",
    "%tracking_error%",
    "%CrossTrackError%",
    "%AlongTrackError%",
    "%PoseError%",
    "%Current%",
    "%current%",
    "%Driver/%",
    "%Stick%",
    "%Throttle%",
    "%Command%",
    "%State%",
    "%Phase%",
    "%Mode%",
    "%Routine/%",
    "%Autonomous/%",
    "%Superstructure/%",
)

/**
 * Scans telemetry frames and recorded alerts to build a chronological list of diagnostic and phase
 * bookmarks for 1-click replay scrubbing.
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
    val singleMotorSpikeAmps = when (league) {
        League.FRC -> 55.0
        League.FTC -> 14.0
        League.XRP -> 2.5
    }
    val totalBusSpikeAmps = when (league) {
        League.FRC -> 140.0
        League.FTC -> 32.0
        League.XRP -> 4.5
    }

    val candidates = mutableListOf<ReplayDiagnosticBookmark>()
    val orderedFrames = frames.asSequence()
        .filter { sessionId == null || it.sessionId == sessionId }
        .sortedWith(compareBy<TelemetryFrame> { it.timestampMs }.thenBy { it.sampleOrder })
        .toList()

    val lastPhaseByTopic = HashMap<String, String>()
    val recentBatterySamples = ArrayDeque<Pair<Long, Double>>()
    var lastHighDriveDemandMs = Long.MIN_VALUE

    for (frame in orderedFrames) {
        val normalizedKey = frame.key.removePrefix("/")
        val keyLower = normalizedKey.lowercase(Locale.ROOT)
        val strVal = frame.stringValue?.trim()

        if (!strVal.isNullOrEmpty() && isPhaseTransitionTopic(normalizedKey, keyLower)) {
            val previous = lastPhaseByTopic.put(normalizedKey, strVal)
            if (previous != strVal) {
                val shortTopic = normalizedKey.substringAfterLast('/')
                candidates += ReplayDiagnosticBookmark(
                    id = "phase-$normalizedKey-${frame.timestampMs}",
                    kind = ReplayDiagnosticKind.PHASE_TRANSITION,
                    timestampMs = frame.timestampMs,
                    label = "$shortTopic → $strVal",
                    detail = if (previous == null) {
                        "$normalizedKey entered '$strVal'"
                    } else {
                        "$normalizedKey transitioned '$previous' → '$strVal'"
                    },
                )
            }
        }

        val value = frame.value
        if (!value.isFinite()) continue

        if (isDriveDemandTopic(keyLower) && abs(value) >= 0.72) {
            lastHighDriveDemandMs = frame.timestampMs
        }

        val isBatteryKey = keyLower.contains("batteryvoltage") ||
            keyLower.contains("battery_voltage") ||
            keyLower.endsWith("/vin") ||
            keyLower == "vin" ||
            keyLower.endsWith("battery")
        if (isBatteryKey && value > 0.5) {
            while (recentBatterySamples.isNotEmpty() && frame.timestampMs - recentBatterySamples.first().first > 450L) {
                recentBatterySamples.removeFirst()
            }
            val recentPeak = recentBatterySamples.maxOfOrNull { it.second }
            recentBatterySamples.addLast(frame.timestampMs to value)

            if (recentPeak != null && recentPeak - value >= 0.95 && frame.timestampMs - lastHighDriveDemandMs in -150L..500L) {
                val drop = recentPeak - value
                candidates += ReplayDiagnosticBookmark(
                    id = "sticksag-${frame.timestampMs}",
                    kind = ReplayDiagnosticKind.STICK_STEP_SAG,
                    timestampMs = frame.timestampMs,
                    label = String.format(Locale.US, "Stick-Step Sag (-%.1f V)", drop),
                    detail = String.format(
                        Locale.US,
                        "High drive demand triggered a %.2f V battery drop (%.2f V → %.2f V)",
                        drop,
                        recentPeak,
                        value,
                    ),
                )
            }

            if (value < voltageSagThreshold) {
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
        }

        val isCurrentKey = keyLower.contains("current") && !keyLower.contains("limit")
        if (isCurrentKey) {
            val isTotalBus = keyLower.contains("total") || keyLower.contains("bus") || keyLower.contains("battery")
            val threshold = if (isTotalBus) totalBusSpikeAmps else singleMotorSpikeAmps
            if (abs(value) >= threshold) {
                val channel = normalizedKey.substringBeforeLast('/').substringAfterLast('/').ifBlank {
                    normalizedKey.substringAfterLast('/')
                }
                candidates += ReplayDiagnosticBookmark(
                    id = "cur-$channel-${frame.timestampMs}",
                    kind = ReplayDiagnosticKind.CURRENT_SPIKE,
                    timestampMs = frame.timestampMs,
                    label = String.format(Locale.US, "Current %s (%.1f A)", channel, abs(value)),
                    detail = String.format(
                        Locale.US,
                        "%s drew %.1f A (>= %.1f A %s spike threshold)",
                        normalizedKey,
                        abs(value),
                        threshold,
                        league.name,
                    ),
                )
            }
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
        val alwaysKeep = bookmark.kind == ReplayDiagnosticKind.ALERT_TRIGGER ||
            bookmark.kind == ReplayDiagnosticKind.PHASE_TRANSITION
        if (alwaysKeep || prevTs == null || bookmark.timestampMs - prevTs >= 1_000L) {
            deduplicated += bookmark
            lastTimestampByKind[bookmark.kind] = bookmark.timestampMs
        }
    }
    return deduplicated.take(20)
}

private fun isPhaseTransitionTopic(normalizedKey: String, keyLower: String): Boolean =
    normalizedKey.startsWith("Routine/", ignoreCase = true) ||
        normalizedKey.startsWith("Autonomous/", ignoreCase = true) ||
        normalizedKey.startsWith("Superstructure/", ignoreCase = true) ||
        keyLower.endsWith("/state") ||
        keyLower.endsWith("/phase") ||
        keyLower.endsWith("/mode") ||
        keyLower == "robot/mode" ||
        keyLower == "match/phase"

private fun isDriveDemandTopic(keyLower: String): Boolean =
    keyLower.contains("driver/") ||
        keyLower.contains("stick") ||
        keyLower.contains("throttle") ||
        keyLower.contains("vx_cmd") ||
        keyLower.contains("vy_cmd") ||
        keyLower.contains("targetvelocity") ||
        keyLower.endsWith("/power")

internal fun DrawScope.drawReplaySliceBracket(
    sliceInMs: Long?,
    sliceOutMs: Long?,
    sessionStartMs: Long,
    sessionDurationMs: Long,
    accentColor: Color,
) {
    if (sessionDurationMs <= 0L || (sliceInMs == null && sliceOutMs == null)) return
    fun xOf(ts: Long): Float =
        (((ts - sessionStartMs).toDouble() / sessionDurationMs.toDouble()).coerceIn(0.0, 1.0) * size.width).toFloat()

    val inX = sliceInMs?.let(::xOf)
    val outX = sliceOutMs?.let(::xOf)
    if (inX != null && outX != null && outX > inX) {
        drawRect(
            color = accentColor.copy(alpha = 0.20f),
            topLeft = Offset(inX, 0f),
            size = Size(outX - inX, size.height),
        )
    }
    if (inX != null) {
        drawLine(accentColor, Offset(inX, 0f), Offset(inX, size.height), strokeWidth = 2.5f)
        drawLine(accentColor, Offset(inX, 0f), Offset((inX + 6f).coerceAtMost(size.width), 0f), strokeWidth = 2.5f)
        drawLine(accentColor, Offset(inX, size.height), Offset((inX + 6f).coerceAtMost(size.width), size.height), strokeWidth = 2.5f)
    }
    if (outX != null) {
        drawLine(accentColor, Offset(outX, 0f), Offset(outX, size.height), strokeWidth = 2.5f)
        drawLine(accentColor, Offset((outX - 6f).coerceAtLeast(0f), 0f), Offset(outX, 0f), strokeWidth = 2.5f)
        drawLine(accentColor, Offset((outX - 6f).coerceAtLeast(0f), size.height), Offset(outX, size.height), strokeWidth = 2.5f)
    }
}

@Composable
internal fun ReplayNotesAndSliceBar(
    replayEngine: ReplayEngineService,
    sessionId: String?,
    sessionStartMs: Long,
    playheadTimestampMs: Long,
    sliceInMs: Long?,
    sliceOutMs: Long?,
    annotations: List<SessionAnnotation>,
    alerts: List<AlertRecord>,
    league: League,
    databaseService: DatabaseService?,
    exportService: ExportService?,
    noteComposerOpen: Boolean,
    onNoteComposerOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var draftNoteText by remember(sessionId) { mutableStateOf("") }
    var sliceStatusMessage by remember(sessionId) { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    replayEngine.pause()
                    onNoteComposerOpenChange(!noteComposerOpen)
                },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, AresGold.copy(alpha = 0.65f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(24.dp),
            ) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = AresGold, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("+ Pin Note (M)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AresGold)
            }

            OutlinedButton(
                onClick = { replayEngine.setSliceIn() },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(24.dp),
            ) {
                val inLabel = sliceInMs?.let { formatReplayDuration((it - sessionStartMs).coerceAtLeast(0L)) } ?: "Set"
                Text("[ In: $inLabel (I)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AresCyan)
            }

            OutlinedButton(
                onClick = { replayEngine.setSliceOut() },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(24.dp),
            ) {
                val outLabel = sliceOutMs?.let { formatReplayDuration((it - sessionStartMs).coerceAtLeast(0L)) } ?: "Set"
                Text("Out: $outLabel ] (O)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AresCyan)
            }

            if (sliceInMs != null || sliceOutMs != null) {
                TextButton(
                    onClick = {
                        replayEngine.clearSlice()
                        sliceStatusMessage = null
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("Clear Slice", fontSize = 10.sp, color = AresTextSecondary)
                }
            }

            if (sliceInMs != null && sliceOutMs != null && sliceOutMs > sliceInMs && !sessionId.isNullOrBlank() && exportService != null && databaseService != null) {
                val activeIn: Long = sliceInMs
                val activeOut: Long = sliceOutMs
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val dest = withContext(Dispatchers.IO) {
                                DesktopFileChoosers.chooseSaveFile(
                                    dialogTitle = "Export Replay Time-Slice CSV",
                                    defaultFileName = "${sessionId.take(12)}_slice_${activeIn}_${activeOut}.csv",
                                    filterDescription = "CSV Files (*.csv)",
                                    extensions = listOf("csv"),
                                )
                            } ?: return@launch
                            runCatching {
                                exportService.exportWindowToCsvList(sessionId, activeIn, activeOut, dest)
                            }.onSuccess {
                                sliceStatusMessage = "Exported slice CSV: ${dest.name}"
                            }.onFailure { err ->
                                sliceStatusMessage = "Slice CSV export failed: ${err.message}"
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Icon(Icons.Default.TableChart, contentDescription = null, tint = AresGreen, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Slice .csv", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AresGreen)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val dest = withContext(Dispatchers.IO) {
                                DesktopFileChoosers.chooseSaveFile(
                                    dialogTitle = "Export Replay Time-Slice Debrief (.md)",
                                    defaultFileName = "${sessionId.take(12)}_slice_debrief.md",
                                    filterDescription = "Markdown Files (*.md)",
                                    extensions = listOf("md"),
                                )
                            } ?: return@launch
                            runCatching {
                                val frames = withContext(Dispatchers.IO) {
                                    databaseService.getTelemetryRange(sessionId, activeIn, activeOut)
                                }
                                val sliceAlerts = alerts.filter { it.triggerTimestampMs in activeIn..activeOut }
                                val sliceNotes = annotations.filter { it.effectiveTimestampMs() in activeIn..activeOut }
                                val md = MatchDebriefReportBuilder.buildSliceMarkdownDebrief(
                                    sessionId = sessionId,
                                    league = league,
                                    sessionStartMs = sessionStartMs,
                                    inMs = activeIn,
                                    outMs = activeOut,
                                    sliceFrames = frames,
                                    sliceAlerts = sliceAlerts,
                                    sliceAnnotations = sliceNotes,
                                )
                                exportService.writeTextReportAtomically(dest, md)
                            }.onSuccess {
                                sliceStatusMessage = "Exported slice debrief: ${dest.name}"
                            }.onFailure { err ->
                                sliceStatusMessage = "Slice debrief failed: ${err.message}"
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = AresGreen, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Slice .md", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AresGreen)
                }
            }

            annotations.forEach { note ->
                val relMs = (note.effectiveTimestampMs() - sessionStartMs).coerceAtLeast(0L)
                Surface(
                    color = AresGold.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, AresGold.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = { replayEngine.seekToTimestamp(note.effectiveTimestampMs()) },
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            modifier = Modifier.height(22.dp),
                        ) {
                            Text(
                                text = "◆ ${formatReplayDuration(relMs)} · ${note.text.take(36)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AresGold,
                            )
                        }
                        IconButton(
                            onClick = { scope.launch { replayEngine.deleteAnnotation(note.annotationId) } },
                            modifier = Modifier.size(16.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Delete note", tint = AresTextSecondary, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }

            sliceStatusMessage?.let { msg ->
                Text(msg, fontSize = 10.sp, color = AresTextSecondary)
            }
        }

        if (noteComposerOpen) {
            val relPlayhead = formatReplayDuration((playheadTimestampMs - sessionStartMs).coerceAtLeast(0L))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draftNoteText,
                    onValueChange = { draftNoteText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text("Pin replay note @ $relPlayhead (e.g. 'Intake jammed on bounce')", fontSize = 11.sp)
                    },
                )
                Button(
                    onClick = {
                        val toSave = draftNoteText.trim()
                        if (toSave.isNotEmpty()) {
                            scope.launch {
                                replayEngine.addPlayheadAnnotation(text = toSave, playheadMs = playheadTimestampMs)
                                draftNoteText = ""
                                onNoteComposerOpenChange(false)
                            }
                        }
                    },
                    enabled = draftNoteText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AresGold, contentColor = AresOnAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Pin @ $relPlayhead", color = AresOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { onNoteComposerOpenChange(false) }) {
                    Text("Cancel", fontSize = 11.sp, color = AresTextSecondary)
                }
            }
        }
    }
}

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
            text = "Events & Diagnostics (${bookmarks.size}):",
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
    ReplayDiagnosticKind.STICK_STEP_SAG -> AresError
    ReplayDiagnosticKind.CURRENT_SPIKE -> AresAmber
    ReplayDiagnosticKind.PHASE_TRANSITION -> AresPurple
    ReplayDiagnosticKind.LOOP_OVERRUN -> AresGold
    ReplayDiagnosticKind.TRACKING_SPIKE -> AresCyan
    ReplayDiagnosticKind.ALERT_TRIGGER -> AresError
}

private fun ReplayDiagnosticKind.icon(): ImageVector = when (this) {
    ReplayDiagnosticKind.VOLTAGE_SAG -> Icons.Default.BatteryAlert
    ReplayDiagnosticKind.STICK_STEP_SAG -> Icons.Default.ElectricBolt
    ReplayDiagnosticKind.CURRENT_SPIKE -> Icons.Default.Bolt
    ReplayDiagnosticKind.PHASE_TRANSITION -> Icons.Default.AccountTree
    ReplayDiagnosticKind.LOOP_OVERRUN -> Icons.Default.Speed
    ReplayDiagnosticKind.TRACKING_SPIKE -> Icons.Default.GpsOff
    ReplayDiagnosticKind.ALERT_TRIGGER -> Icons.Default.Flag
}
