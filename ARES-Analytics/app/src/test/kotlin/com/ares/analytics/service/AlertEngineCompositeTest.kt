package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AlertEngineCompositeTest {
    @Test fun testMotorStallAlertTriggering() = runTest { withAlertEngine {
        emit(TelemetryFrame(100L, "live", "Hardware/Motors/fl/Power", 0.8))
        emit(TelemetryFrame(100L, "live", "Hardware/Motors/fl/Velocity", 0.0))
        assertTrue(alerts.isEmpty(), "power and velocity alone do not establish a stall")
        emit(TelemetryFrame(100L, "live", "Hardware/Motors/fl/CurrentAmps", 10.0))
        val stall = alerts.single { it.ruleKey == "Hardware/Motors/fl/Stall" }
        assertFalse(stall.triaged)
        assertEquals("live", stall.sessionId)
        assertTrue(persisted.any { it.alertId == stall.alertId })
    } }

    @Test fun `ratio based canonical CAN utilization triggers alert`() = runTest { withAlertEngine {
        emit(TelemetryFrame(100L, "can-session", "Diagnostics/CANBus/CAN2/Utilization", 0.91))
        val can = alerts.single { it.ruleKey == "Diagnostics/CANBus/CAN2/Utilization" }
        assertEquals("can-session", can.sessionId)
        assertEquals(0.91, can.peakValue)
        assertTrue(persisted.any { it.alertId == can.alertId })
    } }
}
