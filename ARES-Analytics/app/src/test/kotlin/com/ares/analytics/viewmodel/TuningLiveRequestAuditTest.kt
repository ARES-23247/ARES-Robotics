package com.ares.analytics.viewmodel

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.Nt4ConnectionMetrics
import com.ares.analytics.service.tuning.TuningTransport
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.tuning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TuningLiveRequestAuditTest {
    private val gain = TuningParameterDeclaration("gain.uid", "test.gain", "component.main", "Gain", "Test gain",
        TuningParameterType.DOUBLE, minimum = 0.0, maximum = 10.0,
        defaultValue = TuningValue(doubleValue = 1.0), applyPolicy = TuningApplyPolicy.LIVE_SAFE)
    private val second = gain.copy(uid = "second.uid", key = "test.second", displayName = "Second")
    private val flag = gain.copy(uid = "flag.uid", key = "test.flag", displayName = "Flag", type = TuningParameterType.BOOLEAN,
        minimum = null, maximum = null, defaultValue = TuningValue(booleanValue = false))
    private val count = gain.copy(uid = "count.uid", key = "test.count", type = TuningParameterType.INT, defaultValue = TuningValue(intValue = 1))
    private val label = gain.copy(uid = "label.uid", key = "test.label", type = TuningParameterType.TEXT,
        minimum = null, maximum = null, defaultValue = TuningValue(textValue = "initial"))
    private val mode = label.copy(uid = "mode.uid", key = "test.mode", type = TuningParameterType.ENUM,
        defaultValue = TuningValue(textValue = "coast"), enumOptions = listOf("coast", "brake"))
    private val declarations = listOf(gain, second, flag, count, label, mode)
    private val profile = TuningProfileDocument(uid = "profile.main", profileId = "competition", displayName = "Competition",
        description = "Test profile", projectId = "robot.project", authority = TuningProfileAuthority.CANONICAL_CHECKED_IN, values = emptyList())

    @Test fun `discarding a draft invalidates the live request waiting behind an acknowledgement`() = fixture { f ->
        f.queueTwo()
        f.vm.onIntent(TuningIntent.DiscardProposal)
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertEquals(1, f.requests().size)
    }

    @Test fun `queued live request cannot publish into a newly opened project`() = fixture { f ->
        f.queueTwo()
        f.load("other")
        val status = f.vm.state.value.saveStatus
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertEquals(1, f.requests().size)
        assertEquals(status, f.vm.state.value.saveStatus)
    }

    @Test fun `late live acknowledgement cannot replace the new project status`() = fixture { f ->
        f.stage(gain); f.push(gain)
        f.load("other")
        val status = f.vm.state.value.saveStatus
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertEquals(status, f.vm.state.value.saveStatus)
    }

    @Test fun `reconnection invalidates queued requests even when connection flow conflates the transition`() = fixture { f ->
        f.queueTwo()
        f.connected.value = false
        f.epoch++
        f.connected.value = true
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertEquals(1, f.requests().size)
    }

    @Test fun `old processed nonce cannot acknowledge a new value when request feedback is absent`() = fixture { f ->
        f.latest[TuningTransport.processedNonce(gain)] = f.frame(TuningTransport.processedNonce(gain), 1.0)
        f.latest[TuningTransport.lastResult(gain)] = f.frame(TuningTransport.lastResult(gain), text = "APPLIED")
        f.stage(gain); f.push(gain)
        val nonce = f.published.last { it.first == TuningTransport.requestNonce(gain) }.second as Double
        assertTrue(nonce > 1.0)
        assertFalse(f.vm.state.value.saveStatus.contains("acknowledged"))
    }

    @Test fun `explicit robot rejection is distinguished from an unknown unacknowledged request`() = fixture { f ->
        f.stage(gain); f.push(gain)
        f.acknowledgeLast("SESSION_NOT_ARMED"); advanceTimeBy(100); runCurrent()
        assertFalse(f.vm.state.value.saveStatus.contains("No robot acknowledgement"))
        assertTrue(f.vm.state.value.errorMessage.orEmpty().contains("session not armed"))
    }

    @Test fun `nonfinite consumer support cannot be reported as supported`() = fixture { f ->
        val key = TuningTransport.consumerSupported(gain)
        f.latest[key] = f.frame(key, Double.NaN)
        advanceTimeBy(200); runCurrent()
        assertEquals(false, f.vm.state.value.consumerSupportByUid[gain.uid])
    }

    @Test fun `malformed textual boolean cannot fall back to a stale numeric value`() = fixture { f ->
        val key = TuningTransport.current(flag)
        f.latest[key] = f.frame(key, 1.0, "garbage")
        advanceTimeBy(200); runCurrent()
        assertNull(f.vm.state.value.liveTypedValues[flag.key])
    }

    @Test fun `disconnected live tests publish no requested value or nonce`() = fixture { f ->
        f.connected.value = false; runCurrent()
        f.stage(gain); f.push(gain)
        assertTrue(f.published.isEmpty())
    }

    @Test fun `reordered scalar acknowledgement cannot pair a new nonce with the old applied result`() = fixture { f ->
        f.stage(gain); f.push(gain)
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 3.0)))
        f.push(gain)
        val nonce = f.published.last { it.first == TuningTransport.requestNonce(gain) }.second as Double
        // Scalar topics may occupy different server batches. The old APPLIED result is still retained.
        f.latest[TuningTransport.processedNonce(gain)] = f.frame(TuningTransport.processedNonce(gain), nonce)
        advanceTimeBy(100); runCurrent()
        assertFalse(f.vm.state.value.saveStatus.contains("as applied"))
        val atomic = "${TuningTransport.parameterRoot(gain)}/Acknowledgement"
        f.latest[atomic] = f.frame(atomic, text = "1|${nonce.toLong()}|SESSION_NOT_ARMED")
        advanceTimeBy(100); runCurrent()
        assertTrue(f.vm.state.value.errorMessage.orEmpty().contains("session not armed"))
    }

    @Test fun `eligible typed requests are serialized acknowledged and leave canonical files unchanged`() = fixture { f ->
        val values = listOf(gain to TuningValue(doubleValue = 2.0), count to TuningValue(intValue = 3),
            flag to TuningValue(booleanValue = true), label to TuningValue(textValue = "new label"), mode to TuningValue(textValue = "brake"))
        for ((d, value) in values) f.vm.onIntent(TuningIntent.UpdateTypedConstant(d.key, value))
        f.vm.onIntent(TuningIntent.PushAllToRobot); runCurrent()
        assertEquals(1, f.vmScope.coroutineContext[Job]!!.children.count { it !in f.permanentJobs }, "A batch owns one request coroutine")
        repeat(values.size) { index ->
            assertEquals(index + 1, f.requests().size)
            f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        }
        assertEquals(listOf(2.0, 3.0, true, "new label", "brake"), f.requests().map { it.second })
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), f.published.filter { it.first.endsWith("/RequestNonce") }.map { it.second })
        assertNull(f.vm.state.value.errorMessage)
        assertTrue(f.vm.state.value.saveStatus.contains("as applied"))
        val disk = TuningProfileDocumentCodec.decode(File(f.root, "original/.ares/tuning/main.arestuning").readText(), declarations)
        assertEquals(profile, disk)
    }

    @Test fun `failed enqueue sends neither field and a retry reserves a new nonce`() = fixture { f ->
        f.acceptSend = false
        f.stage(gain); f.push(gain)
        assertTrue(f.published.isEmpty())
        assertTrue(f.vm.state.value.saveStatus.contains("not sent"))
        f.acceptSend = true; f.push(gain)
        assertEquals(2.0, f.published.last().second)
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertNull(f.vm.state.value.errorMessage)
    }

    @Test fun `timeout remains unknown and a fresh retry can be acknowledged`() = fixture { f ->
        f.stage(gain); f.push(gain)
        advanceTimeBy(3_000); runCurrent()
        assertTrue(f.vm.state.value.saveStatus.contains("unknown"))
        f.push(gain)
        assertEquals(2.0, f.published.last().second)
        f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertNull(f.vm.state.value.errorMessage)
    }

    @Test fun `exhausted exact nonce range sends nothing and invalid feedback cannot corrupt its floor`() = fixture { f ->
        assertEquals(11L, nextTuningRequestNonce(10, 1.0, 3.0))
        assertEquals(8L, nextTuningRequestNonce(0, 7.0, 3.0))
        assertEquals(8L, nextTuningRequestNonce(0, 3.0, 7.0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 2.5, 9_007_199_254_740_992.0)) {
            assertEquals(4L, nextTuningRequestNonce(3, invalid, invalid))
        }
        val key = TuningTransport.processedNonce(gain)
        f.latest[key] = f.frame(key, 9_007_199_254_740_991.0)
        f.stage(gain); f.push(gain)
        assertTrue(f.published.isEmpty())
        assertTrue(f.vm.state.value.saveStatus.contains("not sent"))
        assertTrue(f.vm.state.value.errorMessage.orEmpty().contains("exhausted"))
    }

    @Test fun `cancellation releases the request queue without reporting a fabricated robot error`() = fixture { f ->
        f.stage(gain); f.push(gain)
        f.vmScope.coroutineContext[Job]!!.children.filter { it !in f.permanentJobs }.toList().forEach { it.cancel() }
        runCurrent()
        assertNull(f.vm.state.value.errorMessage)
        f.push(gain); f.acknowledgeLast(); advanceTimeBy(100); runCurrent()
        assertEquals(2, f.requests().size)
        assertNull(f.vm.state.value.errorMessage)
        assertTrue(f.vm.state.value.saveStatus.contains("as applied"))
    }

    @Test fun `boolean observations preserve valid typed and legacy values without truthifying malformed numbers`() = fixture { f ->
        val key = TuningTransport.current(flag)
        for ((number, text, expected) in listOf(Triple(0.0, null, false), Triple(1.0, null, true),
            Triple(0.0, "true", true), Triple(1.0, "false", false), Triple(0.25, null, null),
            Triple(Double.POSITIVE_INFINITY, null, null), Triple(1.0, "TRUE", null))) {
            f.latest[key] = f.frame(key, number, text)
            advanceTimeBy(200); runCurrent()
            assertEquals(expected?.let { TuningValue(booleanValue = it) }, f.vm.state.value.liveTypedValues[flag.key])
        }
    }

    @Test fun `legacy scalar-only acknowledgement never claims a verified apply`() = fixture { f ->
        f.stage(gain); f.push(gain); f.acknowledgeLast()
        f.latest.remove(TuningTransport.acknowledgement(gain))
        advanceTimeBy(3_000); runCurrent()
        assertTrue(f.vm.state.value.saveStatus.contains("unknown"))
        assertTrue(f.vm.state.value.errorMessage.orEmpty().contains("older robot code"))
    }

    @Test fun `matching atomic acknowledgement is authoritative despite inconsistent legacy diagnostics`() = fixture { f ->
        f.stage(gain); f.push(gain); f.acknowledgeLast()
        f.latest[TuningTransport.lastResult(gain)] = f.frame(TuningTransport.lastResult(gain), text = "SESSION_NOT_ARMED")
        f.latest[TuningTransport.processedNonce(gain)] = f.frame(TuningTransport.processedNonce(gain), 99.0)
        advanceTimeBy(100); runCurrent()
        assertTrue(f.vm.state.value.saveStatus.contains("as applied"))
        assertNull(f.vm.state.value.errorMessage)
    }

    @Test fun `atomic acknowledgement nonce contributes to the next request floor`() = fixture { f ->
        val key = TuningTransport.acknowledgement(gain)
        f.latest[key] = f.frame(key, text = "1|100|APPLIED")
        f.stage(gain); f.push(gain)
        assertEquals(101.0, f.published.last().second)
        assertTrue(f.vm.state.value.saveStatus.contains("Waiting"))
    }

    private inner class Fixture(val root: File, val clock: TestScope, val client: Nt4ClientService,
        val vm: TuningViewModel, val connected: MutableStateFlow<Boolean>,
        val latest: ConcurrentHashMap<String, TelemetryFrame>, val published: MutableList<Pair<String, Any>>, val vmScope: CoroutineScope) {
        var epoch = 1L
        var acceptSend = true
        val permanentJobs = vmScope.coroutineContext[Job]!!.children.toSet()
        fun frame(key: String, value: Double = 0.0, text: String? = null) =
            TelemetryFrame(clock.testScheduler.currentTime, "live", key, value, stringValue = text)
        fun load(name: String) {
            vm.onIntent(TuningIntent.LoadConstants(File(root, name).path)); clock.runCurrent()
            assertNotNull(vm.state.value.selectedProfile)
        }
        fun stage(d: TuningParameterDeclaration) {
            vm.onIntent(TuningIntent.UpdateTypedConstant(d.key, TuningValue(doubleValue = 2.0)))
        }
        fun push(d: TuningParameterDeclaration) { vm.onIntent(TuningIntent.PushToRobot(d.key)); clock.runCurrent() }
        fun queueTwo() { stage(gain); stage(second); push(gain); push(second); assertEquals(1, requests().size) }
        fun requests() = published.filter { it.first.endsWith("/Requested") }
        fun acknowledgeLast(result: String = "APPLIED") {
            val (key, nonce) = published.last { it.first.endsWith("/RequestNonce") }
            val prefix = key.removeSuffix("/RequestNonce")
            latest["$prefix/LastResult"] = frame("$prefix/LastResult", text = result)
            latest["$prefix/ProcessedNonce"] = frame("$prefix/ProcessedNonce", nonce as Double)
            latest["$prefix/Acknowledgement"] = frame("$prefix/Acknowledgement", text = "1|${nonce.toLong()}|$result")
        }
    }

    private fun fixture(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val root = Files.createTempDirectory("tuning-live-audit").toFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            for (project in listOf("original", "other")) {
                File(root, "$project/.ares/tuning-components/main.arestuningcomponent").apply {
                    parentFile.mkdirs(); writeText(TuningComponentDocumentCodec.encode(TuningComponentDocument(uid = "component.main",
                        projectId = "robot.project", displayName = "Main", description = "Test component", parameters = declarations)))
                }
                File(root, "$project/.ares/tuning/main.arestuning").apply {
                    parentFile.mkdirs(); writeText(TuningProfileDocumentCodec.encode(profile, declarations))
                }
            }
            val client = mock(Nt4ClientService::class.java)
            val connected = MutableStateFlow(true)
            val latest = ConcurrentHashMap<String, TelemetryFrame>()
            val published = mutableListOf<Pair<String, Any>>()
            doReturn(connected).`when`(client).isConnected
            doReturn(latest).`when`(client).latestValues
            val vm = TuningViewModel(client, scope, loadDispatcher = dispatcher, workDispatcher = dispatcher)
            val f = Fixture(root, this, client, vm, connected, latest, published, scope)
            doAnswer { Nt4ConnectionMetrics(f.epoch, f.epoch, f.epoch - 1, connected.value) }.`when`(client).connectionMetrics()
            doAnswer { if (connected.value) f.epoch else null }.`when`(client).tuningConnectionId
            doAnswer {
                val d = it.getArgument<TuningParameterDeclaration>(0)
                val value = it.getArgument<TuningValue>(1)
                val nonce = it.getArgument<Long>(2)
                val connection = it.getArgument<Long>(3)
                if (!f.acceptSend || !connected.value || connection != f.epoch) false else {
                    val wire: Any = value.doubleValue ?: value.intValue?.toDouble() ?: value.booleanValue ?: requireNotNull(value.textValue)
                    published += TuningTransport.requested(d) to wire
                    published += TuningTransport.requestNonce(d) to nonce.toDouble()
                    latest[TuningTransport.requestNonce(d)] = f.frame(TuningTransport.requestNonce(d), nonce.toDouble())
                    true
                }
            }.`when`(client).publishTuningRequest(anyValue(), anyValue(), anyLong(), anyLong())
            doAnswer {
                val key = it.getArgument<String>(0); val value = it.getArgument<Double>(1)
                published += key to value; latest[key] = f.frame(key, value); null
            }.`when`(client).publishDouble(anyString(), anyDouble())
            doAnswer { published += it.getArgument<String>(0) to it.getArgument<Boolean>(1); null }
                .`when`(client).publishBoolean(anyString(), anyBoolean())
            doAnswer { published += it.getArgument<String>(0) to it.getArgument<String>(1); true }
                .`when`(client).publishString(anyString(), anyString())
            f.load("original")
            block(f)
        } finally {
            scope.cancel(); runCurrent()
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T { org.mockito.ArgumentMatchers.any<T>(); return null as T }
}
