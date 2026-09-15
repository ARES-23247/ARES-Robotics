package com.ares.analytics.service

import kotlin.test.*

class ScalarDiagnosticRulesTest {
    @Test fun `CAN sources require an explicit recognized path`() {
        for (key in listOf("CAN/Utilization", "Hardware/CAN/Utilization", "Diagnostics/CANBus/CAN2/Utilization")) {
            assertEquals(ScalarDiagnosticKind.CAN_UTILIZATION, ScalarDiagnosticRules.kind(key))
        }
        for (key in listOf("Diagnostics/CANBus/Utilization", "Diagnostics/CANBus//Utilization",
            "Diagnostics/CANBus/CAN2/UtilizationPercent", "Diagnostics/CANBus/CAN2/ErrorCount",
            "Other/Diagnostics/CANBus/CAN2/Utilization", "CAN/UtilizationExtra", "")) {
            assertNull(ScalarDiagnosticRules.kind(key), key)
        }
    }
    @Test fun `scalar identities are exact and unrelated motor sources are excluded`() {
        assertEquals(ScalarDiagnosticKind.I2C_TIMEOUTS, ScalarDiagnosticRules.kind(ScalarDiagnosticRules.I2C_KEY))
        assertEquals(ScalarDiagnosticKind.LIMELIGHT_FPS, ScalarDiagnosticRules.kind(ScalarDiagnosticRules.VISION_KEY))
        assertNull(ScalarDiagnosticRules.kind("Vision/Limelight/FPSExtra"))
        assertNull(ScalarDiagnosticRules.kind("Hardware/Motors/fl/Power"))
    }
    @Test fun `ratios retain inclusive zero and one without percent guessing`() {
        val kind = ScalarDiagnosticKind.CAN_UTILIZATION
        for (v in listOf(0.0, 0.85, 1.0)) assertTrue(ScalarDiagnosticRules.accepts(kind, v))
        for (v in listOf(-0.01, 1.00001, 85.0, 100.0)) assertFalse(ScalarDiagnosticRules.accepts(kind, v))
    }
    @Test fun `timeout counts must be nonnegative exact integers`() {
        val kind = ScalarDiagnosticKind.I2C_TIMEOUTS
        for (v in listOf(0.0, 1.0, 2_147_483_647.0, 9_007_199_254_740_991.0)) assertTrue(ScalarDiagnosticRules.accepts(kind, v))
        for (v in listOf(-1.0, 0.25, 9_007_199_254_740_992.0, Double.MAX_VALUE)) assertFalse(ScalarDiagnosticRules.accepts(kind, v))
    }
    @Test fun `vision accepts stopped or positive frame rates without an invented upper limit`() {
        val kind = ScalarDiagnosticKind.LIMELIGHT_FPS
        for (v in listOf(0.0, 0.5, 5.0, 120.0, Double.MAX_VALUE)) assertTrue(ScalarDiagnosticRules.accepts(kind, v))
        assertFalse(ScalarDiagnosticRules.accepts(kind, -0.1))
    }
    @Test fun `nonfinite values are invalid for every built in scalar diagnostic`() {
        for (kind in ScalarDiagnosticKind.entries) {
            for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertFalse(ScalarDiagnosticRules.accepts(kind, v))
            }
        }
    }
    @Test fun `default rules retain actual keys units and scalar thresholds`() {
        val can = ScalarDiagnosticRules.defaultRule(ScalarDiagnosticKind.CAN_UTILIZATION, "bus")
        assertEquals("bus", can.key); assertEquals(0.85, can.maxValue); assertNull(can.minValue); assertTrue(can.audibleAlert)
        val i2c = ScalarDiagnosticRules.defaultRule(ScalarDiagnosticKind.I2C_TIMEOUTS, "count")
        assertEquals("count", i2c.key); assertEquals(0.5, i2c.maxValue); assertNull(i2c.minValue)
        val vision = ScalarDiagnosticRules.defaultRule(ScalarDiagnosticKind.LIMELIGHT_FPS, "rate")
        assertEquals("rate", vision.key); assertEquals(5.0, vision.minValue); assertNull(vision.maxValue); assertFalse(vision.audibleAlert)
    }
}
