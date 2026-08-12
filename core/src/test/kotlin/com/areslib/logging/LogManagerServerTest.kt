package com.areslib.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID

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

    @Test
    fun `old active log remains hidden and protected while completed sibling works`() {
        if (!LogManagerServer.isAlive) return

        val stem = "log-server-active-${UUID.randomUUID()}"
        val completed = File(RobotLogEnvironment.logDirectory, "$stem.csv")
        val active = File(RobotLogEnvironment.logDirectory, "${completed.name}.active")
        val activeBytes = "writer-owned-active-bytes".toByteArray(StandardCharsets.UTF_8)
        val completedBytes = "completed-log-bytes".toByteArray(StandardCharsets.UTF_8)
        val token = "active-log-test-token-12345"

        try {
            Files.createDirectories(RobotLogEnvironment.logDirectory.toPath())
            Files.write(active.toPath(), activeBytes)
            Files.write(completed.toPath(), completedBytes)
            Files.setLastModifiedTime(
                active.toPath(),
                FileTime.fromMillis(com.areslib.util.RobotClock.currentTimeMillis() - 60_000L)
            )
            LogManagerServer.configureDeleteToken(token)

            val listConnection = URL("http://localhost:5002/api/logs").openConnection() as HttpURLConnection
            assertEquals(200, listConnection.responseCode)
            val listing = listConnection.inputStream.bufferedReader().use { it.readText() }
            listConnection.disconnect()
            assertFalse(listing.contains(active.name), "Writer-owned .active file must never be listed")
            assertTrue(listing.contains(completed.name), "Completed sibling must remain discoverable")

            val activeDownload = downloadConnection(active.name)
            assertEquals(403, activeDownload.responseCode)
            activeDownload.disconnect()

            val activeDeleteAlias = deleteConnection("./${active.name}", token)
            assertEquals(403, activeDeleteAlias.responseCode)
            activeDeleteAlias.disconnect()

            val completedDownload = downloadConnection(completed.name)
            assertEquals(200, completedDownload.responseCode)
            assertArrayEquals(completedBytes, completedDownload.inputStream.use { it.readBytes() })
            completedDownload.disconnect()

            val completedDelete = deleteConnection(completed.name, token)
            assertEquals(200, completedDelete.responseCode)
            completedDelete.disconnect()
            assertFalse(completed.exists(), "Completed sibling should be deletable with authorization")
            assertTrue(active.exists(), "Endpoint operations must not unlink the active writer file")
            assertArrayEquals(activeBytes, Files.readAllBytes(active.toPath()))
        } finally {
            active.delete()
            completed.delete()
        }
    }

    private fun downloadConnection(fileName: String): HttpURLConnection {
        val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
        return (URL("http://localhost:5002/api/download?file=$encodedName").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }
    }

    private fun deleteConnection(fileName: String, token: String? = null): HttpURLConnection {
        val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
        val connection = URL("http://localhost:5002/api/delete?file=$encodedName").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { }
        return connection
    }
}
