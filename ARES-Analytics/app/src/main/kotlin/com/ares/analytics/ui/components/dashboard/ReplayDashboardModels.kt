package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.TelemetryMetricCatalog

internal typealias ReplayHealthSnapshot = ControllerHealthSnapshot

/** Exact normalized topics only; canonical names precede aliases independently of map order. */
internal fun ReplayFrame.toReplayHealthSnapshot(): ReplayHealthSnapshot {
    fun <T> normalizedHealthValues(input: Map<String, T>): Map<String, T> {
        val result = HashMap<String, T>()
        val sourceKeys = HashMap<String, String>()
        for ((key, value) in input) {
            val normalized = TelemetryMetricCatalog.normalizeTopic(key)
            if (normalized !in HEALTH_KEY_INDEX) continue
            val previous = sourceKeys[normalized]
            if (previous == null || key == normalized || (previous != normalized && key < previous)) {
                result[normalized] = value
                sourceKeys[normalized] = key
            }
        }
        return result
    }
    val numbers = normalizedHealthValues(values)
    val strings = normalizedHealthValues(stringValues)
    return controllerHealthSnapshot(numbers::get, strings::get)
}
