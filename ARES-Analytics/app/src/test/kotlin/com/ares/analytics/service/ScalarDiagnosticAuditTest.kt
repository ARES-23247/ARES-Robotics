package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScalarDiagnosticAuditTest {
    private suspend fun TestScope.withEngine(rule: ThresholdRule? = null, block: suspend Fixture.() -> Unit) {
        val store = TelemetryStore()
        val nt = mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store); `when`(nt.telemetryFlow).thenReturn(store.updates)
        val path = Files.createTempFile("scalar-diagnostics", ".json")
        Files.writeString(path, Json.encodeToString(listOfNotNull(rule)))
        val engine = AlertEngineService(mock(DatabaseService::class.java), nt, path.toString(), StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this, store, engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope: TestScope, val store: TelemetryStore, val engine: AlertEngineService) {
        suspend fun send(ms: Long, key: String, value: Double) {
            store.accept(TelemetryFrame(ms, "live-telemetry", key, value)); scope.runCurrent()
        }
        val active get() = engine.alerts.value.filter { it.resolveTimestampMs == null }
    }
    private val a = "Diagnostics/CANBus/CAN2/Utilization"
    private val b = "Diagnostics/CANBus/rio/Utilization"

    @Test fun `each CAN bus creates its own observed occurrence`() = runTest { withEngine {
        send(100, a, 0.99); send(200, b, 0.90)
        assertEquals(setOf(a, b), active.map { it.ruleKey }.toSet())
    } }
    @Test fun `healthy second CAN bus cannot refresh a cached first bus`() = runTest { withEngine {
        send(100, a, 0.99); val first = active.single()
        send(200, b, 0.20)
        assertEquals(listOf(first), active)
    } }
    @Test fun `legacy CAN ratio preserves the actual source key`() = runTest { withEngine {
        send(100, "CAN/Utilization", 0.95)
        assertEquals("CAN/Utilization", active.single().ruleKey)
    } }
    @Test fun `configured CAN limit is not overridden by the default`() = runTest {
        withEngine(ThresholdRule(a, "Configured", maxValue = 0.95, audibleAlert = false)) {
            send(100, a, 0.90); assertTrue(engine.alerts.value.isEmpty())
        }
    }
    @Test fun `out of range ratio cannot resolve a known CAN fault`() = runTest { withEngine {
        send(100, a, 0.95); send(200, a, 2.0)
        assertEquals(0.95, active.single().peakValue)
    } }
    @Test fun `negative ratio cannot resolve a known CAN fault`() = runTest { withEngine {
        send(100, a, 0.95); send(200, a, -1.0)
        assertEquals(0.95, active.single().peakValue)
    } }
    @Test fun `configured I2C limit does not get a second hard coded violation`() = runTest {
        withEngine(ThresholdRule("Hardware/I2C/Timeouts", "Configured", maxValue = 5.0, audibleAlert = false)) {
            send(100, "Hardware/I2C/Timeouts", 1.0); assertTrue(engine.alerts.value.isEmpty())
        }
    }
    @Test fun `fractional I2C count cannot create a timeout`() = runTest { withEngine {
        send(100, "Hardware/I2C/Timeouts", 0.25); assertTrue(active.isEmpty())
    } }
    @Test fun `negative I2C count cannot resolve a timeout`() = runTest { withEngine {
        send(100, "Hardware/I2C/Timeouts", 1.0); send(200, "Hardware/I2C/Timeouts", -1.0)
        assertEquals(1, active.size)
    } }
    @Test fun `configured vision threshold has one consistent evaluation`() = runTest {
        withEngine(ThresholdRule("Vision/Limelight/FPS", "Configured", minValue = 10.0, audibleAlert = false)) {
            send(100, "Vision/Limelight/FPS", 7.0)
            assertEquals(1, active.size)
        }
    }
    @Test fun `negative vision rate is unknown rather than a measured fault`() = runTest { withEngine {
        send(100, "Vision/Limelight/FPS", -1.0); assertTrue(active.isEmpty())
    } }
    @Test fun `default scalar thresholds preserve exact boundaries and recovery`() = runTest { withEngine {
        send(100, a, 0.85); assertTrue(active.isEmpty())
        send(200, a, 1.0); assertEquals(1, active.size)
        send(300, a, 0.0); assertTrue(active.isEmpty())
        send(400, "Hardware/I2C/Timeouts", 0.0); assertTrue(active.isEmpty())
        send(500, "Hardware/I2C/Timeouts", 1.0); assertEquals(1, active.size)
        send(600, "Hardware/I2C/Timeouts", 0.0); assertTrue(active.isEmpty())
        send(700, "Vision/Limelight/FPS", 0.0); assertEquals(1, active.size)
        send(800, "Vision/Limelight/FPS", 5.0); assertTrue(active.isEmpty())
        assertEquals(3, engine.alerts.value.size)
    } }
    @Test fun `legacy aliases remain independent from canonical CAN buses`() = runTest { withEngine {
        send(100, a, 0.95); send(200, "Hardware/CAN/Utilization", 0.90)
        send(300, "CAN/Utilization", 0.91)
        assertEquals(3, active.size)
        send(400, "Hardware/CAN/Utilization", 0.1)
        assertEquals(setOf(a, "CAN/Utilization"), active.map { it.ruleKey }.toSet())
    } }
    @Test fun `invalid newer observation still prevents a delayed healthy update`() = runTest { withEngine {
        send(100, a, 0.95); send(300, a, -1.0); send(200, a, 0.1)
        assertEquals(1, active.size)
        send(400, a, 0.1); assertTrue(active.isEmpty())
    } }
    @Test fun `normalized configured key and two sided limits are preserved`() = runTest {
        withEngine(ThresholdRule("/Hardware/I2C/Timeouts", "Range", minValue = 1.0, maxValue = 5.0, audibleAlert = false)) {
            send(100, "Hardware/I2C/Timeouts", 0.0)
            assertEquals("/Hardware/I2C/Timeouts", active.single().ruleKey)
            send(200, "Hardware/I2C/Timeouts", 2.0); assertTrue(active.isEmpty())
            send(300, "Hardware/I2C/Timeouts", 6.0); assertEquals(1, active.size)
        }
    }
    @Test fun `unrelated diagnostic topics still support explicit scalar configuration`() = runTest {
        val key = "Diagnostics/CANBus/CAN2/ErrorCount"
        withEngine(ThresholdRule(key, "Custom errors", maxValue = 0.0, audibleAlert = false)) {
            send(100, key, 1.0); assertEquals(key, active.single().ruleKey)
            send(200, key, 0.0); assertTrue(active.isEmpty())
        }
    }
}
