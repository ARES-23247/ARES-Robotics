package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlatformAlertAuditTest {
    private val key = TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey
    private val rule = ThresholdRule(key, "Battery", minValue = 10.5, audibleAlert = false)

    @Test fun `unchanged XRP lookups reuse the effective rule`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        assertSame(policy.effectiveRule(key, rule), policy.effectiveRule(key, rule))
    }
    @Test fun `XRP display includes the retained upper bound`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        val effective = policy.effectiveRule(key, rule.copy(maxValue = 6.0))
        assertTrue(effective.displayName.contains(">6.00V"), effective.displayName)
        assertEquals(6.0, effective.maxValue)
    }
    @Test fun `configured battery aliases use the same XRP project minimum`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        for (alias in TelemetryMetricCatalog.BATTERY_VOLTAGE.keys) {
            val effective = policy.effectiveRule(alias, rule.copy(key = alias))
            assertEquals(4.3, effective.minValue, alias)
            assertEquals(alias, effective.key)
        }
    }
    private suspend fun TestScope.withEngine(rules: List<ThresholdRule> = listOf(rule), block: suspend Fixture.() -> Unit) {
        val store = TelemetryStore(); val nt = mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store); `when`(nt.telemetryFlow).thenReturn(store.updates)
        val path = Files.createTempFile("platform-alert", ".json")
        Files.writeString(path, Json.encodeToString(rules))
        val engine = AlertEngineService(mock(DatabaseService::class.java), nt, path.toString(), StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this, store, engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope: TestScope, val store: TelemetryStore, val engine: AlertEngineService) {
        suspend fun send(ms: Long, volts: Double, source: String = TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey) {
            store.accept(TelemetryFrame(ms, "live-telemetry", source, volts))
            scope.runCurrent()
        }
    }
    @Test fun `reapplying context cannot discard an active battery fault`() = runTest { withEngine {
        send(100, 9.0); val original = engine.alerts.value.single()
        engine.configureRobotContext(League.FTC); scope.runCurrent()
        assertEquals(listOf(original), engine.alerts.value)
    } }
    @Test fun `context refresh retains resolved and acknowledged evidence`() = runTest { withEngine {
        send(100, 9.0); engine.triageAlert(engine.alerts.value.single().alertId); send(200, 12.0)
        val original = engine.alerts.value.single()
        engine.configureRobotContext(League.FRC); scope.runCurrent()
        assertEquals(listOf(original), engine.alerts.value)
    } }
    @Test fun `changed threshold requires fresh evidence before resolving`() = runTest { withEngine {
        send(100, 9.0)
        engine.configureRobotContext(League.XRP, 4.3); scope.runCurrent()
        assertNull(engine.alerts.value.single().resolveTimestampMs)
        send(200, 6.0)
        assertEquals(200L, engine.alerts.value.single().resolveTimestampMs)
    } }
    @Test fun `negative battery feedback cannot create or worsen a voltage fault`() = runTest { withEngine {
        send(100, -1.0); assertTrue(engine.alerts.value.isEmpty())
        send(200, 9.0); val fault = engine.alerts.value.single()
        send(300, -1.0); assertEquals(fault, engine.alerts.value.single())
    } }
    @Test fun `zero battery voltage remains a valid low voltage fault`() = runTest { withEngine {
        send(100, 0.0); assertEquals(0.0, engine.alerts.value.single().peakValue)
        send(200, 12.0); assertEquals(200L, engine.alerts.value.single().resolveTimestampMs)
    } }
    @Test fun `configured battery aliases retain independent XRP evidence`() = runTest {
        val sources = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.toList()
        withEngine(sources.map { rule.copy(key = it) }) {
            engine.configureRobotContext(League.XRP, 4.3)
            for ((i, source) in sources.withIndex()) send(100L + i, 6.0, source)
            assertTrue(engine.alerts.value.isEmpty())
            for ((i, source) in sources.withIndex()) send(200L + i, 4.0, source)
            assertEquals(sources.toSet(), engine.alerts.value.map { it.ruleKey }.toSet())
            send(300, -1.0, sources.last())
            assertTrue(engine.alerts.value.all { it.peakValue == 4.0 })
        }
    }
    @Test fun `target reset still discards previous target evidence`() = runTest { withEngine {
        send(100, 9.0); engine.configureRobotContext(League.FRC)
        store.clear(); scope.runCurrent()
        assertTrue(engine.alerts.value.isEmpty())
        send(200, 9.0); assertEquals(1, engine.alerts.value.size)
    } }
    @Test fun `terminal disposal ignores later context changes`() = runTest { withEngine {
        engine.configureRobotContext(League.XRP, 4.3)
        val name = engine.getRuleDisplayName(key)
        engine.dispose(); engine.configureRobotContext(League.XRP, 6.0)
        assertEquals(name, engine.getRuleDisplayName(key))
    } }
}
