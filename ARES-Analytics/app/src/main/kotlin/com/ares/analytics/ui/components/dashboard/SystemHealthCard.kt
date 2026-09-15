package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.DashboardHealthService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.components.core.*

@Composable
fun SystemHealthCard(
    nt4ClientService: Nt4ClientService,
    dashboardHealthService: DashboardHealthService? = null,
    currentFrame: ReplayFrame? = null,
    league: League,
    isRobotLinkConnected: Boolean,
    xrpBrownoutThresholdVolts: Double? = null,
    modifier: Modifier = Modifier,
    controllerHealth: ControllerHealthObservation? = null,
    replaySelected: Boolean = currentFrame != null,
) {
    val health = controllerHealth ?: run {
        val replayActive by nt4ClientService.isReplayActive.collectAsState()
        rememberControllerHealth(nt4ClientService, currentFrame, replaySelected || replayActive, isRobotLinkConnected)
    }
    val (loopTimeMs, batteryVoltage, brownoutCount, loopOverruns, ftcRuntime) = health.snapshot
    val runtimeHealth = dashboardHealthService?.health?.collectAsState()?.value

    AresCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = controllerHealthTitle(league),
            icon = Icons.Default.Memory,
            iconTint = if (health.source != ControllerHealthSource.OFFLINE) AresGreen else AresTextTertiary,
            statusText = when {
                health.source == ControllerHealthSource.REPLAY -> "REPLAY"
                health.source == ControllerHealthSource.LIVE -> "LIVE"
                else -> "OFFLINE"
            },
            statusColor = if (health.source != ControllerHealthSource.OFFLINE) AresGreen else AresTextTertiary
        )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Loop Time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOOP TIME", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val hz = loopTimeMs?.let { 1000.0 / it }
                    val loopColor = when {
                        hz == null -> AresTextTertiary
                        hz < 35.0 -> AresError
                        hz < 45.0 -> AresGold
                        else -> AresGreen
                    }
                    Text(
                        text = loopTimeMs?.let { String.format("%.1f ms", it) } ?: "--",
                        color = loopColor,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (hz != null) String.format("(%.0f Hz)", hz) else "",
                        color = loopColor,
                        fontSize = 12.sp
                    )
                    if (league == League.FTC) {
                        val runtimePresentation = ftcRuntime.presentation()
                        Text(
                            text = runtimePresentation.transportLabel,
                            color = when (runtimePresentation.transportTone) {
                                FtcRuntimeTone.HEALTHY -> AresGreen
                                FtcRuntimeTone.WARNING -> AresGold
                                FtcRuntimeTone.UNKNOWN -> AresTextTertiary
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = runtimePresentation.proxyLabel,
                            color = when (runtimePresentation.proxyTone) {
                                FtcRuntimeTone.HEALTHY -> AresGreen
                                FtcRuntimeTone.WARNING -> AresGold
                                FtcRuntimeTone.UNKNOWN -> AresTextTertiary
                            },
                            fontSize = 9.sp,
                        )
                    }
                }

                // Overruns
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("OVERRUNS", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val overrunVal = loopOverruns
                    val overrunColor = when {
                        overrunVal == null -> AresTextTertiary
                        overrunVal > 0 -> AresGold
                        else -> AresGreen
                    }
                    Text(
                        text = overrunVal?.toString() ?: "--",
                        color = overrunColor,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Battery Voltage
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BATTERY", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val voltage = batteryVoltage
                    val batteryColor = when (batteryVoltagePolicy(league, xrpBrownoutThresholdVolts).tone(voltage)) {
                        HealthMetricTone.UNKNOWN -> AresTextPrimary
                        HealthMetricTone.NORMAL -> AresGreen
                        HealthMetricTone.CAUTION -> AresGold
                        HealthMetricTone.CRITICAL -> AresError
                    }
                    Text(
                        text = voltage?.let { String.format("%.2f V", it) } ?: "--",
                        color = batteryColor,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Brownouts
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BROWNOUTS", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val brownoutVal = brownoutCount
                    val brownoutColor = if (brownoutVal != null && brownoutVal > 0) AresError else AresTextPrimary
                    Text(
                        text = brownoutVal?.toString() ?: "--",
                        color = brownoutColor,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (runtimeHealth != null) {
                HorizontalDivider(color = AresBorder)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RuntimeMetric("INGEST", "%.0f fps".format(runtimeHealth.ingestFramesPerSecond))
                    RuntimeMetric("DB P95", "%.1f ms".format(runtimeHealth.databaseP95Ms))
                    RuntimeMetric("CACHE HIT", "%.0f%%".format(runtimeHealth.replayCacheHitRatio * 100.0))
                    RuntimeMetric("RECONNECTS", runtimeHealth.reconnects.toString())
                    RuntimeMetric("DROPS", runtimeHealth.droppedFrames?.toString() ?: "N/A")
                }
                if (health.source == ControllerHealthSource.LIVE) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RuntimeMetric("LOG PROFILE", runtimeHealth.robotLogProfile)
                    RuntimeMetric("LOG FILE", formatRuntimeBytes(runtimeHealth.robotLogCurrentFileBytes))
                    RuntimeMetric("LOG RATE", "${formatRuntimeBytes(runtimeHealth.robotLogBytesPerSecond.toLong())}/s")
                    RuntimeMetric("LOG QUEUE", runtimeHealth.robotLogQueueDepth.toString())
                    RuntimeMetric("LOG DROPS", runtimeHealth.robotLogDroppedFrames.toString())
                    RuntimeMetric("LOG PRUNED", runtimeHealth.robotLogPrunedFiles.toString())
                }
                }
            }
        }
    }

@Composable
private fun RuntimeMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = AresCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun formatRuntimeBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

