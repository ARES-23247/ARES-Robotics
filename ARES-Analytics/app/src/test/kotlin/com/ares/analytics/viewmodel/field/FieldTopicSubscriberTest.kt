package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SimulatorPoseFrameSnapshot
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.FieldViewerIntent
import com.ares.analytics.viewmodel.FieldViewerViewModel
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FieldTopicSubscriberTest {
    @Test
    fun `packed simulator pose commits once at its sequence marker`() {
        val accumulator = FieldPoseFrameAccumulator()
        val values = listOf(1.0, 2.0, 0.3, 1.01, 2.01, 0.31, 1.0, 2.0, 0.3)
        values.forEachIndexed { index, value ->
            assertFalse(accumulator.accept("ARES/SimulatorPoseFrame/$index", value))
        }
        assertTrue(accumulator.accept("ARES/SimulatorPoseFrame/9", 42.0))

        val rendered = accumulator.snapshot(LivePoseState())
        assertEquals(1.0, rendered.trueX)
        assertEquals(2.0, rendered.trueY)
        assertEquals(1.0, rendered.odomX)
        assertEquals(2.0, rendered.odomY)
        assertEquals(1.01, rendered.ekfX)
        assertEquals(2.01, rendered.ekfY)
        assertFalse(accumulator.accept("ARES/EstimatedPose/0", -7.0))
        assertEquals(1.01, accumulator.snapshot(rendered).ekfX)
    }

    @Test
    fun `atomic packed parent cannot be overwritten by dropped scalar fragments`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.accept(
            SimulatorPoseFrameSnapshot(
                trueX = 4.0,
                trueY = 5.0,
                trueHeading = 0.4,
                ekfX = 4.01,
                ekfY = 5.01,
                ekfHeading = 0.41,
                odomX = 4.0,
                odomY = 5.0,
                odomHeading = 0.4,
                sequence = 17L,
                timestampMs = 100L,
                timestampUs = 100_000L,
            )
        )

        assertFalse(accumulator.accept("ARES/SimulatorPoseFrame/4", -99.0))
        assertFalse(accumulator.accept("ARES/SimulatorPoseFrame/9", 18.0))
        val rendered = accumulator.snapshot(LivePoseState())
        assertEquals(4.0, rendered.trueX)
        assertEquals(5.01, rendered.ekfY)
        assertEquals(5.0, rendered.odomY)
    }

    @Test
    fun `field subscriber consumes packed parent as one immutable pose`() = runTest {
        val databaseFile = File.createTempFile("field-atomic-pose", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val state = MutableStateFlow(FieldViewerState())
            val livePose = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(nt4, backgroundScope, state, livePose, UnconfinedTestDispatcher(testScheduler))
            runCurrent()

            nt4.handleIncomingText(
                """[{"method":"announce","params":{"name":"/ARES/SimulatorPoseFrame","id":40,"type":"double[]"}}]""",
                "team", "season", "robot"
            )
            nt4.handleIncomingText(
                """[{"topic":40,"time":123000,"value":[1.0,2.0,0.3,1.01,2.01,0.31,1.0,2.0,0.3,42.0]}]""",
                "team", "season", "robot"
            )
            runCurrent()

            assertEquals(42L, nt4.simulatorPoseFrame.value?.sequence)
            assertEquals(1.0, livePose.value.trueX)
            assertEquals(2.0, livePose.value.trueY)
            assertEquals(1.01, livePose.value.ekfX)
            assertEquals(2.01, livePose.value.ekfY)
            assertEquals(1.0, livePose.value.odomX)
            assertEquals(2.0, livePose.value.odomY)
        } finally {
            nt4.stop()
            database.close()
            databaseFile.delete()
        }
    }

    @Test
    fun `simulator rewind owns field pose until returning to realtime`() = runTest {
        val databaseFile = File.createTempFile("field-simulator-rewind", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val state = MutableStateFlow(FieldViewerState())
            val livePose = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(nt4, backgroundScope, state, livePose, UnconfinedTestDispatcher(testScheduler))
            runCurrent()

            nt4.handleIncomingText(
                """[{"method":"announce","params":{"name":"/ARES/SimulatorPoseFrame","id":40,"type":"double[]"}}]""",
                "team", "season", "robot"
            )
            nt4.handleIncomingText(
                """[{"topic":40,"time":123000,"value":[5.0,6.0,0.3,5.1,6.1,0.31,4.9,5.9,0.29,42.0]}]""",
                "team", "season", "robot"
            )
            runCurrent()
            assertEquals(5.0, livePose.value.trueX)
            assertEquals(5.1, livePose.value.ekfX)

            nt4.isReplayActive.value = true
            runCurrent()
            val replayValues = doubleArrayOf(1.0, 2.0, -0.2, 1.1, 2.1, -0.19, 0.9, 1.9, -0.21, 7.0)
            replayValues.forEachIndexed { index, value ->
                nt4.emitReplayFrame(
                    com.ares.analytics.shared.models.TelemetryFrame(
                        timestampMs = 1000L,
                        sessionId = "replay",
                        key = "ARES/SimulatorPoseFrame/$index",
                        value = value
                    )
                )
            }
            runCurrent()
            assertEquals(1.0, livePose.value.trueX)
            assertEquals(2.0, livePose.value.trueY)
            assertEquals(1.1, livePose.value.ekfX)
            assertEquals(0.9, livePose.value.odomX)

            // Fresh network traffic continues to be persisted while rewinding, but it must not
            // seize the displayed field pose from replay.
            nt4.handleIncomingText(
                """[{"topic":40,"time":124000,"value":[9.0,9.0,0.9,9.1,9.1,0.91,8.9,8.9,0.89,43.0]}]""",
                "team", "season", "robot"
            )
            runCurrent()
            assertEquals(1.0, livePose.value.trueX)
            assertEquals(1.1, livePose.value.ekfX)

            nt4.isReplayActive.value = false
            runCurrent()
            nt4.handleIncomingText(
                """[{"topic":40,"time":125000,"value":[9.0,9.0,0.9,9.1,9.1,0.91,8.9,8.9,0.89,44.0]}]""",
                "team", "season", "robot"
            )
            runCurrent()
            assertEquals(9.0, livePose.value.trueX)
            assertEquals(9.1, livePose.value.ekfX)
        } finally {
            nt4.stop()
            database.closeAndJoin()
            databaseFile.delete()
        }
    }

    @Test
    fun `field reducer rejects unrelated high-rate telemetry before state work`() {
        assertTrue(isFieldViewerTopic("ARES/TruePose/0"))
        assertTrue(isFieldViewerTopic("Vision/PoseArray/3"))
        assertTrue(isFieldViewerTopic("ARES/GamePieces/7"))
        assertFalse(isFieldViewerTopic("Tuning/Parameters/ftc.drive.heading.kp/Current"))
        assertFalse(isFieldViewerTopic("Hardware/Motors/fl/Velocity"))
    }

    @Test
    fun `alliance toggle updates the atomic frame selection`() = runTest {
        val databaseFile = File.createTempFile("field-alliance-toggle", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val viewModel = FieldViewerViewModel(nt4, backgroundScope)
            viewModel.onIntent(FieldViewerIntent.ToggleAlliance)
            runCurrent()

            assertFalse(nt4.selectedRedAlliance.value)
            assertFalse(viewModel.state.value.isRedAlliance)
        } finally {
            nt4.stop()
            database.close()
            databaseFile.delete()
        }
    }

    @Test
    fun `recreated field view inherits the dashboard alliance selection`() = runTest {
        val databaseFile = File.createTempFile("field-alliance-lifecycle", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            nt4.selectRedAlliance(false)

            val firstView = FieldViewerViewModel(nt4, backgroundScope)
            val recreatedView = FieldViewerViewModel(nt4, backgroundScope)

            assertFalse(firstView.state.value.isRedAlliance)
            assertFalse(recreatedView.state.value.isRedAlliance)
        } finally {
            nt4.stop()
            database.close()
            databaseFile.delete()
        }
    }

    @Test
    fun `game piece count removes stale array entries`() = runTest {
        val databaseFile = File.createTempFile("field-topic-subscriber", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val state = MutableStateFlow(FieldViewerState())
            val livePose = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(nt4, backgroundScope, state, livePose, UnconfinedTestDispatcher(testScheduler))
            runCurrent()

            nt4.handleIncomingText(
                """[
                    {"method":"announce","params":{"name":"/ARES/GamePieces","id":20,"type":"double[]"}},
                    {"method":"announce","params":{"name":"/ARES/GamePieces/Count","id":21,"type":"double"}}
                ]""".trimIndent(),
                "team", "season", "robot"
            )
            nt4.handleIncomingText(
                """[{"topic":20,"time":1000,"value":[1.0,2.0,0,0,0,0,0,3.0,4.0,0,0,0,0,0]}]""",
                "team", "season", "robot"
            )
            runCurrent()
            assertEquals(2, livePose.value.liveGamePieces.size)

            nt4.handleIncomingText(
                """[{"topic":21,"time":2000,"value":1.0}]""",
                "team", "season", "robot"
            )
            runCurrent()
            assertEquals(setOf(0), livePose.value.liveGamePieces.keys)

            nt4.handleIncomingText(
                """[{"topic":21,"time":3000,"value":0.0}]""",
                "team", "season", "robot"
            )
            runCurrent()
            assertTrue(livePose.value.liveGamePieces.isEmpty())
        } finally {
            nt4.stop()
            database.close()
            databaseFile.delete()
        }
    }

    @Test
    fun `atomic game-piece frame preserves stable identity type and visuals`() = runTest {
        val databaseFile = File.createTempFile("field-game-piece-frame", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val state = MutableStateFlow(FieldViewerState())
            val livePose = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(nt4, backgroundScope, state, livePose, UnconfinedTestDispatcher(testScheduler))
            runCurrent()

            nt4.handleIncomingText(
                """[{"method":"announce","params":{"name":"/ARES/GamePiecesFrame","id":22,"type":"double[]"}}]""",
                "team", "season", "robot"
            )
            nt4.handleIncomingText(
                """[{"topic":22,"time":1000,"value":[2.0,1.0,101.0,202.0,1.25,2.5,0.4,0.30,0.10,1.0,65280.0,7.0]}]""",
                "team", "season", "robot"
            )
            runCurrent()

            val piece = livePose.value.liveGamePieces.values.single()
            assertEquals("sim-101", piece.id)
            assertEquals("sim-type-202", piece.typeId)
            assertEquals(1.25, piece.x)
            assertEquals(2.5, piece.y)
            assertEquals(0.30, piece.widthMeters)
            assertEquals(0.10, piece.heightMeters)
            assertEquals("box", piece.simulationShape)
            assertEquals(0x00FF00, piece.colorRgb)
        } finally {
            nt4.stop()
            database.closeAndJoin()
            databaseFile.delete()
        }
    }

    @Test
    fun `simulator estimate alias cannot be overwritten by duplicate robot pose topics`() = runTest {
        val databaseFile = File.createTempFile("field-pose-source-priority", ".duckdb")
        val database = DatabaseService(databaseFile.absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val state = MutableStateFlow(FieldViewerState())
            val livePose = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(nt4, backgroundScope, state, livePose, UnconfinedTestDispatcher(testScheduler))
            runCurrent()

            nt4.handleIncomingText(
                """[
                    {"method":"announce","params":{"name":"/ARES/TruePose/0","id":30,"type":"double"}},
                    {"method":"announce","params":{"name":"/ARES/TruePose/1","id":31,"type":"double"}},
                    {"method":"announce","params":{"name":"/ARES/TruePose/2","id":32,"type":"double"}},
                    {"method":"announce","params":{"name":"/ARES/EstimatedPose/0","id":33,"type":"double"}},
                    {"method":"announce","params":{"name":"/ARES/EstimatedPose/1","id":34,"type":"double"}},
                    {"method":"announce","params":{"name":"/ARES/EstimatedPose/2","id":35,"type":"double"}},
                    {"method":"announce","params":{"name":"/Drive/Pose_X","id":36,"type":"double"}}
                ]""".trimIndent(),
                "team", "season", "robot"
            )
            nt4.handleIncomingText(
                """[
                    {"topic":30,"time":1000,"value":4.0},
                    {"topic":31,"time":1000,"value":5.0},
                    {"topic":32,"time":1000,"value":0.4},
                    {"topic":33,"time":1000,"value":3.8},
                    {"topic":34,"time":1000,"value":4.8},
                    {"topic":35,"time":1000,"value":0.38},
                    {"topic":36,"time":1000,"value":-7.0}
                ]""",
                "team", "season", "robot"
            )
            runCurrent()

            assertTrue(livePose.value.hasTruePoseData)
            assertEquals(4.0, livePose.value.trueX)
            assertEquals(3.8, livePose.value.ekfX)
        } finally {
            nt4.stop()
            database.close()
            databaseFile.delete()
        }
    }
}
