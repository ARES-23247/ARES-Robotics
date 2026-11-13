package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertRuleHotReloadTest {

    @Test
    fun `replaceRules hot reloads evaluation immediately and persists to thresholds json`() = runTest {
        val initial = listOf(
            ThresholdRule("Robot/BatteryVoltage", "Low Battery", minValue = 10.5, audibleAlert = false),
        )
        withAlertEngine(initial) {
            // 11.2V is above 10.5V initially
            emit(TelemetryFrame(1_000L, "s-hot", "Robot/BatteryVoltage", 11.2))
            assertTrue(alerts.isEmpty())

            // Tighten live threshold to 11.5V without restarting AlertEngineService
            val tighter = listOf(
                ThresholdRule("Robot/BatteryVoltage", "Competition Battery Floor", minValue = 11.5, audibleAlert = false),
            )
            val result = engine.replaceRules(tighter, persistToDisk = true)
            assertTrue(result.isSuccess)
            assertNull(engine.configurationWarningFlow.value)
            assertEquals(11.5, engine.configuredRules.value.single().minValue)

            // Next frame at 11.2V now triggers immediately against hot-reloaded rule
            emit(TelemetryFrame(2_000L, "s-hot", "Robot/BatteryVoltage", 11.2))
            val triggered = alerts.single()
            assertEquals(11.2, triggered.peakValue)
            assertEquals("Competition Battery Floor", engine.getRuleDisplayName(triggered.ruleKey))
        }
    }

    @Test
    fun `replaceRules rejects reversed bounds and preserves previous active rules`() = runTest {
        val initial = listOf(
            ThresholdRule("Robot/BatteryVoltage", "Low Battery", minValue = 10.8, audibleAlert = false),
        )
        withAlertEngine(initial) {
            val invalid = listOf(
                ThresholdRule("Robot/BatteryVoltage", "Broken Bounds", minValue = 13.0, maxValue = 11.0, audibleAlert = false),
            )
            val failure = engine.replaceRules(invalid, persistToDisk = false)
            assertTrue(failure.isFailure)
            assertFalse(engine.configuredRules.value.single().displayName == "Broken Bounds")
            assertEquals(10.8, engine.configuredRules.value.single().minValue)

            emit(TelemetryFrame(1_000L, "s-safe", "Robot/BatteryVoltage", 10.6))
            assertEquals(1, alerts.size)
        }
    }
}
