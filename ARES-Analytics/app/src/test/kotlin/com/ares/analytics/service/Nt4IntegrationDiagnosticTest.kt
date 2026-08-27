package com.ares.analytics.service

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertTrue

class Nt4IntegrationDiagnosticTest {

    @Test
    fun testNt4PipelineDiagnostic() = runBlocking {
        println("=== STARTING NT4 DIAGNOSTIC TEST ===")

        // 1. Reserve a test-only loopback port so an active robot/simulator on 5810 is untouched.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", port)
        println("NT4Server started: $server")

        // 2. Publish server topics
        NT4Server.publishTopic("Drive/Pose_X", 1.25)
        NT4Server.publishTopic("Drive/Pose_Y", 2.50)
        NT4Server.publishTopic("Drive/Pose_Heading", 0.785)
        NT4Server.publishTopic("ARES/DriverStation/TeleOpList", "[\"com.areslib.ftc.hardware.AresHardwareTestOpMode\"]")
        NT4Server.publishTopic("ARES/DriverStation/AutonomousList", "[\"com.areslib.ftc.hardware.AresHardwareTestOpMode\"]")
        NT4Server.publishTopic("Hardware/Motors/fl/Power", 0.8)
        NT4Server.publishTopic("Hardware/Motors/fr/Power", 0.8)

        println("Server published 7 initial topics")

        // 3. Instantiate DatabaseService and Nt4ClientService with temp DB
        val tempDb = File.createTempFile("ares_test_", ".duckdb")
        tempDb.deleteOnExit()
        val dbService = DatabaseService(tempDb.absolutePath)
        val clientService = Nt4ClientService(dbService)

        // 4. Connect client to 127.0.0.1
        clientService.start("127.0.0.1", "23247", "2026", "sim-robot", port)
        println("Client start requested")

        // 5. Wait for WebSocket handshake and announcements
        var waitMs = 0
        while (!clientService.isConnected.value && waitMs < 5000) {
            delay(100)
            waitMs += 100
        }
        assertTrue(clientService.isConnected.value, "Nt4ClientService failed to connect to NT4Server!")
        println("Client connected successfully! (took ${waitMs}ms)")

        // 6. Give time for topic announcements and initial binary frames
        delay(500)

        // 7. Verify active topics list
        val activeTopics = clientService.getActiveTopics()
        println("Active Topics discovered by client (${activeTopics.size}):")
        activeTopics.forEach { println("  - $it") }

        assertTrue(activeTopics.contains("Drive/Pose_X"), "Drive/Pose_X missing from active topics!")
        assertTrue(activeTopics.contains("ARES/DriverStation/TeleOpList"), "TeleOpList missing from active topics!")

        // 8. Test sending OpMode selection and command
        clientService.publishString("ARES/DriverStation/SelectedOpMode", "com.areslib.ftc.hardware.AresHardwareTestOpMode")
        clientService.publishString("ARES/DriverStation/Command", "INIT")
        delay(200)

        // Verify latest values in client
        println("Latest values in client map (${clientService.latestValues.size}):")
        clientService.latestValues.forEach { (k, v) ->
            println("  - $k -> ${v.value} (stringValue=${v.stringValue})")
        }

        val teleOpListFrame = clientService.latestValues["ARES/DriverStation/TeleOpList"]
        println("TeleOpList Frame: $teleOpListFrame")
        assertTrue(teleOpListFrame != null, "TeleOpList frame is null in client latestValues!")
        assertTrue(teleOpListFrame.stringValue?.contains("AresHardwareTestOpMode") == true, "TeleOpList content invalid!")

        println("=== NT4 DIAGNOSTIC TEST PASSED 100% ===")

        clientService.stop()
        server.stop()
    }
}
