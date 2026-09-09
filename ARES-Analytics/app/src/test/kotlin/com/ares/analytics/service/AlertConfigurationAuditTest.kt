package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertConfigurationAuditTest {
    private val key = TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey
    private suspend fun TestScope.checkFallback(contents: String) {
        val path = Files.createTempFile("alert-config", ".json")
        Files.writeString(path, contents)
        try {
            withEngine(path.toString()) { engine ->
                assertEquals("Low Battery Voltage (<10.5V)", engine.getRuleDisplayName(key))
                assertNotNull(engine.configurationWarning)
            }
            assertEquals(contents, Files.readString(path))
        } finally { Files.deleteIfExists(path) }
    }
    private suspend fun TestScope.withEngine(path: String, block: suspend (AlertEngineService) -> Unit) {
        val store = TelemetryStore(); val nt = mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store); `when`(nt.telemetryFlow).thenReturn(store.updates)
        val engine = AlertEngineService(mock(DatabaseService::class.java), nt, path, StandardTestDispatcher(testScheduler))
        try { runCurrent(); block(engine) } finally { engine.dispose(); runCurrent() }
    }
    @Test fun `reversed bounds reject the whole configuration`() = runTest {
        checkFallback("""[{"key":"Robot/BatteryVoltage","displayName":"Invalid","minValue":15,"maxValue":5}]""")
    }
    @Test fun `normalized duplicate keys cannot silently overwrite one another`() = runTest {
        checkFallback("""[{"key":"/Robot/BatteryVoltage","displayName":"First","minValue":10},{"key":"Robot/BatteryVoltage","displayName":"Second","minValue":0}]""")
    }
    @Test fun `unknown threshold fields cannot silently disable a rule`() = runTest {
        checkFallback("""[{"key":"Robot/BatteryVoltage","displayName":"Typo","minValu":10}]""")
    }
    @Test fun `blank names reject incomplete configuration`() = runTest {
        checkFallback("""[{"key":"Robot/BatteryVoltage","displayName":" ","minValue":0}]""")
    }
    @Test fun `failure to create default file does not prevent diagnostics startup`() = runTest {
        val parent = Files.createTempFile("blocked-config-parent", ".txt")
        try {
            withEngine(parent.resolve("thresholds.json").toString()) { engine ->
                assertEquals("Low Battery Voltage (<10.5V)", engine.getRuleDisplayName(key))
                assertNotNull(engine.configurationWarning)
            }
        } finally { Files.deleteIfExists(parent) }
    }
}
