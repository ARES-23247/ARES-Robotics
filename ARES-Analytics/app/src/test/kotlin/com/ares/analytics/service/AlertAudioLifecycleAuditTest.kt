package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import java.nio.file.Files
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AlertAudioLifecycleAuditTest {
    private class Fixture(val scope: TestScope, val store: TelemetryStore, val engine: AlertEngineService) {
        var started = 0; var stopped = 0
        suspend fun alarm() {
            store.accept(TelemetryFrame(100, "session", "Audit/Value", 2.0)); scope.runCurrent()
            assertEquals(1, started); assertEquals(0, stopped)
        }
    }
    private suspend fun TestScope.withEngine(failingStorage: Boolean = false, block: suspend Fixture.() -> Unit) {
        val store = TelemetryStore(); val nt = mock(Nt4ClientService::class.java)
        val db = mock(DatabaseService::class.java) { call ->
            if (failingStorage && call.method.name == "insertAlert") throw IOException("offline")
            else RETURNS_DEFAULTS.answer(call)
        }
        `when`(nt.telemetryStore).thenReturn(store)
        val path = Files.createTempFile("audio-lifecycle", ".json")
        Files.writeString(path, """[{"key":"Audit/Value","displayName":"Alert","maxValue":1,"audibleAlert":true}]""")
        lateinit var fixture: Fixture
        val engine = AlertEngineService(db, nt, path.toString(), StandardTestDispatcher(testScheduler)) {
            fixture.started++
            try { awaitCancellation() } finally { fixture.stopped++ }
        }
        fixture = Fixture(this, store, engine)
        try { runCurrent(); fixture.block() } finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    @Test fun `stopping evaluation also cancels its alert audio`() = runTest { withEngine {
        alarm(); engine.stop(); scope.runCurrent(); assertEquals(1, stopped)
        assertEquals(1, engine.alerts.value.size)
    } }
    @Test fun `target reset cancels obsolete target audio`() = runTest { withEngine {
        alarm(); store.clear(); scope.runCurrent(); assertEquals(1, stopped); assertTrue(engine.alerts.value.isEmpty())
    } }
    @Test fun `immediate disposal cancels audio and remains terminal`() = runTest { withEngine {
        alarm(); engine.dispose(); scope.runCurrent(); assertEquals(1, stopped)
        engine.startEngine(); scope.runCurrent(); assertEquals(1, started)
    } }
    @Test fun `joined disposal waits for accepted audio cancellation`() = runTest { withEngine {
        alarm(); assertTrue(engine.disposeAndJoin()); assertEquals(1, stopped)
    } }
    @Test fun `failed storage drain still stops audio`() = runTest { withEngine(failingStorage = true) {
        alarm(); assertFalse(engine.disposeAndJoin(10)); assertEquals(1, stopped)
    } }
}
