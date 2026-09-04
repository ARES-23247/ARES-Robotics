package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.models.League

internal enum class HealthMetricTone {
    UNKNOWN,
    NORMAL,
    CAUTION,
    CRITICAL,
}

internal data class BatteryVoltagePolicy(
    val criticalBelowVolts: Double,
    val cautionBelowVolts: Double,
) {
    fun tone(voltage: Double?): HealthMetricTone = when {
        voltage == null || !voltage.isFinite() -> HealthMetricTone.UNKNOWN
        voltage < criticalBelowVolts -> HealthMetricTone.CRITICAL
        voltage < cautionBelowVolts -> HealthMetricTone.CAUTION
        else -> HealthMetricTone.NORMAL
    }
}

internal fun batteryVoltagePolicy(
    league: League,
    xrpBrownoutThresholdVolts: Double? = null,
): BatteryVoltagePolicy = when (league) {
    League.XRP -> {
        val brownout = xrpBrownoutThresholdVolts
            ?.takeIf { it.isFinite() && it in 3.0..6.0 }
            ?: DEFAULT_XRP_BROWNOUT_THRESHOLD_VOLTS
        BatteryVoltagePolicy(
            criticalBelowVolts = brownout,
            cautionBelowVolts = (brownout + XRP_BATTERY_CAUTION_MARGIN_VOLTS).coerceAtMost(6.0),
        )
    }
    League.FTC, League.FRC -> BatteryVoltagePolicy(
        criticalBelowVolts = TWELVE_VOLT_CRITICAL_THRESHOLD,
        cautionBelowVolts = TWELVE_VOLT_CAUTION_THRESHOLD,
    )
}

internal fun controllerHealthTitle(league: League): String = when (league) {
    League.FTC -> "Control Hub Health"
    League.FRC -> "RoboRIO Health"
    League.XRP -> "XRP Controller Health"
}

private const val DEFAULT_XRP_BROWNOUT_THRESHOLD_VOLTS = 4.3
private const val XRP_BATTERY_CAUTION_MARGIN_VOLTS = 0.4
private const val TWELVE_VOLT_CRITICAL_THRESHOLD = 11.5
private const val TWELVE_VOLT_CAUTION_THRESHOLD = 12.2
