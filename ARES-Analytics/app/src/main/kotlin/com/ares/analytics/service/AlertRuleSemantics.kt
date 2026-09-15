package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.ThresholdRule

/** Scalar rules use inclusive allowed bounds, including derived binary diagnostic values. */
internal object AlertRuleSemantics {
    fun violates(value: Double, rule: ThresholdRule): Boolean = value.isFinite() &&
        ((rule.minValue?.let { value < it } == true) || (rule.maxValue?.let { value > it } == true))

    /** Loop bounds describe a fixed temporal detector, not an arbitrary scalar comparator. */
    fun configurationProblem(normalizedKey: String, rule: ThresholdRule): String? {
        if (normalizedKey !in TelemetryMetricCatalog.LOOP_TIME.keys) return null
        if (rule.minValue == null &&
            (rule.maxValue == null || rule.maxValue == LoopOverrunWindow.MODERATE_THRESHOLD_MS)) return null
        return "Loop alerts require maxValue 25 and no minimum, or no bounds to disable; " +
            "the temporal detector uses 3 samples above 25 ms or one at least 100 ms."
    }
}
