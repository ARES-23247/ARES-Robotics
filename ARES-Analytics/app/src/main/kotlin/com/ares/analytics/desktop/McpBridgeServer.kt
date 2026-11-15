package com.ares.analytics.desktop

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.AppDataPaths
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionPhase
import com.ares.analytics.shared.mcp.MCP_BRIDGE_FILE_NAME
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.ares.analytics.shared.mcp.McpBridgeHealthResponse
import com.ares.analytics.shared.mcp.McpBridgeReloadRequest
import com.ares.analytics.shared.mcp.McpBridgeReloadResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Localhost-only HTTP bridge inside ARES Robotics Studio (`127.0.0.1:0`) that lets external
 * `ares-project-mcp` stdio servers query the open workspace and trigger non-destructive reloads.
 */
class McpBridgeServer(
    private val projectSession: ProjectSession,
    private val bridgeDirProvider: () -> File = AppDataPaths::rootDirectory,
    private val studioVersion: String = BuildConfig.VERSION,
    private val pid: Long = ProcessHandle.current().pid(),
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(McpBridgeServer::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val bearerToken: String = generateHexToken()
    private val expectedAuthBytes: ByteArray = "Bearer $bearerToken".toByteArray(Charsets.UTF_8)
    private val started = AtomicBoolean(false)
    private val lock = Any()

    private val _descriptorState = MutableStateFlow<McpBridgeDescriptor?>(null)
    val descriptorState: StateFlow<McpBridgeDescriptor?> = _descriptorState.asStateFlow()

    private var serverScope: CoroutineScope? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var sessionObserverJob: Job? = null
    private var boundPort: Int = 0
    private var startedAtEpochMillis: Long = 0L

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        val exceptionHandler = CoroutineExceptionHandler { _, error ->
            logger.warn("MCP bridge server coroutine failure", error)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        val created = scope.embeddedServer(
            CIO,
            host = "127.0.0.1",
            port = 0,
            parentCoroutineContext = exceptionHandler,
        ) {
            routing {
                get("/v1/mcp/health") { handleHealth(call) }
                get("/v1/health") { handleHealth(call) }
                post("/v1/mcp/session/reload") { handleReload(call) }
                post("/v1/session/reload") { handleReload(call) }
            }
        }
        try {
            created.startSuspend(wait = false)
            val resolvedPort = created.engine.resolvedConnectors().first().port
            val launchMillis = System.currentTimeMillis()
            synchronized(lock) {
                serverScope = scope
                server = created
                boundPort = resolvedPort
                startedAtEpochMillis = launchMillis
            }
            publishDescriptor(projectSession.state.value.selection?.projectRoot)
            sessionObserverJob = scope.launch {
                projectSession.state
                    .map { it.selection?.projectRoot }
                    .distinctUntilChanged()
                    .collect { root ->
                        publishDescriptor(root)
                    }
            }
        } catch (error: Throwable) {
            started.set(false)
            runCatching { created.stop(100, 300) }
            scope.cancel()
            throw error
        }
    }

    private suspend fun handleHealth(call: ApplicationCall) {
        if (!isAuthorized(call)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }
        val state = projectSession.state.value
        val payload = McpBridgeHealthResponse(
            ok = true,
            studioVersion = studioVersion,
            pid = pid,
            activeProjectRoot = state.selection?.projectRoot,
            projectLoaded = state.phase == ProjectSessionPhase.READY,
            projectRevision = state.revision?.canonicalContentSha256,
        )
        call.respondText(
            json.encodeToString(McpBridgeHealthResponse.serializer(), payload),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    private suspend fun handleReload(call: ApplicationCall) {
        if (!isAuthorized(call)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }
        val bodyText = call.receiveText()
        val request = runCatching {
            json.decodeFromString(McpBridgeReloadRequest.serializer(), bodyText)
        }.getOrElse {
            call.respondText("""{"error":"invalid reload request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }

        val state = projectSession.state.value
        val openRoot = state.selection?.projectRoot
        if (openRoot.isNullOrBlank()) {
            respondReload(
                call,
                McpBridgeReloadResponse(
                    reloaded = false,
                    message = "No active robot project open in Studio.",
                ),
            )
            return
        }
        if (!sameCanonicalDirectory(openRoot, request.projectRoot)) {
            respondReload(
                call,
                McpBridgeReloadResponse(
                    reloaded = false,
                    activeProjectRoot = openRoot,
                    currentRevision = state.revision?.canonicalContentSha256,
                    message = "Studio currently has a different project open; reload skipped.",
                ),
            )
            return
        }
        val currentHash = state.revision?.canonicalContentSha256
        if (request.expectedRevision != null && currentHash != request.expectedRevision) {
            respondReload(
                call,
                McpBridgeReloadResponse(
                    reloaded = false,
                    activeProjectRoot = openRoot,
                    currentRevision = currentHash,
                    message = "Studio revision mismatch before reload.",
                ),
            )
            return
        }

        val outcome = withContext(Dispatchers.IO) {
            projectSession.reload()
        }
        val response = when (outcome) {
            is ProjectSessionMutationResult.Applied -> McpBridgeReloadResponse(
                reloaded = true,
                activeProjectRoot = outcome.snapshot.selection.projectRoot,
                currentRevision = outcome.snapshot.revision.canonicalContentSha256,
                message = "Studio reloaded project cleanly.",
            )
            is ProjectSessionMutationResult.Stale -> McpBridgeReloadResponse(
                reloaded = false,
                activeProjectRoot = openRoot,
                currentRevision = outcome.actual?.canonicalContentSha256,
                message = "Studio session became stale during reload.",
            )
            is ProjectSessionMutationResult.Conflict -> McpBridgeReloadResponse(
                reloaded = false,
                activeProjectRoot = openRoot,
                currentRevision = projectSession.state.value.revision?.canonicalContentSha256,
                message = outcome.message,
            )
            is ProjectSessionMutationResult.Failed -> McpBridgeReloadResponse(
                reloaded = false,
                activeProjectRoot = openRoot,
                currentRevision = projectSession.state.value.revision?.canonicalContentSha256,
                message = outcome.message,
            )
        }
        respondReload(call, response)
    }

    private suspend fun respondReload(call: ApplicationCall, payload: McpBridgeReloadResponse) {
        call.respondText(
            json.encodeToString(McpBridgeReloadResponse.serializer(), payload),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    private fun isAuthorized(call: ApplicationCall): Boolean {
        val headerValue = call.request.headers[HttpHeaders.Authorization] ?: return false
        return MessageDigest.isEqual(expectedAuthBytes, headerValue.toByteArray(Charsets.UTF_8))
    }

    private fun publishDescriptor(activeProjectRoot: String?) {
        val portCopy: Int
        val startedMillisCopy: Long
        synchronized(lock) {
            if (!started.get() || boundPort !in 1..65535) return
            portCopy = boundPort
            startedMillisCopy = startedAtEpochMillis
        }
        val descriptor = McpBridgeDescriptor(
            pid = pid,
            port = portCopy,
            bearerToken = bearerToken,
            studioVersion = studioVersion,
            activeProjectRoot = activeProjectRoot,
            startedAtEpochMillis = startedMillisCopy,
        )
        runCatching {
            val dir = bridgeDirProvider()
            dir.mkdirs()
            val targetFile = File(dir, MCP_BRIDGE_FILE_NAME)
            val tempFile = File.createTempFile(".mcp-bridge-", ".tmp", dir)
            try {
                tempFile.writeText(json.encodeToString(McpBridgeDescriptor.serializer(), descriptor))
                runCatching {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.getOrElse {
                    Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }.onFailure { error ->
            logger.warn("Unable to publish MCP bridge descriptor file", error)
        }
        _descriptorState.value = descriptor
    }

    override fun close() {
        val wasStarted = started.getAndSet(false)
        val scopeToCancel: CoroutineScope?
        val serverToStop: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>?
        val observer: Job?
        synchronized(lock) {
            scopeToCancel = serverScope
            serverToStop = server
            observer = sessionObserverJob
            serverScope = null
            server = null
            sessionObserverJob = null
            boundPort = 0
        }
        observer?.cancel()
        scopeToCancel?.cancel()
        if (wasStarted || serverToStop != null) {
            runCatching { serverToStop?.stop(250, 750) }
        }
        runCatching {
            val file = File(bridgeDirProvider(), MCP_BRIDGE_FILE_NAME)
            if (file.isFile) {
                val existing = runCatching {
                    json.decodeFromString(McpBridgeDescriptor.serializer(), file.readText())
                }.getOrNull()
                if (existing?.pid == pid) {
                    file.delete()
                }
            }
        }.onFailure { error ->
            logger.debug("Ignoring MCP bridge cleanup error", error)
        }
        _descriptorState.value = null
    }

    private fun sameCanonicalDirectory(left: String, right: String): Boolean =
        runCatching { File(left).canonicalFile == File(right).canonicalFile }.getOrDefault(false)

    companion object {
        private val secureRandom = SecureRandom()

        private fun generateHexToken(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
