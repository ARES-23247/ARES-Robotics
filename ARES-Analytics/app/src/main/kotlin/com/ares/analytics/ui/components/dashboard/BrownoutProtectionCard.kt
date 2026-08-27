package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.ares.analytics.ui.components.core.*

internal data class BrownoutTelemetryState(
    val powerScale: Double? = null,
    val stateOfCharge: Double? = null,
    val brownoutState: String? = null,
    val tripCount: Int? = null,
)

private val KNOWN_BROWNOUT_STATES = setOf("HEALTHY", "WARNING", "CRITICAL")

internal fun BrownoutTelemetryState.acceptBrownoutTelemetry(
    key: String,
    value: Double,
    stringValue: String?,
): BrownoutTelemetryState = when (key) {
    "Robot/BrownoutPowerScale" -> value
        .takeIf { it.isFinite() && it in 0.0..1.0 }
        ?.let { copy(powerScale = it) }
        ?: this
    "Robot/StateOfCharge" -> value
        .takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.let { copy(stateOfCharge = it) }
        ?: this
    "Robot/BrownoutState" -> stringValue
        ?.trim()
        ?.uppercase()
        ?.takeIf { it in KNOWN_BROWNOUT_STATES }
        ?.let { copy(brownoutState = it) }
        ?: this
    "Diagnostics/Power/BrownoutCount" -> value
        .takeIf { it.isFinite() && it >= 0.0 }
        ?.let { copy(tripCount = it.toInt()) }
        ?: this
    else -> this
}

@Composable
fun BrownoutProtectionCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    var connected by remember(nt4ClientService) { mutableStateOf(nt4ClientService.isConnected.value) }
    var telemetry by remember(nt4ClientService) { mutableStateOf(BrownoutTelemetryState()) }

    LaunchedEffect(nt4ClientService) {
        launch {
            nt4ClientService.isConnected.collect { isConnected ->
                connected = isConnected
                if (!isConnected) telemetry = BrownoutTelemetryState()
            }
        }
        launch {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                telemetry = telemetry.acceptBrownoutTelemetry(frame.key, frame.value, frame.stringValue)
            }
        }
    }
    val reportedState = telemetry.brownoutState
    val statusText = when {
        !connected -> "OFFLINE"
        reportedState == null -> "NO DATA"
        else -> reportedState
    }
    val stateColor = when (reportedState) {
        "HEALTHY" -> AresGreen
        "WARNING" -> AresAmber
        "CRITICAL" -> AresRed
        else -> AresBorder
    }

    AnalyticsCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = "Brownout Protection",
            icon = Icons.Default.BatteryAlert,
            iconTint = stateColor,
            statusText = statusText,
            statusColor = stateColor
        )

        MetricRow(
            label = "Power Scale",
            value = telemetry.powerScale?.let { "${(it * 100).toInt()}%" } ?: "---"
        )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { telemetry.powerScale?.toFloat()?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = stateColor,
                trackColor = AresBorder
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("State of Charge", color = AresTextSecondary)
                    Text(
                        text = telemetry.stateOfCharge?.let { "${String.format("%.1f", it)}%" } ?: "---",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Brownout Trips", color = AresTextSecondary)
                    Text(
                        text = telemetry.tripCount?.toString() ?: "---",
                        color = if ((telemetry.tripCount ?: 0) > 0) AresAmber else AresTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

