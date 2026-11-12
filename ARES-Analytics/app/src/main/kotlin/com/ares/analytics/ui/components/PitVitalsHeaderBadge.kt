package com.ares.analytics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DashboardHealthService
import com.ares.analytics.service.DashboardHealthSnapshot
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import java.util.Locale
import kotlinx.coroutines.delay

internal data class PitVitalsSnapshot(
    val isConnected: Boolean,
    val ingestHz: Double,
    val batteryVolts: Double?,
    val loopTimeMs: Double?,
)

private val BATTERY_TOPIC_KEYS = listOf(
    "Robot/BatteryVoltage",
    "Power/BatteryVoltage",
    "Diagnostics/Power/BatteryVoltage",
    "SystemStats/BatteryVoltage",
    "XRP/BatteryVolts",
)

private val LOOP_TIME_TOPIC_KEYS = listOf(
    "Robot/LoopTimeMs",
    "Robot/LoopDurationMs",
    "Diagnostics/Loop/TotalMs",
    "SystemStats/LoopTimeMs",
)

internal fun extractPitVitalsSnapshot(
    store: TelemetryStore,
    health: DashboardHealthSnapshot,
    isRobotLinkConnected: Boolean,
): PitVitalsSnapshot {
    val battery = BATTERY_TOPIC_KEYS.firstNotNullOfOrNull { key ->
        store.latest(key)?.value?.takeIf { it.isFinite() && it > 0.0 }
    }
    val loopMs = LOOP_TIME_TOPIC_KEYS.firstNotNullOfOrNull { key ->
        store.latest(key)?.value?.takeIf { it.isFinite() && it >= 0.0 }
    }
    return PitVitalsSnapshot(
        isConnected = isRobotLinkConnected || health.connected,
        ingestHz = health.ingestFramesPerSecond.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0,
        batteryVolts = battery,
        loopTimeMs = loopMs,
    )
}

/**
 * Compact header telemetry pill shown on non-Dashboard screens so drive-team engineers keep live
 * visibility into link rate, battery voltage, and control-loop timing while editing or tuning.
 */
@Composable
internal fun PitVitalsHeaderBadge(
    nt4ClientService: Nt4ClientService,
    dashboardHealthService: DashboardHealthService,
    isRobotLinkConnected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val health by dashboardHealthService.health.collectAsState()
    var vitals by remember {
        mutableStateOf(extractPitVitalsSnapshot(nt4ClientService.telemetryStore, health, isRobotLinkConnected))
    }

    LaunchedEffect(nt4ClientService, health, isRobotLinkConnected) {
        while (true) {
            vitals = extractPitVitalsSnapshot(nt4ClientService.telemetryStore, health, isRobotLinkConnected)
            delay(250L)
        }
    }

    val dotColor = when {
        !vitals.isConnected -> AresTextSecondary
        vitals.batteryVolts != null && vitals.batteryVolts!! < 11.2 -> AresRed
        vitals.batteryVolts != null && vitals.batteryVolts!! < 12.0 -> AresAmber
        else -> AresGreen
    }
    val hzText = if (vitals.isConnected) {
        String.format(Locale.US, "%.0f Hz", vitals.ingestHz)
    } else {
        "Offline"
    }
    val battText = vitals.batteryVolts?.let { String.format(Locale.US, "%.1fV", it) } ?: "--V"
    val loopText = vitals.loopTimeMs?.let { String.format(Locale.US, "%.1fms", it) }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = AresSurfaceElevated,
        border = BorderStroke(1.dp, if (vitals.isConnected) AresCyan.copy(alpha = 0.45f) else AresBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = hzText,
                color = if (vitals.isConnected) AresTextPrimary else AresTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text("·", color = AresTextSecondary, fontSize = 11.sp)
            Text(
                text = battText,
                color = if (vitals.batteryVolts != null && vitals.batteryVolts!! < 11.5) AresAmber else AresTextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (!compact && loopText != null) {
                Text("·", color = AresTextSecondary, fontSize = 11.sp)
                Text(
                    text = loopText,
                    color = if ((vitals.loopTimeMs ?: 0.0) > 22.0) AresAmber else AresTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}
