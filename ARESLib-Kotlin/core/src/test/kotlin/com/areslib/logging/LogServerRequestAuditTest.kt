package com.areslib.logging

import com.areslib.util.RobotClock
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogServerRequestAuditTest {
    private val owned = mutableListOf<File>()
    private val stem = "server-audit-${UUID.randomUUID()}"
    private val token = "audit-only-delete-token-230"

    @BeforeEach fun reset() { clearLimits(); LogManagerServer.configureDeleteToken(null) }
    @AfterEach fun cleanup() {
        owned.forEach { it.delete() }
        LogManagerServer.configureDeleteToken(null)
        clearLimits()
        RobotClock.useSystemTime()
    }
    private fun clearLimits() {
        val field = LogManagerServer.javaClass.getDeclaredField("rateLimiters").apply { isAccessible = true }
        val limiter = field.get(LogManagerServer)
        limiter.javaClass.getMethod("clear").invoke(limiter)
    }
    private data class Result(val status: Int, val mime: String, val bytes: ByteArray) {
        val text get() = bytes.toString(Charsets.UTF_8)
    }
    private fun request(
        path: String = "/missing", method: NanoHTTPD.Method = NanoHTTPD.Method.GET,
        params: Map<String, List<String>> = emptyMap(), headers: Map<String, String> = emptyMap(),
        ip: String = UUID.randomUUID().toString(), parseBody: () -> Unit = {}
    ): Result {
        val session = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java)) { _, called, _ ->
            when (called.name) {
                "getRemoteIpAddress" -> ip
                "getUri" -> path
                "getMethod" -> method
                "getParameters" -> params
                "getHeaders" -> headers
                "parseBody" -> { parseBody(); null }
                else -> error("Unexpected session call: ${called.name}")
            }
        } as NanoHTTPD.IHTTPSession
        return LogManagerServer.serve(session).use { Result(it.status.requestStatus, it.mimeType, it.data.readBytes()) }
    }
    private fun file(name: String = "$stem.csv", synced: Boolean = false, bytes: ByteArray = byteArrayOf(1, 2, 3)): File {
        val directory = if (synced) File(RobotLogEnvironment.logDirectory, "synced") else RobotLogEnvironment.logDirectory
        directory.mkdirs()
        return File(directory, name).also { owned.add(it); it.writeBytes(bytes) }
    }
    private fun listedNames() = JsonParser.parseString(request("/api/logs").text).asJsonArray.map { it.asJsonObject["name"].asString }

    @Test fun `list and download choose the same unsynced copy for duplicate basenames`() {
        val primary = file(bytes = byteArrayOf(9))
        file(synced = true, bytes = byteArrayOf(4, 5, 6))
        val rows = JsonParser.parseString(request("/api/logs").text).asJsonArray.filter { it.asJsonObject["name"].asString == primary.name }
        assertEquals(1, rows.size)
        assertEquals(1L, rows.single().asJsonObject["sizeBytes"].asLong)
        assertFalse(rows.single().asJsonObject["synced"].asBoolean)
        assertContentEquals(byteArrayOf(9), request("/api/download", params = mapOf("file" to listOf(primary.name))).bytes)
    }
    @Test fun `discovery excludes basenames that requests reject`() {
        val inaccessible = file(" $stem.csv")
        assertFalse(inaccessible.name in listedNames())
        assertEquals(403, request("/api/download", params = mapOf("file" to listOf(inaccessible.name))).status)
    }
    @Test fun `case-equivalent basenames follow filesystem download precedence`() {
        verifyAliasPrecedence("$stem.csv", "$stem.CSV")
    }
    @Test fun `Unicode-equivalent basenames follow filesystem download precedence`() {
        verifyAliasPrecedence("$stem-\u00e9.csv", "$stem-e\u0301.csv")
    }
    private fun verifyAliasPrecedence(primaryName: String, syncedName: String) {
        val primary = file(primaryName, bytes = byteArrayOf(9))
        val synced = file(syncedName, synced = true, bytes = byteArrayOf(4, 5, 6))
        val rootLookup = File(RobotLogEnvironment.logDirectory, syncedName)
        // Exercise the host's actual lookup rules without assuming case folding on Linux.
        val aliasesPrimary = rootLookup.isFile && java.nio.file.Files.isSameFile(rootLookup.toPath(), primary.toPath())
        val rows = JsonParser.parseString(request("/api/logs").text).asJsonArray
            .map { it.asJsonObject }.filter { it["name"].asString in setOf(primaryName, syncedName) }
        assertEquals(if (aliasesPrimary) 1 else 2, rows.size)
        for (row in rows) {
            val expected = if (row["synced"].asBoolean) synced else primary
            val download = request("/api/download", params = mapOf("file" to listOf(row["name"].asString)))
            assertEquals(200, download.status)
            assertEquals(expected.length(), row["sizeBytes"].asLong)
            assertContentEquals(expected.readBytes(), download.bytes)
        }
        assertContentEquals((if (aliasesPrimary) primary else synced).readBytes(),
            request("/api/download", params = mapOf("file" to listOf(syncedName))).bytes)
        LogManagerServer.configureDeleteToken(token)
        assertEquals(200, request("/api/delete", NanoHTTPD.Method.POST,
            mapOf("file" to listOf(syncedName)), mapOf("x-ares-delete-token" to token)).status)
        assertFalse(synced.exists())
        assertEquals(!aliasesPrimary, primary.exists())
    }
    @Test fun `resolved active and outside aliases are not completed log candidates`() {
        val check = LogManagerServer.javaClass.getDeclaredMethod("isCompletedLogFile", File::class.java).apply { isAccessible = true }
        for (target in listOf(File(RobotLogEnvironment.logDirectory, "$stem.csv.active"), File(RobotLogEnvironment.logDirectory.parentFile, "$stem.csv"))) {
            val alias = object : File(RobotLogEnvironment.logDirectory, "$stem.csv") {
                override fun isFile() = true
                override fun getCanonicalFile() = target.canonicalFile
            }
            assertEquals(false, check.invoke(LogManagerServer, alias))
        }
    }
    @Test fun `ambiguous download parameters fail before selecting a file`() {
        val first = file()
        assertEquals(400, request("/api/download", params = mapOf("file" to listOf(first.name, "missing.csv"))).status)
    }
    @Test fun `ambiguous delete parameters leave both copies intact`() {
        val first = file(); val second = file(synced = true)
        LogManagerServer.configureDeleteToken(token)
        assertEquals(400, request("/api/delete", NanoHTTPD.Method.POST, mapOf("file" to listOf(first.name, "missing.csv")), mapOf("x-ares-delete-token" to token)).status)
        assertTrue(first.exists() && second.exists())
    }
    @Test fun `binary downloads retain bytes and use a binary media type`() {
        val bytes = byteArrayOf(0, -1, 13, 10, 42)
        val binary = file("$stem.wpilog", bytes = bytes)
        val response = request("/api/download", params = mapOf("file" to listOf(binary.name)))
        assertEquals(200, response.status)
        assertEquals("application/octet-stream", response.mime)
        assertContentEquals(bytes, response.bytes)
    }
    @Test fun `control characters in a filename are denied before filesystem resolution`() {
        assertEquals(403, request("/api/download", params = mapOf("file" to listOf("bad\u0000.csv"))).status)
    }
    @Test fun `delete authentication runs before body parsing`() {
        var parsed = 0
        val response = request("/api/delete", NanoHTTPD.Method.POST, parseBody = { parsed++ })
        assertEquals(403, response.status)
        LogManagerServer.configureDeleteToken(token)
        assertEquals(401, request("/api/delete", NanoHTTPD.Method.POST, parseBody = { parsed++ }).status)
        assertEquals(0, parsed)
    }
    @Test fun `oversized deletion bodies are rejected before parsing`() {
        LogManagerServer.configureDeleteToken(token)
        var parsed = 0
        val response = request("/api/delete", NanoHTTPD.Method.POST, headers = mapOf("x-ares-delete-token" to token, "content-length" to "10000000"), parseBody = { parsed++ })
        assertEquals(413, response.status)
        assertEquals(0, parsed)
    }
    @Test fun `invalid or chunked body framing is rejected before parsing`() {
        LogManagerServer.configureDeleteToken(token)
        var parsed = 0
        for (headers in listOf(mapOf("content-length" to "-1"), mapOf("content-length" to "invalid"), mapOf("transfer-encoding" to "chunked"))) {
            assertEquals(400, request("/api/delete", NanoHTTPD.Method.POST, headers = headers + ("x-ares-delete-token" to token), parseBody = { parsed++ }).status)
        }
        assertEquals(0, parsed)
    }
    @Test fun `bounded form bodies remain compatible with real HTTP parsing`() {
        val completed = file("$stem form.csv")
        LogManagerServer.configureDeleteToken(token)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = LogManagerServer.serve(session)
        }.apply { setAsyncRunner(LogServerWorkers(workerCount = 1, queueCapacity = 1)) }
        try {
            server.start(1000, true)
            val connection = URL("http://127.0.0.1:${server.listeningPort}/api/delete").openConnection() as HttpURLConnection
            try {
                val bytes = ("file=" + URLEncoder.encode(completed.name, "UTF-8")).toByteArray()
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-ARES-Delete-Token", token)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.connectTimeout = 2000; connection.readTimeout = 2000
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                assertEquals(200, connection.responseCode)
                connection.inputStream.close()
                assertFalse(completed.exists())
            } finally { connection.disconnect() }
        } finally { server.stop() }
    }
    @Test fun `authorized basename deletion removes completed copies and leaves active reservation`() {
        val first = file(); val second = file(synced = true); val active = file("$stem.csv.active")
        LogManagerServer.configureDeleteToken(" $token ")
        assertEquals(200, request("/api/delete", NanoHTTPD.Method.POST, mapOf("file" to listOf(first.name)), mapOf("authorization" to "bEaReR $token")).status)
        assertFalse(first.exists() || second.exists())
        assertTrue(active.exists())
    }
    @Test fun `synced-only files are discoverable and downloadable`() {
        val synced = file(synced = true)
        assertTrue(synced.name in listedNames())
        assertContentEquals(synced.readBytes(), request("/api/download", params = mapOf("file" to listOf(synced.name))).bytes)
    }
    @Test fun `rate limit keeps ten request burst and fractional refill`() {
        RobotClock.useMockTime(1000)
        repeat(10) { assertEquals(404, request(ip = stem).status) }
        assertEquals(429, request(ip = stem).status)
        RobotClock.useMockTime(1099); assertEquals(429, request(ip = stem).status)
        RobotClock.useMockTime(1100); assertEquals(404, request(ip = stem).status)
        assertEquals(429, request(ip = stem).status)
    }
    @Test fun `clock rewind does not remove already earned request credits`() {
        RobotClock.useMockTime(1000); assertEquals(404, request(ip = stem).status)
        RobotClock.useMockTime(0); assertEquals(404, request(ip = stem).status)
    }
    @Test fun `client tracking is bounded and expires idle clients`() {
        RobotClock.useMockTime(1000)
        val accepted = (1..1024).count { request(ip = "client-$it").status == 404 }
        assertTrue(accepted in 1..256, "New clients must not grow the tracking table indefinitely")
        RobotClock.useMockTime(61_001)
        assertEquals(404, request(ip = "new-client").status)
    }
}
