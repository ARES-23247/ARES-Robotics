package com.ares.analytics.service

import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import com.ares.analytics.viewmodel.field.FieldTopicSubscriber
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.mockito.Mockito.mock
import kotlin.test.*

/** Local NT4 transport and field-consumer integration; no robot, OpMode or physics engine runs. */
class AppSimE2EPipelineTest {
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test fun testUnifiedAppToSimE2EPipeline() = runBlocking {
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", 0)
        try {
            awaitCondition { server.port > 0 }
            NT4Server.publishTopic("ARES/EstimatedPose/0", 1.25)
            NT4Server.publishTopic("ARES/EstimatedPose/1", 0.75)
            NT4Server.publishTopic("ARES/EstimatedPose/2", 0.50)
            NT4Server.publishTopic("Drive/Pose_X", 1.25)
            NT4Server.publishTopic("Drive/Pose_Y", 0.75)
            NT4Server.publishTopic("Drive/Pose_Heading", 0.50)
            NT4Server.publishTopic("Hardware/Motors/fl/Power", 0.85)
            val opMode = "com.areslib.ftc.hardware.AresHardwareTestOpMode"
            NT4Server.publishTopic("ARES/DriverStation/TeleOpList", "[\"$opMode\"]")

            val client = Nt4ClientService(mock(DatabaseService::class.java))
            try {
                val owner = SupervisorJob()
                val scope = CoroutineScope(Dispatchers.Default + owner)
                try {
                    val livePose = MutableStateFlow(LivePoseState())
                    FieldTopicSubscriber(client, scope, MutableStateFlow(FieldViewerState()), livePose)
                    client.start("127.0.0.1", "23247", "2026", "sim-robot", port = server.port)
                    withTimeout(5_000) { client.isConnected.first { it } }
                    awaitCondition { client.tuningConnectionId != null }

                    client.publishString("ARES/DriverStation/SelectedOpMode", opMode)
                    client.publishString("ARES/DriverStation/Command", "INIT")
                    awaitCondition { NT4Server.getString("ARES/DriverStation/Command", "") == "INIT" }
                    assertEquals(opMode, NT4Server.getString("ARES/DriverStation/SelectedOpMode", ""))
                    client.publishString("ARES/DriverStation/Command", "START")
                    awaitCondition { NT4Server.getString("ARES/DriverStation/Command", "") == "START" }

                    val flags = ((1 shl 3) or (1 shl 4) or (1 shl 5)).toDouble()
                    val neutral = doubleArrayOf(2.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, flags)
                    assertTrue(client.publishDriveFrame(neutral))
                    awaitCondition { NT4Server.getDoubleArray("ARES/Input/driveFrame", doubleArrayOf()).contentEquals(neutral) }
                    val drive = doubleArrayOf(2.0, 1.0, 1.0, 2.0, 1.5, 0.0, 0.0, flags)
                    assertTrue(client.publishDriveFrame(drive))
                    awaitCondition { NT4Server.getDoubleArray("ARES/Input/driveFrame", doubleArrayOf()).contentEquals(drive) }

                    val pose = withTimeout(5_000) {
                        livePose.first { it.ekfX == 1.25 && it.ekfY == 0.75 && it.ekfHeading == 0.50 }
                    }
                    awaitCondition { client.latestValues["Hardware/Motors/fl/Power"]?.value == 0.85 }
                    assertEquals(1.25, pose.ekfX ?: 0.0, 1e-3)
                    assertEquals(0.75, pose.ekfY ?: 0.0, 1e-3)
                    assertEquals(0.50, pose.ekfHeading ?: 0.0, 1e-3)
                    assertEquals(0.85, client.latestValues["Hardware/Motors/fl/Power"]?.value ?: 0.0, 1e-3)
                } finally {
                    withContext(NonCancellable) { owner.cancelAndJoin() }
                }
            } finally {
                withContext(NonCancellable) { assertTrue(client.disposeAndJoin()) }
            }
        } finally {
            // Only this test's server is stopped; no fixed port or external process is owned here.
            server.stop()
            NT4Server.resetSharedState()
        }
    }
}
