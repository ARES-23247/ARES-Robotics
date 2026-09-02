package com.ares.analytics.service

import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.areslib.telemetry.schema.HARDWARE_TOPOLOGY_TOPIC
import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.HardwareTopologyCodec
import com.ares.analytics.service.nt4.Nt4ConnectionLifecycle
import com.ares.analytics.service.nt4.Nt4InboundRouter
import com.ares.analytics.service.nt4.Nt4OutboundPublisher
import com.ares.analytics.service.nt4.Nt4TargetIdentity
import com.ares.analytics.service.nt4.Nt4Topic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock
import kotlin.math.hypot

internal const val SIMULATOR_POSE_FRAME_TOPIC = "ARES/SimulatorPoseFrame"
internal const val SIMULATOR_POSE_FRAME_VALUE_COUNT = 10
internal const val DRIVE_INPUT_ACK_TOPIC = "ARES/Control/DriveInputAck"
internal const val DRIVE_INPUT_ACK_VALUE_COUNT = 9
internal const val MECANUM_MOTOR_FRAME_TOPIC = "Hardware/Motors/MecanumFrame"
internal const val MECANUM_MOTOR_FRAME_VALUE_COUNT = 13

/** One immutable, same-cycle simulator localization sample decoded from the packed NT4 topic. */
data class SimulatorPoseFrameSnapshot(
    val trueX: Double,
    val trueY: Double,
    val trueHeading: Double,
    val ekfX: Double,
    val ekfY: Double,
    val ekfHeading: Double,
    val odomX: Double,
    val odomY: Double,
    val odomHeading: Double,
    val sequence: Long,
    val timestampMs: Long,
    val timestampUs: Long,
)

/** Latest fail-closed simulator receiver state for the desktop-owned drive-frame lease. */
data class DriveInputAcknowledgement(
    val version: Double,
    val statusCode: Int,
    val acceptedSession: Long,
    val acceptedSequence: Long,
    val leaseAgeMs: Long,
    val appliedVx: Double,
    val appliedVy: Double,
    val appliedOmega: Double,
    val rejectedFrameCount: Long,
    val timestampMs: Long,
)

internal fun decodeDriveInputAcknowledgement(value: Any?, timestampMs: Long): DriveInputAcknowledgement? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size != DRIVE_INPUT_ACK_VALUE_COUNT) return null

    fun numberAt(index: Int): Double? {
        val element = when (value) {
            is JsonArray -> value[index]
            is List<*> -> value[index]
            is DoubleArray -> value[index]
            is FloatArray -> value[index]
            is Array<*> -> value[index]
            else -> null
        }
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is Number -> element.toDouble()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    val version = numberAt(0) ?: return null
    val statusValue = numberAt(1) ?: return null
    val sessionValue = numberAt(2) ?: return null
    val sequenceValue = numberAt(3) ?: return null
    val ageValue = numberAt(4) ?: return null
    val rejectedValue = numberAt(8) ?: return null
    val statusCode = statusValue.toInt()
    val acceptedSession = sessionValue.toLong()
    val acceptedSequence = sequenceValue.toLong()
    val leaseAgeMs = ageValue.toLong()
    val rejectedFrameCount = rejectedValue.toLong()
    if (statusCode.toDouble() != statusValue || acceptedSession.toDouble() != sessionValue ||
        acceptedSequence.toDouble() != sequenceValue || leaseAgeMs.toDouble() != ageValue ||
        rejectedFrameCount.toDouble() != rejectedValue
    ) return null

    return DriveInputAcknowledgement(
        version = version,
        statusCode = statusCode,
        acceptedSession = acceptedSession,
        acceptedSequence = acceptedSequence,
        leaseAgeMs = leaseAgeMs,
        appliedVx = numberAt(5) ?: return null,
        appliedVy = numberAt(6) ?: return null,
        appliedOmega = numberAt(7) ?: return null,
        rejectedFrameCount = rejectedFrameCount,
        timestampMs = timestampMs,
    )
}

/** One complete, same-tick mecanum simulator observation in FL, FR, RL, RR order. */
data class MecanumMotorFrameSnapshot(
    val flPower: Double,
    val frPower: Double,
    val rlPower: Double,
    val rrPower: Double,
    val flVelocity: Double,
    val frVelocity: Double,
    val rlVelocity: Double,
    val rrVelocity: Double,
    val flCurrentAmps: Double,
    val frCurrentAmps: Double,
    val rlCurrentAmps: Double,
    val rrCurrentAmps: Double,
    val sequence: Long,
    val timestampMs: Long,
)

internal fun decodeMecanumMotorFrame(value: Any?, timestampMs: Long): MecanumMotorFrameSnapshot? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size != MECANUM_MOTOR_FRAME_VALUE_COUNT) return null

    fun numberAt(index: Int): Double? {
        val element = when (value) {
            is JsonArray -> value[index]
            is List<*> -> value[index]
            is DoubleArray -> value[index]
            is FloatArray -> value[index]
            is Array<*> -> value[index]
            else -> null
        }
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is Number -> element.toDouble()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    val flPower = numberAt(0) ?: return null
    val frPower = numberAt(1) ?: return null
    val rlPower = numberAt(2) ?: return null
    val rrPower = numberAt(3) ?: return null
    val flVelocity = numberAt(4) ?: return null
    val frVelocity = numberAt(5) ?: return null
    val rlVelocity = numberAt(6) ?: return null
    val rrVelocity = numberAt(7) ?: return null
    val flCurrentAmps = numberAt(8) ?: return null
    val frCurrentAmps = numberAt(9) ?: return null
    val rlCurrentAmps = numberAt(10) ?: return null
    val rrCurrentAmps = numberAt(11) ?: return null
    val sequenceValue = numberAt(12) ?: return null
    val sequence = sequenceValue.toLong()
    if (sequence < 0L || sequence.toDouble() != sequenceValue) return null
    return MecanumMotorFrameSnapshot(
        flPower = flPower, frPower = frPower, rlPower = rlPower, rrPower = rrPower,
        flVelocity = flVelocity, frVelocity = frVelocity, rlVelocity = rlVelocity, rrVelocity = rrVelocity,
        flCurrentAmps = flCurrentAmps, frCurrentAmps = frCurrentAmps,
        rlCurrentAmps = rlCurrentAmps, rrCurrentAmps = rrCurrentAmps,
        sequence = sequence,
        timestampMs = timestampMs,
    )
}

/** Decodes without retaining any producer- or MessagePack-owned array storage. */
internal fun decodeSimulatorPoseFrame(
    value: Any?,
    timestampMs: Long,
    timestampUs: Long,
): SimulatorPoseFrameSnapshot? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size != SIMULATOR_POSE_FRAME_VALUE_COUNT) return null

    fun numberAt(index: Int): Double? {
        val element = when (value) {
            is JsonArray -> value[index]
            is List<*> -> value[index]
            is DoubleArray -> value[index]
            is FloatArray -> value[index]
            is Array<*> -> value[index]
            else -> null
        }
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is Number -> element.toDouble()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    val trueX = numberAt(0) ?: return null
    val trueY = numberAt(1) ?: return null
    val trueHeading = numberAt(2) ?: return null
    val ekfX = numberAt(3) ?: return null
    val ekfY = numberAt(4) ?: return null
    val ekfHeading = numberAt(5) ?: return null
    val odomX = numberAt(6) ?: return null
    val odomY = numberAt(7) ?: return null
    val odomHeading = numberAt(8) ?: return null
    val sequenceValue = numberAt(9) ?: return null
    val sequence = sequenceValue.toLong()
    if (sequence < 0L || sequence.toDouble() != sequenceValue) return null

    return SimulatorPoseFrameSnapshot(
        trueX = trueX,
        trueY = trueY,
        trueHeading = trueHeading,
        ekfX = ekfX,
        ekfY = ekfY,
        ekfHeading = ekfHeading,
        odomX = odomX,
        odomY = odomY,
        odomHeading = odomHeading,
        sequence = sequence,
        timestampMs = timestampMs,
        timestampUs = timestampUs,
    )
}

/**
 * High-performance **NetworkTables NT4 WebSocket Streaming Client**.
 *
 * Establishes real-time, non-blocking binary and JSON WebSocket streams over port `5810` with FRC roboRIOs,
 * FTC Control Hubs, and ARES Physics Simulators.
 *
 * ### NetworkTables 4 Protocol Specifications:
 * - **WebSocket Connection URI:** `ws://<host>:5810/nt/ARES-Analytics-<timestamp>`
 * - **Subscription Handshake:**
 *   $$\text{Subscribe} \iff \{ \text{"method": "subscribe"}, \text{"params": } \{ \text{"topics": } [\text{"/Drive/Pose_X"}, \text{"/Drive/Pose_Y"}, \dots] \} \}$$
 *
 * ### Performance & Memory Guarantees:
 * - **Streaming Rate:** $20\text{ Hz}$ live telemetry to $100\text{ Hz}$ high-density log replay
 * - **Backpressure:** bounded lossless buffers suspend the WebSocket reader when consumers or persistence fall behind.
 * - **Thread Safety:** Fully thread-safe state management via `ConcurrentHashMap` and atomic volatile references.
 *
 * @param databaseService DuckDB log persistence engine for historical telemetry recording.
 * @see TelemetryFrame
 * @see DatabaseService
 */
open class Nt4ClientService(
    private val databaseService: DatabaseService
) {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e -> e.printStackTrace() })
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val liveTimelineEpochUs = System.currentTimeMillis() * 1_000L
    private val liveTimelineMonotonicOriginNs = System.nanoTime()
    private val _selectedRedAlliance = MutableStateFlow(true)
    /** Dashboard-owned alliance selection; survives view/navigation and NT4 reconnect lifecycles. */
    val selectedRedAlliance: StateFlow<Boolean> = _selectedRedAlliance.asStateFlow()
    val isReplayActive = MutableStateFlow(false)

    val telemetryStore = TelemetryStore()
    private val uiTelemetryFanout = UiTelemetryFanout(serviceScope)
    open val telemetryFlow: SharedFlow<TelemetryFrame> = telemetryStore.updates
    /** UI-rate latest values; raw logging and analysis continue to use [telemetryFlow]. */
    open val uiTelemetryFlow: SharedFlow<TelemetryFrame> = uiTelemetryFanout.updates
    private val _simulatorPoseFrame = MutableStateFlow<SimulatorPoseFrameSnapshot?>(null)
    /** Latest packed simulator pose, kept atomic and independent of the lossy telemetry fan-out. */
    val simulatorPoseFrame: StateFlow<SimulatorPoseFrameSnapshot?> = _simulatorPoseFrame.asStateFlow()
    private val _driveInputAcknowledgement = MutableStateFlow<DriveInputAcknowledgement?>(null)
    /** Packed receiver feedback bypasses general telemetry fan-out to avoid a 50 Hz UI storm. */
    val driveInputAcknowledgement: StateFlow<DriveInputAcknowledgement?> =
        _driveInputAcknowledgement.asStateFlow()
    private val _mecanumMotorFrame = MutableStateFlow<MecanumMotorFrameSnapshot?>(null)
    /** Latest complete motor observation, independent of scalar-topic suppression and UI loss. */
    val mecanumMotorFrame: StateFlow<MecanumMotorFrameSnapshot?> = _mecanumMotorFrame.asStateFlow()
    private val _robotLighting = MutableStateFlow(RobotLightingTelemetryState())
    /** One normalized lighting state shared by the dashboard card and field renderer. */
    val robotLighting: StateFlow<RobotLightingTelemetryState> = _robotLighting.asStateFlow()
    private var lastSimulatorPoseDivergenceLogNs = Long.MIN_VALUE

    init {
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            telemetryStore.updates.collect { frame ->
                robotLightingReading(frame.key, frame.value)?.let { reading ->
                    _robotLighting.update { current ->
                        if (current.outputs[reading.stableName] == reading) current
                        else current.copy(outputs = current.outputs + (reading.stableName to reading))
                    }
                }
                val frameTargetEpoch = telemetryStore.currentTargetEpoch()
                if (telemetryStore.isCurrentNotifiedFrame(frame)) {
                    uiTelemetryFanout.offer(frame, frameTargetEpoch)
                }
            }
        }
    }

    private val _consoleFlow = MutableSharedFlow<ConsoleMessage>(
        replay = 100,
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
    )
    val consoleFlow: SharedFlow<ConsoleMessage> = _consoleFlow.asSharedFlow()

    /**
     * Injects a replay frame into the telemetry flow so dashboard widgets consume
     * replay data identically to live data. Called by the replay integration layer.
     */
    suspend fun emitReplayFrame(frame: TelemetryFrame) {
        telemetryStore.accept(frame)
    }

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val _latestTopology = MutableStateFlow<HardwareTopology?>(null)
    val latestTopology: StateFlow<HardwareTopology?> = _latestTopology.asStateFlow()

    fun setLatestTopology(topology: HardwareTopology?) {
        _latestTopology.value = topology
    }

    private val outboundPublisher = Nt4OutboundPublisher()
    private val driveFrameTelemetryRecorder = DriveFrameTelemetryRecorder(serviceScope) { frame ->
        telemetryStore.accept(frame, notifyConsumers = false)
    }
    private val inboundRouter = Nt4InboundRouter(
        onClockSyncReply = outboundPublisher::acceptTimeSyncReply,
        onValue = { topic, value, timestampMs, timestampUs, target ->
            dispatchValue(
                topic,
                value,
                timestampMs,
                timestampUs,
                target.teamId,
                target.seasonId,
                target.robotId
            )
        }
    )
    private val connectionLifecycle = Nt4ConnectionLifecycle(
        scope = serviceScope,
        inboundRouter = inboundRouter,
        outboundPublisher = outboundPublisher,
        subscriptionPrefixes = CANONICAL_SUBSCRIPTION_PREFIXES,
        deleteLiveTelemetry = { databaseService.deleteTelemetryFrames(LIVE_SESSION_ID) },
        flushPendingFrames = ::flushPendingFrames,
        clearLiveTargetState = ::clearLiveTargetState
    )
    open val isConnected: StateFlow<Boolean>
        get() = connectionLifecycle.isConnected
    val serverIp: String
        get() = connectionLifecycle.serverIp

    fun connectionMetrics(): Nt4ConnectionMetrics = connectionLifecycle.metrics()

    internal val topicMap: ConcurrentHashMap<Int, Nt4Topic>
        get() = inboundRouter.topicMap
    internal val malformedTextFrameCount: java.util.concurrent.atomic.AtomicLong
        get() = inboundRouter.malformedTextFrameCount
    /** Direct latest-value view used by snapshot-oriented dashboard components. */
    val latestValues: ConcurrentHashMap<String, TelemetryFrame> = telemetryStore.latestFrames

    fun getActiveTopics(): List<String> = inboundRouter.activeTopics()

    private val pendingFrames = kotlinx.coroutines.channels.Channel<TelemetryFrame>(capacity = 100_000)
    private val retryFrames = java.util.ArrayDeque<TelemetryFrame>()
    private val flushMutex = kotlinx.coroutines.sync.Mutex()
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    suspend fun flushPendingFrames(): Boolean = flushMutex.withLock {
        // Do not drain newer channel values behind a failed batch. Keeping one ordered retry
        // deque plus the bounded channel preserves arrival order and applies backpressure.
        if (retryFrames.isEmpty()) {
            while (true) {
                val frame = pendingFrames.tryReceive().getOrNull() ?: break
                retryFrames.addLast(frame)
            }
        }

        var latestLiveTimestamp: Long? = null
        while (retryFrames.isNotEmpty()) {
            val liveBatch = retryFrames.first().sessionId == LIVE_SESSION_ID
            val chunk = ArrayList<TelemetryFrame>(PERSISTENCE_BATCH_SIZE)
            val iterator = retryFrames.iterator()
            while (iterator.hasNext() && chunk.size < PERSISTENCE_BATCH_SIZE) {
                val frame = iterator.next()
                if ((frame.sessionId == LIVE_SESSION_ID) != liveBatch) break
                chunk.add(frame)
            }

            try {
                databaseService.insertTelemetryFrames(chunk)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withLock false
            }
            repeat(chunk.size) { retryFrames.removeFirst() }
            if (liveBatch) {
                chunk.maxOfOrNull(TelemetryFrame::timestampMs)?.let { chunkMax ->
                    latestLiveTimestamp = maxOf(latestLiveTimestamp ?: Long.MIN_VALUE, chunkMax)
                }
            }
        }

        latestLiveTimestamp?.let { newestTimestamp ->
            try {
                databaseService.pruneTelemetryFrames(LIVE_SESSION_ID, newestTimestamp - LIVE_RETENTION_MS)
            } catch (e: Exception) {
                // Pruning is maintenance, not persistence. Frames were committed successfully.
                e.printStackTrace()
            }
        }
        true
    }

    internal suspend fun retainedRetryFrameCount(): Int = flushMutex.withLock { retryFrames.size }

    /**
     * Live rewind is a laptop-observed session, so its durable timeline uses monotonic receipt
     * time. Robot/server timestamps can begin at zero, reset across simulator OpModes, or describe
     * a retained value from before this client connected. Keeping those timestamps in the raw UI
     * frame is useful, but using them as DuckDB's live timeline creates gaps and broken scrubbing.
     */
    private fun liveReceiptTimestampUs(): Long =
        liveTimelineEpochUs + (System.nanoTime() - liveTimelineMonotonicOriginNs) / 1_000L

    internal fun clearLiveTargetState() {
        inboundRouter.clear()
        val nextTargetEpoch = telemetryStore.clear()
        uiTelemetryFanout.reset(nextTargetEpoch)
        _simulatorPoseFrame.value = null
        _driveInputAcknowledgement.value = null
        _mecanumMotorFrame.value = null
        _robotLighting.value = RobotLightingTelemetryState()
    }

    fun start(host: String, teamId: String, seasonId: String, robotId: String, port: Int = 5810) {
        if (disposed.get()) {
            println("[Nt4ClientService] Ignoring start after terminal disposal")
            return
        }
        connectionLifecycle.start(host, port, Nt4TargetIdentity(teamId, seasonId, robotId))
    }

    internal fun encodeNt4BinaryUpdate(
        pubuid: Int,
        timestampUs: Long,
        typeId: Byte,
        valueBytes: ByteArray
    ): ByteArray = outboundPublisher.encodeNt4BinaryUpdate(pubuid, timestampUs, typeId, valueBytes)

    suspend fun publishInputDouble(pubuid: Int, value: Double): Boolean =
        outboundPublisher.publishInputDouble(pubuid, value)

    suspend fun publishInputString(pubuid: Int, value: String): Boolean =
        outboundPublisher.publishInputString(pubuid, value)

    suspend fun stop(): Boolean {
        connectionLifecycle.stop()
        var persisted = false
        for (attempt in 0 until SHUTDOWN_FLUSH_ATTEMPTS) {
            val sessionFinalized = runCatching {
                if (_currentSession.value != null) stopRecordingSession()
                true
            }.getOrDefault(false)
            if (sessionFinalized && flushPendingFrames()) {
                persisted = true
                break
            }
            if (attempt + 1 < SHUTDOWN_FLUSH_ATTEMPTS) delay(SHUTDOWN_FLUSH_RETRY_MS)
        }
        return persisted
    }

    /**
     * Terminal desktop-shutdown boundary. Unlike [stop], this permanently rejects later starts
     * from Compose effects that may observe simulator/process state while the window is closing.
     */
    suspend fun disposeAndJoin(): Boolean {
        disposed.set(true)
        return stop()
    }

    suspend fun publishFrame(frame: TelemetryFrame) {
        val finalFrame = sessionMutex.withLock {
            val sessionId = _currentSession.value?.sessionId ?: LIVE_SESSION_ID
            frame.copy(sessionId = sessionId).also { pendingFrames.send(it) }
        }
        telemetryStore.accept(finalFrame, notifyConsumers = !isReplayActive.value)
    }

    suspend fun startRecordingSession(
        teamId: String,
        seasonId: String,
        robotId: String,
        matchNumber: Int? = null,
        allianceColor: String? = null,
        tags: List<String> = emptyList()
    ): Session {
        val session = Session(
            sessionId = UUID.randomUUID().toString(),
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            createdAt = System.currentTimeMillis(),
            matchNumber = matchNumber,
            allianceColor = allianceColor,
            tags = tags
        )
        sessionMutex.withLock {
            check(_currentSession.value == null) {
                "A telemetry recording is already in progress. Stop and save it before starting another."
            }
            databaseService.insertSession(session)
            _currentSession.value = session
        }
        return session
    }

    suspend fun stopRecordingSession() {
        sessionMutex.withLock {
            val session = _currentSession.value ?: return
            if (!flushPendingFrames()) {
                throw java.io.IOException("Failed to persist all pending telemetry frames")
            }
            val endTime = System.currentTimeMillis()
            val duration = endTime - session.createdAt
            databaseService.insertSession(session.copy(durationMs = duration))
            _currentSession.value = null
        }
    }

    internal suspend fun handleIncomingText(
        text: String,
        teamId: String,
        seasonId: String,
        robotId: String
    ) = inboundRouter.handleText(text, Nt4TargetIdentity(teamId, seasonId, robotId))

    internal suspend fun handleIncomingBinary(
        bytes: ByteArray,
        teamId: String,
        seasonId: String,
        robotId: String
    ) = inboundRouter.handleBinary(bytes, Nt4TargetIdentity(teamId, seasonId, robotId))

    private suspend fun dispatchValue(
        ntTopic: Nt4Topic,
        valueElement: Any?,
        timestampMs: Long,
        timestampUs: Long,
        teamId: String,
        seasonId: String,
        robotId: String
    ) {
        // Normalize key: strip leading '/' for consistent matching everywhere
        val normalizedName = com.ares.analytics.service.log.TelemetryTopicExtractor.normalizeTopic(ntTopic.name.removePrefix("/"))

        inboundRouter.markDiscovered(normalizedName, ntTopic.type)

        if (normalizedName == SIMULATOR_POSE_FRAME_TOPIC && !isReplayActive.value) {
            decodeSimulatorPoseFrame(valueElement, timestampMs, timestampUs)?.let { frame ->
                logSimulatorPoseDivergence(frame)
                _simulatorPoseFrame.value = frame
            }
        }


        if (normalizedName == DRIVE_INPUT_ACK_TOPIC) {
            if (!isReplayActive.value) {
                decodeDriveInputAcknowledgement(valueElement, timestampMs)?.let {
                    _driveInputAcknowledgement.value = it
                }
            }
            return
        }

        if (normalizedName == MECANUM_MOTOR_FRAME_TOPIC) {
            if (!isReplayActive.value) {
                decodeMecanumMotorFrame(valueElement, timestampMs)?.let {
                    _mecanumMotorFrame.value = it
                }
            }
            return
        }

        // Skip input topics that the dashboard publishes — they echo back from the
        // simulator and cause 50Hz recomposition storms across all widgets
        if (normalizedName.startsWith("ARES/Input/")) return

        // Note: ARES/Session/LogFilePath was previously linked to the session row, but
        // session↔logfile linkage is no longer tracked (the DuckDB session schema has no
        // log_file_path column), so the topic is now intentionally ignored.

        // Handle topology mapping directly
        if (normalizedName == HARDWARE_TOPOLOGY_TOPIC) {
            try {
                val topologyJson = if (valueElement is JsonPrimitive) valueElement.content else valueElement.toString()
                val topology = HardwareTopologyCodec.decode(topologyJson)
                _latestTopology.value = topology
                databaseService.insertTopology(topology)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        // Intercept and handle console log messages. Match a closed set of exact topic
        // names (case-insensitive equality) instead of a substring test — `contains("log")`
        // previously misclassified any telemetry topic containing "log" as console output (AUDIT H8).
        val lowerName = normalizedName.lowercase()
        if (lowerName == "ares/console" || lowerName == "robot/console" ||
            lowerName == "system/print" || lowerName == "robot/print") {
            try {
                val text = if (valueElement is JsonPrimitive) valueElement.content else valueElement.toString()
                val severity = when {
                    text.contains("[ERROR]", ignoreCase = true) || text.contains("error:", ignoreCase = true) -> "ERROR"
                    text.contains("[WARN]", ignoreCase = true) || text.contains("warning:", ignoreCase = true) -> "WARN"
                    else -> "INFO"
                }
                val session = _currentSession.value
                val sessionId = session?.sessionId ?: "live-telemetry"
                val consoleMsg = ConsoleMessage(timestampMs, text, severity)

                // Save in DB if session is active
                if (session != null) {
                    serviceScope.launch {
                        databaseService.insertConsoleMessages(listOf(consoleMsg), sessionId)
                    }
                }
                _consoleFlow.emit(consoleMsg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Console lines are persisted exclusively as console messages; falling through
            // here would double-persist each line as a telemetry frame under the same key.
            return
        }

        val liveReceiptUs = liveReceiptTimestampUs()

        if (valueElement is JsonArray || valueElement is List<*> || valueElement is DoubleArray || valueElement is FloatArray || valueElement is Array<*>) {
            val size = when (valueElement) {
                is JsonArray -> valueElement.size
                is List<*> -> valueElement.size
                is DoubleArray -> valueElement.size
                is FloatArray -> valueElement.size
                is Array<*> -> valueElement.size
                else -> 0
            }
            if (size > MAX_INCOMING_ARRAY_ELEMENTS) {
                println("[Nt4ClientService] Rejected oversized array topic $normalizedName ($size elements)")
                return
            }

            val sb = StringBuilder(normalizedName).append("/")
            val baseLen = sb.length

            for (idx in 0 until size) {
                val element = when (valueElement) {
                    is JsonArray -> valueElement[idx]
                    is List<*> -> valueElement[idx]
                    is DoubleArray -> valueElement[idx]
                    is FloatArray -> valueElement[idx]
                    is Array<*> -> valueElement[idx]
                    else -> null
                }

                val (doubleValue, stringValue) = coerceTelemetryValue(element)
                sb.setLength(baseLen)
                val frameKey = sb.append(idx).toString()
                val frame = sessionMutex.withLock {
                    val sessionId = _currentSession.value?.sessionId ?: LIVE_SESSION_ID
                    TelemetryFrame(
                        timestampMs = timestampMs,
                        sessionId = sessionId,
                        key = frameKey,
                        value = doubleValue,
                        stringValue = stringValue,
                        timestampUs = timestampUs
                    ).also { sourceFrame ->
                        pendingFrames.send(
                            if (sessionId == LIVE_SESSION_ID) {
                                sourceFrame.copy(
                                    timestampMs = liveReceiptUs / 1_000L,
                                    timestampUs = liveReceiptUs
                                )
                            } else {
                                sourceFrame
                            }
                        )
                    }
                }
                telemetryStore.accept(frame, notifyConsumers = !isReplayActive.value)
            }
            return
        }

        // Extract double value and string value
        val (doubleValue, stringValue) = coerceTelemetryValue(valueElement)
        val frame = sessionMutex.withLock {
            val sessionId = _currentSession.value?.sessionId ?: LIVE_SESSION_ID
            TelemetryFrame(
                timestampMs = timestampMs,
                sessionId = sessionId,
                key = normalizedName,
                value = doubleValue,
                stringValue = stringValue,
                timestampUs = timestampUs
            ).also { sourceFrame ->
                pendingFrames.send(
                    if (sessionId == LIVE_SESSION_ID) {
                        sourceFrame.copy(
                            timestampMs = liveReceiptUs / 1_000L,
                            timestampUs = liveReceiptUs
                        )
                    } else {
                        sourceFrame
                    }
                )
            }
        }
        telemetryStore.accept(frame, notifyConsumers = !isReplayActive.value)
    }

    /**
     * Leaves one rate-limited breadcrumb when a simulator publishes localization sources that are
     * visibly far apart. This distinguishes a producer/EKF defect from downstream UI staleness.
     */
    private fun logSimulatorPoseDivergence(frame: SimulatorPoseFrameSnapshot) {
        val ekfErrorM = hypot(frame.ekfX - frame.trueX, frame.ekfY - frame.trueY)
        val odomErrorM = hypot(frame.odomX - frame.trueX, frame.odomY - frame.trueY)
        if (ekfErrorM <= SIMULATOR_POSE_DIVERGENCE_LOG_THRESHOLD_M &&
            odomErrorM <= SIMULATOR_POSE_DIVERGENCE_LOG_THRESHOLD_M
        ) return

        val nowNs = System.nanoTime()
        if (lastSimulatorPoseDivergenceLogNs != Long.MIN_VALUE &&
            nowNs - lastSimulatorPoseDivergenceLogNs < SIMULATOR_POSE_DIVERGENCE_LOG_INTERVAL_NS
        ) return

        lastSimulatorPoseDivergenceLogNs = nowNs
        println(
            "[SimulatorPoseFrame] divergence sequence=${frame.sequence}, " +
                "ekfErrorM=$ekfErrorM, odomErrorM=$odomErrorM, " +
                "truth=(${frame.trueX}, ${frame.trueY}, ${frame.trueHeading}), " +
                "ekf=(${frame.ekfX}, ${frame.ekfY}, ${frame.ekfHeading}), " +
                "odom=(${frame.odomX}, ${frame.odomY}, ${frame.odomHeading})"
        )
    }

    suspend fun publishDouble(key: String, value: Double) {
        val cleanKey = key.removePrefix("/")
        require(!cleanKey.startsWith("ARES/Input/")) {
            "ARES/Input controls must use the atomic driveFrame publisher"
        }
        val frame = TelemetryFrame(
            timestampMs = System.currentTimeMillis(),
            sessionId = _currentSession.value?.sessionId ?: "live-telemetry",
            key = cleanKey,
            value = value
        )
        telemetryStore.accept(frame)
        outboundPublisher.publishDouble(cleanKey, value)
    }

    /** Publishes a typed boolean topic; tuning must not encode booleans as doubles. */
    suspend fun publishBoolean(key: String, value: Boolean) {
        val cleanKey = key.removePrefix("/")
        require(!cleanKey.startsWith("ARES/Input/")) { "ARES/Input controls must use the atomic driveFrame publisher" }
        telemetryStore.accept(TelemetryFrame(System.currentTimeMillis(), _currentSession.value?.sessionId ?: "live-telemetry", cleanKey, if (value) 1.0 else 0.0))
        outboundPublisher.publishBoolean(cleanKey, value)
    }

    /**
     * Publishes a one-shot string after the NT4 clock handshake makes outbound timestamps valid.
     *
     * `isConnected` becomes true as soon as the WebSocket opens, slightly before publisher
     * declarations and time synchronization finish. Driver-station commands are not periodic, so
     * silently dropping one in that window can leave the simulator disabled. Retry only while the
     * same connection remains live, then report the delivery attempt to the caller.
     */
    suspend fun publishString(key: String, value: String): Boolean {
        val cleanKey = key.removePrefix("/")
        require(!cleanKey.startsWith("ARES/Input/") || cleanKey in ALLOWED_INPUT_STRING_TOPICS) {
            "ARES/Input controls must use driveFrame; only field-configuration strings are separate"
        }
        if (!isDashboardDriverStationCommandAllowed(serverIp, cleanKey)) return false
        val frame = TelemetryFrame(
            timestampMs = System.currentTimeMillis(),
            sessionId = _currentSession.value?.sessionId ?: "live-telemetry",
            key = cleanKey,
            value = 0.0,
            stringValue = value
        )
        repeat(100) {
            if (outboundPublisher.publishString(cleanKey, value)) {
                telemetryStore.accept(frame)
                return true
            }
            if (!isConnected.value) return false
            delay(20L)
        }
        return false
    }

    /** Selects the alliance encoded into every subsequent atomic control frame. */
    fun selectRedAlliance(value: Boolean) {
        _selectedRedAlliance.value = value
    }

    /** Returns a process-unique safe integer nonce for a new control session. */
    fun nextDriveSessionNonce(): Double = outboundPublisher.nextDriveSessionNonce()

    suspend fun publishDriveFrame(values: DoubleArray): Boolean {
        // Dashboard drive input is a simulator-only capability. Enforce that at the transport
        // boundary so a stale armed UI cannot publish motion to a Control Hub or roboRIO after a
        // target switch.
        if (!isLoopbackDriveControlHost(serverIp)) return false
        if (!outboundPublisher.publishDriveFrame(values)) return false
        driveFrameTelemetryRecorder.offer(
            DriveFrameTelemetrySnapshot(
                timestampMs = System.currentTimeMillis(),
                sessionId = _currentSession.value?.sessionId ?: "live-telemetry",
                // The UI session reuses its packet buffer. Copy only after the synchronous wire
                // encoder has consumed it, and transfer this copy to the background recorder.
                values = values.copyOf(),
            )
        )
        return true
    }

    fun subscribeDouble(key: String): Flow<Double> {
        return telemetryFlow.filter { it.key == key }.map { it.value }
    }

    internal fun coerceTelemetryValue(valueElement: Any?): Pair<Double, String?> = when (valueElement) {
        is JsonPrimitive -> when {
            valueElement.isString -> (valueElement.content.toDoubleOrNull() ?: 0.0) to valueElement.content
            valueElement.booleanOrNull != null -> (if (valueElement.boolean) 1.0 else 0.0) to null
            else -> (valueElement.doubleOrNull ?: 0.0) to null
        }
        is Boolean -> (if (valueElement) 1.0 else 0.0) to null
        is Number -> valueElement.toDouble() to null
        is String -> (valueElement.toDoubleOrNull() ?: 0.0) to valueElement
        else -> 0.0 to null
    }

    companion object {
        private const val MAX_INCOMING_ARRAY_ELEMENTS = 4_096
        private const val SHUTDOWN_FLUSH_ATTEMPTS = 5
        private const val SHUTDOWN_FLUSH_RETRY_MS = 100L
        private const val PERSISTENCE_BATCH_SIZE = 5_000
        private const val SIMULATOR_POSE_DIVERGENCE_LOG_THRESHOLD_M = 0.25
        private const val SIMULATOR_POSE_DIVERGENCE_LOG_INTERVAL_NS = 30_000_000_000L
        private val ALLOWED_INPUT_STRING_TOPICS = setOf(
            "ARES/Input/obstacles",
            "ARES/Input/fieldConfig",
            "ARES/Input/selectedAuto",
        )
        internal const val LIVE_SESSION_ID = "live-telemetry"
        /** Amount of recent live telemetry intentionally retained in the ephemeral database. */
        internal const val LIVE_RETENTION_MS = 300_000L
        internal val CANONICAL_SUBSCRIPTION_PREFIXES = listOf(
            "ARES", "Drive", "Robot", "Hardware", "Topology", "Tuning",
            "Profiling", "Diagnostics", "Vision", "Path", "Gamepad1", "Gamepad2",
            "Superstructure", "Subsystems", "Calibration", "SysId", "Swerve", "Mechanism",
            "LoopTimeMs", "TimestampMs"
        )
    }
}

/** True only for hosts that cannot directly address a physical robot on the network. */
internal fun isLoopbackDriveControlHost(host: String): Boolean = when (
    host.trim().lowercase().removePrefix("[").removeSuffix("]")
) {
    "127.0.0.1", "localhost", "::1" -> true
    else -> false
}

private val SIMULATOR_ONLY_DRIVER_STATION_TOPICS = setOf(
    "ARES/DriverStation/SelectedOpMode",
    "ARES/DriverStation/Command",
    "ARES/DriverStation/MatchState",
    "ARES/Input/selectedAuto",
)

/** Prevents dashboard OpMode orchestration from reaching a physical robot target. */
internal fun isDashboardDriverStationCommandAllowed(host: String, key: String): Boolean =
    key.removePrefix("/") !in SIMULATOR_ONLY_DRIVER_STATION_TOPICS || isLoopbackDriveControlHost(host)

data class Nt4ConnectionMetrics(
    val attempts: Long,
    val successfulConnections: Long,
    val reconnects: Long,
    val connected: Boolean
)
