package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.ThresholdRule
import java.util.Locale
import java.util.concurrent.atomic.AtomicReferenceArray

/** Applies the XRP project minimum without mutating the configured keys, upper bounds or audio flags. */
internal class PlatformAlertThresholds {
    private val batteryKeys = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.toTypedArray()
    private class CachedRule(val configured: ThresholdRule, val effective: ThresholdRule)
    private class Context(val league: League, val minimum: Double, slots: Int) {
        val rules = AtomicReferenceArray<CachedRule?>(slots)
    }
    @Volatile private var context = Context(League.FTC, DEFAULT_XRP_BATTERY_MINIMUM_VOLTS, batteryKeys.size)

    /** One publication contains the league, minimum and cache ownership. Unchanged settings reuse it. */
    @Synchronized
    fun configure(league: League, xrpBrownoutThresholdVolts: Double?) {
        val minimum = if (league == League.XRP && xrpBrownoutThresholdVolts != null &&
            xrpBrownoutThresholdVolts.isFinite() && xrpBrownoutThresholdVolts in 3.0..6.0
        ) xrpBrownoutThresholdVolts else DEFAULT_XRP_BATTERY_MINIMUM_VOLTS
        val previous = context
        if (previous.league == league && previous.minimum == minimum) return
        context = Context(league, minimum, batteryKeys.size)
    }

    fun effectiveRule(normalizedKey: String, configuredRule: ThresholdRule): ThresholdRule {
        val snapshot = context
        if (snapshot.league != League.XRP) return configuredRule
        var index = 0
        while (index < batteryKeys.size && batteryKeys[index] != normalizedKey) index++
        if (index == batteryKeys.size) return configuredRule
        snapshot.rules[index]?.let { if (it.configured === configuredRule) return it.effective }
        return synchronized(snapshot) {
            snapshot.rules[index]?.let { if (it.configured === configuredRule) return@synchronized it.effective }
            val minimumText = "%.2f".format(Locale.ROOT, snapshot.minimum)
            val maximum = configuredRule.maxValue
            val name = if (maximum == null) "Low XRP Battery Voltage (<${minimumText}V)"
                else "XRP Battery Voltage (<${minimumText}V or >${"%.2f".format(Locale.ROOT, maximum)}V)"
            val effective = configuredRule.copy(displayName = name, minValue = snapshot.minimum)
            snapshot.rules.set(index, CachedRule(configuredRule, effective))
            effective
        }
    }

    private companion object {
        const val DEFAULT_XRP_BATTERY_MINIMUM_VOLTS = 4.3
    }
}
