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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.ares.analytics.ui.components.core.*

private class RunningStats {
    var current: Double = 0.0
    var sum: Double = 0.0
    var count: Long = 0L
    var max: Double = 0.0

    fun update(value: Double) {
        current = value
        sum += value
        count++
        if (value > max) {
            max = value
        }
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

@Composable
fun ProfilingDiagnosticsCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Concurrent map to hold running stats for each profiling key
    val statsMap = remember { ConcurrentHashMap<String, RunningStats>() }
    // State trigger to force recomposition when stats update
    var updateTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        scope.launch {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                val key = frame.key
                if (key.startsWith("Profiling/")) {
                    val cleanKey = key.removePrefix("Profiling/").removeSuffix("_ms")
                    val stats = statsMap.getOrPut(cleanKey) { RunningStats() }
                    stats.update(frame.value)
                    updateTrigger++
                }
            }
        }
    }

    AnalyticsCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = "Loop profiling diagnostics",
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

            // Table Header
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

            // Key mapping for update trigger
            val statsList = remember(updateTrigger) {
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
                        val isOverrunAlert = name.equals("Total", ignoreCase = true) && stats.current > 20.0 // 50Hz budget = 20ms
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

