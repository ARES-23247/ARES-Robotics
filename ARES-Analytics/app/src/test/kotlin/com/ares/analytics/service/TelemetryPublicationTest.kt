package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryPublicationTest {
    private fun frame(ms:Long=100)=TelemetryFrame(ms,"live-telemetry","Audit/Value",11.0)
    @Test fun `raw and tagged views share frame identity and replay order`()=runTest {
        val store=TelemetryStore()
        repeat(105) { store.accept(frame(it.toLong())) }
        val raw=store.updates.replayCache
        val tagged=store.publications.replayCache
        assertEquals(100,raw.size)
        assertEquals((5L..104L).toList(),raw.map { it.timestampMs })
        raw.zip(tagged).forEach { (r,t) -> assertSame(r,t.frame); assertEquals(0L,t.targetEpoch) }
        assertEquals(raw.take(2),store.updates.take(2).toList())
    }
    @Test fun `clear replaces target identity and empties both replay views`()=runTest {
        val store=TelemetryStore(); store.accept(frame()); val epoch=store.clear()
        assertTrue(store.updates.replayCache.isEmpty()); assertTrue(store.publications.replayCache.isEmpty())
        store.accept(frame(0)); assertEquals(epoch,store.publications.replayCache.single().targetEpoch)
    }
    @Test fun `reset invoked by a topic observer cannot relabel or retain its old frame`()=runTest {
        val store=TelemetryStore()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe("Audit/Value").filterNotNull().collect { store.clear() }
        }
        store.accept(frame())
        assertNull(store.latest("Audit/Value")); assertNull(store.observe("Audit/Value").value)
        assertTrue(store.publications.replayCache.isEmpty(),"old target publication survived reset")
        assertEquals(0L,store.snapshotMetrics().acceptedFrames)
    }
    @Test fun `epoch observers see cleared indexes and coherent reset metrics`()=runTest {
        val store=TelemetryStore(); store.accept(frame())
        val metrics=mutableListOf<TelemetryStoreMetrics>()
        val values=mutableListOf<TelemetryFrame?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.targetEpochs.drop(1).collect { metrics+=store.snapshotMetrics(); values+=store.latest("Audit/Value") }
        }
        store.clear()
        assertEquals(listOf(TelemetryStoreMetrics(0,0,0,0)),metrics)
        assertEquals(listOf<TelemetryFrame?>(null),values)
    }
    @Test fun `new target publication from reset callback survives the remaining observer resets`()=runTest {
        val store=TelemetryStore()
        store.accept(frame().copy(key="A")); store.accept(frame().copy(key="B"))
        store.observe("B")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe("A").drop(1).collect { if (it == null) store.accept(frame(200).copy(key="B")) }
        }
        val epoch=store.clear()
        assertEquals(200L,store.latest("B")!!.timestampMs)
        assertEquals(200L,store.observe("B").value!!.timestampMs)
        assertEquals(epoch,store.publications.replayCache.single().targetEpoch)
        assertEquals(1L,store.snapshotMetrics().acceptedFrames)
    }
    @Test fun `reentrant newer publication cannot be overwritten in the topic observer`()=runTest {
        val store=TelemetryStore(); store.observe("Audit/Value")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.publications.collect { if (it.frame.timestampMs==100L) store.accept(frame(200)) }
        }
        store.accept(frame())
        assertEquals(200L,store.latest("Audit/Value")!!.timestampMs)
        assertEquals(200L,store.observe("Audit/Value").value!!.timestampMs)
    }
    @Test fun `silent history never appears in either consumer replay view`()=runTest {
        val store=TelemetryStore(); store.accept(frame(),notifyConsumers=false)
        assertEquals(1L,store.snapshotMetrics().acceptedFrames)
        assertTrue(store.updates.replayCache.isEmpty()); assertTrue(store.publications.replayCache.isEmpty())
        assertNull(store.observe("Audit/Value").value)
    }
    @Test fun `slow tagged subscriber retains nonblocking bounded producer behavior`()=runTest {
        val store=TelemetryStore(); val gate=CompletableDeferred<Unit>()
        backgroundScope.launch(start=CoroutineStart.UNDISPATCHED) { store.publications.collect { gate.await() } }
        withTimeout(1_000) { repeat(5_000) { store.accept(frame(it.toLong())) } }
        assertEquals(100,store.publications.replayCache.size)
        assertEquals(4_999L,store.latest("Audit/Value")!!.timestampMs)
    }
}
