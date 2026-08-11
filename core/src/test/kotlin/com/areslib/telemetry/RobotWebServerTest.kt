package com.areslib.telemetry

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.Socket

class RobotWebServerTest {
    @Test
    fun testStatusEndpointWithVision() {
        // Configure tracker
        RobotStatusTracker.isEnabled = true
        RobotStatusTracker.activeOpMode = "Autonomous"
        RobotStatusTracker.visionConnected = true
        RobotStatusTracker.visionStatus = "ACCEPTED"
        RobotStatusTracker.activeLimelightIps = listOf("172.29.11.7")

        // Start server on a test port
        val testPort = 18082
        RobotWebServer.start(testPort)

        try {
            val url = URL("http://localhost:$testPort/api/status")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            assertEquals(200, connection.responseCode)
            val responseText = connection.inputStream.bufferedReader().readText()
            
            // Check that response contains the expected fields
            assertTrue(responseText.contains("\"enabled\": true"), "Response should contain enabled: true")
            assertTrue(responseText.contains("\"opMode\": \"Autonomous\""), "Response should contain opMode: Autonomous")
            assertTrue(responseText.contains("\"vision\""), "Response should contain vision block")
            assertTrue(responseText.contains("\"connected\": true"), "Response should contain connected: true")
            assertTrue(responseText.contains("\"status\": \"ACCEPTED\""), "Response should contain status: ACCEPTED")
            assertTrue(responseText.contains("\"streamUrl\": \"http://localhost:5800\"") || responseText.contains("\"streamUrl\": \"http://127.0.0.1:5800\""), "Response should contain streamUrl")
        } finally {
            RobotWebServer.stop()
        }
    }

    @Test
    fun `slow clients cannot create an unbounded worker pool`() {
        val testPort = 18083
        val beforeRejected = RobotWebServer.rejectedConnectionCount()
        RobotWebServer.start(testPort)
        val clients = mutableListOf<Socket>()
        try {
            repeat(RobotWebServer.MAX_WORKERS + RobotWebServer.MAX_QUEUED_CLIENTS + 8) {
                clients += Socket("127.0.0.1", testPort)
            }
            val deadline = System.currentTimeMillis() + 2_000L
            while (RobotWebServer.rejectedConnectionCount() == beforeRejected && System.currentTimeMillis() < deadline) {
                Thread.yield()
            }
            assertTrue(RobotWebServer.rejectedConnectionCount() > beforeRejected)
            val workers = Thread.getAllStackTraces().keys.count { it.name == "ARES-WebServer-Worker" }
            assertTrue(workers <= RobotWebServer.MAX_WORKERS)
        } finally {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            RobotWebServer.stop()
        }
    }
}
