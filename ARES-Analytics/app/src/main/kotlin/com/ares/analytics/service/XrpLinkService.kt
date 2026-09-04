package com.ares.analytics.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

enum class XrpRequestedMode { STOPPED, INITIALIZE, TELEOP, AUTONOMOUS }

data class XrpControlRequest(
    val mode: XrpRequestedMode = XrpRequestedMode.STOPPED,
    val autonomousId: String? = null,
    val revision: Long = 0L,
)

data class XrpPeerIdentity(
    val projectId: String,
    val contentSha256: String,
    val drivetrainType: String,
)

/** Dedicated Pico-friendly link; XRP deliberately does not impersonate an NT4 server. */
class XrpLinkService(private val telemetrySink: Nt4ClientService) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val generation = AtomicLong()
    private val controlRevision = AtomicLong()
    private val writerMutex = Mutex()
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _peerIdentity = MutableStateFlow<XrpPeerIdentity?>(null)
    val peerIdentity: StateFlow<XrpPeerIdentity?> = _peerIdentity.asStateFlow()
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
    private val _controlRequest = MutableStateFlow(XrpControlRequest())
    val controlRequest: StateFlow<XrpControlRequest> = _controlRequest.asStateFlow()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var job: Job? = null
    private var lastTelemetrySequence = -1L

    fun start(host: String, port: Int = DEFAULT_PORT, expectedProjectId: String) {
        require(expectedProjectId.isNotBlank()) { "XRP link requires the canonical project ID" }
        val requestedGeneration = generation.incrementAndGet()
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
        writer = null
        _isConnected.value = false
        _peerIdentity.value = null
        _connectionError.value = null
        job = scope.launch {
            while (currentCoroutineContext().isActive && generation.get() == requestedGeneration) {
                try {
                    connectAndRead(host, port, expectedProjectId, requestedGeneration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _connectionError.value = error.message ?: "XRP link connection failed"
                    disconnectSocket()
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    suspend fun stop() {
        requestStop()
        generation.incrementAndGet()
        runCatching { socket?.close() }
        job?.cancelAndJoin()
        job = null
        disconnectSocket()
    }

    suspend fun disposeAndJoin() {
        stop()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    suspend fun publishControl(
        sessionId: String,
        sequence: Long,
        requestRevision: Long,
        armed: Boolean,
        vx: Double,
        vy: Double,
        omega: Double,
        command: String = "START_TELEOP",
        selectedOpMode: String? = null,
    ): Boolean = writerMutex.withLock {
        require(sessionId.isNotBlank()) { "XRP control session ID is required" }
        require(sequence >= 0L) { "XRP control sequence cannot be negative" }
        require(requestRevision >= 0L) { "XRP control request revision cannot be negative" }
        require(vx.isFinite() && vy.isFinite() && omega.isFinite()) { "XRP drive values must be finite" }
        require(command in CONTROL_COMMANDS) { "Unsupported XRP control command: $command" }
        require(command != "START_AUTO" || !selectedOpMode.isNullOrBlank()) {
            "XRP autonomous start requires a selected routine"
        }
        val activeWriter = writer ?: return@withLock false
        val message = JsonObject().apply {
            addProperty("protocol", PROTOCOL)
            addProperty("type", "control")
            addProperty("sessionId", sessionId)
            addProperty("sequence", sequence)
            addProperty("requestRevision", requestRevision)
            addProperty("armed", armed)
            addProperty("command", command)
            selectedOpMode?.let { addProperty("selectedOpMode", it) }
            add("driveFrame", com.google.gson.JsonArray().apply {
                add(if (armed) vx else 0.0)
                add(if (armed) vy else 0.0)
                add(if (armed) omega else 0.0)
            })
        }
        val sent = runCatching {
            activeWriter.write(message.toString())
            activeWriter.newLine()
            activeWriter.flush()
        }.isSuccess
        if (!sent) {
            writer = null
            runCatching { socket?.close() }
            socket = null
            _isConnected.value = false
            _controlRequest.value = stoppedRequest()
        }
        sent
    }

    fun requestTeleOp() {
        _controlRequest.value = XrpControlRequest(
            mode = XrpRequestedMode.TELEOP,
            revision = controlRevision.incrementAndGet(),
        )
    }

    fun requestInitialize() {
        _controlRequest.value = XrpControlRequest(
            mode = XrpRequestedMode.INITIALIZE,
            revision = controlRevision.incrementAndGet(),
        )
    }

    fun requestAutonomous(entryId: String) {
        require(entryId.isNotBlank()) { "XRP autonomous entry ID is required" }
        _controlRequest.value = XrpControlRequest(
            mode = XrpRequestedMode.AUTONOMOUS,
            autonomousId = entryId,
            revision = controlRevision.incrementAndGet(),
        )
    }

    fun requestStop() {
        _controlRequest.value = stoppedRequest()
    }

    private suspend fun connectAndRead(
        host: String,
        port: Int,
        expectedProjectId: String,
        requestedGeneration: Long,
    ) {
        val connected = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            soTimeout = HANDSHAKE_TIMEOUT_MS
        }
        socket = connected
        writer = connected.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
        connected.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val hello = reader.readLine()?.takeIf { it.length <= MAX_LINE_LENGTH }
                ?: error("XRP link closed before protocol handshake")
            val peer = parseHello(hello) ?: error("XRP link protocol handshake was invalid")
            require(peer.projectId == expectedProjectId) {
                "XRP link reached project '${peer.projectId}', but Studio opened '$expectedProjectId'"
            }
            connected.soTimeout = 0
            lastTelemetrySequence = -1L
            _peerIdentity.value = peer
            _connectionError.value = null
            _isConnected.value = true
            while (currentCoroutineContext().isActive && generation.get() == requestedGeneration) {
                val line = reader.readLine() ?: break
                if (line.length > MAX_LINE_LENGTH) continue
                acceptLine(line)
            }
        }
        disconnectSocket()
    }

    private fun parseHello(line: String): XrpPeerIdentity? {
        val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        if (root.get("protocol")?.asString != PROTOCOL ||
            root.get("type")?.asString != "hello" ||
            root.get("role")?.asString != "robot"
        ) return null
        val projectId = root.stringOrNull("projectId")?.takeIf { it.isNotBlank() } ?: return null
        val contentSha256 = root.stringOrNull("contentSha256")
            ?.lowercase()
            ?.takeIf { it.matches(SHA256) }
            ?: return null
        val drivetrainType = root.stringOrNull("drivetrainType")
            ?.takeIf { it in DRIVETRAIN_TYPES }
            ?: return null
        return XrpPeerIdentity(projectId, contentSha256, drivetrainType)
    }

    private suspend fun acceptLine(line: String) {
        val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
        if (root.get("protocol")?.asString != PROTOCOL || root.get("type")?.asString != "telemetry") return
        val sequence = root.finiteLong("sequence") ?: return
        if (sequence <= lastTelemetrySequence) return
        val x = root.finiteDouble("poseX") ?: return
        val y = root.finiteDouble("poseY") ?: return
        val heading = root.finiteDouble("heading") ?: return
        val values = listOf(x, y, heading)
        lastTelemetrySequence = sequence
        values.forEachIndexed { index, value ->
            telemetrySink.acceptExternalLiveTelemetry("ARES/TruePose/$index", value)
            telemetrySink.acceptExternalLiveTelemetry("ARES/EstimatedPose/$index", value)
        }
        root.finiteDouble("battery")?.let { telemetrySink.acceptExternalLiveTelemetry("Robot/BatteryVoltage", it) }
        root.get("mode")?.takeIf { it.isJsonPrimitive }?.asString?.let {
            telemetrySink.acceptExternalLiveTelemetry("Robot/Mode", 0.0, it)
        }
    }

    private suspend fun disconnectSocket() {
        writerMutex.withLock {
            writer = null
            runCatching { socket?.close() }
            socket = null
            _isConnected.value = false
            _peerIdentity.value = null
            _controlRequest.value = stoppedRequest()
            lastTelemetrySequence = -1L
        }
    }

    private fun JsonObject.finiteDouble(name: String): Double? = runCatching {
        get(name)?.takeIf { it.isJsonPrimitive }?.asDouble?.takeIf(Double::isFinite)
    }.getOrNull()

    private fun JsonObject.stringOrNull(name: String): String? = runCatching {
        get(name)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun JsonObject.finiteLong(name: String): Long? = runCatching {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return@runCatching null
        value.takeIf { it.isFinite() && it >= 0.0 && it % 1.0 == 0.0 && it <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
    }.getOrNull()

    private fun stoppedRequest() = XrpControlRequest(revision = controlRevision.incrementAndGet())

    companion object {
        const val PROTOCOL = "ares-xrp/1"
        const val DEFAULT_PORT = 5811
        private val CONTROL_COMMANDS = setOf("INIT", "START_TELEOP", "START_AUTO", "STOP")
        private val DRIVETRAIN_TYPES = setOf("differential", "mecanum")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val CONNECT_TIMEOUT_MS = 500
        private const val HANDSHAKE_TIMEOUT_MS = 1_000
        private const val RECONNECT_DELAY_MS = 500L
        private const val MAX_LINE_LENGTH = 16_384
    }
}
