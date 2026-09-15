package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.SessionSummary
import com.areslib.telemetry.schema.HardwareTopology
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import org.mockito.Mockito.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareTopologySelectionAuditTest {
    private class Storage {
        val summaries = mutableMapOf<String, SessionSummary>()
        val topologies = mutableMapOf<String, HardwareTopology>()
        val gates = mutableMapOf<String, CompletableDeferred<HardwareTopology?>>()
        val reads = mutableListOf<String>()
        var fail = false
        var ignoreCancellation = false
        val db = mock(DatabaseService::class.java) { call ->
            when (call.method.name) {
                "getSessionSummary" -> {
                    if (fail) error("injected storage failure")
                    summaries[call.arguments[0]]
                }
                "getTopology" -> {
                    val id = call.arguments[0] as String
                    reads += id
                    val load: suspend () -> HardwareTopology? = {
                        val gate = gates[id]
                        if (gate == null) topologies[id]
                        else if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
                    }
                    @Suppress("UNCHECKED_CAST")
                    load.startCoroutineUninterceptedOrReturn(call.rawArguments.last() as Continuation<HardwareTopology?>)
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        fun add(session: String, robot: String) {
            summaries[session] = SessionSummary(session, "team", "season", robot, 0)
            topologies[robot] = HardwareTopology(robot)
        }
        fun release() { gates.values.forEach { it.complete(null) } }
    }

    private class Harness(val scope: TestScope, val storage: Storage) {
        val database = mutableStateOf(storage.db)
        val session = mutableStateOf<String?>(null)
        val live = mutableStateOf<HardwareTopology?>(HardwareTopology("live"))
        var observed = HardwareTopologySelection(null, false)
        val seen = mutableListOf<HardwareTopologySelection>()
        val scene = ImageComposeScene(10, 10, coroutineContext = StandardTestDispatcher(scope.testScheduler))
        init {
            scene.setContent {
                val next = rememberHardwareTopologySelection(live.value, database.value, session.value)
                SideEffect { observed = next; seen += next }
            }
        }
        fun pump() = with(scope) {
            runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close()
            runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close(); runCurrent()
        }
        fun close() { scene.close(); storage.release(); scope.runCurrent() }
    }

    @Test fun liveSelectionNeverLoadsHistoricalStorageOrFallsBackToOldCachedState() = runTest {
        val storage = Storage()
        val h = Harness(this, storage)
        try {
            h.pump(); assertEquals(HardwareTopology("live"), h.observed.topology); assertFalse(h.observed.cached)
            h.session.value = "live-telemetry"; h.pump()
            h.live.value = null; h.pump(); assertNull(h.observed.topology)
            assertTrue(storage.reads.isEmpty())
            verify(storage.db, never()).getSessionSummary(anyString())
        } finally { h.close() }
    }

    @Test fun sessionSwitchClearsPriorRobotBeforeLoadingAndIgnoresLateCancelledCompletion() = runTest {
        val storage = Storage().apply {
            add("a", "robot-a"); add("b", "robot-b")
            gates["robot-b"] = CompletableDeferred()
            ignoreCancellation = true
        }
        val h = Harness(this, storage)
        try {
            h.session.value = "a"; h.pump()
            assertEquals("robot-a", h.observed.topology?.robotId)
            h.seen.clear()
            h.session.value = "b"; h.pump()
            assertTrue(h.observed.loading); assertNull(h.observed.topology)
            assertTrue(h.seen.all { it.topology == null })
            h.session.value = null; h.pump()
            assertEquals("live", h.observed.topology?.robotId)
            storage.gates.getValue("robot-b").complete(HardwareTopology("robot-b")); h.pump()
            assertEquals("live", h.observed.topology?.robotId)
            h.session.value = "b"; h.pump()
            assertEquals("robot-b", h.observed.topology?.robotId)
            assertTrue(h.observed.cached); assertFalse(h.observed.loading)
        } finally { h.close() }
    }

    @Test fun missingSummaryAndBlankRobotNeverReadAnEmptyIdentityOrShowLiveTopology() = runTest {
        val storage = Storage().apply { add("blank", "") }
        val h = Harness(this, storage)
        try {
            for (session in listOf("missing", "blank")) {
                h.session.value = session; h.pump()
                assertTrue(h.observed.cached); assertNull(h.observed.topology)
                assertFalse(h.observed.failed); assertFalse(h.observed.loading)
            }
            assertTrue(storage.reads.isEmpty())
        } finally { h.close() }
    }

    @Test fun failuresAndMismatchedRobotMapsAreContainedAndChangingDatabaseReloadsSelection() = runTest {
        val storage = Storage().apply { add("a", "robot-a"); fail = true }
        val replacement = Storage().apply { add("a", "robot-a") }
        val h = Harness(this, storage)
        try {
            h.session.value = "a"; h.pump(); assertTrue(h.observed.failed); assertNull(h.observed.topology)
            storage.fail = false
            storage.add("mismatch", "expected")
            storage.topologies["expected"] = HardwareTopology("different")
            h.session.value = "mismatch"; h.pump()
            assertTrue(h.observed.failed); assertNull(h.observed.topology)
            h.session.value = "a"; h.database.value = replacement.db; h.pump()
            assertEquals("robot-a", h.observed.topology?.robotId); assertFalse(h.observed.failed)
            assertEquals(listOf("robot-a"), replacement.reads)
        } finally { h.close(); replacement.release(); runCurrent() }
    }
}
