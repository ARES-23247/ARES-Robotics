package com.ares.analytics.service.nt4

import com.ares.analytics.service.DriveFrameContractValidator
import com.areslib.telemetry.schema.DesktopDriveProtocol
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns NT4 publisher registration, wire encoding, clock translation, and the leased drive-frame
 * sender contract. Connection and telemetry-reduction state intentionally live elsewhere.
 */
internal class Nt4OutboundPublisher(
    private val monotonicTimeUs: () -> Long = { System.nanoTime() / 1_000L }
) {
    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var serverTimeOffsetUs: Long? = null

    @Volatile
    private var bestClockRoundTripUs: Long = Long.MAX_VALUE

    private val driveFramePublishMutex = Mutex()
    private val driveFrameValidator = DriveFrameContractValidator()
    private val driveSessionNonceCounter = AtomicLong(
        ThreadLocalRandom.current().nextLong(1L, DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG)
    )
    private val dynamicPubMutex = Mutex()
    private var nextPubUid = 2_000
    private val dynamicPubUids = ConcurrentHashMap(FIXED_PUBLISH_UIDS)
    private val publisherTypes = ConcurrentHashMap(FIXED_PUBLISH_TYPES)
    private val publishDoubleBuffer = ThreadLocal.withInitial { ByteArray(9) }
    private val publishDoubleArrayBuffer = ThreadLocal.withInitial { ByteArray(73) }

    suspend fun attach(connectedSession: DefaultClientWebSocketSession) {
        driveFramePublishMutex.withLock { driveFrameValidator.reset() }
        serverTimeOffsetUs = null
        bestClockRoundTripUs = Long.MAX_VALUE
        session = connectedSession
    }

    fun detach(connectedSession: DefaultClientWebSocketSession) {
        if (session === connectedSession) {
            session = null
            serverTimeOffsetUs = null
        }
    }

    fun fixedPublishMessage(): String = FIXED_PUBLISH_MESSAGE

    suspend fun dynamicPublishMessages(): List<String> = dynamicPubMutex.withLock {
        dynamicPubUids.mapNotNull { (key, id) ->
            if (key in FIXED_PUBLISH_TOPICS) null
            else publisherTypes[key]?.let { type -> buildPublishMessage(key, id, type) }
        }
    }

    suspend fun sendTimeSyncRequest() {
        val sentAtUs = monotonicTimeUs()
        val buffer = ByteArray(13)
        buffer[0] = 0x94.toByte()
        buffer[1] = 0xff.toByte()
        buffer[2] = 0x00
        buffer[3] = 0x02
        buffer[4] = 0xd3.toByte()
        for (index in 0 until 8) {
            buffer[5 + index] = (sentAtUs shr (56 - index * 8)).toByte()
        }
        session?.send(Frame.Binary(true, buffer))
    }

    fun acceptTimeSyncReply(serverTimestampUs: Long, sentAtUs: Long) {
        val receivedAtUs = monotonicTimeUs()
        val roundTripUs = receivedAtUs - sentAtUs
        if (roundTripUs >= 0L && roundTripUs < bestClockRoundTripUs) {
            bestClockRoundTripUs = roundTripUs
            serverTimeOffsetUs = serverTimestampUs + roundTripUs / 2L - receivedAtUs
        }
    }

    fun nextDriveSessionNonce(): Double = driveSessionNonceCounter.getAndUpdate { current ->
        if (current >= DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG) 1L else current + 1L
    }.toDouble()

    suspend fun publishDriveFrame(values: DoubleArray): Boolean = driveFramePublishMutex.withLock {
        // The caller retains the buffer until this method returns. Validation and MessagePack
        // encoding are synchronous inside this mutex, so a defensive second array copy only adds
        // allocation pressure to the 50 Hz control path.
        val pendingState = driveFrameValidator.validate(values)
        if (!publishInputDoubleArray(DRIVE_FRAME_PUB_UID, values)) return@withLock false
        driveFrameValidator.commit(pendingState)
        true
    }

    suspend fun publishDouble(key: String, value: Double): Boolean {
        val pubUid = ensurePublisher(key, "double")
        return publishInputDouble(pubUid, value)
    }

    suspend fun publishBoolean(key: String, value: Boolean): Boolean {
        val pubUid = ensurePublisher(key, "boolean")
        return sendBinaryUpdate(
            pubUid,
            0.toByte(),
            byteArrayOf(if (value) 0xc3.toByte() else 0xc2.toByte())
        )
    }

    suspend fun publishString(key: String, value: String): Boolean {
        val pubUid = ensurePublisher(key, "string")
        return publishInputString(pubUid, value)
    }

    internal fun encodeNt4BinaryUpdate(
        pubUid: Int,
        timestampUs: Long,
        typeId: Byte,
        valueBytes: ByteArray
    ): ByteArray {
        require(pubUid in 0..0xffff) { "publisher UID must fit an unsigned 16-bit NT4 ID" }
        val buffer = ByteArray(14 + valueBytes.size)
        buffer[0] = 0x94.toByte()
        buffer[1] = 0xcd.toByte()
        buffer[2] = (pubUid shr 8).toByte()
        buffer[3] = pubUid.toByte()
        buffer[4] = 0xcf.toByte()
        buffer[5] = (timestampUs shr 56).toByte()
        buffer[6] = (timestampUs shr 48).toByte()
        buffer[7] = (timestampUs shr 40).toByte()
        buffer[8] = (timestampUs shr 32).toByte()
        buffer[9] = (timestampUs shr 24).toByte()
        buffer[10] = (timestampUs shr 16).toByte()
        buffer[11] = (timestampUs shr 8).toByte()
        buffer[12] = timestampUs.toByte()
        buffer[13] = typeId
        System.arraycopy(valueBytes, 0, buffer, 14, valueBytes.size)
        return buffer
    }

    private suspend fun ensurePublisher(key: String, type: String): Int = dynamicPubMutex.withLock {
        require(publisherTypes.putIfAbsent(key, type) in arrayOf(null, type)) {
            "NT4 topic $key was already published with a different type"
        }
        dynamicPubUids[key] ?: nextPubUid++.also { id ->
            dynamicPubUids[key] = id
            session?.send(Frame.Text(buildPublishMessage(key, id, type)))
        }
    }

    internal suspend fun publishInputDouble(pubUid: Int, value: Double): Boolean {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        val valueBytes = publishDoubleBuffer.get()
        valueBytes[0] = 0xcb.toByte()
        for (index in 0 until 8) {
            valueBytes[index + 1] = (bits shr (56 - index * 8)).toByte()
        }
        return sendBinaryUpdate(pubUid, 1.toByte(), valueBytes)
    }

    internal suspend fun publishInputString(pubUid: Int, value: String): Boolean {
        val stringBytes = value.toByteArray(Charsets.UTF_8)
        require(stringBytes.size <= MAX_STRING_BYTES) {
            "NT4 strings are limited to $MAX_STRING_BYTES UTF-8 bytes"
        }
        val size = stringBytes.size
        val headerBytes = when {
            size <= 31 -> byteArrayOf((0xa0 or size).toByte())
            size <= 255 -> byteArrayOf(0xd9.toByte(), size.toByte())
            size <= 65_535 -> byteArrayOf(0xda.toByte(), (size shr 8).toByte(), size.toByte())
            else -> byteArrayOf(
                0xdb.toByte(),
                (size shr 24).toByte(),
                (size shr 16).toByte(),
                (size shr 8).toByte(),
                size.toByte()
            )
        }
        val valueBytes = ByteArray(headerBytes.size + stringBytes.size)
        System.arraycopy(headerBytes, 0, valueBytes, 0, headerBytes.size)
        System.arraycopy(stringBytes, 0, valueBytes, headerBytes.size, stringBytes.size)
        // Commands and configuration documents are ordered by the WebSocket stream rather than
        // by a dashboard wall-clock timestamp. The simulator's fixed-step RobotClock can pause,
        // restart, or run slower than wall time, so a translated timestamp may be rejected as
        // stale even though the message has just arrived. Timestamp zero asks the NT4 receiver to
        // stamp arrival time and keeps one-shot strings reliable across simulator lifecycles.
        return sendBinaryUpdate(pubUid, 4.toByte(), valueBytes, useServerReceiptTimestamp = true)
    }

    private suspend fun publishInputDoubleArray(pubUid: Int, values: DoubleArray): Boolean {
        require(values.size == DesktopDriveProtocol.VALUE_COUNT) {
            "drive frame must contain 8 doubles"
        }
        val valueBytes = publishDoubleArrayBuffer.get()
        valueBytes[0] = (0x90 or values.size).toByte()
        var offset = 1
        for (value in values) {
            val bits = java.lang.Double.doubleToRawLongBits(value)
            valueBytes[offset++] = 0xcb.toByte()
            for (shift in 56 downTo 0 step 8) valueBytes[offset++] = (bits shr shift).toByte()
        }
        // The simulator's RobotClock is fixed-step and can intentionally diverge from wall time
        // while paused or under load. NT4 timestamp zero asks the receiver to stamp arrival time,
        // avoiding rejection from a stale clock-sync offset. Frame element 3 still carries the
        // sender's monotonic clock for protocol ordering and diagnostics.
        return sendBinaryUpdate(pubUid, 17.toByte(), valueBytes, useServerReceiptTimestamp = true)
    }

    private fun sendBinaryUpdate(
        pubUid: Int,
        typeId: Byte,
        valueBytes: ByteArray,
        useServerReceiptTimestamp: Boolean = false,
    ): Boolean {
        val offsetUs = serverTimeOffsetUs
        if (!useServerReceiptTimestamp && offsetUs == null) return false
        val connectedSession = session ?: return false
        val timestampUs = if (useServerReceiptTimestamp) 0L else monotonicTimeUs() + requireNotNull(offsetUs)
        val buffer = encodeNt4BinaryUpdate(pubUid, timestampUs, typeId, valueBytes)
        return connectedSession.outgoing.trySend(Frame.Binary(true, buffer)).isSuccess
    }

    private fun buildPublishMessage(name: String, pubUid: Int, type: String): String =
        buildJsonArray {
            add(buildJsonObject {
                put("method", "publish")
                put("params", buildJsonObject {
                    put("name", name)
                    put("pubuid", pubUid)
                    put("type", type)
                })
            })
        }.toString()

    companion object {
        private const val DRIVE_FRAME_PUB_UID = 1_020
        private const val MAX_STRING_BYTES = 65_536
        private val FIXED_PUBLISH_UIDS = mapOf(
            "ARES/DriverStation/Command" to 1_011,
            "ARES/DriverStation/SelectedOpMode" to 1_012,
            "ARES/DriverStation/MatchTime" to 1_013,
            "ARES/DriverStation/MatchState" to 1_014,
            "SysId/Command" to 1_015,
            "SysId/EnableToken" to 1_016,
            "SysId/EnableLease" to 1_017,
            "ARES/Input/driveFrame" to DRIVE_FRAME_PUB_UID
        )
        private val FIXED_PUBLISH_TYPES = mapOf(
            "ARES/DriverStation/Command" to "string",
            "ARES/DriverStation/SelectedOpMode" to "string",
            "ARES/DriverStation/MatchTime" to "double",
            "ARES/DriverStation/MatchState" to "string",
            "SysId/Command" to "string",
            "SysId/EnableToken" to "string",
            "SysId/EnableLease" to "double",
            "ARES/Input/driveFrame" to "double[]"
        )
        private val FIXED_PUBLISH_TOPICS = FIXED_PUBLISH_UIDS.keys
        private val FIXED_PUBLISH_MESSAGE = """
            [
              {"method": "publish", "params": {"name": "ARES/Input/driveFrame", "pubuid": 1020, "type": "double[]"}},
              {"method": "publish", "params": {"name": "ARES/DriverStation/Command", "pubuid": 1011, "type": "string"}},
              {"method": "publish", "params": {"name": "ARES/DriverStation/SelectedOpMode", "pubuid": 1012, "type": "string"}},
              {"method": "publish", "params": {"name": "ARES/DriverStation/MatchTime", "pubuid": 1013, "type": "double"}},
              {"method": "publish", "params": {"name": "ARES/DriverStation/MatchState", "pubuid": 1014, "type": "string"}},
              {"method": "publish", "params": {"name": "SysId/Command", "pubuid": 1015, "type": "string"}},
              {"method": "publish", "params": {"name": "SysId/EnableToken", "pubuid": 1016, "type": "string"}},
              {"method": "publish", "params": {"name": "SysId/EnableLease", "pubuid": 1017, "type": "double"}}
            ]
        """.trimIndent()
    }
}
