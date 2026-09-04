package com.ares.analytics.ui.components.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresTextTertiary

/** Classification of active data source for clear evidence vs. simulation vs. replay boundary. */
enum class DashboardDataSourceType(
    val label: String,
    val badge: String,
    val icon: ImageVector,
    val explanation: String,
) {
    SIMULATION_TRUTH(
        label = "Local Simulator",
        badge = "SIM TRUTH",
        icon = Icons.Default.Computer,
        explanation = "Synthetic dyn4j 2D physics ground truth and simulated IO",
    ),
    LIVE_ROBOT_FTC(
        label = "FTC Robot (Control Hub)",
        badge = "HARDWARE",
        icon = Icons.Default.PrecisionManufacturing,
        explanation = "Real-time measurements from FTC REV Control Hub",
    ),
    LIVE_ROBOT_FRC(
        label = "FRC Robot (RoboRIO)",
        badge = "HARDWARE",
        icon = Icons.Default.Memory,
        explanation = "Real-time measurements from FRC RoboRIO and CTRE CAN bus",
    ),
    LIVE_ROBOT_XRP(
        label = "XRP Robot (MicroPython)",
        badge = "HARDWARE",
        icon = Icons.Default.Memory,
        explanation = "Real-time measurements from the XRP controller and ARES MicroPython runtime",
    ),
    HISTORICAL_REPLAY(
        label = "Historical Replay",
        badge = "REPLAY",
        icon = Icons.Default.Replay,
        explanation = "Deterministic log playback from DuckDB persistent session",
    ),
    NO_ACTIVE_SOURCE(
        label = "Offline / No Active Source",
        badge = "OFFLINE",
        icon = Icons.Default.WifiOff,
        explanation = "No live telemetry streaming. Select Local Sim or connect a robot.",
    ),
}

/** Freshness classification for real-time telemetry. */
enum class TelemetryFreshness(val label: String, val badge: String, val color: Color) {
    FRESH("Fresh", "LIVE", AresGreen),
    STALE("Stale (>500ms)", "STALE", AresAmber),
    INACTIVE("Inactive", "OFFLINE", AresTextTertiary),
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
    val xrpBrownoutThresholdVolts: Double? = null,
    val activeAlerts: List<AlertRecord> = emptyList(),
    val frameRateHz: Double = 0.0,
    val lastUpdateAgeMs: Long = -1L,
    val hostIp: String = "127.0.0.1",
) {
    val sourceType: DashboardDataSourceType
        get() = when {
            isReplayActive || primarySessionId != null -> DashboardDataSourceType.HISTORICAL_REPLAY
            isConnected && isLocalSimulator -> DashboardDataSourceType.SIMULATION_TRUTH
            isConnected && workspace.league == League.FTC -> DashboardDataSourceType.LIVE_ROBOT_FTC
            isConnected && workspace.league == League.FRC -> DashboardDataSourceType.LIVE_ROBOT_FRC
            isConnected && workspace.league == League.XRP -> DashboardDataSourceType.LIVE_ROBOT_XRP
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
            batteryVoltagePolicy(workspace.league, xrpBrownoutThresholdVolts).tone(batteryVoltage) in
                setOf(HealthMetricTone.CAUTION, HealthMetricTone.CRITICAL) ->
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
