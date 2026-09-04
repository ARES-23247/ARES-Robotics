package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.ThresholdRule
import java.util.Locale

/** Applies platform safety thresholds without mutating user-authored alert rules. */
internal class PlatformAlertThresholds {
    @Volatile private var activeLeague = League.FTC
    @Volatile private var xrpBatteryMinimumVolts = DEFAULT_XRP_BATTERY_MINIMUM_VOLTS

    fun configure(league: League, xrpBrownoutThresholdVolts: Double?) {
        activeLeague = league
        xrpBatteryMinimumVolts = xrpBrownoutThresholdVolts
            ?.takeIf { it.isFinite() && it in 3.0..6.0 }
            ?: DEFAULT_XRP_BATTERY_MINIMUM_VOLTS
    }

    fun effectiveRule(normalizedKey: String, configuredRule: ThresholdRule): ThresholdRule {
        if (normalizedKey != TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey || activeLeague != League.XRP) {
            return configuredRule
        }
        return configuredRule.copy(
            displayName = "Low XRP Battery Voltage (<${"%.2f".format(Locale.ROOT, xrpBatteryMinimumVolts)}V)",
            minValue = xrpBatteryMinimumVolts,
        )
    }

    private companion object {
        const val DEFAULT_XRP_BATTERY_MINIMUM_VOLTS = 4.3
    }
}
