package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.ThresholdRule
import kotlin.test.*

class AlertRuleSemanticsTest {
    @Test fun `scalar and binary domains share inclusive allowed bounds`() {
        val range = ThresholdRule("custom", "Range", minValue = 0.0, maxValue = 1.0)
        for (value in listOf(0.0, 0.5, 1.0)) assertFalse(AlertRuleSemantics.violates(value, range))
        for (value in listOf(-Double.MIN_VALUE, 1.0000000000000002)) assertTrue(AlertRuleSemantics.violates(value, range))
        assertTrue(AlertRuleSemantics.violates(0.0, range.copy(minValue = 0.5)))
        assertTrue(AlertRuleSemantics.violates(1.0, range.copy(maxValue = 0.5)))
    }
    @Test fun `boundless rules and nonfinite measurements do not violate`() {
        val rule = ThresholdRule("custom", "Disabled")
        for (value in listOf(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE)) assertFalse(AlertRuleSemantics.violates(value, rule))
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
            assertFalse(AlertRuleSemantics.violates(value, rule.copy(minValue = 0.0, maxValue = 1.0)))
    }
    @Test fun `all loop aliases accept fixed or disabled policy only`() {
        for (key in TelemetryMetricCatalog.LOOP_TIME.keys) {
            val rule = ThresholdRule(key, "Loop", maxValue = 25.0)
            assertNull(AlertRuleSemantics.configurationProblem(key, rule))
            assertNull(AlertRuleSemantics.configurationProblem(key, rule.copy(maxValue = null)))
            for (invalid in listOf(rule.copy(maxValue = 500.0), rule.copy(maxValue = 10.0),
                rule.copy(minValue = 0.0), rule.copy(minValue = 5.0, maxValue = null)))
                assertNotNull(AlertRuleSemantics.configurationProblem(key, invalid))
        }
    }
    @Test fun `ordinary similarly named custom topics retain arbitrary bounds`() {
        for (key in listOf("Custom/LoopTimeMs", "Robot/LoopTimeMs/Extra", "Hardware/Motors/arm/Stall")) {
            assertNull(AlertRuleSemantics.configurationProblem(key, ThresholdRule(key, "Custom", minValue = 2.0, maxValue = 500.0)))
        }
    }
}
