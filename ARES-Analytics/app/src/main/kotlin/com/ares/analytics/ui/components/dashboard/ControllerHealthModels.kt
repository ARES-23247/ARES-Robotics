package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.areslib.telemetry.TelemetryTopicConstants

/** Validated observations; absence and invalid input stay unknown. */
data class ControllerHealthSnapshot(
    val loopTimeMs: Double? = null,
    val batteryVoltage: Double? = null,
    val brownoutCount: Int? = null,
    val loopOverruns: Int? = null,
    val ftcRuntime: FtcRuntimeDashboardState = FtcRuntimeDashboardState(),
)

enum class ControllerHealthSource { LIVE, REPLAY, OFFLINE }

data class ControllerHealthObservation(
    val snapshot: ControllerHealthSnapshot = ControllerHealthSnapshot(),
    val lastUpdateAgeMs: Long = -1L,
    val source: ControllerHealthSource = ControllerHealthSource.OFFLINE,
)

internal val HEALTH_LOOP_KEYS = TelemetryMetricCatalog.LOOP_TIME.keys.toList()
internal val HEALTH_BATTERY_KEYS = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.toList()
internal val HEALTH_BROWNOUT_KEYS = listOf("Diagnostics/Power/BrownoutCount", "Robot/BrownoutCount")
internal val HEALTH_OVERRUN_KEYS = listOf("Diagnostics/LoopOverruns", "Robot/LoopOverruns")
internal val HEALTH_RUNTIME_KEYS = listOf(
    TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT, TelemetryTopicConstants.FTC_PHOTON_ACTIVE,
    TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_CONFIGURED, TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_ACTIVE,
)
internal val HEALTH_KEYS = (HEALTH_LOOP_KEYS + HEALTH_BATTERY_KEYS + HEALTH_BROWNOUT_KEYS +
    HEALTH_OVERRUN_KEYS + HEALTH_RUNTIME_KEYS).distinct()
internal val HEALTH_KEY_INDEX = HEALTH_KEYS.withIndex().associate { it.value to it.index }

internal fun healthBoolean(value: Double?): Boolean? = when (value) { 0.0 -> false; 1.0 -> true; else -> null }

private fun healthCounter(value: Double?): Int? = value?.takeIf {
    it.isFinite() && it >= 0.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0
}?.toInt()

internal fun controllerHealthSnapshot(
    number: (String) -> Double?,
    text: (String) -> String?,
): ControllerHealthSnapshot = ControllerHealthSnapshot(
    loopTimeMs = HEALTH_LOOP_KEYS.firstNotNullOfOrNull(number)?.takeIf {
        it.isFinite() && it > 0.0 && (1_000.0 / it).isFinite()
    },
    batteryVoltage = HEALTH_BATTERY_KEYS.firstNotNullOfOrNull(number)?.takeIf { it.isFinite() && it >= 0.0 },
    brownoutCount = healthCounter(HEALTH_BROWNOUT_KEYS.firstNotNullOfOrNull(number)),
    loopOverruns = healthCounter(HEALTH_OVERRUN_KEYS.firstNotNullOfOrNull(number)),
    ftcRuntime = FtcRuntimeDashboardState(
        hubCommandTransport = text(TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT)
            ?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
        photonActive = healthBoolean(number(TelemetryTopicConstants.FTC_PHOTON_ACTIVE)),
        limelightProxyConfigured = healthBoolean(number(TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_CONFIGURED)),
        limelightProxyActive = healthBoolean(number(TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_ACTIVE)),
    ),
)

data class FtcRuntimeDashboardState(
    val hubCommandTransport: String? = null,
    val photonActive: Boolean? = null,
    val limelightProxyConfigured: Boolean? = null,
    val limelightProxyActive: Boolean? = null,
) {
    internal fun presentation(): FtcRuntimePresentation {
        val transport = when (hubCommandTransport) {
            "STANDARD_SDK" -> "FTC SDK SELECTED" to FtcRuntimeTone.HEALTHY
            "ARES_PHOTON" -> when (photonActive) {
                true -> "PHOTON ACTIVE" to FtcRuntimeTone.HEALTHY
                false -> "PHOTON SELECTED · INACTIVE" to FtcRuntimeTone.WARNING
                null -> "PHOTON SELECTED · STATUS UNKNOWN" to FtcRuntimeTone.UNKNOWN
            }
            else -> "HUB MODE --" to FtcRuntimeTone.UNKNOWN
        }
        val proxy = when {
            limelightProxyConfigured == null -> "LIMELIGHT PROXY --" to FtcRuntimeTone.UNKNOWN
            limelightProxyConfigured == false -> "LIMELIGHT PROXY OFF" to FtcRuntimeTone.UNKNOWN
            limelightProxyActive == true -> "LIMELIGHT PROXY ACTIVE" to FtcRuntimeTone.HEALTHY
            limelightProxyActive == false -> "LIMELIGHT PROXY SELECTED · INACTIVE" to FtcRuntimeTone.WARNING
            else -> "LIMELIGHT PROXY SELECTED · STATUS UNKNOWN" to FtcRuntimeTone.UNKNOWN
        }
        return FtcRuntimePresentation(transport.first, transport.second, proxy.first, proxy.second)
    }
}

internal enum class FtcRuntimeTone { HEALTHY, WARNING, UNKNOWN }

internal data class FtcRuntimePresentation(
    val transportLabel: String,
    val transportTone: FtcRuntimeTone,
    val proxyLabel: String,
    val proxyTone: FtcRuntimeTone,
)
