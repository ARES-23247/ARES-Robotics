package com.areslib.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import fi.iki.elonen.NanoHTTPD

class LogManagerServerTest {

    @BeforeEach
    fun setUp() {
        LogManagerServer.configureDeleteToken(null)
        LogManagerServer.startServer()
        assertTrue(LogManagerServer.isAlive, "Log server could not bind test port 5002")
    }

    @AfterEach
    fun tearDown() {
        LogManagerServer.configureDeleteToken(null)
        LogManagerServer.stop()
    }

    @Test
    fun testServerEndpoints() {

        // Test root endpoint (Dashboard)
        val conn = awaitGet("/")
        assertEquals(200, conn.responseCode)
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(text.contains("ARES Telemetry Log Portal") || text.contains("Log"), "Response should be a dashboard page")

        // Test API Logs endpoint
        val apiConn = awaitGet("/api/logs")
        assertEquals(200, apiConn.responseCode)
        val apiText = apiConn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(apiText.startsWith("["), "Response should be a JSON array")
    }

    private fun awaitGet(path: String): HttpURLConnection {
        var lastFailure: Exception? = null
        repeat(100) {
            val connection = URL("http://127.0.0.1:5002$path").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 250
            connection.readTimeout = 1_000
            try {
                connection.responseCode
                return connection
            } catch (failure: Exception) {
                lastFailure = failure
                connection.disconnect()
                Thread.sleep(20L)
            }
        }
        throw AssertionError("LogManagerServer did not become reachable at $path", lastFailure)
    }

    @Test
    fun `delete is disabled by default and requires configured bearer token`() {
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

            var listing = ""
            var listingPollsRemaining = 50
            while (!listing.contains(completed.name) && listingPollsRemaining > 0) {
                val listConnection = URL("http://localhost:5002/api/logs").openConnection() as HttpURLConnection
                assertEquals(200, listConnection.responseCode)
                listing = listConnection.inputStream.bufferedReader().use { it.readText() }
                listConnection.disconnect()
                if (!listing.contains(completed.name)) Thread.sleep(20L)
                listingPollsRemaining--
            }
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

    @Test
    fun `concurrent starts retain one usable listener and support restart`() {
        LogManagerServer.stop()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { pool.submit(Callable { start.await(); LogManagerServer.startServer() }) }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(LogManagerServer.isAlive)
            val first = awaitGet("/api/logs")
            try { assertEquals(200, first.responseCode); first.inputStream.close() } finally { first.disconnect() }
            LogManagerServer.stop()
            LogManagerServer.startServer()
            val restarted = awaitGet("/api/logs")
            try { assertEquals(200, restarted.responseCode); restarted.inputStream.close() } finally { restarted.disconnect() }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `restart after listener failure closes the previous connection workers`() {
        val oldWorkers = LogManagerServer.javaClass.getDeclaredField("requestWorkers")
            .apply { isAccessible = true }.get(LogManagerServer) as LogServerWorkers
        val pool = LogServerWorkers::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(oldWorkers) as ThreadPoolExecutor
        try {
            Socket("127.0.0.1", 5002).use {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (pool.activeCount != 1 && System.nanoTime() - deadline < 0) Thread.sleep(5)
                assertEquals(1, pool.activeCount)
                (NanoHTTPD::class.java.getDeclaredField("myServerSocket").apply { isAccessible = true }
                    .get(LogManagerServer) as ServerSocket).close()
                (NanoHTTPD::class.java.getDeclaredField("myThread").apply { isAccessible = true }
                    .get(LogManagerServer) as Thread).join(2000)
                assertFalse(LogManagerServer.isAlive)
                LogManagerServer.startServer()
                assertTrue(LogManagerServer.isAlive)
                assertTrue(pool.isShutdown, "A replacement listener must not orphan the old worker pool")
                assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
                val connection = awaitGet("/api/logs")
                try { assertEquals(200, connection.responseCode); connection.inputStream.close() }
                finally { connection.disconnect() }
            }
        } finally { oldWorkers.closeAll() }
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
