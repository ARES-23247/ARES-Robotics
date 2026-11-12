package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.components.core.*
import com.ares.analytics.ui.theme.*
import java.util.concurrent.ConcurrentHashMap

internal class RunningStats {
    var current: Double = 0.0
    var sum: Double = 0.0
    var count: Long = 0L
    var max: Double = 0.0

    fun update(value: Double) {
        if (!value.isFinite() || value < 0.0) return
        current = value
        sum += value
        count++
        if (value > max) max = value
    }

    fun reset() {
        current = 0.0
        sum = 0.0
        count = 0L
        max = 0.0
    }

    val average: Double
        get() = if (count > 0) sum / count else 0.0
}

internal fun extractReplayProfilingValues(frame: ReplayFrame): Map<String, Double> {
    val result = LinkedHashMap<String, Double>()
    for ((rawKey, value) in frame.values) {
        if (rawKey in frame.stringValues || !value.isFinite() || value < 0.0) continue
        val key = rawKey.trim().trimStart('/')
        if (key.startsWith("Profiling/")) {
            val cleanName = key.removePrefix("Profiling/").removeSuffix("_ms")
            if (cleanName.isNotBlank()) result[cleanName] = value
        } else if (key.equals("Robot/LoopTimeMs", ignoreCase = true) && "Total" !in result) {
            result["Total"] = value
        }
    }
    return result
}

@Composable
fun ProfilingDiagnosticsCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier,
    replayFrame: ReplayFrame? = null,
) {
    val statsMap = remember(replayFrame?.sessionId ?: "live") { ConcurrentHashMap<String, RunningStats>() }
    var lastReplayPlayheadMs by remember(replayFrame?.sessionId) { mutableStateOf(Long.MIN_VALUE) }
    var updateTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(nt4ClientService, replayFrame != null) {
        if (replayFrame != null) return@LaunchedEffect
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            val key = frame.key.removePrefix("/")
            if (key.startsWith("Profiling/")) {
                val cleanKey = key.removePrefix("Profiling/").removeSuffix("_ms")
                val stats = statsMap.getOrPut(cleanKey) { RunningStats() }
                stats.update(frame.value)
                updateTrigger++
            }
        }
    }

    LaunchedEffect(replayFrame?.sessionId, replayFrame?.sequence) {
        val replay = replayFrame ?: return@LaunchedEffect
        if (replay.playheadMs < lastReplayPlayheadMs) {
            statsMap.values.forEach { it.reset() }
        }
        lastReplayPlayheadMs = replay.playheadMs
        val currentFrameMetrics = extractReplayProfilingValues(replay)
        currentFrameMetrics.forEach { (name, ms) ->
            statsMap.getOrPut(name) { RunningStats() }.update(ms)
        }
        if (currentFrameMetrics.isNotEmpty()) updateTrigger++
    }

    AresCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = if (replayFrame != null) "Loop profiling · Replay" else "Loop profiling diagnostics",
            icon = Icons.Default.HourglassEmpty,
            iconTint = AresCyan,
            trailingContent = {
                IconButton(
                    onClick = {
                        statsMap.values.forEach { it.reset() }
                        updateTrigger++
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Statistics",
                        tint = AresTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Component", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
            Text("Current", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Average", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Maximum", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }

        val statsList = remember(updateTrigger, statsMap) {
            statsMap.entries.map { it.key to it.value }.sortedBy { it.first }
        }

        if (statsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Waiting for robot Profiling telemetry...",
                    color = AresTextTertiary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                statsList.forEach { (name, stats) ->
                    val displayName = name.replace(Regex("([a-z])([A-Z]+)"), "$1 $2").replaceFirstChar { it.uppercase() }
                    val isOverrunAlert = name.equals("Total", ignoreCase = true) && stats.current > 20.0
                    val textColor = when {
                        isOverrunAlert -> AresRed
                        name.equals("Total", ignoreCase = true) -> AresCyan
                        else -> AresTextPrimary
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AresBackground.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .border(1.dp, AresBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = if (name.equals("Total", ignoreCase = true)) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            text = String.format("%.2f ms", stats.current),
                            color = textColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format("%.2f ms", stats.average),
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format("%.2f ms", stats.max),
                            color = if (stats.max > 20.0) AresRed else AresTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (stats.max > 20.0) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
