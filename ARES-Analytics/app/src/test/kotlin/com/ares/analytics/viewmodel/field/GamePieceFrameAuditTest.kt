package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.GamePieceTelemetry
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.mock
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GamePieceFrameAuditTest {
    private fun frame(sequence: Double = 7.0) = doubleArrayOf(2.0, 1.0, 101.0, 202.0, 1.25, 2.5, 0.4, 0.30, 0.10, 1.0, 65280.0, sequence)
    private fun GamePieceFrameAccumulator.feed(values: DoubleArray, skip: Int = -1): Map<Int, GamePiece>? {
        var result: Map<Int, GamePiece>? = null
        values.forEachIndexed { index, value -> if (index != skip) result = accept("ARES/GamePiecesFrame/$index", value) }
        return result
    }

    @Test fun `count and records without a version cannot invent a typed frame`() {
        assertNull(GamePieceFrameAccumulator().feed(frame(), skip = 0))
    }
    @Test fun `unsupported version cannot be repaired by a later count`() {
        assertNull(GamePieceFrameAccumulator().feed(frame().apply { this[0] = 3.0 }))
    }
    @Test fun `missing coordinates cannot reuse values from the previous frame`() {
        val accumulator = GamePieceFrameAccumulator()
        assertNotNull(accumulator.feed(frame()))
        assertNull(accumulator.feed(frame(8.0).apply { this[4] = 99.0 }, skip = 5))
    }
    @Test fun `sequence markers do not republish a consumed record buffer`() {
        val accumulator = GamePieceFrameAccumulator()
        assertNotNull(accumulator.feed(frame()))
        assertNull(accumulator.accept("ARES/GamePiecesFrame/11", 8.0))
    }
    @Test fun `nonfinite record fields never become rendered coordinates`() {
        for (index in 2..11) assertNull(GamePieceFrameAccumulator().feed(frame().apply { this[index] = Double.NaN }), "index $index")
    }
    @Test fun `fractional and unsafe identities are rejected instead of rounded`() {
        for (id in listOf(-1.0, 0.0, 101.5, 9_007_199_254_740_992.0)) {
            for (index in 2..3) assertNull(GamePieceFrameAccumulator().feed(frame().apply { this[index] = id }), "$index: $id")
        }
    }
    @Test fun `unknown shape codes and out of range colors are rejected`() {
        for (shape in listOf(-1.0, 0.5, 2.0)) assertNull(GamePieceFrameAccumulator().feed(frame().apply { this[9] = shape }))
        for (color in listOf(-1.0, 0.5, 16777216.0)) assertNull(GamePieceFrameAccumulator().feed(frame().apply { this[10] = color }))
    }
    @Test fun `nonpositive dimensions do not silently select a fallback size`() {
        for (dimension in listOf(0.0, -1.0)) for (index in 7..8) {
            assertNull(GamePieceFrameAccumulator().feed(frame().apply { this[index] = dimension }))
        }
    }
    @Test fun `fractional negative and unsafe sequences are rejected`() {
        for (sequence in listOf(-1.0, 0.5, 9_007_199_254_740_992.0)) assertNull(GamePieceFrameAccumulator().feed(frame(sequence)))
    }
    @Test fun `replay decoding applies the same identity and shape contract`() {
        for (index in listOf(2, 3, 9, 10, 11)) {
            val values = frame().apply { this[index] = -1.0 }
                .mapIndexed { i, value -> "ARES/GamePiecesFrame/$i" to value }.toMap()
            assertNull(GamePieceFrameAccumulator.decodeSnapshot(values), "index $index")
        }
    }

    private class HeldDispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued += block }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }
    private suspend fun TestScope.withClient(block: suspend (Nt4ClientService, MutableStateFlow<LivePoseState>, HeldDispatcher) -> Unit) {
        val client = Nt4ClientService(mock(DatabaseService::class.java))
        val lifetime = SupervisorJob(backgroundScope.coroutineContext[Job])
        val owner = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val dispatcher = HeldDispatcher()
        try {
            val pose = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(client, owner, MutableStateFlow(FieldViewerState()), pose, dispatcher)
            runCurrent(); dispatcher.drain()
            client.handleIncomingText("""[{"method":"announce","params":{"name":"/ARES/GamePiecesFrame","id":22,"type":"double[]"}}]""", "team", "season", "robot")
            block(client, pose, dispatcher)
        } finally {
            withContext(NonCancellable) {
                lifetime.cancel(); dispatcher.drain(); lifetime.join()
                assertTrue(client.disposeAndJoin())
            }
        }
    }
    @Test fun `slow field consumer receives a complete packed frame despite scalar bus overflow`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":22,"time":1000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,7]}]""", "team", "season", "robot")
            repeat(5000) { client.telemetryStore.accept(com.ares.analytics.shared.models.TelemetryFrame(it.toLong(), "live", "Unrelated/Value", it.toDouble())) }
            dispatcher.drain(); runCurrent()
            val piece = pose.value.liveGamePieces.values.single()
            assertEquals(1.25, piece.x)
            assertEquals(2.5, piece.y)
        }
    }
    @Test fun `trailing packed payload data does not publish a plausible prefix`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":22,"time":1000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,7,999]}]""", "team", "season", "robot")
            dispatcher.drain(); runCurrent()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }

    @Test fun `complete frames preserve all visual units and identity`() {
        val piece = assertNotNull(GamePieceFrameAccumulator().feed(frame())).values.single()
        assertEquals("sim-101", piece.id)
        assertEquals("sim-type-202", piece.typeId)
        assertEquals(1.25, piece.x)
        assertEquals(2.5, piece.y)
        assertEquals(0.4, piece.rotationRadians)
        assertEquals(0.3, piece.widthMeters)
        assertEquals(0.1, piece.heightMeters)
        assertEquals("box", piece.simulationShape)
        assertEquals(65280, piece.colorRgb)
    }
    @Test fun `empty frames own the layer and subsequent frames recover`() {
        val accumulator = GamePieceFrameAccumulator()
        assertNotNull(accumulator.feed(frame()))
        assertTrue(assertNotNull(accumulator.feed(doubleArrayOf(2.0, 0.0, 8.0))).isEmpty())
        assertTrue(accumulator.hasSeenFrame)
        assertNotNull(accumulator.feed(frame(9.0)))
    }
    @Test fun `bad counts discard staging without poisoning the next full frame`() {
        val accumulator = GamePieceFrameAccumulator()
        for (count in listOf(-1.0, 0.5, Double.NaN, 10001.0)) {
            assertNull(accumulator.feed(frame().apply { this[1] = count }))
            assertNotNull(accumulator.feed(frame()))
        }
    }
    @Test fun `reset prevents partial records crossing source boundaries`() {
        val accumulator = GamePieceFrameAccumulator()
        frame().take(6).forEachIndexed { i, value -> accumulator.accept("ARES/GamePiecesFrame/$i", value) }
        accumulator.reset()
        assertFalse(accumulator.hasSeenFrame)
        for (i in 6..11) assertNull(accumulator.accept("ARES/GamePiecesFrame/$i", frame()[i]))
        assertNotNull(accumulator.feed(frame()))
    }
    @Test fun `supported numeric array representations decode consistently`() {
        val variants = listOf(frame(), frame().toList(), frame().toTypedArray(), frame().map { it.toFloat() }.toFloatArray(),
            kotlinx.serialization.json.Json.parseToJsonElement(frame().joinToString(prefix = "[", postfix = "]")))
        variants.forEach { value ->
            val decoded = assertNotNull(GamePieceTelemetry.decodePacked(value))
            assertEquals(7L, decoded.sequence)
            val piece = decoded.pieces.values.single()
            assertEquals(1.25, piece.x)
            assertEquals(0.3, assertNotNull(piece.widthMeters), 1e-6)
        }
    }
    @Test fun `text boolean nested and missing array elements are rejected`() {
        for (invalid in listOf("0", true, emptyList<Any>(), null)) {
            val values = frame().map { it as Any? }.toMutableList()
            values[4] = invalid
            assertNull(GamePieceTelemetry.decodePacked(values))
        }
        assertNull(GamePieceTelemetry.decodePacked(kotlinx.serialization.json.Json.parseToJsonElement(
            """[2,1,101,202,"1.25",2.5,0.4,0.3,0.1,1,65280,7]""")))
    }
    private fun manyPieces(count: Int): DoubleArray = DoubleArray(3 + 9 * count).also { values ->
        values[0] = 2.0; values[1] = count.toDouble()
        repeat(count) { index ->
            frame().copyInto(values, 2 + 9 * index, 2, 11)
            values[2 + 9 * index] = (index + 1).toDouble()
        }
    }
    @Test fun `packed input and staging counts enforce bounded allocation sizes`() {
        assertEquals(454, assertNotNull(GamePieceTelemetry.decodePacked(manyPieces(454))).pieces.size)
        assertNull(GamePieceTelemetry.decodePacked(manyPieces(455)))
        assertEquals(90003, GamePieceTelemetry.requiredSize(10000))
        for (count in listOf(-1, 10001, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { GamePieceTelemetry.requiredSize(count) }
    }
    @Test fun `colliding integer hashes and shared fallback identities preserve every record`() {
        val values = manyPieces(3)
        values[2] = 1.0
        values[11] = 4294967296.0
        values[20] = 1.0
        val pieces = assertNotNull(GamePieceTelemetry.decodePacked(values)).pieces.values
        assertEquals(listOf("sim-1", "sim-4294967296", "sim-1"), pieces.map { it.id })
    }
    @Test fun `decoder never keeps mutable input array ownership`() {
        val values = frame()
        val decoded = assertNotNull(GamePieceTelemetry.decodePacked(values))
        values.fill(-1.0)
        assertEquals(1.25, decoded.pieces.values.single().x)
        assertEquals(7L, decoded.sequence)
    }
    @Test fun `text backed replay coordinates do not become invented numeric zeros`() {
        val values = frame().mapIndexed { i, value -> "ARES/GamePiecesFrame/$i" to value }.toMap()
        assertTrue(ReplayFrame(1, values, mapOf("ARES/GamePiecesFrame/4" to "unavailable")).toReplayPoseState().liveGamePieces.isEmpty())
    }
    @Test fun `legacy replay also excludes coordinates backed by text`() {
        val values = mapOf("ARES/GamePieces/0" to 0.0, "ARES/GamePieces/1" to 2.0)
        assertTrue(ReplayFrame(1, values, mapOf("ARES/GamePieces/0" to "unavailable")).toReplayPoseState().liveGamePieces.isEmpty())
    }
    @Test fun `target reset discards queued packed and scalar data`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":22,"time":1000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,7]}]""", "team", "season", "robot")
            client.clearLiveTargetState()
            dispatcher.drain(); runCurrent()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `queued live frame cannot repopulate the live layer after entering replay`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":22,"time":1000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,7]}]""", "team", "season", "robot")
            client.isReplayActive.value = true
            runCurrent()
            dispatcher.drain(); runCurrent()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `rejected packed frames suppress stale legacy fallback`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":22,"time":1000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,7,999]}]""", "team", "season", "robot")
            client.telemetryStore.accept(com.ares.analytics.shared.models.TelemetryFrame(2, "live", "ARES/GamePieces/0", 9.0))
            client.telemetryStore.accept(com.ares.analytics.shared.models.TelemetryFrame(2, "live", "ARES/GamePieces/1", 8.0))
            dispatcher.drain(); runCurrent()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `unchanged piece records retain the same rendered state across new sequence markers`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":22,"time":1000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,7]}]""", "team", "season", "robot")
            dispatcher.drain(); runCurrent()
            val previous = pose.value
            assertEquals(1, previous.liveGamePieces.size)
            client.handleIncomingText("""[{"topic":22,"time":2000,"value":[2,1,101,202,1.25,2.5,0.4,0.30,0.10,1,65280,8]}]""", "team", "season", "robot")
            dispatcher.drain(); runCurrent()
            assertSame(previous, pose.value)
        }
    }
}
