package com.ares.analytics.shared.mcp

import kotlinx.serialization.Serializable

/** Canonical filename under Studio's AppData root holding the active loopback MCP bridge descriptor. */
const val MCP_BRIDGE_FILE_NAME = "mcp-bridge.json"

/** Protocol version spoken between the headless `:mcp` server and Studio `:app`'s loopback bridge. */
const val MCP_BRIDGE_PROTOCOL_VERSION = 1

/** Persisted descriptor advertised by a running ARES Robotics Studio desktop instance. */
@Serializable
data class McpBridgeDescriptor(
    val protocolVersion: Int = MCP_BRIDGE_PROTOCOL_VERSION,
    val pid: Long,
    val port: Int,
    val bearerToken: String,
    val studioVersion: String,
    val activeProjectRoot: String? = null,
    val startedAtEpochMillis: Long,
)

/** Response from `GET /v1/mcp/health` on Studio's loopback bridge. */
@Serializable
data class McpBridgeHealthResponse(
    val ok: Boolean,
    val protocolVersion: Int = MCP_BRIDGE_PROTOCOL_VERSION,
    val studioVersion: String,
    val pid: Long,
    val activeProjectRoot: String? = null,
    val projectLoaded: Boolean = false,
    val projectRevision: String? = null,
)

/** Request body for `POST /v1/mcp/session/reload` on Studio's loopback bridge. */
@Serializable
data class McpBridgeReloadRequest(
    val projectRoot: String,
    val expectedRevision: String? = null,
    val reason: String? = null,
)

/** Response body from `POST /v1/mcp/session/reload`. */
@Serializable
data class McpBridgeReloadResponse(
    val reloaded: Boolean,
    val activeProjectRoot: String? = null,
    val currentRevision: String? = null,
    val message: String,
)
