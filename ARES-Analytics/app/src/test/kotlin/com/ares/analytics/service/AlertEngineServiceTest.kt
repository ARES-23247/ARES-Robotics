package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AlertEngineServiceTest {
    @Test fun `fixture finishes persistence even when its test body fails`() = runTest {
        var observed: AlertEngineService? = null
        val failure = assertFailsWith<IllegalStateException> {
            withAlertEngine {
                observed = engine
                emit(TelemetryFrame(1, "fixture-failure", "Robot/BatteryVoltage", 9.0))
                error("injected body failure")
            }
        }
        assertEquals("injected body failure", failure.message)
        val status = assertNotNull(observed).persistenceStatus.value
        assertTrue(status.stopped)
        assertEquals(0, status.pending)
    }

    @Test fun `XRP battery rule uses the canonical project threshold`() = runTest { withAlertEngine {
        engine.configureRobotContext(League.XRP, xrpBrownoutThresholdVolts = 4.3)
        emit(TelemetryFrame(1_000L, "xrp-session", "Robot/BatteryVoltage", 6.0))
        assertTrue(alerts.isEmpty())
        emit(TelemetryFrame(1_020L, "xrp-session", "Robot/BatteryVoltage", 4.2))
        val alert = alerts.single()
        assertEquals("xrp-session", alert.sessionId)
        assertEquals(4.2, alert.peakValue)
        assertEquals("Low XRP Battery Voltage (<4.30V)", engine.getRuleDisplayName(alert.ruleKey))
    } }

    @Test fun `one moderate loop spike is diagnostic evidence but repeated spikes alert`() = runTest { withAlertEngine {
        emit(TelemetryFrame(1_000L, "loop-session", "Robot/LoopTimeMs", 40.0))
        assertFalse(alerts.any { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey })
        emit(TelemetryFrame(1_020L, "loop-session", "Robot/LoopTimeMs", 30.0))
        assertTrue(alerts.isEmpty(), "two moderate spikes do not meet the three-sample rule")
        emit(TelemetryFrame(1_040L, "loop-session", "Robot/LoopTimeMs", 28.0))
        val alert = alerts.single { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey }
        assertNull(alert.resolveTimestampMs)
        assertEquals(40.0, alert.peakValue)
    } }

    @Test fun `severe loop stall alerts immediately and sustained healthy timing resolves it`() = runTest { withAlertEngine {
        emit(TelemetryFrame(2_000L, "severe-session", "Robot/LoopTimeMs", 120.0))
        val alert = alerts.single { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey }
        assertNull(alert.resolveTimestampMs)
        emit(TelemetryFrame(3_100L, "severe-session", "Robot/LoopTimeMs", 20.0))
        val resolved = alerts.single { it.alertId == alert.alertId }
        assertEquals(3_100L, resolved.resolveTimestampMs)
        assertEquals(1_100L, resolved.durationMs)
    } }

    @Test fun testAlertEvaluation() = runTest {
        withAlertEngine(listOf(
            ThresholdRule("/Drive/Voltage", "Low Battery Voltage", minValue = 11.5, audibleAlert = false),
            ThresholdRule("/Drive/EkfDrift", "High EKF Position Drift", maxValue = 0.20, audibleAlert = false),
        )) {
            emit(TelemetryFrame(1000L, "session-123", "/Drive/Voltage", 11.0))
            val alert = alerts.single()
            assertEquals("/Drive/Voltage", alert.ruleKey)
            assertEquals("session-123", alert.sessionId)
            assertEquals(11.0, alert.peakValue)
            assertFalse(alert.triaged)
            emit(TelemetryFrame(1020L, "session-123", "/Drive/Voltage", 12.0))
            val resolved = alerts.single()
            assertEquals(1020L, resolved.resolveTimestampMs)
            assertEquals(20L, resolved.durationMs)
            triage(resolved.alertId)
            assertTrue(alerts.single().triaged)
            assertTrue(persisted.any { it.alertId == resolved.alertId && it.triaged && it.durationMs == 20L })
        }
    }

    @Test fun `same rule in consecutive sessions creates independent alerts`() = runTest {
        withAlertEngine(listOf(ThresholdRule("Robot/BatteryVoltage", "Low battery", minValue = 10.5, audibleAlert = false))) {
            emit(TelemetryFrame(1_000L, "session-a", "Robot/BatteryVoltage", 10.0))
            emit(TelemetryFrame(2_000L, "session-b", "/Robot/BatteryVoltage", 9.8))
            val matching = alerts.filter { it.ruleKey == "Robot/BatteryVoltage" }
            assertEquals(2, matching.size)
            assertEquals(setOf("session-a", "session-b"), matching.map { it.sessionId }.toSet())
            assertEquals(2, matching.map { it.alertId }.toSet().size)
        }
    }
}
