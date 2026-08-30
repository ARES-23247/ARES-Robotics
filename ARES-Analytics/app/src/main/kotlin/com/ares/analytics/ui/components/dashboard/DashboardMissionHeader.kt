package com.ares.analytics.ui.components.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

/** Classification of active data source for clear evidence vs. simulation vs. replay boundary. */
enum class DashboardDataSourceType(
    val label: String,
    val badge: String,
    val icon: ImageVector,
    val explanation: String
) {
    SIMULATION_TRUTH(
        label = "Local Simulator",
        badge = "SIM TRUTH",
        icon = Icons.Default.Computer,
        explanation = "Synthetic dyn4j 2D physics ground truth and simulated IO"
    ),
    LIVE_ROBOT_FTC(
        label = "FTC Robot (Control Hub)",
        badge = "HARDWARE",
        icon = Icons.Default.PrecisionManufacturing,
        explanation = "Real-time measurements from FTC REV Control Hub"
    ),
    LIVE_ROBOT_FRC(
        label = "FRC Robot (RoboRIO)",
        badge = "HARDWARE",
        icon = Icons.Default.Memory,
        explanation = "Real-time measurements from FRC RoboRIO and CTRE CAN bus"
    ),
    HISTORICAL_REPLAY(
        label = "Historical Replay",
        badge = "REPLAY",
        icon = Icons.Default.Replay,
        explanation = "Deterministic log playback from DuckDB persistent session"
    ),
    NO_ACTIVE_SOURCE(
        label = "Offline / No Active Source",
        badge = "OFFLINE",
        icon = Icons.Default.WifiOff,
        explanation = "No live telemetry streaming. Select Local Sim or connect a robot."
    )
}

/** Freshness classification for real-time telemetry. */
enum class TelemetryFreshness(val label: String, val badge: String, val color: Color) {
    FRESH("Fresh", "LIVE", AresGreen),
    STALE("Stale (>500ms)", "STALE", AresAmber),
    INACTIVE("Inactive", "OFFLINE", AresTextTertiary)
}

/** Snapshot of mission control state computed from live environment. */
data class DashboardMissionSnapshot(
    val workspace: WorkspaceConfig,
    val isConnected: Boolean,
    val isLocalSimulator: Boolean,
    val isSimulatorRunning: Boolean,
    val isReplayActive: Boolean,
    val primarySessionId: String?,
    val loopTimeMs: Double? = null,
    val batteryVoltage: Double? = null,
    val brownoutCount: Int? = null,
    val loopOverruns: Int? = null,
    val activeAlerts: List<AlertRecord> = emptyList(),
    val frameRateHz: Double = 0.0,
    val lastUpdateAgeMs: Long = -1L,
    val hostIp: String = "127.0.0.1"
) {
    val sourceType: DashboardDataSourceType
        get() = when {
            isReplayActive || primarySessionId != null -> DashboardDataSourceType.HISTORICAL_REPLAY
            isConnected && isLocalSimulator -> DashboardDataSourceType.SIMULATION_TRUTH
            isConnected && workspace.league == League.FTC -> DashboardDataSourceType.LIVE_ROBOT_FTC
            isConnected && workspace.league == League.FRC -> DashboardDataSourceType.LIVE_ROBOT_FRC
            else -> DashboardDataSourceType.NO_ACTIVE_SOURCE
        }

    val freshness: TelemetryFreshness
        get() = when {
            !isConnected && !isReplayActive && primarySessionId == null -> TelemetryFreshness.INACTIVE
            lastUpdateAgeMs < 0L -> TelemetryFreshness.INACTIVE
            lastUpdateAgeMs in 0..500 -> TelemetryFreshness.FRESH
            else -> TelemetryFreshness.STALE
        }

    val healthSummary: String
        get() = when {
            !isConnected && primarySessionId == null ->
                "No live connection. You can practice safely in the Local Simulator or configure mechanisms in Robot Studio."
            isReplayActive || primarySessionId != null ->
                "Replaying session ${primarySessionId?.take(12) ?: "run"}. Review telemetry trends, alerts, and timeline scrubbing."
            freshness == TelemetryFreshness.INACTIVE ->
                "Connection selected, but no telemetry evidence has arrived yet. Check the selected target and connection before trusting dashboard values."
            freshness == TelemetryFreshness.STALE ->
                "Telemetry is stale (${lastUpdateAgeMs} ms since the last frame). Do not treat displayed values as current."
            brownoutCount != null && brownoutCount > 0 ->
                "Warning: $brownoutCount brownout events detected! Check battery voltage (${batteryVoltage?.let { String.format("%.2fV", it) } ?: "low"}) and motor current draw."
            loopOverruns != null && loopOverruns > 0 ->
                "Warning: $loopOverruns control-loop overruns detected. Review blocking I/O and periodic workload before operating the robot."
            batteryVoltage != null && batteryVoltage < 11.5 ->
                "Caution: Low battery voltage (${String.format("%.2fV", batteryVoltage)}). Risk of mechanism stall or brownout under acceleration."
            loopTimeMs != null && loopTimeMs.isFinite() && loopTimeMs > 30.0 ->
                "Degraded: Control loop period is high (${String.format("%.1f ms", loopTimeMs)} / ${String.format("%.0f Hz", 1000.0 / loopTimeMs)}). Check for blocking I/O."
            batteryVoltage == null || !batteryVoltage.isFinite() ||
                loopTimeMs == null || !loopTimeMs.isFinite() || loopTimeMs <= 0.0 ->
                "Connection is active, but battery and control-loop evidence is incomplete. Missing values are not assumed healthy."
            else ->
                "All systems nominal. Battery at ${String.format("%.2fV", batteryVoltage)}, control loop ${String.format("%.0f Hz", 1000.0 / loopTimeMs)} with ${loopOverruns ?: 0} overruns."
        }

    val highestPriorityAlert: AlertRecord?
        get() = activeAlerts.firstOrNull { alert ->
            alert.ruleKey.contains("brownout", ignoreCase = true) ||
                alert.ruleKey.contains("comms", ignoreCase = true) ||
                alert.ruleKey.contains("can", ignoreCase = true) ||
                alert.ruleKey.contains("battery", ignoreCase = true)
        } ?: activeAlerts.firstOrNull()
}

/**
 * Compact evidence/status header for Mission Control. Workspace identity and navigation live in
 * the global shell, so this component does not repeat them or reserve space for quick links.
 */
@Composable
fun DashboardMissionHeader(
    snapshot: DashboardMissionSnapshot,
    onNavigate: (NavigationTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    var detailsOpen by remember { mutableStateOf(false) }
    val topAlert = snapshot.highestPriorityAlert

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (topAlert != null) AresAmber.copy(alpha = 0.6f) else AresBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            HealthAndFreshnessRow(snapshot, compact = true)
            Text(
                text = snapshot.healthSummary,
                color = if (topAlert != null) AresAmber else AresTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { detailsOpen = true }
                    .padding(horizontal = 5.dp, vertical = 5.dp),
            )
            IconButton(onClick = { detailsOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Info, "Open dashboard status and diagnostics", tint = AresCyan, modifier = Modifier.size(17.dp))
            }
        }
    }

    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = {
                Text("Dashboard status & diagnostics", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.width(760.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HealthAndFreshnessRow(snapshot)
                    HealthSummaryBanner(snapshot, onNavigate)
                    TechnicalDiagnosticsPanel(snapshot)
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsOpen = false }) { Text("Close") }
            },
            containerColor = AresSurface,
        )
    }
}

@Composable
private fun HealthAndFreshnessRow(snapshot: DashboardMissionSnapshot, compact: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Target & Source Pill
        Surface(
            color = when (snapshot.sourceType) {
                DashboardDataSourceType.SIMULATION_TRUTH -> AresCyan.copy(alpha = 0.12f)
                DashboardDataSourceType.LIVE_ROBOT_FTC, DashboardDataSourceType.LIVE_ROBOT_FRC -> AresGreen.copy(alpha = 0.12f)
                DashboardDataSourceType.HISTORICAL_REPLAY -> AresAmber.copy(alpha = 0.12f)
                DashboardDataSourceType.NO_ACTIVE_SOURCE -> AresSurfaceElevated
            },
            border = BorderStroke(
                1.dp,
                when (snapshot.sourceType) {
                    DashboardDataSourceType.SIMULATION_TRUTH -> AresCyan.copy(alpha = 0.5f)
                    DashboardDataSourceType.LIVE_ROBOT_FTC, DashboardDataSourceType.LIVE_ROBOT_FRC -> AresGreen.copy(alpha = 0.5f)
                    DashboardDataSourceType.HISTORICAL_REPLAY -> AresAmber.copy(alpha = 0.5f)
                    DashboardDataSourceType.NO_ACTIVE_SOURCE -> AresBorder
                }
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (compact) 7.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!compact) {
                    Icon(
                        imageVector = snapshot.sourceType.icon,
                        contentDescription = null,
                        tint = when (snapshot.sourceType) {
                            DashboardDataSourceType.SIMULATION_TRUTH -> AresCyan
                            DashboardDataSourceType.LIVE_ROBOT_FTC, DashboardDataSourceType.LIVE_ROBOT_FRC -> AresGreen
                            DashboardDataSourceType.HISTORICAL_REPLAY -> AresAmber
                            DashboardDataSourceType.NO_ACTIVE_SOURCE -> AresTextTertiary
                        },
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = if (compact) snapshot.sourceType.badge else snapshot.sourceType.label,
                    color = AresTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Replay is an archive source, so a live-connection freshness badge beside it is
        // misleading and wastes the narrow global toolbar. Detailed status stays available
        // from the diagnostics dialog; live sources retain the freshness indicator.
        if (!compact || snapshot.sourceType != DashboardDataSourceType.HISTORICAL_REPLAY) {
            Surface(
                color = snapshot.freshness.color.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, snapshot.freshness.color.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = when (snapshot.freshness) {
                            TelemetryFreshness.FRESH -> Icons.Default.CheckCircle
                            TelemetryFreshness.STALE -> Icons.Default.Warning
                            TelemetryFreshness.INACTIVE -> Icons.Default.WifiOff
                        },
                        contentDescription = snapshot.freshness.label,
                        tint = snapshot.freshness.color,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = snapshot.freshness.badge,
                        color = snapshot.freshness.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthSummaryBanner(
    snapshot: DashboardMissionSnapshot,
    onNavigate: (NavigationTarget) -> Unit
) {
    val topAlert = snapshot.highestPriorityAlert
    val isCritical = topAlert != null && (
        topAlert.ruleKey.contains("brownout", ignoreCase = true) ||
            topAlert.ruleKey.contains("comms", ignoreCase = true) ||
            topAlert.ruleKey.contains("can", ignoreCase = true) ||
            topAlert.ruleKey.contains("battery", ignoreCase = true)
    )

    Surface(
        color = when {
            isCritical -> AresError.copy(alpha = 0.1f)
            topAlert != null -> AresAmber.copy(alpha = 0.08f)
            !snapshot.isConnected && snapshot.primarySessionId == null -> AresCyan.copy(alpha = 0.06f)
            else -> AresSurfaceElevated
        },
        border = BorderStroke(
            1.dp,
            when {
                isCritical -> AresError.copy(alpha = 0.5f)
                topAlert != null -> AresAmber.copy(alpha = 0.4f)
                !snapshot.isConnected && snapshot.primarySessionId == null -> AresCyan.copy(alpha = 0.35f)
                else -> AresBorder
            }
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = when {
                    isCritical -> Icons.Default.Warning
                    topAlert != null -> Icons.Default.Info
                    !snapshot.isConnected && snapshot.primarySessionId == null -> Icons.Default.PlayCircle
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = when {
                    isCritical -> AresError
                    topAlert != null -> AresAmber
                    !snapshot.isConnected && snapshot.primarySessionId == null -> AresCyan
                    else -> AresGreen
                },
                modifier = Modifier.size(20.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                if (topAlert != null) {
                    Text(
                        text = "ATTENTION: ${topAlert.ruleKey.replace('_', ' ').uppercase()}",
                        color = if (isCritical) AresError else AresAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = snapshot.healthSummary,
                    color = AresTextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            if (!snapshot.isConnected && snapshot.primarySessionId == null) {
                OutlinedButton(
                    onClick = { onNavigate(NavigationTarget.ACADEMY) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("First Mission Lab", fontSize = 11.sp)
                }
            } else if (topAlert != null) {
                OutlinedButton(
                    onClick = { onNavigate(NavigationTarget.TUNING) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Investigate", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TechnicalDiagnosticsPanel(snapshot: DashboardMissionSnapshot) {
    Surface(
        color = AresBackground,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("TECHNICAL DIAGNOSTICS & NT4 TELEMETRY SPECIFICATION", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DiagnosticItem("Target Host", "${snapshot.hostIp}:5810")
                DiagnosticItem("Log Server", "${snapshot.hostIp}:5002")
                DiagnosticItem("Telemetry Rate", if (snapshot.frameRateHz > 0.0) String.format("%.1f Hz", snapshot.frameRateHz) else "--")
                DiagnosticItem("Control Loop", snapshot.loopTimeMs?.let { String.format("%.1f ms (%.0f Hz)", it, 1000.0 / it) } ?: "--")
                DiagnosticItem("Battery Voltage", snapshot.batteryVoltage?.let { String.format("%.2f V", it) } ?: "--")
                DiagnosticItem("Active Alerts", "${snapshot.activeAlerts.size}")
            }

            HorizontalDivider(color = AresBorder.copy(alpha = 0.5f))

            Text(
                "Data classifications: [SIM TRUTH] is deterministic physics simulation; [HARDWARE] represents raw device sensors; [ESTIMATED] is EKF fused state; [REPLAY] is historical DuckDB playback.",
                color = AresTextTertiary,
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun DiagnosticItem(label: String, value: String) {
    Column {
        Text(label, color = AresTextTertiary, fontSize = 10.sp)
        Text(value, color = AresTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}
