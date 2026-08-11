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
        LogManagerServer.configureDeleteToken(null)
        LogManagerServer.startServer()
    }

    @AfterEach
    fun tearDown() {
        LogManagerServer.configureDeleteToken(null)
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

    @Test
    fun `delete is disabled by default and requires configured bearer token`() {
        if (!LogManagerServer.isAlive) return
        val disabled = deleteConnection("missing.jsonl")
        assertEquals(403, disabled.responseCode)

        val token = "test-delete-token-12345"
        LogManagerServer.configureDeleteToken(token)
        val unauthorized = deleteConnection("missing.jsonl")
        assertEquals(401, unauthorized.responseCode)

        val authorized = deleteConnection("missing.jsonl", token)
        assertEquals(404, authorized.responseCode, "Authorized request should reach file validation")
    }

    @Test
    fun `weak delete tokens are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LogManagerServer.configureDeleteToken("short")
        }
    }

    private fun deleteConnection(fileName: String, token: String? = null): HttpURLConnection {
        val connection = URL("http://localhost:5002/api/delete?file=$fileName").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { }
        return connection
    }
}
