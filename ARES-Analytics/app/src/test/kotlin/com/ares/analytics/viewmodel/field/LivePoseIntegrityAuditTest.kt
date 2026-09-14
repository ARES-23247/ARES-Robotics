package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.*
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.mockito.Mockito.mock
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LivePoseIntegrityAuditTest {
    private fun packed(sequence: Double = 9.0) = DoubleArray(10) { if (it == 9) sequence else it.toDouble() }
    private fun FieldPoseFrameAccumulator.feed(values: DoubleArray, skip: Int = -1): Boolean {
        var committed = false
        values.forEachIndexed { index, value -> if (index != skip) committed = accept("ARES/SimulatorPoseFrame/$index", value) }
        return committed
    }
    private fun FieldPoseFrameAccumulator.scalar(prefix: String, x: Double, y: Double, h: Double) {
        listOf(x, y, h).forEachIndexed { i, v -> accept("$prefix/$i", v) }
    }

    @Test fun `a marker alone cannot claim a complete packed pose`() {
        assertFalse(FieldPoseFrameAccumulator().accept("ARES/SimulatorPoseFrame/9", 1.0))
    }
    @Test fun `missing packed slots cannot reuse coordinates from the previous sample`() {
        val accumulator = FieldPoseFrameAccumulator()
        assertTrue(accumulator.feed(packed()))
        val first = accumulator.snapshot(LivePoseState())
        assertFalse(accumulator.feed(packed(10.0).apply { this[0] = 99.0 }, skip = 1))
        assertEquals(first, accumulator.snapshot(first))
    }
    @Test fun `packed scalar components and sequence must be finite exact and valid`() {
        for (index in 0..8) assertFalse(FieldPoseFrameAccumulator().feed(packed().apply { this[index] = Double.NaN }))
        for (value in listOf(-1.0, 0.5, Double.POSITIVE_INFINITY, 9_007_199_254_740_992.0)) {
            assertFalse(FieldPoseFrameAccumulator().feed(packed(value)), "$value")
        }
    }
    @Test fun `consumed packed sequence cannot be replayed without a fresh sample`() {
        val accumulator = FieldPoseFrameAccumulator()
        assertTrue(accumulator.feed(packed()))
        assertFalse(accumulator.accept("ARES/SimulatorPoseFrame/9", 10.0))
        assertFalse(accumulator.feed(packed()))
    }
    @Test fun `a scalar truth coordinate does not invent the other two coordinates`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.accept("ARES/TruePose/0", 3.0)
        assertFalse(accumulator.snapshot(LivePoseState()).hasTruePoseData)
    }
    @Test fun `estimator fallback cannot combine different source axes`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.accept("Drive/Pose_X", 1.0)
        accumulator.accept("Drive/Pose_Y", 2.0)
        accumulator.accept("Drive/Pose_Heading", 0.3)
        accumulator.accept("ARES/EstimatedPose/0", 99.0)
        val state = accumulator.snapshot(LivePoseState())
        assertEquals(1.0, state.ekfX)
        assertEquals(2.0, state.ekfY)
    }
    @Test fun `complete estimator owns its layer even without simulator truth`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.scalar("ARES/EstimatedPose", 1.0, 2.0, 0.3)
        accumulator.accept("Drive/Pose_X", 99.0)
        assertEquals(1.0, accumulator.snapshot(LivePoseState()).ekfX)
    }
    @Test fun `reset removes old pose sources instead of borrowing previous rendered state`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.feed(packed())
        val first = accumulator.snapshot(LivePoseState())
        accumulator.reset()
        val reset = accumulator.snapshot(first)
        assertFalse(reset.hasTruePoseData)
        assertNull(reset.ekfX)
        assertNull(reset.odomX)
        assertNull(reset.simHeading)
    }
    @Test fun `unchanged poses reuse the rendered object`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.feed(packed())
        val first = accumulator.snapshot(LivePoseState())
        assertSame(first, accumulator.snapshot(first))
    }
    @Test fun `invalid manually supplied atomic parent cannot become a pose`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.accept(SimulatorPoseFrameSnapshot(Double.NaN, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 1L, 1L, 1000L))
        assertFalse(accumulator.snapshot(LivePoseState()).hasTruePoseData)
    }
    @Test fun `packed decoder rejects JSON numeric strings`() {
        for (index in 0..9) {
            val values = JsonArray(packed().mapIndexed { i, v -> if (i == index) JsonPrimitive(v.toString()) else JsonPrimitive(v) })
            assertNull(decodeSimulatorPoseFrame(values, 1, 1000))
        }
    }
    @Test fun `packed decoder rejects sequences beyond exact integer precision`() {
        assertNull(decodeSimulatorPoseFrame(packed(9_007_199_254_740_992.0), 1, 1000))
    }


    @Test fun `scalar poses complete within a source and retain unchanged coordinates`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.accept("ARES/TruePose/0", 1.0)
        accumulator.accept("ARES/TruePose/1", 2.0)
        assertFalse(accumulator.snapshot(LivePoseState()).hasTruePoseData)
        accumulator.accept("ARES/TruePose/2", -10.0)
        val first = accumulator.snapshot(LivePoseState())
        assertTrue(first.hasTruePoseData)
        assertEquals(-10.0, first.trueHeading)
        accumulator.accept("ARES/TruePose/0", 3.0)
        val second = accumulator.snapshot(first)
        assertEquals(3.0, second.trueX)
        assertEquals(2.0, second.trueY)
        assertEquals(-10.0, second.trueHeading)
    }

    @Test fun `invalid scalar groups disappear and recover without touching other field layers`() {
        val accumulator = FieldPoseFrameAccumulator()
        accumulator.scalar("ARES/EstimatedPose", 1.0, 2.0, 0.3)
        val first = accumulator.snapshot(LivePoseState(visionHasTarget = true, indicatorLights = mapOf("a" to 1.0)))
        accumulator.accept("ARES/EstimatedPose/1", Double.POSITIVE_INFINITY)
        val invalid = accumulator.snapshot(first)
        assertNull(invalid.ekfX)
        assertNull(invalid.ekfY)
        assertTrue(invalid.visionHasTarget)
        assertEquals(first.indicatorLights, invalid.indicatorLights)
        accumulator.accept("ARES/EstimatedPose/1", 4.0)
        assertEquals(4.0, accumulator.snapshot(invalid).ekfY)
    }

    @Test fun `packed staging recovers and resets sequence ownership at source boundaries`() {
        val accumulator = FieldPoseFrameAccumulator()
        assertFalse(accumulator.feed(packed(), skip = 2))
        assertTrue(accumulator.feed(packed(0.0)))
        assertFalse(accumulator.accept("ARES/SimulatorPoseFrame/10", 1.0))
        assertTrue(accumulator.feed(packed(9_007_199_254_740_991.0)))
        val first = accumulator.snapshot(LivePoseState())
        accumulator.reset()
        assertTrue(accumulator.feed(packed(0.0)))
        assertSame(first, accumulator.snapshot(first))
    }

    @Test fun `atomic parent rejects invalid metadata and protects scalar source ownership`() {
        val valid = assertNotNull(decodeSimulatorPoseFrame(packed(), 1, 1000))
        val accumulator = FieldPoseFrameAccumulator()
        assertTrue(accumulator.accept(valid))
        val first = accumulator.snapshot(LivePoseState())
        for (bad in listOf(valid.copy(sequence = -1), valid.copy(sequence = Long.MAX_VALUE),
            valid.copy(odomHeading = Double.NEGATIVE_INFINITY))) {
            assertFalse(accumulator.accept(bad))
            assertSame(first, accumulator.snapshot(first))
        }
        assertFalse(accumulator.accept("ARES/SimulatorPoseFrame/0", 99.0))
        assertFalse(accumulator.accept("Drive/Pose_X", 99.0))
    }

    @Test fun `packed decoder supports owned numeric carriers and target identity`() {
        val original = packed()
        val carriers = listOf(original, original.toList(), original.toTypedArray(),
            original.map { it.toFloat() }.toFloatArray(), JsonArray(original.map(::JsonPrimitive)))
        for (carrier in carriers) {
            val decoded = assertNotNull(decodeSimulatorPoseFrame(carrier, 1, 1000, 7))
            assertEquals(7L, decoded.targetEpoch)
            assertEquals(3.0, decoded.ekfX)
            assertEquals(8.0, decoded.odomHeading)
        }
        val owned = assertNotNull(decodeSimulatorPoseFrame(original, 1, 1000))
        original[0] = 99.0
        assertEquals(0.0, owned.trueX)
        assertNull(decodeSimulatorPoseFrame(original + 11.0, 1, 1000))
        assertNull(decodeSimulatorPoseFrame(original.take(9), 1, 1000))
    }

    @Test fun `vision triples require one timestamp and never mix array families`() {
        val vision = VisionPoseAccumulator()
        fun update(key: String, value: Double, us: Long = 1000) {
            vision.accept(TelemetryFrame(us / 1000, "live", key, value, timestampUs = us))
        }
        update("Vision/HasTarget", 1.0)
        update("Vision/PoseArray/0", 1.0)
        update("AdvantageScope/VisionPose/1", 2.0)
        update("AdvantageScope/VisionPose/2", 0.3)
        assertTrue(vision.snapshot(LivePoseState()).visionPoses.isEmpty())
        update("Vision/PoseArray/1", 2.0, 1001)
        update("Vision/PoseArray/2", 0.3)
        assertTrue(vision.snapshot(LivePoseState()).visionPoses.isEmpty())
        update("Vision/PoseArray/1", 2.0)
        val state = vision.snapshot(LivePoseState())
        assertEquals(mapOf(0 to 1.0, 1 to 2.0, 2 to 0.3), state.visionPoses)
        assertSame(state, vision.snapshot(state))
    }

    @Test fun `vision loss clears staged coordinates and permits a new alias source`() {
        val vision = VisionPoseAccumulator()
        fun update(key: String, value: Double) { vision.accept(TelemetryFrame(1, "live", key, value)) }
        update("Vision/HasTarget", 1.0)
        update("Vision/Pose_X", 1.0); update("Vision/Pose_Y", 2.0); update("Vision/Pose_Heading", 0.3)
        assertEquals(1.0, vision.snapshot(LivePoseState()).visionX)
        update("Vision/HasTarget", 0.0)
        val empty = vision.snapshot(LivePoseState())
        update("Vision/Pose_X", 99.0)
        assertSame(empty, vision.snapshot(empty))
        update("Vision/HasTarget", 1.0)
        update("Vision/Pose_Heading", 0.4)
        assertNull(vision.snapshot(empty).visionX)
        for (index in 0..2) update("AdvantageScope/VisionPose/$index", index.toDouble())
        assertEquals(mapOf(0 to 0.0, 1 to 1.0, 2 to 2.0), vision.snapshot(empty).visionPoses)
    }

    @Test fun `vision array bounds invalidation and new sample cleanup remain bounded`() {
        val vision = VisionPoseAccumulator()
        vision.accept(TelemetryFrame(1, "live", "Vision/HasTarget", 1.0))
        for (index in 4092..4094) vision.accept(TelemetryFrame(1, "live", "Vision/PoseArray/$index", index.toDouble()))
        val first = vision.snapshot(LivePoseState())
        assertEquals(setOf(4092, 4093, 4094), first.visionPoses.keys)
        vision.accept(TelemetryFrame(1, "live", "Vision/PoseArray/4093", 0.0, "bad"))
        assertTrue(vision.snapshot(first).visionPoses.isEmpty())
        for (index in 0..2) vision.accept(TelemetryFrame(2, "live", "Vision/PoseArray/$index", index.toDouble()))
        val second = vision.snapshot(first)
        assertEquals(setOf(0, 1, 2), second.visionPoses.keys)
        vision.accept(TelemetryFrame(1, "live", "Vision/PoseArray/0", 99.0))
        assertSame(second, vision.snapshot(second))
    }

    @Test fun `rejected parent payload cannot sneak through its scalar prefix`() = runTest {
        withClient { client, pose, dispatcher ->
            client.handleIncomingText("""[{"topic":40,"time":1000,"value":[0,1,2,3,4,5,6,7,8,9,10]}]""", "team", "season", "robot")
            dispatcher.drain()
            assertNull(client.simulatorPoseFrame.value)
            assertFalse(pose.value.hasTruePoseData)
            client.parent(); dispatcher.drain()
            assertTrue(pose.value.hasTruePoseData)
        }
    }

    @Test fun `target switch rejects held parents and accepts the next target sample`() = runTest {
        withClient { client, pose, dispatcher ->
            client.parent()
            client.clearLiveTargetState()
            dispatcher.drain(); runCurrent()
            assertFalse(pose.value.hasTruePoseData)
            client.handleIncomingText("""[{"method":"announce","params":{"name":"/ARES/SimulatorPoseFrame","id":40,"type":"double[]"}}]""", "team", "season", "robot")
            client.parent(); dispatcher.drain()
            assertTrue(pose.value.hasTruePoseData)
            assertEquals(client.telemetryStore.currentTargetEpoch(), client.simulatorPoseFrame.value?.targetEpoch)
        }
    }

    @Test fun `queued scalar and vision data are rejected during replay`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("Vision/HasTarget", 1.0)
            client.scalar("Vision/Pose_X", 1.0); client.scalar("Vision/Pose_Y", 2.0); client.scalar("Vision/Pose_Heading", 0.3)
            client.scalar("ARES/TruePose/0", 1.0); client.scalar("ARES/TruePose/1", 2.0); client.scalar("ARES/TruePose/2", 0.3)
            client.isReplayActive.value = true
            runCurrent(); dispatcher.drain()
            assertFalse(pose.value.hasTruePoseData)
            assertFalse(pose.value.visionHasTarget)
            assertNull(pose.value.visionX)
        }
    }

    @Test fun `current field admission rejects superseded publications for both consumers`() = runTest {
        withClient { client, _, _ ->
            val old = client.telemetryStore.accept(TelemetryFrame(1, "live", "Drive/Pose_X", 1.0))
            assertTrue(client.isCurrentFieldUpdate(old))
            val latest = client.telemetryStore.accept(old.copy(value = 2.0))
            assertFalse(client.isCurrentFieldUpdate(old))
            assertTrue(client.isCurrentFieldUpdate(latest))
            client.clearLiveTargetState()
            assertFalse(client.isCurrentFieldUpdate(latest))
        }
    }

    @Test fun `invalid values cannot refresh the pose telemetry activity badge`() {
        fun valid(key: String, value: Double, text: String? = null) =
            com.ares.analytics.ui.components.dashboard.isValidPoseStatusValue(TelemetryFrame(1, "live", key, value, text))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.5, 2.0, -1.0)) {
            assertFalse(valid("Vision/HasTarget", bad))
        }
        assertFalse(valid("Drive/Pose_X", 0.0, "offline"))
        assertTrue(valid("Vision/HasTarget", 0.0))
        assertTrue(valid("Vision/HasTarget", 1.0))
        assertTrue(valid("Drive/Pose_X", -3.0))
    }

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
            client.handleIncomingText("""[{"method":"announce","params":{"name":"/ARES/SimulatorPoseFrame","id":40,"type":"double[]"}}]""", "team", "season", "robot")
            block(client, pose, dispatcher)
        } finally {
            withContext(NonCancellable) {
                lifetime.cancel(); dispatcher.drain(); lifetime.join()
                assertTrue(client.disposeAndJoin())
            }
        }
    }
    private suspend fun Nt4ClientService.parent() {
        handleIncomingText("""[{"topic":40,"time":1000,"value":[0,1,2,3,4,5,6,7,8,9]}]""", "team", "season", "robot")
    }
    private suspend fun Nt4ClientService.scalar(key: String, value: Double, text: String? = null) {
        telemetryStore.accept(TelemetryFrame(1, "live", key, value, text))
    }

    @Test fun `clearing the target removes already rendered pose sources`() = runTest {
        withClient { client, pose, dispatcher ->
            client.parent(); dispatcher.drain()
            assertTrue(pose.value.hasTruePoseData)
            client.clearLiveTargetState(); dispatcher.drain(); runCurrent()
            assertFalse(pose.value.hasTruePoseData)
            assertNull(pose.value.ekfX)
            assertNull(pose.value.odomX)
        }
    }
    @Test fun `queued live pose cannot repopulate the layer after entering replay`() = runTest {
        withClient { client, pose, dispatcher ->
            client.parent()
            client.isReplayActive.value = true
            runCurrent(); dispatcher.drain()
            assertFalse(pose.value.hasTruePoseData)
            assertNull(pose.value.ekfX)
        }
    }
    @Test fun `text scalar pose placeholders are rejected`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("ARES/TruePose/0", 0.0, "missing")
            client.scalar("ARES/TruePose/1", 1.0)
            client.scalar("ARES/TruePose/2", 2.0)
            dispatcher.drain()
            assertFalse(pose.value.hasTruePoseData)
        }
    }
    @Test fun `vision target flag must be a finite numeric boolean`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("Vision/HasTarget", 2.0); dispatcher.drain()
            assertFalse(pose.value.visionHasTarget)
            client.scalar("Vision/HasTarget", 1.0, "true"); dispatcher.drain()
            assertFalse(pose.value.visionHasTarget)
        }
    }
    @Test fun `vision arrays reject negative and huge sparse indexes`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("Vision/HasTarget", 1.0)
            client.scalar("Vision/PoseArray/-1", 1.0)
            client.scalar("Vision/PoseArray/2147483647", 1.0)
            dispatcher.drain()
            assertTrue(pose.value.visionPoses.isEmpty())
        }
    }
    @Test fun `nonfinite vision coordinates cannot become rendered values`() = runTest {
        withClient { client, pose, dispatcher ->
            client.scalar("Vision/HasTarget", 1.0)
            client.scalar("Vision/Pose_X", Double.NaN)
            client.scalar("Vision/Pose_Y", 1.0)
            client.scalar("Vision/Pose_Heading", 2.0)
            dispatcher.drain()
            assertNull(pose.value.visionX)
        }
    }
}
