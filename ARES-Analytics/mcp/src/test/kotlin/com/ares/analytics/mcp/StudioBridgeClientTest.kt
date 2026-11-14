package com.ares.analytics.mcp

import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.shared.mcp.MCP_BRIDGE_FILE_NAME
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.ares.analytics.shared.mcp.McpBridgeHealthResponse
import com.ares.analytics.shared.mcp.McpBridgeReloadResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioBridgeClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health and notifyReload fall back cleanly when descriptor is missing or pid is dead`() = runBlocking {
        val bridgeDir = Files.createTempDirectory("ares-mcp-bridge-offline-").toFile()
        try {
            val missingClient = StudioBridgeClient(bridgeDir = bridgeDir)
            val missingHealth = missingClient.health()
            assertFalse(missingHealth.connected)
            assertFalse(missingClient.notifyReload("/tmp/project").studioNotified)

            File(bridgeDir, MCP_BRIDGE_FILE_NAME).writeText(
                json.encodeToString(
                    McpBridgeDescriptor.serializer(),
                    McpBridgeDescriptor(
                        pid = 9999999L,
                        port = 54321,
                        bearerToken = "secret",
                        studioVersion = "7.0.79",
                        startedAtEpochMillis = 1_700_000_000_000L,
                    ),
                ),
            )
            val deadClient = StudioBridgeClient(bridgeDir = bridgeDir, isPidAlive = { false })
            val deadHealth = deadClient.health()
            assertFalse(deadHealth.connected)
            assertTrue(deadHealth.fallbackReason?.contains("no longer running", ignoreCase = true) == true)
            assertFalse(deadClient.notifyReload("/tmp/project").studioNotified)
        } finally {
            bridgeDir.deleteRecursively()
        }
    }

    @Test
    fun `health and notifyReload validate bearer auth against loopback bridge`() = runBlocking {
        val bridgeDir = Files.createTempDirectory("ares-mcp-bridge-live-").toFile()
        val expectedToken = "unit-test-token-256bit"
        val currentPid = ProcessHandle.current().pid()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/mcp/health") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            if (auth != "Bearer $expectedToken") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val body = json.encodeToString(
                McpBridgeHealthResponse.serializer(),
                McpBridgeHealthResponse(
                    ok = true,
                    studioVersion = "7.0.79",
                    pid = currentPid,
                    activeProjectRoot = "C:/robot",
                    projectLoaded = true,
                    projectRevision = "rev-abc",
                ),
            ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/mcp/session/reload") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            if (auth != "Bearer $expectedToken") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val body = json.encodeToString(
                McpBridgeReloadResponse.serializer(),
                McpBridgeReloadResponse(
                    reloaded = true,
                    activeProjectRoot = "C:/robot",
                    currentRevision = "rev-def",
                    message = "Reloaded cleanly.",
                ),
            ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            File(bridgeDir, MCP_BRIDGE_FILE_NAME).writeText(
                json.encodeToString(
                    McpBridgeDescriptor.serializer(),
                    McpBridgeDescriptor(
                        pid = currentPid,
                        port = port,
                        bearerToken = expectedToken,
                        studioVersion = "7.0.79",
                        activeProjectRoot = "C:/robot",
                        startedAtEpochMillis = 1_700_000_000_000L,
                    ),
                ),
            )
            val client = StudioBridgeClient(bridgeDir = bridgeDir, isPidAlive = { true })
            val health = client.health()
            assertTrue(health.connected)
            assertEquals("7.0.79", health.studioVersion)
            assertEquals("rev-abc", health.projectRevision)

            val reload = client.notifyReload("C:/robot", expectedRevision = "rev-abc", reason = "test")
            assertTrue(reload.studioNotified)
            assertTrue(reload.reloaded)
            assertEquals("rev-def", reload.currentRevision)

            // Reject mismatched bearer token cleanly
            File(bridgeDir, MCP_BRIDGE_FILE_NAME).writeText(
                json.encodeToString(
                    McpBridgeDescriptor.serializer(),
                    McpBridgeDescriptor(
                        pid = currentPid,
                        port = port,
                        bearerToken = "wrong-token",
                        studioVersion = "7.0.79",
                        startedAtEpochMillis = 1_700_000_000_000L,
                    ),
                ),
            )
            val unauthorizedHealth = client.health()
            assertFalse(unauthorizedHealth.connected)
            assertFalse(client.notifyReload("C:/robot").studioNotified)
        } finally {
            server.stop(0)
            bridgeDir.deleteRecursively()
        }
    }
}
