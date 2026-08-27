package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import com.areslib.telemetry.TelemetryTopicConstants

internal data class ReplayHealthSnapshot(
    val loopTimeMs: Double?,
    val batteryVoltage: Double?,
    val brownoutCount: Int?,
    val loopOverruns: Int?,
    val ftcRuntime: FtcRuntimeDashboardState,
)

/** Extracts only values present in the recording; missing data never becomes a healthy zero. */
internal fun ReplayFrame.toReplayHealthSnapshot(): ReplayHealthSnapshot {
    fun valueMatching(vararg fragments: String): Double? = values.entries.firstOrNull { entry ->
        val key = entry.key.lowercase()
        fragments.any(key::contains)
    }?.value

    return ReplayHealthSnapshot(
        loopTimeMs = valueMatching("looptime", "loop_time"),
        batteryVoltage = valueMatching("batteryvoltage", "battery_voltage"),
        brownoutCount = valueMatching("brownoutcount", "brownout_count")?.toInt(),
        loopOverruns = valueMatching("loopoverruns", "loop_overruns")?.toInt(),
        ftcRuntime = FtcRuntimeDashboardState(
            hubCommandTransport = stringValues[TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT]
                ?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            photonActive = values[TelemetryTopicConstants.FTC_PHOTON_ACTIVE]?.let { it >= 0.5 },
            limelightProxyConfigured = values[TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_CONFIGURED]?.let { it >= 0.5 },
            limelightProxyActive = values[TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_ACTIVE]?.let { it >= 0.5 },
        ),
    )
}
