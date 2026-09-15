package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayLifecycleAuditTest {
    private class Clock(var now: Long = 0) : ReplayClock {
        var failed = false
        override fun nowMs(): Long { check(!failed) { "clock failed" }; return now }
    }
    private class Request(val session: String) { val gate = CompletableDeferred<Unit>() }
    private class Source(private val rows: List<TelemetryFrame>) : ReplayWindowSource {
        var hold = false
        var ignoreCancellation = false
        var calls = 0
        val started = Channel<Request>(Channel.UNLIMITED)
        val requests = mutableListOf<Request>()
        override suspend fun load(sessionId: String, startMs: Long, endMs: Long, sessionStartMs: Long): ReplayWindow {
            calls++
            if (hold) {
                val request = Request(sessionId).also(requests::add)
                started.send(request)
                if (ignoreCancellation) withContext(NonCancellable) { request.gate.await() }
                else request.gate.await()
            }
            return ReplayWindow(sessionId, startMs, endMs, emptyList(), rows.filter { it.sessionId == sessionId })
        }
        fun releaseAll() = requests.forEach { it.gate.complete(Unit) }
    }
    private fun audit(load: Boolean = true, block: suspend TestScope.(ReplayEngineService, Clock, Source) -> Unit) = runTest {
        val root = Files.createTempDirectory("replay-lifecycle-audit").toFile()
        val db = DatabaseService(root.resolve("test.duckdb").path)
        val rows = listOf("first", "second").flatMap { session ->
            listOf(0L, 1000L).map { TelemetryFrame(it, session, "Value", it.toDouble()) }
        }
        val source = Source(rows)
        val clock = Clock()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replay = ReplayEngineService(db, null, clock, dispatcher, source, dispatcher)
        try {
            db.insertTelemetryFrames(rows)
            if (load) replay.loadSession("first")
            block(replay, clock, source)
        } finally {
            source.releaseAll()
            replay.disposeAndJoin()
            db.close(); root.deleteRecursively()
        }
    }
    @Test fun `speed changes settle elapsed time at the old rate`() = audit { replay, clock, _ ->
        replay.play(); runCurrent()
        clock.now = 10; replay.setSpeed(2.0)
        assertEquals(10L, replay.playheadTimestampMs.value)
        clock.now = 20; advanceTimeBy(20); runCurrent()
        assertEquals(30L, replay.playheadTimestampMs.value)
    }
    @Test fun `pause settles partial ticks and resume excludes paused time`() = audit { replay, clock, _ ->
        replay.play(); runCurrent()
        clock.now = 7; replay.pause()
        assertEquals(7L, replay.playheadTimestampMs.value)
        clock.now = 1000; replay.play(); runCurrent()
        assertEquals(7L, replay.playheadTimestampMs.value)
        clock.now = 1005; replay.pause()
        assertEquals(12L, replay.playheadTimestampMs.value)
    }
    @Test fun `manual navigation resets the pending playback interval`() = audit { replay, clock, _ ->
        replay.play(); runCurrent()
        clock.now = 10; replay.seekToTimestamp(200)
        clock.now = 20; advanceTimeBy(20); runCurrent()
        assertEquals(210L, replay.playheadTimestampMs.value)
        clock.now = 30; replay.scrubTo(0.5)
        clock.now = 40; advanceTimeBy(20); runCurrent()
        assertEquals(510L, replay.playheadTimestampMs.value)
    }
    @Test fun `loop policy changes settle elapsed time under the old policy`() = audit { replay, clock, _ ->
        replay.setLooping(true); replay.play(); runCurrent()
        clock.now = 1100; replay.setLooping(false)
        assertEquals(100L, replay.playheadTimestampMs.value)
        assertEquals(ReplayState.PLAYING, replay.state.value)
        clock.now = 2000; replay.pause()
        assertEquals(1000L, replay.playheadTimestampMs.value)
        assertEquals(ReplayState.ENDED, replay.state.value)
    }
    @Test fun `huge clock jumps clamp or wrap without killing the playback job`() = audit { replay, clock, _ ->
        replay.setSpeed(8.0); replay.setLooping(true); replay.play(); runCurrent()
        clock.now = Long.MAX_VALUE; advanceTimeBy(20); runCurrent()
        assertEquals(456L, replay.playheadTimestampMs.value)
        assertEquals(ReplayState.PLAYING, replay.state.value)
        replay.stop(); clock.now = 0; replay.setLooping(false); replay.play(); runCurrent()
        clock.now = Long.MAX_VALUE; advanceTimeBy(20); runCurrent()
        assertEquals(1000L, replay.playheadTimestampMs.value)
        assertEquals(ReplayState.ENDED, replay.state.value)
        assertEquals(ReplayLoadState.READY, replay.loadState.value)
    }
    @Test fun `clock failure pauses playback and publishes an error`() = audit { replay, clock, _ ->
        replay.play(); runCurrent(); clock.failed = true
        advanceTimeBy(20); runCurrent()
        assertEquals(ReplayState.PAUSED, replay.state.value)
        assertEquals(ReplayLoadState.ERROR, replay.loadState.value)
        assertEquals("clock failed", replay.loadError.value)
        assertFalse(replay.isSeeking.value)
    }
    @Test fun `disposed engines clear snapshots and reject loads before IO`() = audit { replay, _, source ->
        replay.play(); runCurrent(); replay.disposeAndJoin()
        val calls = source.calls
        replay.play(); replay.seekToTimestamp(300); replay.scrubTo(0.5)
        replay.stepForward(); replay.stepBackward(); replay.setSpeed(2.0); replay.setLooping(true); replay.stop()
        assertFailsWith<IllegalStateException> { replay.loadSession("second") }
        replay.disposeAndJoin(); runCurrent()
        assertEquals(calls, source.calls)
        assertEquals(ReplayState.STOPPED, replay.state.value)
        assertEquals(ReplayLoadState.IDLE, replay.loadState.value)
        assertNull(replay.sessionInfo.value); assertNull(replay.currentFrame.value)
        assertEquals(ReplayCacheMetrics(), replay.cacheMetrics.value)
        assertEquals(0L, replay.sessionDurationMs.value)
        assertEquals(1.0, replay.speed.value); assertFalse(replay.looping.value)
    }
    @Test fun `caller cancellation clears loading and joins its initial reader`() = audit(load = false) { replay, _, source ->
        source.hold = true
        val load = launch { replay.loadSession("first") }
        source.started.receive()
        assertEquals(ReplayLoadState.LOADING, replay.loadState.value)
        load.cancelAndJoin()
        assertEquals(ReplayLoadState.IDLE, replay.loadState.value)
        assertNull(replay.currentFrame.value)
    }
    @Test fun `disposal waits for a noncancellable initial read and prevents late publication`() = audit(load = false) { replay, _, source ->
        source.hold = true; source.ignoreCancellation = true
        val load = launch { replay.loadSession("first") }
        val request = source.started.receive()
        val close = launch { replay.disposeAndJoin() }; runCurrent()
        assertFalse(close.isCompleted); assertFalse(load.isCompleted)
        assertEquals(ReplayLoadState.IDLE, replay.loadState.value)
        request.gate.complete(Unit); close.join(); load.join()
        assertNull(replay.currentFrame.value); assertNull(replay.sessionInfo.value)
        assertEquals(ReplayLoadState.IDLE, replay.loadState.value)
    }
    @Test fun `superseded initial reads cannot clear or replace the new session`() = audit(load = false) { replay, _, source ->
        source.hold = true; source.ignoreCancellation = true
        val old = launch { replay.loadSession("first") }
        val oldRequest = source.started.receive()
        val current = launch { replay.loadSession("second") }
        val currentRequest = source.started.receive()
        assertEquals("second", currentRequest.session)
        currentRequest.gate.complete(Unit); current.join()
        assertEquals("second", replay.currentFrame.value?.sessionId)
        oldRequest.gate.complete(Unit); old.join()
        assertTrue(old.isCancelled)
        assertEquals("second", replay.sessionInfo.value?.sessionId)
        assertEquals(ReplayLoadState.READY, replay.loadState.value)
    }
}
