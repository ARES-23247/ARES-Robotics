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
    LOCAL_SIMULATION(
        label = "Local Simulator",
        badge = "SIMULATED",
        icon = Icons.Default.Computer,
        explanation = "Simulated sensors and estimated state; ground-truth topics are identified separately",
    ),
    LIVE_ROBOT_FTC(
        label = "FTC Robot (Control Hub)",
        badge = "HARDWARE",
        icon = Icons.Default.PrecisionManufacturing,
        explanation = "Live FTC robot telemetry, including measured and estimated values",
    ),
    LIVE_ROBOT_FRC(
        label = "FRC Robot (RoboRIO)",
        badge = "HARDWARE",
        icon = Icons.Default.Memory,
        explanation = "Live FRC robot telemetry, including measured and estimated values",
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
    HISTORICAL("Recorded data", "REPLAY", AresAmber),
    FRESH("Fresh", "LIVE", AresGreen),
    STALE("Stale (>500ms)", "STALE", AresAmber),
    INACTIVE("Inactive", "OFFLINE", AresTextTertiary),
}

enum class MissionHealthTone { NEUTRAL, HEALTHY, WARNING, CRITICAL }

data class MissionHealthSummary(val text: String, val tone: MissionHealthTone)

/** Snapshot of mission control state computed from the selected source. */
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
    val liveSessionId: String = "live-telemetry",
) {
    val sourceType: DashboardDataSourceType
        get() = when {
            isReplayActive || primarySessionId != null -> DashboardDataSourceType.HISTORICAL_REPLAY
            isConnected && isLocalSimulator -> DashboardDataSourceType.LOCAL_SIMULATION
            isConnected && workspace.league == League.FTC -> DashboardDataSourceType.LIVE_ROBOT_FTC
            isConnected && workspace.league == League.FRC -> DashboardDataSourceType.LIVE_ROBOT_FRC
            isConnected && workspace.league == League.XRP -> DashboardDataSourceType.LIVE_ROBOT_XRP
            else -> DashboardDataSourceType.NO_ACTIVE_SOURCE
        }

    val isHistorical: Boolean get() = isReplayActive || primarySessionId != null
    val validBatteryVoltage: Double? = batteryVoltage?.takeIf { it.isFinite() && it >= 0.0 }
    val validLoopTimeMs: Double? = loopTimeMs?.takeIf { it.isFinite() && it > 0.0 && (1_000.0 / it).isFinite() }
    val validLoopFrequencyHz: Double? = validLoopTimeMs?.let { 1_000.0 / it }
    private val validBrownoutCount = brownoutCount?.takeIf { it >= 0 }
    private val validOverruns = loopOverruns?.takeIf { it >= 0 }

    val currentAlerts: List<AlertRecord> = currentDashboardAlerts(activeAlerts, liveSessionId, isConnected && !isHistorical)
    val highestPriorityAlert: AlertRecord? = highestPriorityDashboardAlert(currentAlerts)

    val freshness: TelemetryFreshness
        get() = when {
            isHistorical -> TelemetryFreshness.HISTORICAL
            !isConnected || lastUpdateAgeMs < 0L -> TelemetryFreshness.INACTIVE
            lastUpdateAgeMs <= 500L -> TelemetryFreshness.FRESH
            else -> TelemetryFreshness.STALE
        }

    val health: MissionHealthSummary = summarizeHealth()
    val healthSummary: String get() = health.text

    private fun summarizeHealth(): MissionHealthSummary {
        fun summary(text: String, tone: MissionHealthTone = MissionHealthTone.NEUTRAL) = MissionHealthSummary(text, tone)
        val batteryTone = batteryVoltagePolicy(workspace.league, xrpBrownoutThresholdVolts).tone(validBatteryVoltage)
        return when {
            isHistorical && lastUpdateAgeMs < 0L ->
                summary("Selected replay ${primarySessionId?.take(12) ?: "live rewind"}. Waiting for matching recorded telemetry.")
            isHistorical ->
                summary("Replaying session ${primarySessionId?.take(12) ?: "live rewind"}. Review telemetry trends, alerts, and timeline scrubbing.")
            !isConnected ->
                summary("No live connection. You can practice safely in the Local Simulator or configure mechanisms in Robot Studio.")
            freshness == TelemetryFreshness.INACTIVE ->
                summary("Connection selected, but no telemetry evidence has arrived yet. Check the selected target and connection before trusting dashboard values.")
            freshness == TelemetryFreshness.STALE ->
                summary("Telemetry is stale (${lastUpdateAgeMs} ms since the last frame). Do not treat displayed values as current.", MissionHealthTone.WARNING)
            highestPriorityAlert != null ->
                summary("Active alert: ${criticalAlertTitle(highestPriorityAlert.ruleKey)}. Review current telemetry and the alert history.",
                    if (dashboardCriticalKind(highestPriorityAlert.ruleKey) != null) MissionHealthTone.CRITICAL else MissionHealthTone.WARNING)
            validBrownoutCount != null && validBrownoutCount > 0 ->
                summary("Warning: $validBrownoutCount brownout events detected! Check battery voltage (${validBatteryVoltage?.let { String.format("%.2fV", it) } ?: "unknown"}) and motor current draw.", MissionHealthTone.CRITICAL)
            validOverruns != null && validOverruns > 0 ->
                summary("Warning: $validOverruns control-loop overruns detected. Review blocking I/O and periodic workload before operating the robot.", MissionHealthTone.WARNING)
            batteryTone == HealthMetricTone.CAUTION || batteryTone == HealthMetricTone.CRITICAL ->
                summary("Caution: Low battery voltage (${String.format("%.2fV", validBatteryVoltage)}). Risk of mechanism stall or brownout under acceleration.",
                    if (batteryTone == HealthMetricTone.CRITICAL) MissionHealthTone.CRITICAL else MissionHealthTone.WARNING)
            validLoopTimeMs != null && validLoopTimeMs > 30.0 ->
                summary("Degraded: Control loop period is high (${String.format("%.1f ms", validLoopTimeMs)} / ${String.format("%.0f Hz", validLoopFrequencyHz)}). Check for blocking I/O.", MissionHealthTone.WARNING)
            validBatteryVoltage == null || validLoopTimeMs == null || validBrownoutCount == null || validOverruns == null ->
                summary("Connection is active, but controller health evidence is incomplete. Missing values are not assumed healthy.")
            else ->
                summary("Observed health metrics are nominal. Battery at ${String.format("%.2fV", validBatteryVoltage)}, control loop ${String.format("%.0f Hz", validLoopFrequencyHz)} with $validOverruns overruns.", MissionHealthTone.HEALTHY)
        }
    }
}
