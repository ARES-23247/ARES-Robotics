package com.areslib.tuning

import com.areslib.telemetry.TelemetryTopicNormalizer

/** Strict declaration-driven NetworkTables contract. */
object TuningTopics {
    const val SCHEMA_VERSION = 3
    const val ROOT = "Tuning"
    const val SCHEMA_VERSION_TOPIC = "$ROOT/SchemaVersion"

    /** Normalizes transport-only leading slashes; aliases are intentionally unsupported. */
    fun canonicalize(topic: String): String {
        val normalized = TelemetryTopicNormalizer.normalizeTopic(topic)
        return if (normalized.startsWith("$ROOT/")) normalized else "$ROOT/$normalized"
    }
}
