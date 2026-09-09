package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AlertPersistenceWriterTest {
    private fun alert(id: String = "one") = AlertRecord(id, "recording", "Audit/Value", 100, peakValue = 20.0)

    @Test fun `slow initial write cannot erase newer resolved and triaged state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<AlertRecord>()
        val writer = AlertPersistenceWriter(backgroundScope) { written += it; if (written.size == 1) gate.await() }
        val initial = alert()
        writer.submit(initial); runCurrent()
        val latest = initial.copy(peakValue = 40.0, resolveTimestampMs = 200, durationMs = 100, triaged = true)
        repeat(1_000) { writer.submit(initial.copy(peakValue = 21.0 + it)) }
        writer.submit(latest)
        assertEquals(1, writer.status.value.pending)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(initial, latest), written)
        assertEquals(0, writer.status.value.pending)
        writer.close()
    }
    @Test fun `coalescing before IO preserves initial occurrence and final resolution`() = runTest {
        val written = mutableListOf<AlertRecord>()
        val writer = AlertPersistenceWriter(backgroundScope) { written += it }
        val initial = alert(); val resolved = initial.copy(resolveTimestampMs = 200, durationMs = 100)
        writer.submit(initial); writer.submit(resolved); runCurrent()
        assertEquals(listOf(initial, resolved), written)
        writer.close()
    }
    @Test fun `failure retries with backoff and no new submissions`() = runTest {
        val attempts = mutableListOf<Long>()
        var failing = true
        val writer = AlertPersistenceWriter(backgroundScope) {
            attempts += currentTime
            if (failing) throw IOException("offline")
        }
        writer.submit(alert()); runCurrent()
        advanceTimeBy(249); runCurrent(); assertEquals(listOf(0L), attempts)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(0L, 250L), attempts)
        advanceTimeBy(500); runCurrent(); assertEquals(listOf(0L, 250L, 750L), attempts)
        assertTrue(writer.status.value.failed)
        failing = false; advanceTimeBy(1_000); runCurrent()
        assertEquals(AlertPersistenceStatus(), writer.status.value)
        writer.close()
    }
    @Test fun `new submissions cannot bypass retry delay`() = runTest {
        var attempts = 0
        val writer = AlertPersistenceWriter(backgroundScope) { attempts++; throw IOException("offline") }
        writer.submit(alert()); runCurrent()
        repeat(100) { writer.submit(alert().copy(peakValue = 21.0 + it)); runCurrent() }
        assertEquals(1, attempts)
        writer.close()
    }
    @Test fun `one rejected occurrence cannot starve another`() = runTest {
        val written = mutableListOf<String>()
        val writer = AlertPersistenceWriter(backgroundScope) {
            if (it.alertId == "bad") throw IOException("rejected")
            written += it.alertId
        }
        writer.submit(alert("bad")); writer.submit(alert("good")); runCurrent()
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf("good"), written)
        assertEquals(1, writer.status.value.pending)
        assertTrue(writer.status.value.failed)
        writer.close()
    }
    @Test fun `worker cancellation stays cancellation and does not fabricate successful persistence`() = runTest {
        val writer = AlertPersistenceWriter(backgroundScope) { throw CancellationException("cancelled IO") }
        writer.submit(alert()); runCurrent()
        assertEquals(AlertPersistenceStatus(pending = 1, stopped = true), writer.status.value)
        assertFalse(writer.finish(100))
        assertFailsWith<IllegalStateException> { writer.submit(alert("two")) }
        writer.close()
    }
    @Test fun `graceful finish waits for in flight IO and rejects new submissions`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writer = AlertPersistenceWriter(backgroundScope) { gate.await() }
        writer.submit(alert()); runCurrent()
        val finish = async { writer.finish() }; runCurrent()
        assertFalse(finish.isCompleted)
        assertFailsWith<IllegalStateException> { writer.submit(alert("two")) }
        gate.complete(Unit); runCurrent()
        assertTrue(finish.await())
        assertEquals(AlertPersistenceStatus(stopped = true), writer.status.value)
    }
    @Test fun `failed drain retains retryable queue for a later shutdown attempt`() = runTest {
        var failing = true
        val writer = AlertPersistenceWriter(backgroundScope) { if (failing) throw IOException("offline") }
        writer.submit(alert()); runCurrent()
        assertFalse(writer.finish(100))
        assertFalse(writer.status.value.stopped)
        failing = false
        assertTrue(writer.finish(1_000))
        assertEquals(0, writer.status.value.pending)
    }
    @Test fun `immediate close reports unsaved data and cancels retries`() = runTest {
        var attempts = 0
        val writer = AlertPersistenceWriter(backgroundScope) { attempts++; throw IOException("offline") }
        writer.submit(alert()); runCurrent(); writer.close()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, attempts)
        assertEquals(AlertPersistenceStatus(1, failed = true, stopped = true), writer.status.value)
    }
    @Test fun `live-only observations are not persisted`() = runTest {
        val writer = AlertPersistenceWriter(backgroundScope) { fail("live evidence must not create history") }
        writer.submit(alert().copy(sessionId = "live-telemetry")); runCurrent()
        assertTrue(writer.finish())
    }
    @Test fun `retry backoff caps at thirty seconds`() = runTest {
        val attempts = mutableListOf<Long>()
        val writer = AlertPersistenceWriter(backgroundScope) { attempts += currentTime; throw IOException("offline") }
        writer.submit(alert()); runCurrent(); advanceTimeBy(180_000); runCurrent()
        val gaps = attempts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), gaps.take(8))
        assertTrue(gaps.drop(8).all { it == 30_000L })
        writer.close()
    }
    @Test fun `idle writer wakes after draining its previous occurrence`() = runTest {
        val ids = mutableListOf<String>()
        val writer = AlertPersistenceWriter(backgroundScope) { ids += it.alertId }
        writer.submit(alert()); runCurrent(); writer.submit(alert("two")); runCurrent()
        assertEquals(listOf("one", "two"), ids)
        assertTrue(writer.finish())
    }
}
