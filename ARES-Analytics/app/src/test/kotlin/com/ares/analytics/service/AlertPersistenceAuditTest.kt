package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import org.mockito.Mockito.*
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertPersistenceAuditTest {
    private class Storage {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val written = mutableListOf<AlertRecord>()
        val db = mock(DatabaseService::class.java) { call ->
            if (call.method.name == "insertAlert") {
                if (fail) throw IOException("Injected storage failure")
                written += call.arguments[0] as AlertRecord
                val blocker = gate
                if (blocker == null) null else {
                    val wait: suspend () -> Unit = { blocker.await() }
                    @Suppress("UNCHECKED_CAST")
                    wait.startCoroutineUninterceptedOrReturn(call.rawArguments.last() as Continuation<Unit>)
                }
            } else RETURNS_DEFAULTS.answer(call)
        }
    }
    private suspend fun TestScope.withEngine(block: suspend Fixture.() -> Unit) {
        val storage = Storage()
        val store = TelemetryStore()
        val nt = mock(Nt4ClientService::class.java)
        `when`(nt.telemetryStore).thenReturn(store)
        `when`(nt.telemetryFlow).thenReturn(store.updates)
        val path = Files.createTempFile("alert-persistence", ".json")
        Files.writeString(path, """[{"key":"Audit/Value","displayName":"Audit","maxValue":10.0,"audibleAlert":false}]""")
        val engine = AlertEngineService(storage.db, nt, path.toString(), StandardTestDispatcher(testScheduler))
        try { runCurrent(); Fixture(this, storage, store, engine).block() }
        finally { engine.dispose(); runCurrent(); Files.deleteIfExists(path) }
    }
    private class Fixture(val scope: TestScope, val storage: Storage, val store: TelemetryStore, val engine: AlertEngineService) {
        suspend fun send(ms: Long, value: Double) {
            store.accept(TelemetryFrame(ms, "recording", "Audit/Value", value))
            scope.runCurrent()
        }
    }
    @Test fun `storage failure cannot stop later diagnostic resolution`() = runTest { withEngine {
        storage.fail = true
        send(100, 20.0)
        send(200, 5.0)
        assertEquals(200L, engine.alerts.value.single().resolveTimestampMs)
    } }
    @Test fun `failed unchanged alert is retried without new telemetry`() = runTest { withEngine {
        storage.fail = true
        send(100, 20.0)
        storage.fail = false
        scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(engine.alerts.value.single(), storage.written.last())
    } }
    @Test fun `failed triage is retained for retry`() = runTest { withEngine {
        send(100, 20.0)
        storage.fail = true
        engine.triageAlert(engine.alerts.value.single().alertId); scope.runCurrent()
        assertTrue(engine.alerts.value.single().triaged)
        storage.fail = false
        scope.advanceTimeBy(1_000); scope.runCurrent()
        assertTrue(storage.written.last().triaged)
    } }
    @Test fun `slow database does not hold diagnostic transition lock`() = runTest { withEngine {
        val gate = CompletableDeferred<Unit>(); storage.gate = gate
        send(100, 20.0); send(200, 5.0)
        assertEquals(200L, engine.alerts.value.single().resolveTimestampMs)
        assertEquals(1, engine.persistenceStatus.value.pending)
        storage.gate = null; gate.complete(Unit); scope.runCurrent()
        assertEquals(listOf(null, 200L), storage.written.map { it.resolveTimestampMs })
        assertEquals(0, engine.persistenceStatus.value.pending)
    } }
    @Test fun `target reset preserves old unsaved history and permits new diagnostics`() = runTest { withEngine {
        storage.fail = true; send(100, 20.0)
        val oldId = engine.alerts.value.single().alertId
        store.clear(); scope.runCurrent(); send(200, 30.0)
        val newId = engine.alerts.value.single().alertId
        assertNotEquals(oldId, newId)
        assertEquals(2, engine.persistenceStatus.value.pending)
        storage.fail = false; scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(setOf(oldId, newId), storage.written.map { it.alertId }.toSet())
    } }
    @Test fun `graceful disposal drains accepted records before completing and cannot restart`() = runTest { withEngine {
        val gate = CompletableDeferred<Unit>(); storage.gate = gate; send(100, 20.0)
        val finish = scope.async { engine.disposeAndJoin() }; scope.runCurrent()
        assertFalse(finish.isCompleted)
        storage.gate = null; gate.complete(Unit); scope.runCurrent(); assertTrue(finish.await())
        engine.startEngine(); send(200, 5.0)
        assertNull(engine.alerts.value.single().resolveTimestampMs)
        assertEquals(AlertPersistenceStatus(stopped = true), engine.persistenceStatus.value)
    } }
    @Test fun `failed shutdown retains pending alerts and supports a later drain`() = runTest { withEngine {
        storage.fail = true; send(100, 20.0)
        assertFalse(engine.disposeAndJoin(100))
        assertEquals(1, engine.persistenceStatus.value.pending)
        storage.fail = false
        assertTrue(engine.disposeAndJoin(1_000))
        assertEquals(1, storage.written.size)
    } }
    @Test fun `pause does not cancel persistence retries`() = runTest { withEngine {
        storage.fail = true; send(100, 20.0); engine.stop(); scope.runCurrent()
        storage.fail = false; scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(1, storage.written.size)
        engine.startEngine(); send(200, 5.0)
        assertEquals(200L, engine.alerts.value.single().resolveTimestampMs)
    } }
}
