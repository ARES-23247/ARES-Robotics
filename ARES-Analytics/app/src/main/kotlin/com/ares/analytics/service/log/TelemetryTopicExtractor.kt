package com.ares.analytics.service.log

import com.ares.analytics.shared.TelemetryFrame
import com.areslib.telemetry.TelemetryTopicNormalizer

/**
 * Utility object for standardizing NetworkTables 4 (NT4) topic strings and telemetry keys across diverse robot log formats.
 *
 * Delegates topic normalization to [TelemetryTopicNormalizer] from `ARESLib-Kotlin` to transform physical motor keys
 * (mapping `bl`/`br` back-left/right to standard `rl`/`rr` rear-left/right motor names), remove illegal characters,
 * and enforce unified NT4 forward-slash topic pathing (`"Drive/Pose_X"`).
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
     * @param key Raw topic string extracted from log files or NetworkTables (e.g., `"hardware.motors.bl.power"`).
     * @return Canonicalized slash-separated topic key (e.g., `"Hardware/Motors/rl/Power"`).
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

