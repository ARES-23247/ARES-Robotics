package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.components.core.ChartContainer
import com.ares.analytics.ui.components.core.ChartSeries
import com.ares.analytics.ui.components.core.GlassCard
import com.ares.analytics.ui.components.core.MetricValueBadge
import com.ares.analytics.ui.components.core.StatusIndicatorPill
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun BatteryHealthCard(
    databaseService: DatabaseService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    var voltageFrames by remember(sessionId) { mutableStateOf<List<TelemetryFrame>>(emptyList()) }

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            while (isActive) {
                val frames = withContext(Dispatchers.IO) {
                    databaseService.getTelemetryForFilters(
                        sessionId,
                        TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.toList(),
                        emptyList()
                    )
                }
                voltageFrames = frames
                if (sessionId != "live-telemetry") break
                delay(1000)
            }
        } else {
            voltageFrames = emptyList()
        }
    }

    BatteryHealthCard(
        voltageFrames = voltageFrames,
        modifier = modifier
    )
}

@Composable
fun BatteryHealthCard(
    voltageFrames: List<TelemetryFrame>,
    modifier: Modifier = Modifier
) {
    val latestVoltage = voltageFrames.lastOrNull()?.value
    val minVoltage = voltageFrames.minOfOrNull { it.value }
    val hasTelemetry = latestVoltage != null && latestVoltage.isFinite()
    val effectiveVoltage = latestVoltage ?: 0.0
    val statusColor = when {
        !hasTelemetry -> AresTextSecondary
        effectiveVoltage < 11.5 -> AresError
        effectiveVoltage < 12.5 -> AresAmber
        else -> AresCyan
    }
    val statusText = when {
        !hasTelemetry -> "No Telemetry / Offline"
        effectiveVoltage < 11.5 -> "CRITICAL BROWNOUT RISK"
        effectiveVoltage < 12.5 -> "Warning: Voltage Dropping"
        else -> "Healthy State"
    }

    GlassCard(
        modifier = modifier
    ) {
        CardHeader(
            title = "Battery Diagnostics",
            icon = if (hasTelemetry && effectiveVoltage < 11.5) Icons.Default.BatteryAlert else Icons.Default.BatteryChargingFull,
            iconTint = statusColor
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricValueBadge(
                label = "LATEST VOLTAGE",
                value = if (hasTelemetry) String.format("%.2f V", effectiveVoltage) else "-- V",
                statusColor = statusColor
            )
            MetricValueBadge(
                label = "MINIMUM LOGGED",
                value = if (minVoltage != null && minVoltage.isFinite()) String.format("%.2f V", minVoltage) else "-- V",
                statusColor = AresTextSecondary
            )
        }

        StatusIndicatorPill(
            text = statusText,
            color = statusColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (voltageFrames.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No battery voltage logged for this session.",
                    color = AresTextTertiary,
                    fontSize = 11.sp
                )
            }
        } else {
            val chartSeries = remember(voltageFrames, statusColor) {
                listOf(
                    ChartSeries(
                        name = "Voltage",
                        points = voltageFrames.sortedBy { it.timestampMs }.map { Pair(it.timestampMs.toDouble(), it.value) },
                        color = statusColor,
                        fillGradient = true
                    )
                )
            }
            ChartContainer(
                seriesList = chartSeries,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}
