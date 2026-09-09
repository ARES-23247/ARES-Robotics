package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayWindowAuditTest {
    private class Request(val start: Long, val end: Long) {
        val gate = CompletableDeferred<Unit>()
    }
    private class ControlledSource(private val frames: List<TelemetryFrame>) : ReplayWindowSource {
        val requests = mutableListOf<Request>()
        var ignoreCancellation = false
        override suspend fun load(sessionId: String, startMs: Long, endMs: Long, sessionStartMs: Long): ReplayWindow {
            if (startMs > sessionStartMs) {
                val request = Request(startMs, endMs).also(requests::add)
                if (ignoreCancellation) withContext(NonCancellable) { request.gate.await() } else request.gate.await()
            }
            val baseline = frames.filter { it.timestampMs < startMs }.groupBy { it.key }.values.map { it.last() }
            return ReplayWindow(sessionId, startMs, endMs, baseline, frames.filter { it.timestampMs in startMs..endMs })
        }
        fun releaseAll() = requests.forEach { it.gate.complete(Unit) }
    }
    private fun audit(ignoreCancellation: Boolean = false, block: suspend TestScope.(ReplayEngineService, ControlledSource) -> Unit) = runTest {
        val root = Files.createTempDirectory("replay-window-audit").toFile()
        val db = DatabaseService(root.resolve("test.duckdb").path)
        val rows = (0L..30_000L step 1_000L).map { TelemetryFrame(it, "recorded", "Value", it.toDouble()) }
        val source = ControlledSource(rows).apply { this.ignoreCancellation = ignoreCancellation }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replay = ReplayEngineService(db, null, SystemReplayClock, dispatcher, source, dispatcher)
        try {
            db.insertTelemetryFrames(rows)
            replay.loadSession("recorded")
            runCurrent()
            block(replay, source)
        } finally {
            source.releaseAll()
            replay.disposeAndJoin()
            db.close(); root.deleteRecursively()
        }
    }
    @Test fun `nearby seeks coalesce into one pending read and commit the latest target`() = audit { replay, source ->
        assertEquals(1, source.requests.size)
        replay.seekToTimestamp(16_000); runCurrent()
        val request = source.requests.last()
        replay.seekToTimestamp(17_000); runCurrent()
        replay.seekToTimestamp(18_000); runCurrent()
        assertEquals(2, source.requests.size)
        request.gate.complete(Unit); runCurrent()
        assertFalse(replay.isSeeking.value)
        assertEquals(18_000L, replay.currentFrame.value?.playheadMs)
        assertEquals(18_000.0, replay.currentFrame.value?.values?.get("Value"))
    }
    @Test fun `seeking into an in flight prefetch shares its read`() = audit { replay, source ->
        val prefetch = source.requests.single()
        replay.seekToTimestamp(6_000); runCurrent()
        assertEquals(1, source.requests.size)
        prefetch.gate.complete(Unit); runCurrent()
        assertFalse(replay.isSeeking.value)
        assertEquals(6_000L, replay.currentFrame.value?.playheadMs)
        assertEquals(1, replay.cacheMetrics.value.prefetchHits)
    }
    @Test fun `returning to the active cache invalidates a slow foreground request`() = audit { replay, source ->
        source.ignoreCancellation = true
        replay.seekToTimestamp(20_000); runCurrent()
        val old = source.requests.last()
        replay.seekToTimestamp(1_000)
        val committed = replay.currentFrame.value
        assertFalse(replay.isSeeking.value)
        old.gate.complete(Unit); runCurrent()
        assertEquals(committed, replay.currentFrame.value)
        assertEquals(0L, replay.cacheMetrics.value.windowStartMs)
    }
    @Test fun `cached prefetch hit cancels a different outstanding request`() = audit { replay, source ->
        source.requests.single().gate.complete(Unit); runCurrent()
        assertTrue(replay.cacheMetrics.value.hasPrefetchedWindow)
        replay.seekToTimestamp(22_000); runCurrent()
        replay.seekToTimestamp(7_000)
        assertFalse(replay.isSeeking.value)
        assertEquals(7_000L, replay.currentFrame.value?.playheadMs)
        runCurrent()
        assertEquals(7_000L, replay.currentFrame.value?.playheadMs)
    }
    @Test fun `failed required read clears seeking and permits an explicit retry`() = audit { replay, source ->
        replay.seekToTimestamp(18_000); runCurrent()
        source.requests.last().gate.completeExceptionally(IllegalStateException("disk failed")); runCurrent()
        assertFalse(replay.isSeeking.value)
        assertEquals(ReplayLoadState.ERROR, replay.loadState.value)
        assertEquals("disk failed", replay.loadError.value)
        assertEquals(0L, replay.currentFrame.value?.playheadMs)
        replay.seekToTimestamp(18_000); runCurrent()
        source.requests.last().gate.complete(Unit); runCurrent()
        assertEquals(ReplayLoadState.READY, replay.loadState.value)
        assertNull(replay.loadError.value)
        assertEquals(18_000L, replay.currentFrame.value?.playheadMs)
    }
    @Test fun `unused failed prefetch does not fail the active replay snapshot`() = audit { replay, source ->
        source.requests.single().gate.completeExceptionally(IllegalStateException("optional read failed")); runCurrent()
        assertEquals(ReplayLoadState.READY, replay.loadState.value)
        assertFalse(replay.isSeeking.value)
        replay.seekToTimestamp(6_000); runCurrent()
        assertEquals(2, source.requests.size)
        source.requests.last().gate.complete(Unit); runCurrent()
        assertEquals(6_000L, replay.currentFrame.value?.playheadMs)
    }
    @Test fun `seek completion observers see the committed snapshot and metadata`() = audit { replay, source ->
        replay.seekToTimestamp(18_000); runCurrent()
        var observed: ReplayFrame? = null
        var progress = -1.0
        val watcher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            replay.isSeeking.collect { seeking -> if (!seeking) { observed = replay.currentFrame.value; progress = replay.progress.value } }
        }
        source.requests.last().gate.complete(Unit); runCurrent()
        assertEquals(18_000L, observed?.playheadMs)
        assertEquals(0.6, progress)
        watcher.cancel()
    }
    @Test fun `cancelled prefetch cannot populate a new request for the same bounds`() = audit(ignoreCancellation=true) { replay, source ->
        val obsolete = source.requests.single()
        replay.seekToTimestamp(20_000); runCurrent()
        source.requests.last().gate.complete(Unit); runCurrent()
        replay.seekToTimestamp(0); runCurrent()
        assertEquals(obsolete.start, source.requests.last().start)
        assertFalse(replay.cacheMetrics.value.hasPrefetchedWindow)
        obsolete.gate.complete(Unit); runCurrent()
        assertFalse(replay.cacheMetrics.value.hasPrefetchedWindow)
        source.requests.last().gate.complete(Unit); runCurrent()
        assertTrue(replay.cacheMetrics.value.hasPrefetchedWindow)
    }
    @Test fun `end of recording clears obsolete prefetch metrics`() = audit { replay, source ->
        source.requests.single().gate.complete(Unit); runCurrent()
        assertTrue(replay.cacheMetrics.value.hasPrefetchedWindow)
        replay.seekToTimestamp(30_000); runCurrent()
        source.requests.last().gate.complete(Unit); runCurrent()
        assertEquals(30_000L, replay.currentFrame.value?.playheadMs)
        assertFalse(replay.cacheMetrics.value.hasPrefetchedWindow)
    }
    @Test fun `disposal waits for superseded owned readers to finish`() = audit { replay, source ->
        source.ignoreCancellation = true
        replay.seekToTimestamp(18_000); runCurrent()
        val old = source.requests.last()
        replay.seekToTimestamp(1_000)
        val dispose = backgroundScope.launch { replay.disposeAndJoin() }; runCurrent()
        assertFalse(dispose.isCompleted)
        old.gate.complete(Unit); runCurrent()
        assertTrue(dispose.isCompleted)
    }
}
