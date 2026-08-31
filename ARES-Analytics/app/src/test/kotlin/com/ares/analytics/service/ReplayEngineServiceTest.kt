package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ReplayEngineServiceTest class.
 */
class ReplayEngineServiceTest {

    @Test
    /**
     * testReplayLifecycle fun.
     */
    fun testReplayLifecycle() = runTest {
        val tempDb = File.createTempFile("replay_db_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        val session = Session(
            sessionId = "replay-session",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            createdAt = 1000L
        )
        databaseService.insertSession(session)
        val frames = listOf(
            TelemetryFrame(1000L, session.sessionId, "/Test/Val", 1.0),
            TelemetryFrame(1100L, session.sessionId, "/Test/Val", 2.0),
            TelemetryFrame(1200L, session.sessionId, "/Test/Val", 3.0)
        )
        databaseService.insertTelemetryFrames(frames)

        // Load session
        replayEngine.loadSession(session.sessionId)
        assertEquals(ReplayState.STOPPED, replayEngine.state.value)
        assertEquals(0.0, replayEngine.progress.value)
        assertEquals(1.0, replayEngine.currentFrame.value?.values?.get("Test/Val"))
        assertTrue(replayEngine.telemetryDensity.value.isNotEmpty())

        // Play
        replayEngine.play()
        assertEquals(ReplayState.PLAYING, replayEngine.state.value)

        // Delay to allow playback progress
        delay(200)

        // Pause
        replayEngine.pause()
        assertEquals(ReplayState.PAUSED, replayEngine.state.value)

        // Scrub
        replayEngine.scrubTo(0.5)
        assertEquals(1100L, replayEngine.currentFrame.value?.timestampMs ?: 0L)
        assertEquals(0.5, replayEngine.progress.value, 0.05)

        // Step forward
        replayEngine.stepForward()
        assertEquals(1200L, replayEngine.currentFrame.value?.timestampMs ?: 0L)

        // Step backward
        replayEngine.stepBackward()
        assertEquals(1100L, replayEngine.currentFrame.value?.timestampMs ?: 0L)

        // Stop
        replayEngine.stop()
        assertEquals(ReplayState.STOPPED, replayEngine.state.value)
        assertEquals(1000L, replayEngine.currentFrame.value?.timestampMs ?: 0L)

        tempDb.delete()
    }

    @Test
    fun `scrub retains latched values from before replay window`() = runTest {
        val tempDb = File.createTempFile("replay_seek_baseline", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        val session = Session("seek-session", "23247", "2026", "ares-bot", 1000L)
        databaseService.insertSession(session)
        databaseService.insertTelemetryFrames(
            listOf(
                TelemetryFrame(1000L, session.sessionId, "Drive/Pose_X", 4.25),
                TelemetryFrame(10_000L, session.sessionId, "Robot/BatteryVoltage", 12.1)
            )
        )

        try {
            replayEngine.loadSession(session.sessionId)
            replayEngine.scrubTo(1.0)
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (replayEngine.currentFrame.value?.timestampMs != 10_000L) delay(10)
                }
            }
            val values = replayEngine.currentFrame.value?.values.orEmpty()
            assertEquals(4.25, values["Drive/Pose_X"])
            assertEquals(12.1, values["Robot/BatteryVoltage"])
        } finally {
            replayEngine.dispose()
            databaseService.close()
            tempDb.delete()
        }
    }

    @Test
    fun `loading a second session cannot reuse first session replay cache`() = runTest {
        val tempDb = File.createTempFile("replay_session_isolation", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        try {
            val first = Session("first", "23247", "2026", "one", 1000L)
            val second = Session("second", "23247", "2026", "two", 1000L)
            databaseService.insertSession(first)
            databaseService.insertSession(second)
            databaseService.insertTelemetryFrames(listOf(TelemetryFrame(1000L, first.sessionId, "Only/First", 1.0)))
            databaseService.insertTelemetryFrames(listOf(TelemetryFrame(1000L, second.sessionId, "Only/Second", 2.0)))

            replayEngine.loadSession(first.sessionId)
            assertEquals(1.0, replayEngine.currentFrame.value?.values?.get("Only/First"))
            replayEngine.loadSession(second.sessionId)

            val values = replayEngine.currentFrame.value?.values.orEmpty()
            assertEquals(2.0, values["Only/Second"])
            assertTrue("Only/First" !in values)
        } finally {
            replayEngine.dispose()
            databaseService.close()
            tempDb.delete()
        }
    }

    @Test
    fun `string telemetry survives the atomic replay snapshot`() = runBlocking {
        val tempDb = File.createTempFile("replay_strings", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        try {
            val session = Session("strings", "23247", "2026", "bot", 1000L)
            databaseService.insertSession(session)
            databaseService.insertTelemetryFrames(
                listOf(TelemetryFrame(1000L, session.sessionId, "Robot/Mode", 0.0, "AUTO"))
            )
            replayEngine.loadSession(session.sessionId)

            assertEquals("AUTO", replayEngine.currentFrame.value?.stringValues?.get("Robot/Mode"))
        } finally {
            replayEngine.dispose()
            databaseService.close()
            tempDb.delete()
        }
    }

    @Test
    fun `stop resets playhead as one atomic replay snapshot`() = runTest {
        val tempDb = File.createTempFile("replay_stop_emission", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        try {
            val session = Session("stop", "23247", "2026", "bot", 1000L)
            databaseService.insertSession(session)
            databaseService.insertTelemetryFrames(listOf(TelemetryFrame(1000L, session.sessionId, "Test", 1.0)))
            replayEngine.loadSession(session.sessionId)
            replayEngine.stop()

            assertEquals(1000L, replayEngine.currentFrame.value?.timestampMs)
        } finally {
            replayEngine.dispose()
            databaseService.close()
            tempDb.delete()
        }
    }

    @Test
    fun `playback speed rate adjustments apply cleanly to replay state`() = runTest {
        val tempDb = File.createTempFile("replay_speed", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        try {
            val session = Session("speed-test", "23247", "2026", "bot", 1000L)
            databaseService.insertSession(session)
            databaseService.insertTelemetryFrames(listOf(TelemetryFrame(1000L, session.sessionId, "Test", 1.0)))
            replayEngine.loadSession(session.sessionId)

            assertEquals(1.0, replayEngine.speed.value)

            replayEngine.setSpeed(2.0)
            assertEquals(2.0, replayEngine.speed.value)

            replayEngine.setSpeed(0.5)
            assertEquals(0.5, replayEngine.speed.value)
        } finally {
            replayEngine.dispose()
            databaseService.close()
            tempDb.delete()
        }
    }

    @Test
    fun `loading empty session resets playhead and safely ignores play requests`() = runTest {
        val tempDb = File.createTempFile("replay_empty_session", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val replayEngine = ReplayEngineService(databaseService)
        try {
            val session = Session("empty-replay", "23247", "2026", "bot", 1000L)
            databaseService.insertSession(session)
            replayEngine.loadSession(session.sessionId)

            assertEquals(ReplayState.STOPPED, replayEngine.state.value)
            assertEquals(null, replayEngine.currentFrame.value)
            assertEquals(0.0, replayEngine.progress.value)
            assertTrue(replayEngine.telemetryDensity.value.isEmpty())
            assertTrue(replayEngine.sessionActions.value.isEmpty())

            // Play should safely no-op when there are no timestamps
            replayEngine.play()
            assertEquals(ReplayState.STOPPED, replayEngine.state.value)

            // Pause / Step / Stop should also safely no-op
            replayEngine.pause()
            assertEquals(ReplayState.STOPPED, replayEngine.state.value)
            replayEngine.stepForward()
            replayEngine.stepBackward()
            replayEngine.stop()
            assertEquals(ReplayState.STOPPED, replayEngine.state.value)
        } finally {
            replayEngine.dispose()
            databaseService.close()
            tempDb.delete()
        }
    }
}
