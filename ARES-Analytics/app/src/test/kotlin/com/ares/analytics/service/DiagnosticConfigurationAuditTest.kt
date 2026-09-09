package com.ares.analytics.service

import com.ares.analytics.shared.models.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticConfigurationAuditTest {
    private val stallKey = "Hardware/Motors/fl/Stall"
    private val disconnectedKey = "Hardware/Motors/fl/Disconnected"
    private val loopKey = "Robot/LoopTimeMs"
    private suspend fun TestScope.withEngine(rules: List<ThresholdRule>, block: suspend Fixture.() -> Unit) {
        val store = TelemetryStore(); val nt = mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store)
        val path = Files.createTempFile("diagnostic-config", ".json")
        Files.writeString(path, Json.encodeToString(rules))
        val engine = AlertEngineService(mock(DatabaseService::class.java), nt, path.toString(), StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this, store, engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope: TestScope, val store: TelemetryStore, val engine: AlertEngineService) {
        suspend fun send(us: Long, key: String, value: Double) {
            store.accept(TelemetryFrame(us / 1000, "session", key, value, timestampUs = us)); scope.runCurrent()
        }
        suspend fun motor(current: Double = 10.0, power: Double = 0.8) {
            send(100_000, "Hardware/Motors/fl/Power", power)
            send(100_000, "Hardware/Motors/fl/Velocity", 0.0)
            send(100_000, "Hardware/Motors/fl/CurrentAmps", current)
        }
        val active get() = engine.alerts.value.filter { it.resolveTimestampMs == null }
    }
    @Test fun `boundless motor rules disable both derived alerts`() = runTest {
        withEngine(listOf(ThresholdRule(stallKey, "Disabled"), ThresholdRule(disconnectedKey, "Disabled"))) {
            motor(); assertTrue(active.isEmpty())
            send(2_000_000, "Hardware/Motors/fl/Power", 0.8)
            send(2_000_000, "Hardware/Motors/fl/Velocity", 0.0)
            send(2_000_000, "Hardware/Motors/fl/CurrentAmps", 0.0)
            assertTrue(active.isEmpty())
        }
    }
    @Test fun `binary diagnostic value equal to maximum does not violate it`() = runTest {
        withEngine(listOf(ThresholdRule(stallKey, "Inclusive", maxValue = 1.0, audibleAlert = false))) {
            motor(); assertTrue(active.isEmpty())
        }
    }
    @Test fun `lower bound evaluates measured false and resolves on true`() = runTest {
        withEngine(listOf(ThresholdRule(stallKey, "Expect binding", minValue = 0.5, audibleAlert = false))) {
            motor(power = 0.0); assertEquals(0.0, active.single().peakValue)
            send(200_000, "Hardware/Motors/fl/Power", 0.8)
            assertTrue(active.isEmpty()); assertEquals(200L, engine.alerts.value.single().resolveTimestampMs)
        }
    }
    @Test fun `raw derived topic cannot fabricate a motor diagnosis`() = runTest {
        withEngine(listOf(ThresholdRule(stallKey, "Derived", maxValue = 0.5, audibleAlert = false))) {
            send(100_000, "Hardware/Motors/fl/Stall", 1.0)
            assertTrue(active.isEmpty())
        }
    }
    @Test fun `raw derived topic cannot clear a diagnosis backed by feedback`() = runTest {
        withEngine(emptyList()) {
            motor(); val observed = active.single()
            send(200_000, "/Hardware/Motors/fl/Stall", 0.0)
            assertEquals(observed, active.single())
        }
    }
    @Test fun `disabled canonical loop policy also disables inherited aliases`() = runTest {
        withEngine(listOf(ThresholdRule(loopKey, "Disabled", audibleAlert = false))) {
            send(100_000, loopKey, 200.0)
            send(200_000, "Profiling/LoopTime_ms", 200.0)
            assertTrue(active.isEmpty())
        }
    }
    @Test fun `explicitly enabled alias remains independent of disabled canonical policy`() = runTest {
        withEngine(listOf(ThresholdRule(loopKey, "Disabled"),
            ThresholdRule("Profiling/LoopTime_ms", "Enabled", maxValue = 25.0, audibleAlert = false))) {
            send(100_000, loopKey, 200.0)
            send(200_000, "Profiling/LoopTime_ms", 200.0)
            assertEquals("Profiling/LoopTime_ms", active.single().ruleKey)
        }
    }
    @Test fun `unsupported loop limits cannot silently claim a different detector policy`() = runTest {
        withEngine(listOf(ThresholdRule(loopKey, "Custom 500", maxValue = 500.0, audibleAlert = false))) {
            assertNotNull(engine.configurationWarning)
            assertTrue(engine.configurationWarning.contains("loop", ignoreCase = true))
            assertFalse(engine.getRuleDisplayName(loopKey).contains("500"))
        }
    }
    @Test fun `loop alert retains configured transport key and updates its peak`() = runTest {
        withEngine(listOf(ThresholdRule("/$loopKey", "Configured", maxValue = 25.0, audibleAlert = false))) {
            send(100_000, loopKey, 100.0); send(200_000, loopKey, 150.0)
            assertEquals("/$loopKey", active.single().ruleKey)
            assertEquals(150.0, active.single().peakValue)
        }
    }
    @Test fun `explicitly disabled alias does not disable enabled canonical source`() = runTest {
        withEngine(listOf(ThresholdRule(loopKey, "Enabled", maxValue = 25.0, audibleAlert = false),
            ThresholdRule("Profiling/LoopTime_ms", "Disabled"))) {
            send(100_000, "Profiling/LoopTime_ms", 200.0)
            send(200_000, loopKey, 200.0)
            assertEquals(loopKey, active.single().ruleKey)
        }
    }
    @Test fun `unknown feedback cannot satisfy or resolve a lower-bound motor rule`() = runTest {
        withEngine(listOf(ThresholdRule(stallKey, "Expect binding", minValue = 0.5, audibleAlert = false))) {
            send(0, "Hardware/Motors/fl/Power", 0.0); assertTrue(active.isEmpty())
            motor(power = 0.0); val observed = active.single()
            send(200_000, "Hardware/Motors/fl/CurrentAmps", Double.NaN)
            assertEquals(observed, active.single())
        }
    }
    @Test fun `custom motor names remain ordinary configured telemetry topics`() = runTest {
        val custom = "Hardware/Motors/arm/Stall"
        withEngine(listOf(ThresholdRule(custom, "Device condition", maxValue = 0.5, audibleAlert = false))) {
            send(100_000, custom, 1.0); assertEquals(custom, active.single().ruleKey)
            send(200_000, custom, 0.0); assertTrue(active.isEmpty())
        }
    }
}
