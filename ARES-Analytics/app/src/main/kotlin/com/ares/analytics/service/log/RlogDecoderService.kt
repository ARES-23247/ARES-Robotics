package com.ares.analytics.service.log

import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/** Streaming decoder for AdvantageKit `.rlog` revisions 1 and 2. */
class RlogDecoderService : BaseLogDecoder() {

    override suspend fun decode(file: File, sessionId: String, batcher: FrameBatcher) {
        decode(file, sessionId) { frame -> batcher.add(frame) }
    }

    internal suspend fun decode(
        file: File,
        sessionId: String,
        emitFrame: suspend (TelemetryFrame) -> Unit
    ) {
        try {
            require(file.isFile && file.length() >= 2L) { "RLOG file is missing or truncated" }
            DataInputStream(BufferedInputStream(file.inputStream(), STREAM_BUFFER_BYTES)).use { input ->
                val logRevision = input.read()
                if (logRevision == -1) return
                if (input.read() == -1) throw EOFException("truncated RLOG header")
                require(logRevision == 1 || logRevision == 2) { "Unsupported RLOG revision $logRevision" }

                val keyNames = mutableMapOf<Int, String>()
                val keyTypes = mutableMapOf<Int, String>()
                while (true) {
                    val timestampSec = input.readTimestampOrNull() ?: break
                    require(timestampSec.isFinite() && timestampSec >= 0.0) {
                        "RLOG timestamp must be finite and non-negative"
                    }
                    require(timestampSec <= MAX_TIMESTAMP_MS.toDouble() / 1000.0) {
                        "RLOG timestamp exceeds the supported domain"
                    }
                    val timestampMs = (timestampSec * 1000.0).toLong()
                    val blockFrames = mutableListOf<TelemetryFrame>()
                    var blockFrameCount = 0
                    val emitToBlock: suspend (Long, String?, Double, String?, Int?) -> Unit =
                        { frameTime, keyName, value, stringValue, arrayIndex ->
                            if (keyName != null) {
                                require(value.isFinite()) { "Non-finite RLOG value for $keyName" }
                                blockFrameCount++
                                require(blockFrameCount <= MAX_FRAMES_PER_TIMESTAMP_BLOCK) {
                                    "RLOG timestamp block contains too many frames"
                                }
                                val normalizedKey = if (arrayIndex == null) keyName else "$keyName/$arrayIndex"
                                blockFrames += TelemetryFrame(frameTime, sessionId, normalizedKey, value, stringValue)
                            }
                        }

                    var blockRecordCount = 0
                    while (true) {
                        val recordType = input.read()
                        if (recordType == -1) {
                            throw EOFException("truncated RLOG timestamp block")
                        }
                        if (recordType == 0) break
                        blockRecordCount++
                        require(blockRecordCount <= MAX_RECORDS_PER_TIMESTAMP_BLOCK) {
                            "RLOG timestamp block contains too many records"
                        }

                        when (recordType) {
                            1 -> {
                                val keyId = input.readUnsignedShort()
                                val keyName = input.readUtf8(input.readUnsignedShort(), "key name")
                                keyNames[keyId] = keyName
                                if (logRevision == 2) {
                                    keyTypes[keyId] = input.readUtf8(input.readUnsignedShort(), "key type")
                                }
                            }

                            2 -> {
                                val keyId = input.readUnsignedShort()
                                val keyName = keyNames[keyId]
                                if (logRevision == 2) {
                                    decodeRevisionTwoValue(
                                        input = input,
                                        valueLength = input.readUnsignedShort(),
                                        fieldType = keyTypes[keyId],
                                        timestampMs = timestampMs,
                                        keyName = keyName,
                                        emit = emitToBlock
                                    )
                                } else {
                                    decodeRevisionOneValue(
                                        input = input,
                                        valueType = input.readUnsignedByte(),
                                        timestampMs = timestampMs,
                                        keyName = keyName,
                                        emit = emitToBlock
                                    )
                                }
                            }

                            else -> throw IllegalArgumentException("Unknown RLOG record type $recordType")
                        }
                    }
                    for (frame in blockFrames) emitFrame(frame)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is IllegalArgumentException && error.message?.startsWith("Corrupt RLOG") == true) {
                throw error
            }
            throw IllegalArgumentException("Corrupt RLOG ${file.name}: ${error.message}", error)
        }
    }

    private suspend fun decodeRevisionTwoValue(
        input: DataInputStream,
        valueLength: Int,
        fieldType: String?,
        timestampMs: Long,
        keyName: String?,
        emit: suspend (Long, String?, Double, String?, Int?) -> Unit
    ) {
        if (fieldType == null || keyName == null) {
            input.skipExactly(valueLength)
            return
        }
        when (fieldType) {
            "boolean" -> {
                requireLength(valueLength, 1, fieldType)
                emit(timestampMs, keyName, input.readBooleanValue(fieldType), null, null)
            }
            "int", "int64" -> {
                requireLength(valueLength, 8, fieldType)
                emit(timestampMs, keyName, input.readLong().toDouble(), null, null)
            }
            "float" -> {
                requireLength(valueLength, 4, fieldType)
                emit(timestampMs, keyName, input.readFloat().toDouble(), null, null)
            }
            "double" -> {
                requireLength(valueLength, 8, fieldType)
                emit(timestampMs, keyName, input.readDouble(), null, null)
            }
            "string", "json" -> emit(
                timestampMs,
                keyName,
                0.0,
                input.readUtf8(valueLength, "string value"),
                null
            )
            "boolean[]" -> repeat(requireElementCount(valueLength, 1, fieldType)) { index ->
                emit(timestampMs, keyName, input.readBooleanValue(fieldType), null, index)
            }
            "int[]", "int64[]" -> {
                repeat(requireElementCount(valueLength, 8, fieldType)) { index ->
                    emit(timestampMs, keyName, input.readLong().toDouble(), null, index)
                }
            }
            "float[]" -> {
                repeat(requireElementCount(valueLength, 4, fieldType)) { index ->
                    emit(timestampMs, keyName, input.readFloat().toDouble(), null, index)
                }
            }
            "double[]" -> {
                repeat(requireElementCount(valueLength, 8, fieldType)) { index ->
                    emit(timestampMs, keyName, input.readDouble(), null, index)
                }
            }
            else -> input.skipExactly(valueLength)
        }
    }

    private suspend fun decodeRevisionOneValue(
        input: DataInputStream,
        valueType: Int,
        timestampMs: Long,
        keyName: String?,
        emit: suspend (Long, String?, Double, String?, Int?) -> Unit
    ) {
        when (valueType) {
            0 -> emit(timestampMs, keyName, 0.0, null, null)
            1 -> emit(timestampMs, keyName, input.readBooleanValue("boolean"), null, null)
            9 -> emit(timestampMs, keyName, input.readUnsignedByte().toDouble(), null, null)
            3 -> emit(timestampMs, keyName, input.readInt().toDouble(), null, null)
            5 -> emit(timestampMs, keyName, input.readDouble(), null, null)
            7 -> emit(timestampMs, keyName, 0.0, input.readUtf8(input.readUnsignedShort(), "string value"), null)
            2 -> repeat(input.readUnsignedShort()) { index ->
                emit(timestampMs, keyName, input.readBooleanValue("boolean[]"), null, index)
            }
            10 -> repeat(input.readUnsignedShort()) { index ->
                emit(timestampMs, keyName, input.readUnsignedByte().toDouble(), null, index)
            }
            4 -> repeat(input.readUnsignedShort()) { index ->
                emit(timestampMs, keyName, input.readInt().toDouble(), null, index)
            }
            6 -> repeat(input.readUnsignedShort()) { index ->
                emit(timestampMs, keyName, input.readDouble(), null, index)
            }
            8 -> repeat(input.readUnsignedShort()) {
                input.skipExactly(input.readUnsignedShort())
            }
            else -> throw IllegalArgumentException("Unknown revision-1 value type $valueType")
        }
    }

    private fun requireLength(actual: Int, expected: Int, fieldType: String) {
        require(actual == expected) { "$fieldType payload length $actual, expected $expected" }
    }

    private fun requireElementCount(actual: Int, elementBytes: Int, fieldType: String): Int {
        require(actual % elementBytes == 0) { "$fieldType payload length $actual is misaligned" }
        val count = actual / elementBytes
        require(count <= MAX_ARRAY_ELEMENTS) { "$fieldType contains too many elements: $count" }
        return count
    }

    private fun DataInputStream.readBooleanValue(label: String): Double {
        val raw = readUnsignedByte()
        require(raw == 0 || raw == 1) { "$label contains invalid boolean byte $raw" }
        return raw.toDouble()
    }

    private fun DataInputStream.readTimestampOrNull(): Double? {
        val first = read()
        if (first == -1) return null
        var bits = first.toLong() and 0xffL
        repeat(7) { bits = (bits shl 8) or readUnsignedByte().toLong() }
        return Double.fromBits(bits)
    }

    private fun DataInputStream.readUtf8(length: Int, label: String): String {
        require(length in 0..MAX_VALUE_BYTES) { "$label length $length exceeds limit" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.skipExactly(length: Int) {
        require(length in 0..MAX_VALUE_BYTES) { "payload length $length exceeds limit" }
        var remaining = length
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped == 0) {
                if (read() == -1) throw EOFException("truncated payload")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_VALUE_BYTES = 16 * 1024 * 1024
        const val MAX_ARRAY_ELEMENTS = 65_536
        const val MAX_RECORDS_PER_TIMESTAMP_BLOCK = 65_536
        const val MAX_FRAMES_PER_TIMESTAMP_BLOCK = 65_536
        const val MAX_TIMESTAMP_MS = 4_102_444_800_000L
    }
}
