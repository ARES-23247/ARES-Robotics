package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.ares.analytics.ui.components.core.*

internal data class PowerCurrentTopic(val channelName: String?, val isTotal: Boolean)

private val MOTOR_CURRENT_TOPIC = Regex("^Hardware/Motors/([^/]+)/Current(?:Amps)?$", RegexOption.IGNORE_CASE)
private val DRIVE_MOTOR_CURRENT_TOPIC = Regex("^Drive/MotorCurrent_([^/]+)$", RegexOption.IGNORE_CASE)
private val POWER_CHANNEL_TOPIC = Regex(
    "^(?:PDH|PDP)/Channel(?:/)?([^/]+?)(?:_Current|/Current(?:Amps)?)$",
    RegexOption.IGNORE_CASE
)

internal fun parsePowerCurrentTopic(rawKey: String): PowerCurrentTopic? {
    val key = rawKey.trim().trimStart('/')
    if (key.equals("Robot/TotalCurrentAmps", ignoreCase = true) ||
        key.equals("PDH/TotalCurrent", ignoreCase = true) ||
        key.equals("PDP/TotalCurrent", ignoreCase = true)
    ) {
        return PowerCurrentTopic(channelName = null, isTotal = true)
    }

    val channel = MOTOR_CURRENT_TOPIC.matchEntire(key)?.groupValues?.get(1)
        ?: DRIVE_MOTOR_CURRENT_TOPIC.matchEntire(key)?.groupValues?.get(1)
        ?: POWER_CHANNEL_TOPIC.matchEntire(key)?.groupValues?.get(1)
        ?: return null
    return PowerCurrentTopic(channelName = channel, isTotal = false)
}

@Composable
fun PowerDistributionCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    // Map of channel name to current in amps
    val currentDraws = remember(nt4ClientService) { mutableStateMapOf<String, Double>() }
    var reportedTotalCurrent by remember(nt4ClientService) { mutableStateOf<Double?>(null) }

    LaunchedEffect(nt4ClientService) {
        launch {
            nt4ClientService.isConnected.collect { connected ->
                if (!connected) {
                    currentDraws.clear()
                    reportedTotalCurrent = null
                }
            }
        }
        launch {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                if (!frame.value.isFinite() || frame.value < 0.0) return@collect
                parsePowerCurrentTopic(frame.key)?.let { topic ->
                    if (topic.isTotal) {
                        reportedTotalCurrent = frame.value
                    } else {
                        topic.channelName?.let { channel ->
                            currentDraws[channel] = frame.value
                        }
                    }
                }
            }
        }
    }
    val displayedTotalCurrent = reportedTotalCurrent ?: currentDraws.values
        .sum()
        .takeIf { currentDraws.isNotEmpty() }

    AresCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = "Power Distribution",
            icon = Icons.Default.ElectricBolt,
            iconTint = AresGold,
            trailingContent = {
                Text(
                    text = displayedTotalCurrent?.let { String.format("%.1f A Total", it) } ?: "--- A Total",
                    color = if ((displayedTotalCurrent ?: 0.0) > 120.0) AresError else AresGold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        )

            if (currentDraws.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No current draw telemetry detected.", color = AresTextTertiary, fontSize = 12.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    currentDraws.toList().sortedByDescending { it.second }.take(6).forEach { (channel, amps) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = channel.take(12),
                                color = AresTextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.width(80.dp),
                                maxLines = 1
                            )

                            // Visual bar
                            Box(modifier = Modifier.weight(1f).height(12.dp).background(AresSurface, RoundedCornerShape(4.dp))) {
                                val fraction = (amps / 40.0).coerceIn(0.0, 1.0).toFloat()
                                val barColor = when {
                                    amps > 30.0 -> AresError
                                    amps > 20.0 -> AresGold
                                    else -> AresCyan
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction)
                                        .background(barColor, RoundedCornerShape(4.dp))
                                )
                            }

                            Text(
                                text = String.format("%5.1f A", amps),
                                color = AresTextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(60.dp).padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

