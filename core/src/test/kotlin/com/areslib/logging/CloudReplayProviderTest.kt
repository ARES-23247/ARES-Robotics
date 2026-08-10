package com.areslib.logging

import com.areslib.logging.CloudExporter
import fi.iki.elonen.NanoHTTPD
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CloudReplayProviderTest {

    private lateinit var server: NanoHTTPD
    private var originalUrl = ""

    @BeforeEach
    fun setUp() {
        originalUrl = CloudExporter.areswebServerUrl
        // Spin up a mock server on port 58083
        server = object : NanoHTTPD(58083) {
            override fun serve(session: IHTTPSession): Response {
                System.err.println("MOCK_SERVER_URI: " + session.uri)
                return if (session.uri.contains("test_run_123")) {
                    val frameJson = """{"timestampMs":1000,"odometryInputs":{"posX":0.0,"posY":0.0,"heading":0.0,"velX":0.0,"velY":0.0,"headingVelocity":0.0},"imuInputs":{"pitchRadians":0.0,"rollRadians":0.0},"visionInputs":{"measurements":[]}}"""
                    newFixedLengthResponse(Response.Status.OK, "application/json", frameJson)
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            }
        }
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        CloudExporter.areswebServerUrl = "http://localhost:58083"
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        CloudExporter.areswebServerUrl = originalUrl
    }

    @Test
    fun testLoadRunFromMockCloud() {
        System.err.println("TEST_SERVER_URL: " + CloudExporter.areswebServerUrl)
        val summary = CloudReplayProvider.loadRun("test_run_123")
        assertNotNull(summary)
        assertEquals(1, summary.steps.size)
        assertEquals(1000L, summary.steps[0].timestampMs)
    }

    @Test
    fun testLoadRunNotFound() {
        val summary = CloudReplayProvider.loadRun("invalid_run")
        assertNotNull(summary)
        assertTrue(summary.steps.isEmpty())
    }
}
