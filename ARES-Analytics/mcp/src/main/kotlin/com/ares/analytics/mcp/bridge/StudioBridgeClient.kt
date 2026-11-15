package com.ares.analytics.mcp.bridge

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.mcp.MCP_BRIDGE_FILE_NAME
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.ares.analytics.shared.mcp.McpBridgeHealthResponse
import com.ares.analytics.shared.mcp.McpBridgeReloadRequest
import com.ares.analytics.shared.mcp.McpBridgeReloadResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.parameter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.io.File

/** Result of checking Studio's optional loopback bridge. */
@Serializable
data class StudioBridgeStatus(
    val connected: Boolean,
    val bridgeFilePath: String,
    val port: Int? = null,
    val studioVersion: String? = null,
    val activeProjectRoot: String? = null,
    val projectLoaded: Boolean = false,
    val projectRevision: String? = null,
    val fallbackReason: String? = null,
)

/** Result of notifying a running Studio instance after an MCP filesystem edit. */
@Serializable
data class StudioReloadOutcome(
    val studioNotified: Boolean,
    val reloaded: Boolean,
    val currentRevision: String? = null,
    val message: String,
)

/**
 * Dual-mode loopback HTTP client to a running ARES Robotics Studio desktop instance.
 *
 * When Studio is offline, the bridge descriptor is stale, or authentication is rejected, this
 * client degrades cleanly to headless offline operation instead of failing MCP tool calls.
 */
class StudioBridgeClient(
    private val bridgeDir: File,
    private val httpClientFactory: () -> HttpClient = ::defaultHttpClient,
    private val isPidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
) {
    val bridgeFile: File get() = File(bridgeDir, MCP_BRIDGE_FILE_NAME)

    suspend fun health(): StudioBridgeStatus {
        val descriptor = readDescriptorOrNull()
            ?: return StudioBridgeStatus(
                connected = false,
                bridgeFilePath = bridgeFile.absolutePath,
                fallbackReason = "No active Studio bridge descriptor found; operating in offline headless mode.",
            )
        if (!isPidAlive(descriptor.pid)) {
            return StudioBridgeStatus(
                connected = false,
                bridgeFilePath = bridgeFile.absolutePath,
                port = descriptor.port,
                studioVersion = descriptor.studioVersion,
                activeProjectRoot = null,
                fallbackReason = "Studio PID ${descriptor.pid} is no longer running; operating in offline headless mode.",
            )
        }
        return runCatching {
            httpClientFactory().use { client ->
                val response = client.get("http://127.0.0.1:${descriptor.port}/v1/mcp/health") {
                    header(HttpHeaders.Authorization, "Bearer ${descriptor.bearerToken}")
                }
                if (response.status != HttpStatusCode.OK) {
                    return@use StudioBridgeStatus(
                        connected = false,
                        bridgeFilePath = bridgeFile.absolutePath,
                        port = descriptor.port,
                        studioVersion = descriptor.studioVersion,
                        activeProjectRoot = null,
                        fallbackReason = "Studio bridge rejected health probe (${response.status.value}); using headless mode.",
                    )
                }
                val body = AppJson.decodeFromString<McpBridgeHealthResponse>(response.bodyAsText())
                StudioBridgeStatus(
                    connected = body.ok,
                    bridgeFilePath = bridgeFile.absolutePath,
                    port = descriptor.port,
                    studioVersion = body.studioVersion,
                    activeProjectRoot = if (body.ok) body.activeProjectRoot else null,
                    projectLoaded = body.projectLoaded,
                    projectRevision = body.projectRevision,
                )
            }
        }.getOrElse { error ->
            StudioBridgeStatus(
                connected = false,
                bridgeFilePath = bridgeFile.absolutePath,
                port = descriptor.port,
                studioVersion = descriptor.studioVersion,
                activeProjectRoot = null,
                fallbackReason = "Studio bridge unreachable (${error.message ?: "connection failed"}); using headless mode.",
            )
        }
    }

    suspend fun recordedRuns(id: String? = null): String {
        val descriptor = readDescriptorOrNull()?.takeIf { isPidAlive(it.pid) }
            ?: error("Open the workspace in Studio to read its recorded runs.")
        return httpClientFactory().use { client ->
            val response = client.get("http://127.0.0.1:" + descriptor.port + "/v1/mcp/runs") {
                header(HttpHeaders.Authorization, "Bearer " + descriptor.bearerToken)
                if (id != null) parameter("sessionId", id)
            }
            check(response.status == HttpStatusCode.OK) {
                when (response.status) {
                    HttpStatusCode.NotFound -> "Run unavailable in the active Studio workspace."
                    HttpStatusCode.Conflict -> "Open a workspace in Studio and retry."
                    else -> "Studio could not read recorded runs (HTTP " + response.status.value + ")."
                }
            }
            response.bodyAsText()
        }
    }

    suspend fun notifyReload(
        projectRoot: String,
        expectedRevision: String? = null,
        reason: String? = null,
    ): StudioReloadOutcome {
        val descriptor = readDescriptorOrNull()
            ?: return StudioReloadOutcome(
                studioNotified = false,
                reloaded = false,
                message = "Studio offline; changes saved to disk only.",
            )
        if (!isPidAlive(descriptor.pid)) {
            return StudioReloadOutcome(
                studioNotified = false,
                reloaded = false,
                message = "Studio process exited; changes saved to disk only.",
            )
        }
        return runCatching {
            httpClientFactory().use { client ->
                val response = client.post("http://127.0.0.1:${descriptor.port}/v1/mcp/session/reload") {
                    header(HttpHeaders.Authorization, "Bearer ${descriptor.bearerToken}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        AppJson.encodeToString(
                            McpBridgeReloadRequest(
                                projectRoot = projectRoot,
                                expectedRevision = expectedRevision,
                                reason = reason,
                            ),
                        ),
                    )
                }
                if (response.status != HttpStatusCode.OK) {
                    return@use StudioReloadOutcome(
                        studioNotified = false,
                        reloaded = false,
                        message = "Studio bridge returned HTTP ${response.status.value}; disk write preserved.",
                    )
                }
                val payload = AppJson.decodeFromString<McpBridgeReloadResponse>(response.bodyAsText())
                StudioReloadOutcome(
                    studioNotified = true,
                    reloaded = payload.reloaded,
                    currentRevision = payload.currentRevision,
                    message = payload.message,
                )
            }
        }.getOrElse { error ->
            StudioReloadOutcome(
                studioNotified = false,
                reloaded = false,
                message = "Studio bridge reload skipped (${error.message ?: "unreachable"}); disk write preserved.",
            )
        }
    }

    internal fun readDescriptorOrNull(): McpBridgeDescriptor? {
        val file = bridgeFile
        if (!file.isFile) return null
        return runCatching {
            AppJson.decodeFromString<McpBridgeDescriptor>(file.readText())
        }.getOrNull()?.takeIf { it.port in 1..65535 && it.bearerToken.isNotBlank() }
    }

    companion object {
        fun resolveBridgeDir(explicitDir: File? = null): File {
            if (explicitDir != null) return explicitDir.absoluteFile
            val prop = System.getProperty("ares.analytics.dataDir")?.trim().orEmpty()
            if (prop.isNotEmpty()) return File(prop).absoluteFile
            val env = System.getenv("ARES_ANALYTICS_DATA_DIR")?.trim().orEmpty()
            if (env.isNotEmpty()) return File(env).absoluteFile
            return File(System.getProperty("user.home"), ".ares-analytics").absoluteFile
        }

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 1_200L
                requestTimeoutMillis = 2_500L
                socketTimeoutMillis = 2_500L
            }
        }
    }
}
