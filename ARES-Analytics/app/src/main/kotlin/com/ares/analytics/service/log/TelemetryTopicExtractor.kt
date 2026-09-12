package com.ares.analytics.service.log

import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.TelemetryTopicNormalizer

/**
 * Utility object for standardizing NetworkTables 4 (NT4) topic strings and telemetry keys across diverse robot log formats.
 *
 * Delegates to [TelemetryTopicNormalizer] to remove transport-only leading slashes.
 * Interior separators, case and hardware IDs are preserved; this does not rename motors or
 * validate topic characters.
 *
 * ### Thread Safety & Performance Guarantees:
 * Thread-safe stateless singleton object. Functions execute without locking or mutable state side-effects.
 *
 * @see com.ares.analytics.service.nt4.Nt4Topic
 */
object TelemetryTopicExtractor {

    /**
     * Normalizes a raw telemetry topic string into canonical NT4 hierarchy format.
     *
     * @param key Raw topic string extracted from logs or NT4 (e.g., `"/Hardware/Motors/bl/Power"`).
     * @return The same key without leading slashes (e.g., `"Hardware/Motors/bl/Power"`).
     */
    fun normalizeTopic(key: String): String {
        return TelemetryTopicNormalizer.normalizeTopic(key)
    }

    /**
     * Creates a copy of the target [TelemetryFrame] with its topic key normalized.
     *
     * @param frame Raw telemetry frame sample.
     * @return Updated [TelemetryFrame] instance containing the canonicalized topic key.
     */
    fun extractTopics(frame: TelemetryFrame): TelemetryFrame {
        return frame.copy(key = normalizeTopic(frame.key))
    }
}

