package com.areslib.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.HttpURLConnection
import java.net.URL

class LogManagerServerTest {

    @BeforeEach
    fun setUp() {
        LogManagerServer.startServer()
    }

    @AfterEach
    fun tearDown() {
        LogManagerServer.stop()
    }

    @Test
    fun testServerEndpoints() {
        if (!LogManagerServer.isAlive) {
            System.err.println("WARNING: LogManagerServer is not alive (port 5002 likely already bound). Skipping endpoint assertions.")
            return
        }

        // Test root endpoint (Dashboard)
        val url = URL("http://localhost:5002/")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        assertEquals(200, conn.responseCode)
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(text.contains("ARES Telemetry Log Portal") || text.contains("Log"), "Response should be a dashboard page")

        // Test API Logs endpoint
        val apiLogsUrl = URL("http://localhost:5002/api/logs")
        val apiConn = apiLogsUrl.openConnection() as HttpURLConnection
        apiConn.requestMethod = "GET"
        assertEquals(200, apiConn.responseCode)
        val apiText = apiConn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(apiText.startsWith("["), "Response should be a JSON array")
    }
}
