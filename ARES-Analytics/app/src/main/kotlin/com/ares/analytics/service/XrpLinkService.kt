package com.ares.analytics.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ares.analytics.util.Sha256
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    val boardType: String? = null,
    val micropythonVersion: String? = null,
    val xrplibVersion: String? = null,
    val aresRuntimeVersion: String? = null,
    val canonicalContentSha256: String? = null,
)

data class XrpFieldApplyReceipt(
    val session: String,
    val sequence: Long,
    val configId: String,
    val revision: Long,
    val sha256: String,
    val obstacleCount: Int,
    val elementCount: Int,
    val aprilTagCount: Int,
) {
    val eventId: String get() = "$session:$sequence"
}

data class XrpFieldApplyFailure(
    val eventId: String,
    val message: String,
)

/** Dedicated Pico-friendly link; XRP deliberately does not impersonate an NT4 server. */
class XrpLinkService internal constructor(
    private val telemetrySink: Nt4ClientService,
    private val socketFactory: () -> Socket,
) {
    constructor(telemetrySink: Nt4ClientService) : this(telemetrySink, ::Socket)
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
    private val _fieldApplyReceipt = MutableStateFlow<XrpFieldApplyReceipt?>(null)
    val fieldApplyReceipt: StateFlow<XrpFieldApplyReceipt?> = _fieldApplyReceipt.asStateFlow()
    private val _fieldApplyFailure = MutableStateFlow<XrpFieldApplyFailure?>(null)
    val fieldApplyFailure: StateFlow<XrpFieldApplyFailure?> = _fieldApplyFailure.asStateFlow()
    private val lifecycleLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private var job: Job? = null
    private var lastTelemetrySequence = -1L

    fun start(host: String, port: Int = DEFAULT_PORT, expectedProjectId: String, expectedCanonicalContentSha256: String) = synchronized(lifecycleLock) {
        require(expectedProjectId.isNotBlank()) { "XRP link requires the canonical project ID" }
        require(expectedCanonicalContentSha256.matches(SHA256)) { "XRP link requires the canonical project fingerprint" }
        val requestedGeneration = generation.incrementAndGet()
        val previous = job
        previous?.cancel()
        runCatching { socket?.close() }
        clearConnectionState()
        job = scope.launch {
            // A replaced owner must finish all telemetry delivery and cleanup before
            // its successor can publish a connection, even if connect was blocking.
            previous?.cancelAndJoin()
            currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) {
                if (generation.get() != requestedGeneration) return@launch
                clearConnectionState()
                _connectionError.value = null
            }
            while (currentCoroutineContext().isActive && generation.get() == requestedGeneration) {
                try {
                    connectAndRead(host, port, expectedProjectId, expectedCanonicalContentSha256, requestedGeneration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(lifecycleLock) {
                        if (generation.get() == requestedGeneration) {
                            _connectionError.value = error.message ?: "XRP link connection failed"
                        }
                    }
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    suspend fun stop() {
        val stopping = synchronized(lifecycleLock) {
            generation.incrementAndGet()
            runCatching { socket?.close() }
            clearConnectionState()
            job?.also { it.cancel() }
        }
        stopping?.join()
        synchronized(lifecycleLock) {
            if (job === stopping) job = null
        }
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
        val active = synchronized(lifecycleLock) {
            if (!_isConnected.value) null else socket?.let { connection -> writer?.let { connection to it } }
        } ?: return@withLock false
        val (activeSocket, activeWriter) = active
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
        if (!sent) disconnectSocket(activeSocket)
        sent
    }

    suspend fun publishFieldConfig(payload: String): Boolean = writerMutex.withLock {
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return@withLock false
        val configId = root.stringOrNull("id")?.takeIf(String::isNotBlank)
            ?: return@withLock false
        val revision = root.finiteLong("revision") ?: return@withLock false
        val active = synchronized(lifecycleLock) {
            if (!_isConnected.value) null else socket?.let { connection -> writer?.let { connection to it } }
        } ?: return@withLock false
        val (activeSocket, activeWriter) = active
        val message = JsonObject().apply {
            addProperty("protocol", PROTOCOL)
            addProperty("type", "fieldConfig")
            addProperty("configId", configId)
            addProperty("revision", revision)
            addProperty("sha256", Sha256.hex(payload))
            addProperty("payload", payload)
        }
        val sent = runCatching {
            activeWriter.write(message.toString())
            activeWriter.newLine()
            activeWriter.flush()
        }.isSuccess
        if (!sent) disconnectSocket(activeSocket)
        sent
    }

    suspend fun awaitFieldApply(
        configId: String,
        revision: Long,
        sha256: String,
        previousEventId: String?,
    ): XrpFieldApplyReceipt? = withTimeoutOrNull(FIELD_APPLY_TIMEOUT_MS) {
        fieldApplyReceipt.first { receipt ->
            receipt != null &&
                receipt.eventId != previousEventId &&
                receipt.configId == configId &&
                receipt.revision == revision &&
                receipt.sha256.equals(sha256, ignoreCase = true)
        }
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
        expectedCanonicalContentSha256: String,
        requestedGeneration: Long,
    ) {
        val connected = socketFactory()
        try {
            synchronized(lifecycleLock) {
                if (generation.get() != requestedGeneration) return
                socket = connected // stop() can now interrupt connect and handshake reads.
            }
            connected.tcpNoDelay = true
            connected.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            connected.soTimeout = HANDSHAKE_TIMEOUT_MS
            connected.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val hello = reader.readLine()?.takeIf { it.length <= MAX_LINE_LENGTH }
                    ?: error("XRP link closed before protocol handshake")
                val peer = parseHello(hello) ?: error("XRP link protocol handshake was invalid")
                require(peer.projectId == expectedProjectId) {
                    "XRP link reached project '${peer.projectId}', but Studio opened '$expectedProjectId'"
                }
                require(peer.canonicalContentSha256 == expectedCanonicalContentSha256) {
                    "XRP project content differs from Studio. Verify and deploy the current project before enabling control."
                }
                currentCoroutineContext().ensureActive()
                synchronized(lifecycleLock) {
                    if (generation.get() != requestedGeneration || socket !== connected) return
                    connected.soTimeout = 0
                    lastTelemetrySequence = -1L
                    writer = connected.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    _peerIdentity.value = peer
                    _connectionError.value = null
                    _isConnected.value = true
                }
                while (currentCoroutineContext().isActive && generation.get() == requestedGeneration) {
                    val line = reader.readLine() ?: break
                    currentCoroutineContext().ensureActive()
                    if (generation.get() != requestedGeneration) break
                    if (line.length > MAX_LINE_LENGTH) continue
                    acceptLine(line)
                }
            }
        } finally {
            disconnectSocket(connected)
        }
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
        return XrpPeerIdentity(
            projectId = projectId,
            contentSha256 = contentSha256,
            drivetrainType = drivetrainType,
            boardType = root.stringOrNull("boardType"),
            micropythonVersion = root.stringOrNull("micropythonVersion"),
            xrplibVersion = root.stringOrNull("xrplibVersion"),
            aresRuntimeVersion = root.stringOrNull("aresRuntimeVersion"),
            canonicalContentSha256 = root.stringOrNull("canonicalContentSha256")?.lowercase()?.takeIf { it.matches(SHA256) },
        )
    }

    private suspend fun acceptLine(line: String) {
        val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
        if (root.get("protocol")?.asString != PROTOCOL) return
        when (root.stringOrNull("type")) {
            "fieldApplied" -> acceptFieldApplied(root)
            "fieldRejected" -> acceptFieldRejected(root)
            "telemetry" -> acceptTelemetry(root)
        }
    }

    private fun acceptFieldApplied(root: JsonObject) {
        val session = root.stringOrNull("session")?.takeIf(String::isNotBlank) ?: return
        val sequence = root.finiteLong("sequence") ?: return
        val configId = root.stringOrNull("configId")?.takeIf(String::isNotBlank) ?: return
        val revision = root.finiteLong("revision") ?: return
        val sha256 = root.stringOrNull("sha256")?.lowercase()?.takeIf { it.matches(SHA256) } ?: return
        val obstacleCount = root.finiteInt("obstacleCount") ?: return
        val elementCount = root.finiteInt("elementCount") ?: return
        val aprilTagCount = root.finiteInt("aprilTagCount") ?: return
        _fieldApplyReceipt.value = XrpFieldApplyReceipt(
            session,
            sequence,
            configId,
            revision,
            sha256,
            obstacleCount,
            elementCount,
            aprilTagCount,
        )
    }

    private fun acceptFieldRejected(root: JsonObject) {
        val session = root.stringOrNull("session")?.takeIf(String::isNotBlank) ?: return
        val sequence = root.finiteLong("sequence") ?: return
        val message = root.stringOrNull("message")?.takeIf(String::isNotBlank) ?: return
        _fieldApplyFailure.value = XrpFieldApplyFailure("$session:$sequence", message)
    }

    private suspend fun acceptTelemetry(root: JsonObject) {
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
        root.finiteDouble("loopTimeMs")?.let { telemetrySink.acceptExternalLiveTelemetry("Robot/LoopTimeMs", it) }
        root.get("faulted")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
            telemetrySink.acceptExternalLiveTelemetry("Robot/Faulted", if (it) 1.0 else 0.0)
        }
        root.get("mode")?.takeIf { it.isJsonPrimitive }?.asString?.let {
            telemetrySink.acceptExternalLiveTelemetry("Robot/Mode", 0.0, it)
        }
        root.getAsJsonObject("subsystems")?.entrySet()?.forEach { (subsystemId, state) ->
            if (!state.isJsonObject) return@forEach
            state.asJsonObject.entrySet().forEach fieldLoop@{ (fieldId, value) ->
                if (!value.isJsonPrimitive) return@fieldLoop
                val path = "Subsystem/$subsystemId/$fieldId"
                val primitive = value.asJsonPrimitive
                when {
                    primitive.isNumber -> primitive.asDouble.takeIf(Double::isFinite)?.let {
                        telemetrySink.acceptExternalLiveTelemetry(path, it)
                    }
                    primitive.isBoolean -> telemetrySink.acceptExternalLiveTelemetry(path, if (primitive.asBoolean) 1.0 else 0.0)
                    primitive.isString -> telemetrySink.acceptExternalLiveTelemetry(path, 0.0, primitive.asString)
                }
            }
        }
    }

    private fun disconnectSocket(ownedSocket: Socket) {
        runCatching { ownedSocket.close() }
        synchronized(lifecycleLock) {
            if (socket === ownedSocket) clearConnectionState()
        }
    }

    /** Called only while holding lifecycleLock. */
    private fun clearConnectionState() {
        writer = null
        socket = null
        _isConnected.value = false
        _peerIdentity.value = null
        _fieldApplyReceipt.value = null
        _fieldApplyFailure.value = null
        _controlRequest.value = stoppedRequest()
        lastTelemetrySequence = -1L
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

    private fun JsonObject.finiteInt(name: String): Int? = finiteLong(name)
        ?.takeIf { it <= Int.MAX_VALUE }
        ?.toInt()

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
        private const val FIELD_APPLY_TIMEOUT_MS = 2_000L
        private const val MAX_LINE_LENGTH = 16_384
    }
}
