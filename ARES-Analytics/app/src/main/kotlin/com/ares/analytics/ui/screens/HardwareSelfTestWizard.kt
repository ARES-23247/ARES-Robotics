package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val ARES_TELEMETRY_HEARTBEAT_TOPIC = "ARES/Telemetry/FrameSequence"
internal const val HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS = 1_500L

internal enum class SelfTestStatus {
    WAITING,
    OBSERVED,
    WARNING
}

internal data class SelfTestStep(
    val id: String,
    val name: String,
    val description: String,
    val status: SelfTestStatus,
    val details: String
)

/**
 * Builds an honest, read-only preflight from telemetry already published by the robot.
 *
 * An observed topic proves only that the signal reached the app. It does not pulse motors,
 * infer encoder direction, or certify match readiness. Active checks require a future
 * robot-side protocol with acknowledgements and safety interlocks.
 */
internal fun evaluateHardwareReadiness(
    connected: Boolean,
    frames: Map<String, TelemetryFrame>,
    heartbeatAgeMs: Long?,
): List<SelfTestStep> {
    data class Definition(val id: String, val name: String, val description: String)
    val definitions = listOf(
        Definition("battery", "Battery voltage", "Confirms a finite robot voltage signal above the pit warning threshold."),
        Definition("motor_fl", "Front-left drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_fr", "Front-right drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_rl", "Rear-left drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("motor_rr", "Rear-right drive signal", "Confirms telemetry exists; it does not command or certify the motor."),
        Definition("pinpoint", "Pinpoint / odometry", "Checks the published health or odometry evidence without moving the robot."),
        Definition("limelight", "Limelight / vision", "Checks published camera evidence without assuming that a target must be visible.")
    )
    if (!connected) {
        return definitions.map { definition ->
            SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, "Connect to a robot or simulator to observe this signal.")
        }
    }

    fun numeric(vararg keys: String): TelemetryFrame? = keys.firstNotNullOfOrNull { frames[it] }
    fun observed(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.OBSERVED, details)
    fun waiting(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, details)
    fun warning(definition: Definition, details: String) =
        SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WARNING, details)

    val results = mutableListOf<SelfTestStep>()
    val batteryDefinition = definitions[0]
    val battery = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.firstNotNullOfOrNull { frames[it] }?.value
    results += when {
        battery == null || !battery.isFinite() -> waiting(batteryDefinition, "No finite battery-voltage topic received.")
        battery < 11.5 -> warning(batteryDefinition, "${"%.2f".format(battery)} V observed; charge or inspect the battery before a match.")
        else -> observed(batteryDefinition, "${"%.2f".format(battery)} V observed.")
    }
    if (heartbeatAgeMs == null || heartbeatAgeMs !in 0L..HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS) {
        val details = if (heartbeatAgeMs == null) {
            "The network socket is connected, but no ARES telemetry-loop heartbeat has arrived."
        } else {
            "The last ARES telemetry-loop heartbeat is ${heartbeatAgeMs} ms old. Treat displayed values as stale."
        }
        return definitions.map { definition ->
            SelfTestStep(definition.id, definition.name, definition.description, SelfTestStatus.WAITING, details)
        }
    }

    listOf("fl" to definitions[1], "fr" to definitions[2], "rl" to definitions[3], "rr" to definitions[4]).forEach { (name, definition) ->
        val aliases = if (name == "rl") listOf("rl", "bl") else if (name == "rr") listOf("rr", "br") else listOf(name)
        val current = aliases.firstNotNullOfOrNull { numeric("Hardware/Motors/$it/CurrentAmps", "Hardware/Motors/$it/Current") }
        val motion = aliases.firstNotNullOfOrNull { numeric("Hardware/Motors/$it/Velocity", "Hardware/Motors/$it/Power") }
        results += if (current != null || motion != null) {
            val evidence = listOfNotNull(
                current?.value?.takeIf(Double::isFinite)?.let { "current ${"%.2f".format(it)} A" },
                motion?.value?.takeIf(Double::isFinite)?.let { "motion signal ${"%.2f".format(it)}" }
            ).joinToString()
            observed(definition, if (evidence.isBlank()) "Motor topics observed." else "Telemetry observed: $evidence.")
        } else {
            waiting(definition, "No current, velocity, or power topic received for ${aliases.joinToString("/")}.")
        }
    }

    val pinpointDefinition = definitions[5]
    val pinpointStatus = frames["Drive/Pinpoint_Status"]?.stringValue?.trim().orEmpty()
    val odometryObserved = listOf("Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading")
        .all { frames[it]?.value?.isFinite() == true }
    results += when {
        pinpointStatus.contains("FAULT", true) || pinpointStatus.contains("DISCONNECT", true) ->
            warning(pinpointDefinition, "Robot reports: $pinpointStatus")
        pinpointStatus.isNotBlank() && !pinpointStatus.equals("UNKNOWN", true) ->
            observed(pinpointDefinition, "Robot reports: $pinpointStatus")
        odometryObserved -> warning(pinpointDefinition, "Odometry values are present, but the robot did not identify Pinpoint health.")
        else -> waiting(pinpointDefinition, "No Pinpoint health or complete odometry pose received.")
    }

    val visionDefinition = definitions[6]
    val fps = frames["Vision/Limelight/FPS"]?.value
    val visionTopicObserved = frames.containsKey("Vision/HasTarget") || frames.containsKey("Vision/MeasurementCount")
    results += when {
        fps != null && fps.isFinite() && fps >= 5.0 -> observed(visionDefinition, "${"%.1f".format(fps)} FPS observed.")
        fps != null && fps.isFinite() -> warning(visionDefinition, "Only ${"%.1f".format(fps)} FPS observed.")
        visionTopicObserved -> warning(visionDefinition, "Vision topics are present, but camera frame rate is not published.")
        else -> waiting(visionDefinition, "No Limelight or vision-health topic received.")
    }
    return results
}

/** Read-only pit preflight. This screen never commands robot hardware. */
@Composable
fun HardwareSelfTestWizard(nt4ClientService: Nt4ClientService) {
    val connected by nt4ClientService.isConnected.collectAsState()
    var lastHeartbeatReceipt by remember { mutableStateOf<TimeMark?>(null) }
    var streamLive by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf(evaluateHardwareReadiness(connected, nt4ClientService.latestValues, null)) }

    LaunchedEffect(nt4ClientService) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            if (frame.key == ARES_TELEMETRY_HEARTBEAT_TOPIC) {
                lastHeartbeatReceipt = TimeSource.Monotonic.markNow()
            }
        }
    }

    LaunchedEffect(connected, nt4ClientService) {
        if (!connected) {
            lastHeartbeatReceipt = null
            streamLive = false
        }
        while (isActive) {
            val heartbeatAgeMs = lastHeartbeatReceipt?.elapsedNow()?.inWholeMilliseconds
            streamLive = connected && heartbeatAgeMs != null && heartbeatAgeMs <= HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS
            steps = evaluateHardwareReadiness(connected, nt4ClientService.latestValues, heartbeatAgeMs)
            delay(250)
        }
    }

    val observedCount = steps.count { it.status == SelfTestStatus.OBSERVED }
    Column(
        Modifier.fillMaxSize().background(AresBackground),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.HealthAndSafety, null, tint = AresCyan, modifier = Modifier.size(24.dp))
                        Column {
                            Text("Pit readiness signals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                            Text("Live, read-only evidence from NT4", color = AresTextSecondary, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            when {
                                !connected -> "OFFLINE"
                                streamLive -> "STREAM LIVE"
                                else -> "STREAM STALE"
                            },
                            if (streamLive) AresGreen else AresRed,
                        )
                        StatusPill("$observedCount/${steps.size} CONFIRMED", AresCyan)
                        IconButton(onClick = {
                            val heartbeatAgeMs = lastHeartbeatReceipt?.elapsedNow()?.inWholeMilliseconds
                            streamLive = connected && heartbeatAgeMs != null && heartbeatAgeMs <= HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS
                            steps = evaluateHardwareReadiness(connected, nt4ClientService.latestValues, heartbeatAgeMs)
                        }) {
                            Icon(Icons.Default.Refresh, "Refresh readiness signals", tint = AresTextSecondary)
                        }
                    }
                }
                Surface(color = AresGold.copy(alpha = .10f), shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp, AresGold.copy(alpha = .45f))) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = AresGold, modifier = Modifier.size(18.dp))
                        Text(
                            "Signal presence is not a hardware certification. This page never pulses motors or declares the robot match-ready.",
                            color = AresTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f)) {
            val columns = if (maxWidth < 900.dp) 1 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(steps, key = SelfTestStep::id) { step -> ReadinessCard(step) }
            }
        }
    }
}

@Composable
private fun ReadinessCard(step: SelfTestStep) {
    val color = when (step.status) {
        SelfTestStatus.OBSERVED -> AresGreen
        SelfTestStatus.WARNING -> AresGold
        SelfTestStatus.WAITING -> AresTextSecondary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, if (step.status == SelfTestStatus.WAITING) AresBorder else color.copy(alpha = .65f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(step.name, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                StatusPill(step.status.name, color)
            }
            Text(step.description, color = AresTextSecondary, fontSize = 12.sp)
            Text(step.details, color = color, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color.copy(alpha = .6f))) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}
