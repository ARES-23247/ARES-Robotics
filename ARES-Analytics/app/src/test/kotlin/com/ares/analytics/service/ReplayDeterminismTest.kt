package com.ares.analytics.service

import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayDeterminismTest {
    @Test
    fun `golden dashboard recording imports and replays the same atomic evidence`() = runBlocking {
        withDatabase { database ->
            val sysId = SysIdService(database)
            val driverAnalysis = DriverAnalysisService(database, sysId)
            val summary = SummaryEngineService(database, sysId, driverAnalysis)
            val parser = LogParserService(database, summary)
            val fixture = java.io.File(
                requireNotNull(javaClass.getResource("/replay/golden-dashboard-replay.csv")).toURI()
            )
            val session = parser.parseLogFile(fixture, "23247", "2026", "golden-replay")
            val replay = ReplayEngineService(database)
            try {
                replay.loadSession(session.sessionId)
                assertEquals(1_250L, replay.sessionDurationMs.value)
                assertEquals(19, replay.sessionInfo.value?.topicCount)

                replay.scrubTo(0.6)
                val snapshot = requireNotNull(replay.currentFrame.value)
                assertEquals(750L, snapshot.timestampMs)
                assertEquals(0.7, snapshot.values["ARES/SimulatorPoseFrame/0"])
                assertEquals(0.68, snapshot.values["ARES/SimulatorPoseFrame/3"])
                assertEquals(0.72, snapshot.values["ARES/SimulatorPoseFrame/6"])
                assertEquals("TELEOP", snapshot.stringValues["Robot/Mode"])
                assertEquals(28.0, snapshot.values["Robot/LoopTimeMs"])
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    @Test
    fun `duplicate source instants use stable sample order and commit atomically`() = runBlocking {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    frame("duplicates", 1_000, "Pose/X", 1.0, timestampUs = 1_000_001, order = 1),
                    frame("duplicates", 1_000, "Pose/Y", 2.0, timestampUs = 1_000_001, order = 2),
                    frame("duplicates", 1_000, "Pose/X", 3.0, timestampUs = 1_000_001, order = 3),
                    frame("duplicates", 1_100, "Pose/Y", 4.0, timestampUs = 1_100_000, order = 4),
                )
            )
            val replay = ReplayEngineService(database)
            try {
                replay.loadSession("duplicates")
                val first = requireNotNull(replay.currentFrame.value)
                assertEquals(1_000L, first.timestampMs)
                assertEquals(1_000L, first.playheadMs)
                assertEquals(mapOf("Pose/X" to 3.0, "Pose/Y" to 2.0), first.values)

                replay.scrubTo(1.0)
                val last = requireNotNull(replay.currentFrame.value)
                assertEquals(mapOf("Pose/X" to 3.0, "Pose/Y" to 4.0), last.values)
                assertTrue(last.sequence > first.sequence)
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    @Test
    fun `actions annotate the timeline but never synthesize localization telemetry`() = runBlocking {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    frame("actions", 1_000, "Robot/LoopTimeMs", 20.0),
                    frame("actions", 1_100, "Robot/LoopTimeMs", 21.0),
                )
            )
            database.insertRobotActionsBulk(
                listOf(
                    RobotActionRecord(
                        timestampMs = 50_000,
                        sessionId = "actions",
                        runId = "run",
                        robotId = "robot",
                        actionType = "PoseUpdate",
                        payloadJson = """{"xMeters":99.0,"yMeters":98.0,"headingRadians":2.0}""",
                    )
                )
            )
            val replay = ReplayEngineService(database)
            try {
                replay.loadSession("actions")
                replay.scrubTo(1.0)
                val snapshot = requireNotNull(replay.currentFrame.value)
                assertEquals(100L, replay.sessionDurationMs.value)
                assertFalse(snapshot.values.keys.any { it.startsWith("ARES/EstimatedPose/") })
                assertFalse(snapshot.values.keys.any { it.startsWith("Drive/Odom_") })
                assertEquals(1, replay.sessionActions.value.size)
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    @Test
    fun `rapid seeks commit only the newest requested playhead`() = runBlocking {
        withDatabase { database ->
            database.insertTelemetryFrames(
                (0L..30_000L step 100L).map { time -> frame("seek", time, "Replay/Value", time.toDouble()) }
            )
            val replay = ReplayEngineService(database)
            try {
                replay.loadSession("seek")
                replay.scrubTo(0.9)
                replay.scrubTo(0.1)
                withTimeout(5_000) {
                    while (replay.isSeeking.value) delay(5)
                }
                val snapshot = requireNotNull(replay.currentFrame.value)
                assertEquals(3_000L, snapshot.playheadMs)
                assertEquals(3_000.0, snapshot.values["Replay/Value"])
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    @Test
    fun `replay never mutates the live telemetry store`() = runBlocking {
        withDatabase { database ->
            database.insertTelemetryFrames(listOf(frame("isolated", 1_000, "Only/Replay", 7.0)))
            val nt4 = Nt4ClientService(database)
            val replay = ReplayEngineService(database, nt4)
            try {
                replay.loadSession("isolated")
                replay.scrubTo(1.0)
                assertEquals(7.0, replay.currentFrame.value?.values?.get("Only/Replay"))
                assertNull(nt4.telemetryStore.latest("Only/Replay"))
            } finally {
                replay.disposeAndJoin()
                nt4.disposeAndJoin()
            }
        }
    }

    @Test
    fun `empty and zero-duration sessions have explicit bounded states`() = runBlocking {
        withDatabase { database ->
            val replay = ReplayEngineService(database)
            try {
                replay.loadSession("missing")
                assertEquals(ReplayLoadState.EMPTY, replay.loadState.value)
                assertNull(replay.currentFrame.value)

                database.insertTelemetryFrames(listOf(frame("single", 5_000, "Only/Value", 1.0)))
                replay.loadSession("single")
                assertEquals(ReplayLoadState.READY, replay.loadState.value)
                assertEquals(0L, replay.sessionDurationMs.value)
                replay.scrubTo(Double.POSITIVE_INFINITY.coerceAtMost(1.0))
                assertEquals(5_000L, replay.currentFrame.value?.playheadMs)
                replay.play()
                assertEquals(ReplayState.ENDED, replay.state.value)
            } finally {
                replay.disposeAndJoin()
            }
        }
    }

    private suspend fun withDatabase(block: suspend (DatabaseService) -> Unit) {
        val directory = Files.createTempDirectory("ares-replay-determinism").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun frame(
        session: String,
        time: Long,
        key: String,
        value: Double,
        timestampUs: Long = time * 1_000L,
        order: Long = 0L,
    ) = TelemetryFrame(time, session, key, value, timestampUs = timestampUs, sampleOrder = order)
}
