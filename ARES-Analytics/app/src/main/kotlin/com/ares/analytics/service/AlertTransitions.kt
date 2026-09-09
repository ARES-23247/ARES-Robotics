package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import kotlin.math.max

internal data class AlertOutcome(val alert: AlertRecord, val shouldBeep: Boolean)

/** Acknowledgment belongs to an occurrence; only fresh healthy evidence resolves it. */
internal fun alertTransition(
    current: Map<String, AlertRecord>, rule: ThresholdRule, key: String,
    sessionId: String, timestampMs: Long, value: Double, isViolating: Boolean,
): AlertOutcome? {
    if (!value.isFinite() || timestampMs < 0L) return null
    val normalizedKey = TelemetryMetricCatalog.normalizeTopic(key)
    val existing = current.values.firstOrNull {
        it.sessionId == sessionId && it.resolveTimestampMs == null &&
            TelemetryMetricCatalog.normalizeTopic(it.ruleKey) == normalizedKey
    }
    if (existing != null && timestampMs < existing.triggerTimestampMs) return null
    if (existing == null) {
        if (!isViolating) return null
        return AlertOutcome(AlertRecord(UUID.randomUUID().toString(), sessionId, key, timestampMs,
            peakValue = value), rule.audibleAlert)
    }
    val updated = if (!isViolating) {
        existing.copy(resolveTimestampMs = timestampMs, durationMs = timestampMs - existing.triggerTimestampMs)
    } else {
        val peak = alertPeak(existing.peakValue, value, rule)
        if (peak == existing.peakValue) return null
        existing.copy(peakValue = peak)
    }
    return AlertOutcome(updated, false)
}

/** For a two-sided rule, peak means the largest absolute excursion past either bound. */
internal fun alertPeak(previous: Double, value: Double, rule: ThresholdRule): Double {
    fun excursion(v: Double, scaled: Boolean = false): Double {
        val scale = if (scaled) 0.5 else 1.0
        val lower = rule.minValue?.let { it * scale - v * scale } ?: 0.0
        val upper = rule.maxValue?.let { v * scale - it * scale } ?: 0.0
        return max(0.0, max(lower, upper))
    }
    val old = excursion(previous)
    val next = excursion(value)
    // Finite input subtraction can overflow; scale only in that rare tied-infinity case.
    val worse = if (old.isInfinite() && next.isInfinite()) excursion(value, true) > excursion(previous, true)
        else next > old
    return if (worse) value else previous
}

/** Return only the outcome whose CAS succeeded, never an abandoned retry's side effects. */
internal fun commitAlertTransition(
    state: MutableStateFlow<Map<String, AlertRecord>>,
    compute: (Map<String, AlertRecord>) -> AlertOutcome?,
): AlertOutcome? {
    while (true) {
        val current = state.value
        val result = compute(current) ?: return null
        if (current[result.alert.alertId] == result.alert) return null
        val updated = current + (result.alert.alertId to result.alert)
        if (state.compareAndSet(current, updated)) return result
    }
}
