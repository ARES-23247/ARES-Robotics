package com.ares.analytics.service

import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.field.FieldPoseBufferManager
import com.ares.analytics.viewmodel.field.FieldTopicSubscriber
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

class AppSimE2EPipelineTest {

    @Test
    fun testUnifiedAppToSimE2EPipeline() {
        runBlocking {
            println("=== E2E PIPELINE: Starting NT4Server on 127.0.0.1:5818... ===")

            val server = NT4Instance.defaultInstance.startServer("127.0.0.1", 5818)
            assertNotNull(server)

            NT4Server.publishTopic("ARES/EstimatedPose/0", 1.25)
            NT4Server.publishTopic("ARES/EstimatedPose/1", 0.75)
            NT4Server.publishTopic("ARES/EstimatedPose/2", 0.50)
            NT4Server.publishTopic("Drive/Pose_X", 1.25)
            NT4Server.publishTopic("Drive/Pose_Y", 0.75)
            NT4Server.publishTopic("Drive/Pose_Heading", 0.50)
            NT4Server.publishTopic("Hardware/Motors/fl/Power", 0.85)
            NT4Server.publishTopic("ARES/DriverStation/TeleOpList", "[\"com.areslib.ftc.hardware.AresHardwareTestOpMode\"]")

            val tempDb = File.createTempFile("ares_test_e2e_", ".duckdb")
            tempDb.deleteOnExit()
            val dbService = DatabaseService(tempDb.absolutePath)
            val clientService = Nt4ClientService(dbService)

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val stateFlow = MutableStateFlow(FieldViewerState())
            val livePoseFlow = MutableStateFlow(com.ares.analytics.viewmodel.LivePoseState())
            val topicSubscriber = FieldTopicSubscriber(clientService, scope, stateFlow, livePoseFlow)
            val bufferManager = FieldPoseBufferManager(scope, stateFlow, livePoseFlow)

            clientService.start("127.0.0.1", "23247", "2026", "sim-robot", port = 5818)

            var waitMs = 0
            while (!clientService.isConnected.value && waitMs < 5000) {
                delay(100)
                waitMs += 100
            }
            assertTrue(clientService.isConnected.value, "Nt4ClientService should connect to NT4Server on port 5818")
            println("[E2E Pipeline] Client connected successfully! (took ${waitMs}ms)")

            delay(500)

            clientService.publishString("ARES/DriverStation/SelectedOpMode", "com.areslib.ftc.hardware.AresHardwareTestOpMode")
            clientService.publishString("ARES/DriverStation/Command", "INIT")
            delay(200)

            clientService.publishString("ARES/DriverStation/Command", "START")
            delay(200)

            println("[E2E Pipeline] Injecting joystick drive input (vx = 1.5 m/s)...")
            val controlFlags = (1 shl 3) or (1 shl 4) or (1 shl 5)
            var neutralSent = false
            repeat(100) {
                if (!neutralSent) {
                    neutralSent = clientService.publishDriveFrame(
                        doubleArrayOf(2.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, controlFlags.toDouble())
                    )
                    if (!neutralSent) delay(20)
                }
            }
            assertTrue(neutralSent, "clock sync must complete before the neutral handshake is accepted")
            assertTrue(clientService.publishDriveFrame(
                doubleArrayOf(2.0, 1.0, 1.0, 2.0, 1.5, 0.0, 0.0, controlFlags.toDouble())
            ))

            delay(300)

            val currentState = livePoseFlow.value
            val flPower = clientService.latestValues["Hardware/Motors/fl/Power"]?.value ?: 0.0

            println("[E2E Pipeline] Current State Pose: ekfX=${currentState.ekfX}, ekfY=${currentState.ekfY}, FL Power: $flPower")

            assertEquals(1.25, currentState.ekfX ?: 0.0, 1e-3)
            assertEquals(0.75, currentState.ekfY ?: 0.0, 1e-3)
            assertEquals(0.50, currentState.ekfHeading ?: 0.0, 1e-3)
            assertEquals(0.85, flPower, 1e-3)

            println("=== E2E PIPELINE TEST PASSED 100% ===")
            clientService.stop()
            server.stop()
            tempDb.delete()
        }
    }
}
