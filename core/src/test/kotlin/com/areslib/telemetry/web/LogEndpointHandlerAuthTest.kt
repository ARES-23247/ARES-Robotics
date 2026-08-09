package com.areslib.telemetry.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * Verifies the optional bearer-token authentication on [LogEndpointHandler].
 *
 * When an `authToken` is configured, every `/api/` route requires an
 * `Authorization: Bearer <token>` header. With no token configured the handler
 * remains open (backward-compatible default).
 */
class LogEndpointHandlerAuthTest {

    private fun newTempDir(): File =
        Files.createTempDirectory("ares-logtest").toFile()

    /**
     * Sends a raw HTTP request through the handler and returns the numeric status code.
     */
    private fun requestStatus(handler: LogEndpointHandler, requestLine: String, headers: Map<String, String>): Int {
        val server = ServerSocket(0)
        try {
            val client = Socket()
            client.connect(InetSocketAddress("127.0.0.1", server.localPort))
            val accepted = server.accept()

            val sb = StringBuilder(requestLine).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("Host: 127.0.0.1\r\n\r\n")

            client.getOutputStream().write(sb.toString().toByteArray())
            client.getOutputStream().flush()

            // handleClient reads the request, writes the response, and closes `accepted`.
            handler.handleClient(accepted)

            val statusLine = client.getInputStream().bufferedReader().readLine() ?: ""
            client.close()
            // Format: "HTTP/1.1 401 Unauthorized"
            return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        } finally {
            server.close()
        }
    }

    @Test
    fun `request rejected with 401 when token configured but header absent`() {
        val dir = newTempDir()
        try {
            val handler = LogEndpointHandler(dir, authToken = "team-secret")
            val status = requestStatus(handler, "GET /api/status HTTP/1.1", emptyMap())
            assertEquals(401, status, "Missing Authorization header must yield 401")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `request rejected with 401 when bearer token is wrong`() {
        val dir = newTempDir()
        try {
            val handler = LogEndpointHandler(dir, authToken = "team-secret")
            val status = requestStatus(
                handler, "GET /api/status HTTP/1.1",
                mapOf("Authorization" to "Bearer wrong-token")
            )
            assertEquals(401, status, "Wrong bearer token must yield 401")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `request allowed with correct bearer token`() {
        val dir = newTempDir()
        try {
            val handler = LogEndpointHandler(dir, authToken = "team-secret")
            val status = requestStatus(
                handler, "GET /api/status HTTP/1.1",
                mapOf("Authorization" to "Bearer team-secret")
            )
            assertEquals(200, status, "Correct bearer token must be allowed through")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no token configured keeps endpoints open (backward compatible)`() {
        val dir = newTempDir()
        try {
            val handler = LogEndpointHandler(dir, authToken = null)
            val status = requestStatus(handler, "GET /api/status HTTP/1.1", emptyMap())
            assertEquals(200, status, "Null token must leave the server open")
        } finally {
            dir.deleteRecursively()
        }
    }
}
