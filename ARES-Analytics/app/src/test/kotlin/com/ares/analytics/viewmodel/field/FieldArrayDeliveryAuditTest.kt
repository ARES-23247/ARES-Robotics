package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.mock
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FieldArrayDeliveryAuditTest {
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
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
            client.handleIncomingText("""[
                {"method":"announce","params":{"name":"/ARES/GamePieces","id":20,"type":"double[]"}},
                {"method":"announce","params":{"name":"/ARES/GamePieces/Count","id":21,"type":"double"}},
                {"method":"announce","params":{"name":"/Vision/PoseArray","id":30,"type":"double[]"}},
                {"method":"announce","params":{"name":"/AdvantageScope/VisionPose","id":31,"type":"double[]"}},
                {"method":"announce","params":{"name":"/Vision/HasTarget","id":32,"type":"boolean"}},
                {"method":"announce","params":{"name":"/ARES/GamePiecesFrame","id":40,"type":"double[]"}}
            ]""", "team", "season", "robot")
            block(client, pose, dispatcher)
        } finally {
            withContext(NonCancellable) {
                lifetime.cancel(); dispatcher.drain(); lifetime.join()
                assertTrue(client.disposeAndJoin())
            }
        }
    }
    private suspend fun Nt4ClientService.scalar(key: String, value: Double, text: String? = null, timeUs: Long = 1000) {
        telemetryStore.accept(TelemetryFrame(timeUs / 1000, "live", key, value, text, timeUs))
    }
    private suspend fun Nt4ClientService.array(topic: Int, payload: String, timeUs: Long = 1000) {
        handleIncomingText("""[{"topic":$topic,"time":$timeUs,"value":$payload}]""", "team", "season", "robot")
    }
    private suspend fun Nt4ClientService.piece(index: Int = 0) {
        scalar("ARES/GamePieces/${index * 7}", 1.0)
        scalar("ARES/GamePieces/${index * 7 + 1}", 2.0)
    }
    private suspend fun Nt4ClientService.target(value: Boolean, timeUs: Long = 1000) {
        array(32, value.toString(), timeUs)
    }


    @Test fun `legacy ignored and unchanged attributes preserve the same map`() {
        val accumulator = LegacyGamePieceAccumulator()
        fun frame(slot: Int, value: Double) = TelemetryFrame(1, "live", "ARES/GamePieces/$slot", value)
        assertNull(accumulator.accept(frame(0, 1.0)))
        val first = assertNotNull(accumulator.accept(frame(1, 2.0)))
        for (slot in 2..6) assertNull(accumulator.accept(frame(slot, 99.0)))
        assertNull(accumulator.accept(frame(0, 1.0)))
        assertSame(first, accumulator.snapshot())
        assertEquals(1.0, first.getValue(0).x)
        assertNotNull(accumulator.accept(frame(0, 3.0)))
        assertEquals(1.0, first.getValue(0).x)
        assertEquals(3.0, accumulator.snapshot().getValue(0).x)
    }

    @Test fun `legacy scalar staging remains bounded and resets count ownership`() {
        val accumulator = LegacyGamePieceAccumulator()
        fun input(key: String, value: Double) = accumulator.accept(TelemetryFrame(1, "live", key, value))
        input("ARES/GamePieces/69993", 1.0)
        input("ARES/GamePieces/69994", 2.0)
        assertEquals(setOf(9999), accumulator.snapshot().keys)
        input("ARES/GamePieces/Count", 10001.0)
        assertTrue(accumulator.snapshot().isEmpty())
        input("ARES/GamePieces/0", 1.0); input("ARES/GamePieces/1", 2.0)
        assertTrue(accumulator.snapshot().isEmpty())
        accumulator.reset()
        input("ARES/GamePieces/0", 3.0); input("ARES/GamePieces/1", 4.0)
        assertEquals(4.0, accumulator.snapshot().getValue(0).y)
    }

    @Test fun `field array decoders support numeric carriers without retaining input storage`() {
        val data = doubleArrayOf(1.0, 2.0, 0.3)
        val carriers = listOf(data, data.toList(), data.toTypedArray(), data.map { it.toFloat() }.toFloatArray(),
            kotlinx.serialization.json.JsonArray(data.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        for (carrier in carriers) {
            val decoded = assertNotNull(com.ares.analytics.service.VisionPoseArrayTelemetry.decode(carrier))
            assertEquals(3, decoded.length)
            assertEquals(1.0, decoded.values.getValue(0))
        }
        val owned = assertNotNull(com.ares.analytics.service.VisionPoseArrayTelemetry.decode(data))
        data[0] = 99.0
        assertEquals(1.0, owned.values.getValue(0))
        val legacy = assertNotNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(listOf(1, 2, 0, 1, 0, 0, 0)))
        assertEquals(2.0, legacy.values.getValue(0).y)
    }

    @Test fun `legacy active count tolerates unused capacity while requiring active records`() {
        val values = listOf(1, 2, 0, 1, 0, 0, 0, "unused")
        val decoded = assertNotNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(values, 1))
        assertEquals(7, decoded.length)
        assertEquals(setOf(0), decoded.values.keys)
        assertNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(values))
        assertNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(values, 2))
        assertTrue(assertNotNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(values, 0)).values.isEmpty())
    }

    @Test fun `field array decoders reject oversized malformed textual and nonfinite active data`() {
        for (bad in listOf<Any?>(null, 1.0, listOf(1, 2), listOf("1", 2, 3),
            listOf(1, Double.NaN, 3), DoubleArray(4098))) {
            assertNull(com.ares.analytics.service.VisionPoseArrayTelemetry.decode(bad))
        }
        val json = kotlinx.serialization.json.Json.parseToJsonElement("""["1",2,3]""")
        assertNull(com.ares.analytics.service.VisionPoseArrayTelemetry.decode(json))
        assertNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(listOf(1, 2, 0, Double.POSITIVE_INFINITY, 0, 0, 0)))
        assertNull(com.ares.analytics.service.LegacyGamePieceTelemetry.decode(DoubleArray(4097), 1))
    }

    @Test fun `replay parent lengths clear and shorten both field layers`() {
        val values = mapOf("Vision/HasTarget" to 1.0) +
            (0..5).associate { "Vision/PoseArray/$it" to it.toDouble() } +
            (0..13).associate { "ARES/GamePieces/$it" to it.toDouble() }
        fun state(visionLength: Double, legacyLength: Double) = com.ares.analytics.service.ReplayFrame(1,
            values + mapOf("Vision/PoseArray/Length" to visionLength, "ARES/GamePieces/Length" to legacyLength)).toReplayPoseState()
        assertEquals(setOf(0, 1, 2), state(3.0, 7.0).visionPoses.keys)
        assertEquals(setOf(0), state(3.0, 7.0).liveGamePieces.keys)
        assertTrue(state(0.0, 0.0).visionPoses.isEmpty())
        assertTrue(state(0.0, 0.0).liveGamePieces.isEmpty())
    }

    @Test fun `replay rejects malformed length metadata and never uses text placeholders`() {
        val vision = (0..2).associate { "Vision/PoseArray/$it" to it.toDouble() }
        val legacy = (0..6).associate { "ARES/GamePieces/$it" to it.toDouble() }
        for (bad in listOf(-1.0, 0.5, 1.0, 4097.0, Double.NaN)) {
            assertTrue(com.ares.analytics.service.VisionPoseArrayTelemetry.decodeSnapshot(
                vision + ("Vision/PoseArray/Length" to bad), emptyMap()).isEmpty())
            assertTrue(com.ares.analytics.service.LegacyGamePieceTelemetry.decodeSnapshot(
                legacy + ("ARES/GamePieces/Length" to bad), emptyMap()).isEmpty())
        }
        assertTrue(com.ares.analytics.service.VisionPoseArrayTelemetry.decodeSnapshot(
            vision + ("Vision/PoseArray/Length" to 3.0), mapOf("Vision/PoseArray/1" to "missing")).isEmpty())
        assertTrue(com.ares.analytics.service.LegacyGamePieceTelemetry.decodeSnapshot(
            legacy + ("ARES/GamePieces/Length" to 7.0), mapOf("ARES/GamePieces/Length" to "7")).isEmpty())
    }

    @Test fun `remote legacy count changes cannot revive removed parent records`() = runTest {
        withClient { client, pose, dispatcher ->
            client.array(20, "[1,2,0,0,0,0,0,3,4,0,0,0,0,0]"); dispatcher.drain()
            client.array(21, "1", 2000); dispatcher.drain()
            client.array(21, "2", 3000); dispatcher.drain()
            assertEquals(setOf(0), pose.value.liveGamePieces.keys)
            assertEquals(7.0, client.telemetryStore.latest("ARES/GamePieces/Length")?.value)
            client.array(20, "[5,6,0,0,0,0,0,7,8,0,0,0,0,0]", 4000); dispatcher.drain()
            assertEquals(setOf(0, 1), pose.value.liveGamePieces.keys)
        }
    }

    @Test fun `target and replay boundaries reject queued array parents`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(30, "[1,2,0.3]")
            client.array(20, "[1,2,0,0,0,0,0]")
            client.isReplayActive.value = true
            runCurrent(); dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
            assertTrue(pose.value.liveGamePieces.isEmpty())
            client.clearLiveTargetState(); dispatcher.drain()
            assertNull(client.visionPoseArrayFrame.value)
            assertNull(client.legacyGamePieceFrame.value)
        }
    }

    @Test fun `typed game-piece parents retain priority over legacy arrays and counts`() = runTest {
        withClient { client, pose, dispatcher ->
            client.array(40, "[2,1,101,202,1.25,2.5,0.4,0.3,0.1,1,65280,7]"); dispatcher.drain()
            val typed = pose.value.liveGamePieces
            client.array(20, "[9,8,0,0,0,0,0]", 2000)
            client.array(21, "0", 3000); dispatcher.drain()
            assertSame(typed, pose.value.liveGamePieces)
            assertEquals("sim-101", pose.value.liveGamePieces.values.single().id)
        }
    }

    @Test fun `canonical vision parents keep priority and target loss requires fresh observations`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(30, "[1,2,0.3]"); dispatcher.drain()
            client.array(31, "[9,8,0.7]", 2000); dispatcher.drain()
            assertEquals(1.0, pose.value.visionPoses[0])
            client.target(false, 3000); dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
            client.target(true, 4000); dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
            client.array(30, "[5,6,0.4]", 4000); dispatcher.drain()
            assertEquals(5.0, pose.value.visionPoses[0])
        }
    }

    @Test fun `legacy count state survives scalar-bus overflow for a newly created view`() = runTest {
        withClient { client, _, dispatcher ->
            client.array(20, "[1,2,0,0,0,0,0]")
            client.array(21, "0", 2000)
            repeat(5000) { client.scalar("Other/$it", it.toDouble()) }
            dispatcher.drain()
            val state = MutableStateFlow(LivePoseState())
            val lifetime = SupervisorJob(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + lifetime)
            try {
                FieldTopicSubscriber(client, owner, MutableStateFlow(FieldViewerState()), state, dispatcher)
                runCurrent(); dispatcher.drain()
                assertTrue(state.value.liveGamePieces.isEmpty())
            } finally {
                withContext(NonCancellable) { lifetime.cancel(); dispatcher.drain(); lifetime.join() }
            }
        }
    }

    private fun recordingTest(block: suspend (DatabaseService, Nt4ClientService) -> Unit) = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("ares-field-array-recording").toFile()
        var database: DatabaseService? = null
        var client: Nt4ClientService? = null
        try {
            val ownedDatabase = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
            database = ownedDatabase
            val ownedClient = Nt4ClientService(ownedDatabase)
            client = ownedClient
            ownedClient.handleIncomingText("""[
                {"method":"announce","params":{"name":"/ARES/GamePieces","id":20,"type":"double[]"}},
                {"method":"announce","params":{"name":"/ARES/GamePieces/Count","id":21,"type":"double"}},
                {"method":"announce","params":{"name":"/Vision/PoseArray","id":30,"type":"double[]"}},
                {"method":"announce","params":{"name":"/Vision/HasTarget","id":32,"type":"boolean"}}
            ]""", "team", "season", "robot")
            block(ownedDatabase, ownedClient)
        } finally {
            try { client?.let { assertTrue(it.disposeAndJoin()) } }
            finally { try { database?.closeAndJoin() } finally { directory.deleteRecursively() } }
        }
    }

    @Test fun `recorded length shares parent receipt time and empty arrays replay as empty layers`() = recordingTest { database, client ->
        client.target(true)
        client.array(30, "[1,2,0.3]")
        client.array(20, "[1,2,0,0,0,0,0]")
        assertTrue(client.flushPendingFrames())
        val frames = database.getTelemetryRange("live-telemetry", 0, Long.MAX_VALUE)
        for ((prefix, width) in listOf("Vision/PoseArray" to 3, "ARES/GamePieces" to 7)) {
            val parent = frames.filter { it.key.startsWith("$prefix/") }
            assertEquals(width + 1, parent.size)
            assertEquals(1, parent.map { it.timestampUs }.distinct().size)
            assertEquals(width.toDouble(), parent.single { it.key == "$prefix/Length" }.value)
        }
        client.array(30, "[]", 2000)
        client.array(20, "[]", 2000)
        assertTrue(client.flushPendingFrames())
        val latest = database.getLatestTelemetryBefore("live-telemetry", Long.MAX_VALUE)
        val replay = com.ares.analytics.service.ReplayFrame(1, latest.associate { it.key to it.value }).toReplayPoseState()
        assertTrue(replay.visionHasTarget)
        assertTrue(replay.visionPoses.isEmpty())
        assertTrue(replay.liveGamePieces.isEmpty())
    }

    @Test fun `recording during replay preserves malformed and removed parent metadata without live publication`() = recordingTest { database, client ->
        client.isReplayActive.value = true
        client.target(true)
        client.array(30, "[1,2,0.3,4]", 2000)
        client.array(21, "1", 2000)
        client.array(20, "[1,2,0,0,0,0,0,99]", 2000)
        assertTrue(client.flushPendingFrames())
        assertNull(client.visionPoseArrayFrame.value)
        assertNull(client.legacyGamePieceFrame.value)
        assertEquals(-1.0, database.getTelemetryForKey("live-telemetry", "Vision/PoseArray/Length").last().value)
        assertEquals(7.0, database.getTelemetryForKey("live-telemetry", "ARES/GamePieces/Length").last().value)
        client.target(false, 3000)
        client.target(true, 4000)
        client.array(21, "0", 4000)
        assertTrue(client.flushPendingFrames())
        val latest = database.getLatestTelemetryBefore("live-telemetry", Long.MAX_VALUE)
        val replay = com.ares.analytics.service.ReplayFrame(1, latest.associate { it.key to it.value }).toReplayPoseState()
        assertTrue(replay.visionPoses.isEmpty())
        assertTrue(replay.liveGamePieces.isEmpty())
    }

    @Test fun `legacy pieces require both coordinates before appearing`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("ARES/GamePieces/0", 1.0); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `unused legacy attributes cannot create display objects`() = runTest {
        withClient { client, pose, dispatcher ->
            for (index in 2..6) client.scalar("ARES/GamePieces/$index", 1.0)
            dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `negative legacy indexes are rejected`() = runTest {
        withClient { client, pose, dispatcher ->
            client.piece(-1); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `oversized legacy indexes are rejected`() = runTest {
        withClient { client, pose, dispatcher ->
            client.piece(10_000); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `legacy count constrains subsequent scalar updates`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("ARES/GamePieces/Count", 1.0)
            client.piece(1); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `fractional legacy count cannot retain valid-looking objects`() = runTest {
        withClient { client, pose, dispatcher ->
            client.piece(); dispatcher.drain()
            client.scalar("ARES/GamePieces/Count", 1.5); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `nonfinite legacy count invalidates its layer`() = runTest {
        withClient { client, pose, dispatcher ->
            client.piece(); dispatcher.drain()
            client.scalar("ARES/GamePieces/Count", Double.NaN); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `removed legacy coordinates cannot reappear after count grows`() = runTest {
        withClient { client, pose, dispatcher ->
            client.piece(); dispatcher.drain()
            client.scalar("ARES/GamePieces/Count", 0.0); dispatcher.drain()
            client.scalar("ARES/GamePieces/Count", 1.0)
            client.scalar("ARES/GamePieces/0", 9.0); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `invalid legacy coordinates clear a previously valid piece`() = runTest {
        withClient { client, pose, dispatcher ->
            client.piece(); dispatcher.drain()
            client.scalar("ARES/GamePieces/1", 0.0, "missing"); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `empty legacy parent arrays clear the complete layer`() = runTest {
        withClient { client, pose, dispatcher ->
            client.array(20, "[1,2,0,0,0,0,0]"); dispatcher.drain()
            assertEquals(1, pose.value.liveGamePieces.size)
            client.array(20, "[]", 2000); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `shorter legacy parents remove trailing records even without count updates`() = runTest {
        withClient { client, pose, dispatcher ->
            client.array(20, "[1,2,0,0,0,0,0,3,4,0,0,0,0,0]"); dispatcher.drain()
            client.array(20, "[5,6,0,0,0,0,0]", 2000); dispatcher.drain()
            assertEquals(setOf(0), pose.value.liveGamePieces.keys)
            assertEquals(5.0, pose.value.liveGamePieces.getValue(0).x)
        }
    }
    @Test fun `incomplete legacy parent records cannot display a valid prefix`() = runTest {
        withClient { client, pose, dispatcher ->
            client.array(20, "[1,2,0,0,0,0,0,3]"); dispatcher.drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }
    @Test fun `empty vision arrays clear old observations while target flag remains true`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(30, "[1,2,0.3]"); dispatcher.drain()
            assertEquals(3, pose.value.visionPoses.size)
            client.array(30, "[]", 2000); dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
        }
    }
    @Test fun `shorter vision arrays remove trailing poses even at the same source timestamp`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(30, "[1,2,0.3,4,5,0.6]"); dispatcher.drain()
            client.array(30, "[7,8,0.9]"); dispatcher.drain()
            assertEquals(mapOf(0 to 7.0, 1 to 8.0, 2 to 0.9), pose.value.visionPoses)
        }
    }
    @Test fun `malformed vision parent length rejects its valid-looking prefix`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(30, "[1,2,0.3,4]"); dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
        }
    }
    @Test fun `complete vision parent survives raw telemetry overflow`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(30, "[1,2,0.3]")
            repeat(5000) { client.scalar("Other/$it", it.toDouble()) }
            dispatcher.drain()
            assertEquals(mapOf(0 to 1.0, 1 to 2.0, 2 to 0.3), pose.value.visionPoses)
        }
    }
    @Test fun `vision array received before same-time target flag is preserved`() = runTest {
        withClient { client, pose, dispatcher ->
            client.array(30, "[1,2,0.3]"); dispatcher.drain()
            client.target(true); dispatcher.drain()
            assertEquals(mapOf(0 to 1.0, 1 to 2.0, 2 to 0.3), pose.value.visionPoses)
        }
    }
    @Test fun `empty canonical vision parent suppresses an earlier alias observation`() = runTest {
        withClient { client, pose, dispatcher ->
            client.target(true); dispatcher.drain()
            client.array(31, "[1,2,0.3]"); dispatcher.drain()
            client.array(30, "[]", 2000); dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
        }
    }
}
