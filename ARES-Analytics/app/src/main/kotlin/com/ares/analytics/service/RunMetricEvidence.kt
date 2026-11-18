package com.ares.analytics.service

import com.ares.analytics.shared.models.SessionSummary
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Run-wide summary. A null value means the recorded signal is unavailable. */
internal fun SessionSummary?.metricEvidence() = buildJsonObject {
    val summary = this@metricEvidence
    fun metric(name: String, key: String, value: Double?, unit: String) {
        putJsonObject(name) {
            put("unit", unit)
            val measured = value?.let { summary?.observedMetric(key, it) }
            put("value", measured?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
    metric("minimumBatteryVoltage", "battery", summary?.minBatteryVoltage, "V")
    metric("maximumEkfDrift", "drift", summary?.maxEkfDrift, "m")
    metric("meanLoopTime", "loop", summary?.avgLoopTimeMs, "ms")
    metric("p95LoopTime", "loop_p95", summary?.p95LoopTimeMs, "ms")
    metric("meanCrossTrackError", "cross_track", summary?.avgCrossTrackError, "m")
    metric("meanBatteryResistance", "resistance", summary?.avgBatteryResistance, "ohm")
    metric("visionAcceptance", "acceptance", summary?.visionAcceptanceRate, "fraction")
    metric("meanVisionLatency", "latency", summary?.avgVisionLatencyMs, "ms")
}
