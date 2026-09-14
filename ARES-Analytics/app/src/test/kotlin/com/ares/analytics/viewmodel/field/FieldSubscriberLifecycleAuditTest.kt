package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.mock
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FieldSubscriberLifecycleAuditTest {
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        fun drainReverse() { while (queue.isNotEmpty()) queue.removeLast().run() }
    }
    private class Client(val connected: MutableStateFlow<Boolean>) : Nt4ClientService(mock(DatabaseService::class.java)) {
        override val isConnected get() = connected
    }
    private class Fixture(parent: CoroutineScope) {
        val client = Client(MutableStateFlow(true))
        val ownerDispatcher = HeldDispatcher()
        val processing = HeldDispatcher()
        val lifetime = SupervisorJob(parent.coroutineContext[Job])
        val owner = CoroutineScope(parent.coroutineContext + lifetime + ownerDispatcher)
        fun view(): MutableStateFlow<LivePoseState> = MutableStateFlow(LivePoseState()).also {
            FieldTopicSubscriber(client, owner, MutableStateFlow(FieldViewerState()), it, processing)
        }
        fun drain() { ownerDispatcher.drain(); processing.drain(); ownerDispatcher.drain(); processing.drain() }
        suspend fun publish(id: Int, payload: String, time: Long = 1000) {
            client.handleIncomingText("""[{"topic":$id,"time":$time,"value":$payload}]""", "team", "season", "robot")
        }
        suspend fun scalar(key: String, value: Double, text: String? = null, time: Long = 1000) {
            client.telemetryStore.accept(TelemetryFrame(time / 1000, "live", key, value, text, time))
        }
        suspend fun legacy() { publish(2, "[1,2,0,1,0,0,0]"); drain() }
        suspend fun overflow() { repeat(5000) { scalar("Unrelated/Noise", it.toDouble()) } }
        suspend fun announce() {
            client.handleIncomingText("""[
                {"method":"announce","params":{"name":"/ARES/SimulatorPoseFrame","id":1,"type":"double[]"}},
                {"method":"announce","params":{"name":"/ARES/GamePieces","id":2,"type":"double[]"}},
                {"method":"announce","params":{"name":"/ARES/GamePiecesFrame","id":3,"type":"double[]"}},
                {"method":"announce","params":{"name":"/Vision/PoseArray","id":4,"type":"double[]"}},
                {"method":"announce","params":{"name":"/Vision/HasTarget","id":5,"type":"boolean"}},
                {"method":"announce","params":{"name":"/Vision/Pose_X","id":6,"type":"double"}},
                {"method":"announce","params":{"name":"/Vision/Pose_Y","id":7,"type":"double"}},
                {"method":"announce","params":{"name":"/Vision/Pose_Heading","id":8,"type":"double"}}
            ]""", "team", "season", "robot")
        }
        suspend fun close() = withContext(NonCancellable) {
            lifetime.cancel(); drain(); lifetime.join()
            assertTrue(client.disposeAndJoin())
        }
    }
    private suspend fun TestScope.fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture(backgroundScope)
        try { f.announce(); f.block() } finally { f.close() }
    }

    @Test fun `late owner startup cannot erase cached packed pose`() = runTest {
        fixture {
            publish(1, "[1,2,0.3,4,5,0.6,7,8,0.9,10]")
            val pose = view()
            processing.drain(); ownerDispatcher.drain(); processing.drain()
            assertTrue(pose.value.hasTruePoseData)
            assertEquals(4.0, pose.value.ekfX)
        }
    }

    @Test fun `late owner startup cannot erase cached legacy parent`() = runTest {
        fixture {
            publish(2, "[1,2,0,1,0,0,0]")
            val pose = view()
            processing.drain(); ownerDispatcher.drain(); processing.drain()
            assertEquals(2.0, pose.value.liveGamePieces.getValue(0).y)
        }
    }

    @Test fun `text typed header claims source and clears legacy layer`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            scalar("ARES/GamePiecesFrame/0", 2.0, "2"); drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
            publish(2, "[9,9,0,1,0,0,0]", 2000); drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }

    @Test fun `nonfinite typed header claims source and clears legacy layer`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            scalar("ARES/GamePiecesFrame/0", Double.NaN); drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }

    @Test fun `incomplete first typed header cannot display a legacy piece`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            scalar("ARES/GamePiecesFrame/0", 2.0); drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }

    @Test fun `new view restores vision target after raw replay cache overflow`() = runTest {
        fixture {
            publish(5, "true"); publish(4, "[1,2,0.3]"); overflow()
            val pose = view(); drain()
            assertTrue(pose.value.visionHasTarget)
            assertEquals(setOf(0,1,2), pose.value.visionPoses.keys)
        }
    }

    @Test fun `active view receives target flag despite raw bus overflow`() = runTest {
        fixture {
            val pose = view(); drain()
            publish(5, "true"); publish(4, "[1,2,0.3]"); overflow(); drain()
            assertTrue(pose.value.visionHasTarget)
            assertEquals(setOf(0,1,2), pose.value.visionPoses.keys)
        }
    }

    @Test fun `conflated target loss cannot revive earlier scalar vision coordinates`() = runTest {
        fixture {
            val pose = view(); drain()
            publish(5, "true"); drain()
            publish(6, "1"); publish(7, "2"); publish(8, "0.3"); drain()
            assertEquals(1.0, pose.value.visionX)
            publish(5, "false", 2000); publish(5, "true", 3000); drain()
            assertNull(pose.value.visionX)
            assertNull(pose.value.visionY)
            assertNull(pose.value.visionHeading)
        }
    }

    @Test fun `cached sources initialize when owner dispatcher runs first`() = runTest {
        fixture {
            publish(1, "[1,2,0.3,4,5,0.6,7,8,0.9,10]")
            publish(3, "[2,1,101,202,1,2,0.3,0.3,0.1,1,65280,7]")
            publish(5, "true"); publish(4, "[1,2,0.3]")
            val pose = view(); drain()
            assertEquals(4.0, pose.value.ekfX)
            assertEquals("sim-101", pose.value.liveGamePieces.getValue(0).id)
            assertTrue(pose.value.visionHasTarget)
            assertEquals(setOf(0,1,2), pose.value.visionPoses.keys)
        }
    }

    @Test fun `replay clears live layers and returning live restores last published parents`() = runTest {
        fixture {
            val pose = view(); drain()
            publish(1, "[1,2,0.3,4,5,0.6,7,8,0.9,10]")
            publish(2, "[1,2,0,1,0,0,0]")
            publish(5, "true"); publish(4, "[1,2,0.3]"); drain()
            client.isReplayActive.value = true
            publish(1, "[9,9,0.3,9,9,0.6,9,9,0.9,11]", 2000)
            drain()
            assertFalse(pose.value.hasTruePoseData)
            assertNull(pose.value.ekfX)
            assertFalse(pose.value.visionHasTarget)
            assertTrue(pose.value.visionPoses.isEmpty())
            assertTrue(pose.value.liveGamePieces.isEmpty())
            client.isReplayActive.value = false; drain()
            // Existing published snapshots are latched observations, not a freshness promise.
            assertEquals(1.0, pose.value.trueX)
            assertEquals(4.0, pose.value.ekfX)
            assertEquals(2.0, pose.value.liveGamePieces.getValue(0).y)
            assertTrue(pose.value.visionHasTarget)
            assertEquals(setOf(0,1,2), pose.value.visionPoses.keys)
        }
    }

    @Test fun `target switch releases typed ownership and resets cached status`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            scalar("ARES/GamePiecesFrame/0", Double.NaN); drain()
            assertTrue(pose.value.liveGamePieces.isEmpty())
            publish(5, "true"); publish(4, "[1,2,0.3]"); drain()
            client.clearLiveTargetState(); drain()
            assertFalse(pose.value.visionHasTarget)
            assertTrue(pose.value.liveGamePieces.isEmpty())
            assertNull(client.visionTargetFrame.value)
            announce()
            publish(2, "[3,4,0,1,0,0,0]")
            publish(5, "true"); publish(4, "[5,6,0.7]"); drain()
            assertEquals(4.0, pose.value.liveGamePieces.getValue(0).y)
            assertEquals(5.0, pose.value.visionPoses.getValue(0))
        }
    }

    @Test fun `disconnect clears parents until a new observation arrives`() = runTest {
        fixture {
            val pose = view(); drain()
            publish(1, "[1,2,0.3,4,5,0.6,7,8,0.9,10]"); legacy()
            client.connected.value = false; drain()
            assertFalse(pose.value.isConnected)
            assertFalse(pose.value.hasTruePoseData)
            assertNull(pose.value.ekfX)
            assertTrue(pose.value.liveGamePieces.isEmpty())
            client.connected.value = true; drain()
            assertTrue(pose.value.isConnected)
            assertFalse(pose.value.hasTruePoseData)
            assertTrue(pose.value.liveGamePieces.isEmpty())
            publish(1, "[3,4,0.3,5,6,0.6,7,8,0.9,11]", 2000)
            publish(2, "[3,4,0,1,0,0,0]", 2000); drain()
            assertEquals(5.0, pose.value.ekfX)
            assertEquals(4.0, pose.value.liveGamePieces.getValue(0).y)
        }
    }

    @Test fun `cancelling view ownership stops every field collector`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            val before = pose.value
            lifetime.cancel(); drain(); lifetime.join()
            publish(2, "[3,4,0,1,0,0,0]", 2000)
            publish(5, "true"); publish(4, "[5,6,0.7]"); drain()
            assertEquals(4.0, client.legacyGamePieceFrame.value?.pieces?.getValue(0)?.y)
            assertSame(before, pose.value)
        }
    }

    @Test fun `raw typed decoder recovers after invalid source takeover`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            scalar("ARES/GamePiecesFrame/0", 2.0, "2"); drain()
            val values = listOf(2.0,1.0,101.0,202.0,3.0,4.0,0.3,0.3,0.1,1.0,65280.0,7.0)
            values.forEachIndexed { index, value -> scalar("ARES/GamePiecesFrame/$index", value, time = 2000); drain() }
            assertEquals("sim-101", pose.value.liveGamePieces.getValue(0).id)
            assertEquals(4.0, pose.value.liveGamePieces.getValue(0).y)
            val complete = pose.value
            scalar("ARES/GamePiecesFrame/0", 2.0, time = 3000); drain()
            scalar("ARES/GamePiecesFrame/1", 1.0, time = 3000); drain()
            scalar("ARES/GamePiecesFrame/2", 0.0, "bad", 3000); drain()
            assertSame(complete, pose.value)
            listOf(2.0,0.0,8.0).forEachIndexed { index, value ->
                scalar("ARES/GamePiecesFrame/$index", value, time = 4000); drain()
            }
            assertTrue(pose.value.liveGamePieces.isEmpty())
        }
    }

    @Test fun `unrelated and unchanged observations retain the same rendered state`() = runTest {
        fixture {
            val pose = view(); drain(); legacy()
            publish(5, "true"); publish(4, "[1,2,0.3]"); drain()
            val before = pose.value
            overflow(); drain()
            assertSame(before, pose.value)
            publish(2, "[1,2,0,1,0,0,0]", 2000)
            publish(4, "[1,2,0.3]", 2000); drain()
            assertSame(before, pose.value)
        }
    }

    @Test fun `lighting is projected from shared state and masked during replay`() = runTest {
        fixture {
            val pose = view(); drain()
            scalar("Subsystems/lights/AppliedOutputs/status/INDICATOR_LIGHT", 1.0)
            scalar("Subsystems/lights/AppliedOutputs/rgb/PRISM_DRIVER", 7.0)
            withContext(Dispatchers.IO) {
                withTimeout(5000) { client.robotLighting.first { it.outputs.size == 2 } }
            }
            drain()
            assertEquals(mapOf("lights/status" to 1.0), pose.value.indicatorLights)
            assertEquals(mapOf("lights/rgb" to 7.0), pose.value.prismLights)
            client.isReplayActive.value = true; drain()
            assertTrue(pose.value.indicatorLights.isEmpty())
            assertTrue(pose.value.prismLights.isEmpty())
            client.isReplayActive.value = false; drain()
            assertEquals(mapOf("lights/status" to 1.0), pose.value.indicatorLights)
            assertEquals(mapOf("lights/rgb" to 7.0), pose.value.prismLights)
        }
    }

    @Test fun `vision target cache survives unrelated topic eviction`() = runTest {
        fixture {
            publish(5, "true"); publish(4, "[1,2,0.3]")
            repeat(5000) { scalar("Unrelated/Topic$it", it.toDouble()) }
            assertNull(client.telemetryStore.latest("Vision/HasTarget"))
            val pose = view(); drain()
            assertTrue(pose.value.visionHasTarget)
            assertEquals(2.0, pose.value.visionPoses.getValue(1))
        }
    }

    @Test fun `text target flag clears vision and requires fresh scalar data`() = runTest {
        fixture {
            val pose = view(); drain()
            publish(5, "true"); publish(4, "[1,2,0.3]"); drain()
            publish(5, "\"true\"", 2000); drain()
            assertFalse(pose.value.visionHasTarget)
            assertTrue(pose.value.visionPoses.isEmpty())
            publish(5, "true", 3000); drain()
            assertTrue(pose.value.visionHasTarget)
            assertNull(pose.value.visionX)
            assertTrue(pose.value.visionPoses.isEmpty())
            publish(6, "4", 3000); publish(7, "5", 3000); publish(8, "0.6", 3000); drain()
            assertEquals(4.0, pose.value.visionX)
            assertEquals(5.0, pose.value.visionY)
        }
    }

    @Test fun `scalar samples from before reacquisition cannot complete a new target`() = runTest {
        fixture {
            val pose = view(); drain()
            publish(5, "true", 3000); drain()
            publish(6, "1", 1000); publish(7, "2", 1000); publish(8, "0.3", 1000); drain()
            assertNull(pose.value.visionX)
            assertNull(pose.value.visionY)
            publish(6, "4", 3000); publish(7, "5", 3000); publish(8, "0.6", 3000); drain()
            assertEquals(4.0, pose.value.visionX)
        }
    }

    @Test fun `replay recording never publishes live vision target flags`() = runTest {
        fixture {
            client.isReplayActive.value = true
            publish(5, "false"); publish(5, "true", 2000)
            assertNull(client.visionTargetFrame.value)
            val pose = view(); drain()
            assertFalse(pose.value.visionHasTarget)
            client.isReplayActive.value = false; drain()
            assertFalse(pose.value.visionHasTarget)
            publish(5, "true", 3000); publish(4, "[1,2,0.3]", 3000); drain()
            assertTrue(pose.value.visionHasTarget)
            assertEquals(setOf(0,1,2), pose.value.visionPoses.keys)
        }
    }

    @Test fun `new view inherits cached scalar pose after raw replay cache overflow`() = runTest {
        fixture {
            scalar("Drive/Pose_X", 1.0); scalar("Drive/Pose_Y", 2.0); scalar("Drive/Pose_Heading", 0.3)
            scalar("Drive/Odom_X", 4.0); scalar("Drive/Odom_Y", 5.0); scalar("Drive/Odom_Heading", 0.6)
            overflow()
            val pose = view(); drain()
            assertEquals(1.0, pose.value.ekfX)
            assertEquals(2.0, pose.value.ekfY)
            assertEquals(0.3, pose.value.ekfHeading)
            assertEquals(4.0, pose.value.odomX)
            assertEquals(5.0, pose.value.odomY)
        }
    }

    @Test fun `active view receives scalar pose despite raw bus overflow`() = runTest {
        fixture {
            val pose = view(); drain()
            scalar("Drive/Pose_X", 1.0); scalar("Drive/Pose_Y", 2.0); scalar("Drive/Pose_Heading", 0.3)
            overflow(); drain()
            assertEquals(1.0, pose.value.ekfX)
            assertEquals(2.0, pose.value.ekfY)
            assertEquals(0.3, pose.value.ekfHeading)
        }
    }

    @Test fun `new view inherits complete scalar vision after raw replay cache overflow`() = runTest {
        fixture {
            scalar("Vision/HasTarget", 1.0)
            scalar("Vision/Pose_X", 1.0); scalar("Vision/Pose_Y", 2.0); scalar("Vision/Pose_Heading", 0.3)
            overflow()
            val pose = view(); drain()
            assertTrue(pose.value.visionHasTarget)
            assertEquals(1.0, pose.value.visionX)
            assertEquals(2.0, pose.value.visionY)
            assertEquals(0.3, pose.value.visionHeading)
        }
    }

    @Test fun `reverse collector startup restores complete scalar vision`() = runTest {
        fixture {
            scalar("Vision/HasTarget", 1.0)
            scalar("Vision/Pose_X", 1.0); scalar("Vision/Pose_Y", 2.0); scalar("Vision/Pose_Heading", 0.3)
            overflow()
            val pose = view()
            processing.drainReverse(); ownerDispatcher.drain(); processing.drainReverse()
            assertTrue(pose.value.visionHasTarget)
            assertEquals(1.0, pose.value.visionX)
            assertEquals(2.0, pose.value.visionY)
            assertEquals(0.3, pose.value.visionHeading)
        }
    }

    @Test fun `view recreation reuses a bounded set of scalar observers`() = runTest {
        fixture {
            view(); drain()
            val count = client.telemetryStore.topicObserverCount
            assertTrue(count in 1..20)
            repeat(5) { view(); drain() }
            assertEquals(count, client.telemetryStore.topicObserverCount)
        }
    }

    @Test fun `returning live restores notified scalar cache without exposing silent replay inputs`() = runTest {
        fixture {
            val pose = view(); drain()
            scalar("Drive/Pose_X", 1.0); scalar("Drive/Pose_Y", 2.0); scalar("Drive/Pose_Heading", 0.3); drain()
            client.isReplayActive.value = true; drain()
            assertNull(pose.value.ekfX)
            client.telemetryStore.accept(TelemetryFrame(2, "live", "Drive/Pose_X", 99.0), notifyConsumers = false)
            client.isReplayActive.value = false; drain()
            assertEquals(1.0, pose.value.ekfX)
            assertEquals(2.0, pose.value.ekfY)
            assertEquals(0.3, pose.value.ekfHeading)
        }
    }

    @Test fun `disconnect rejects queued cached scalar coordinates until fresh complete pose`() = runTest {
        fixture {
            val pose = view(); drain()
            scalar("Drive/Pose_X", 1.0); scalar("Drive/Pose_Y", 2.0); scalar("Drive/Pose_Heading", 0.3); drain()
            scalar("Drive/Pose_X", 9.0, time = 2000)
            client.connected.value = false; drain()
            assertNull(pose.value.ekfX)
            client.connected.value = true; drain()
            assertNull(pose.value.ekfX)
            scalar("Drive/Pose_X", 3.0, time = 3000); drain()
            assertNull(pose.value.ekfX)
            scalar("Drive/Pose_Y", 4.0, time = 3000)
            scalar("Drive/Pose_Heading", 0.5, time = 3000); drain()
            assertEquals(3.0, pose.value.ekfX)
            assertEquals(4.0, pose.value.ekfY)
        }
    }
}
