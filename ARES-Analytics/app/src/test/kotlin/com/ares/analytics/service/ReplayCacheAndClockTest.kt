package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReplayCacheAndClockTest {
    @Test
    fun `playback elapsed time comes from injected clock`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    frame("clock", 1_000, 1.0),
                    frame("clock", 1_100, 2.0)
                )
            )
            val clock = IncrementingClock(stepMs = 25)
            val replay = ReplayEngineService(
                database,
                clock = clock,
                replayDispatcher = StandardTestDispatcher(testScheduler)
            )
            try {
                replay.loadSession("clock")
                replay.play()
                runCurrent()
                replay.pause()

                assertEquals(0.25, replay.progress.value, absoluteTolerance = 0.01)
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    @Test
    fun `adjacent replay window is prefetched and reused`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                (0..20_000 step 100).map { time -> frame("prefetch", time.toLong(), time.toDouble()) }
            )
            val replay = ReplayEngineService(database)
            try {
                replay.loadSession("prefetch")
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        replay.cacheMetrics.first { it.hasPrefetchedWindow }
                    }
                }

                replay.scrubTo(0.3)

                assertEquals(1L, replay.cacheMetrics.value.prefetchHits)
                assertTrue(replay.cacheMetrics.value.cachedFrames <= 200_000)
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    @Test
    fun `replay window reads every database page`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames((0L..4L).map { time -> frame("paged", time, time.toDouble()) })

            val frames = loadTelemetryWindowPages(database, "paged", 0L, 4L, pageSize = 2)

            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), frames.map { it.timestampMs })
        }
    }

    private suspend fun withDatabase(block: suspend (DatabaseService) -> Unit) {
        val directory = Files.createTempDirectory("ares-replay-cache").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun frame(session: String, time: Long, value: Double) = TelemetryFrame(
        timestampMs = time,
        sessionId = session,
        key = "Replay/Value",
        value = value
    )

    private class IncrementingClock(private val stepMs: Long) : ReplayClock {
        private var now = -stepMs
        override fun nowMs(): Long {
            now += stepMs
            return now
        }
    }
}
