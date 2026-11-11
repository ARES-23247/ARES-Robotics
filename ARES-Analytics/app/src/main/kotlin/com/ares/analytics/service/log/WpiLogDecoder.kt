package com.ares.analytics.service.log

import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.models.TelemetryFrame
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Decodes the WPILib DataLog (`.wpilog`) wire format. */
class WpiLogDecoder {

    suspend fun parseWpiLog(file: File, sessionId: String, batcher: FrameBatcher) {
        val fileLength = file.length()
        BufferedInputStream(FileInputStream(file), STREAM_BUFFER_BYTES).use { input ->
            val fixedHeader = ByteArray(FILE_HEADER_SIZE)
            input.readExactInto(fixedHeader, FILE_HEADER_SIZE, "WPILOG header")
            for (i in MAGIC.indices) {
                if (fixedHeader[i] != MAGIC[i]) {
                    throw IOException("Invalid WPILOG magic in ${file.name}")
                }
            }

            val header = ByteBuffer.wrap(fixedHeader).order(ByteOrder.LITTLE_ENDIAN)
            header.position(MAGIC.size)
            val version = header.short.toInt() and 0xFFFF
            if (version < MIN_SUPPORTED_VERSION) {
                throw IOException("Invalid WPILOG version 0x${version.toString(16).padStart(4, '0')}")
            }
            val extraHeaderSize = header.int.toLong() and UINT32_MASK
            if (extraHeaderSize > MAX_EXTRA_HEADER_BYTES || extraHeaderSize > fileLength - FILE_HEADER_SIZE) {
                throw IOException("Invalid WPILOG extra-header size: $extraHeaderSize")
            }
            input.skipExact(extraHeaderSize, "WPILOG extra header")

            val utf8Decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val entries = HashMap<Long, EntryDefinition>(64)
            val varHeaderBytes = ByteArray(16)
            val varHeaderBuf = ByteBuffer.wrap(varHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
            var payloadBytes = ByteArray(256)
            var payloadBuf = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)

            var remaining = fileLength - FILE_HEADER_SIZE - extraHeaderSize
            while (remaining > 0) {
                val descriptor = input.read()
                if (descriptor < 0) throw EOFException("Truncated WPILOG record descriptor")
                remaining--
                if (descriptor and RESERVED_DESCRIPTOR_BIT != 0) {
                    throw IOException("WPILOG record descriptor uses reserved bit")
                }

                val entryIdSize = (descriptor and 0x03) + 1
                val payloadSizeSize = ((descriptor ushr 2) and 0x03) + 1
                val timestampSize = ((descriptor ushr 4) and 0x07) + 1
                val variableHeaderSize = entryIdSize + payloadSizeSize + timestampSize
                if (variableHeaderSize.toLong() > remaining) {
                    throw EOFException("Truncated WPILOG record header")
                }
                input.readExactInto(varHeaderBytes, variableHeaderSize, "WPILOG record header")
                remaining -= variableHeaderSize

                varHeaderBuf.clear().limit(variableHeaderSize)
                val entryId = readUnsigned(varHeaderBuf, entryIdSize)
                val payloadSize = readUnsigned(varHeaderBuf, payloadSizeSize)
                val timestampMicros = readUnsigned(varHeaderBuf, timestampSize)
                if (timestampMicros < 0) {
                    throw IOException("WPILOG timestamp exceeds signed 64-bit range")
                }
                if (payloadSize > MAX_RECORD_BYTES || payloadSize > remaining) {
                    throw IOException("Invalid WPILOG record payload size: $payloadSize")
                }
                val payloadLen = payloadSize.toInt()
                if (payloadLen > payloadBytes.size) {
                    var nextCap = payloadBytes.size
                    while (nextCap < payloadLen) {
                        nextCap = (nextCap * 2).coerceAtLeast(payloadLen.coerceAtMost(4096))
                        if (nextCap < 0) {
                            nextCap = payloadLen
                            break
                        }
                    }
                    payloadBytes = ByteArray(maxOf(nextCap, payloadLen))
                    payloadBuf = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
                }
                input.readExactInto(payloadBytes, payloadLen, "WPILOG record payload")
                remaining -= payloadSize

                payloadBuf.clear().limit(payloadLen)
                if (entryId == CONTROL_ENTRY_ID) {
                    decodeControlRecord(payloadBuf, payloadLen, entries, utf8Decoder)
                } else {
                    val definition = entries[entryId]
                    if (definition != null) {
                        decodeDataRecord(
                            definition = definition,
                            payloadBytes = payloadBytes,
                            payloadLen = payloadLen,
                            buffer = payloadBuf,
                            timestampMs = timestampMicros / MICROS_PER_MILLISECOND,
                            timestampUs = timestampMicros,
                            sessionId = sessionId,
                            batcher = batcher,
                            utf8Decoder = utf8Decoder,
                        )
                    }
                }
            }
        }
    }

    private fun decodeControlRecord(
        buffer: ByteBuffer,
        payloadLen: Int,
        entries: MutableMap<Long, EntryDefinition>,
        utf8Decoder: CharsetDecoder,
    ) {
        if (payloadLen == 0) throw IOException("Empty WPILOG control record")
        when (buffer.get().toInt() and 0xFF) {
            CONTROL_START -> {
                requireRemaining(buffer, Integer.BYTES, "start entry id")
                val entryId = buffer.int.toLong() and UINT32_MASK
                if (entryId == CONTROL_ENTRY_ID) throw IOException("WPILOG data entry id cannot be zero")
                val name = readLengthPrefixedString(buffer, "entry name", utf8Decoder)
                val type = readLengthPrefixedString(buffer, "entry type", utf8Decoder)
                skipLengthPrefixedString(buffer, "entry metadata", utf8Decoder)
                requireFullyConsumed(buffer, "start")
                entries[entryId] = EntryDefinition(name, type, classifyKind(type))
            }

            CONTROL_FINISH -> {
                requireRemaining(buffer, Integer.BYTES, "finish entry id")
                entries.remove(buffer.int.toLong() and UINT32_MASK)
                requireFullyConsumed(buffer, "finish")
            }

            CONTROL_SET_METADATA -> {
                requireRemaining(buffer, Integer.BYTES, "metadata entry id")
                buffer.int
                skipLengthPrefixedString(buffer, "entry metadata", utf8Decoder)
                requireFullyConsumed(buffer, "metadata")
            }

            else -> throw IOException("Unknown WPILOG control record type")
        }
    }

    private suspend fun decodeDataRecord(
        definition: EntryDefinition,
        payloadBytes: ByteArray,
        payloadLen: Int,
        buffer: ByteBuffer,
        timestampMs: Long,
        timestampUs: Long,
        sessionId: String,
        batcher: FrameBatcher,
        utf8Decoder: CharsetDecoder,
    ) {
        when (definition.kind) {
            EntryKind.DOUBLE -> {
                requirePayloadSize(payloadLen, Double.SIZE_BYTES, definition)
                batcher.add(TelemetryFrame(timestampMs, sessionId, definition.name, buffer.double, timestampUs = timestampUs))
            }
            EntryKind.FLOAT -> {
                requirePayloadSize(payloadLen, Float.SIZE_BYTES, definition)
                batcher.add(TelemetryFrame(timestampMs, sessionId, definition.name, buffer.float.toDouble(), timestampUs = timestampUs))
            }
            EntryKind.INT64 -> {
                requirePayloadSize(payloadLen, Long.SIZE_BYTES, definition)
                batcher.add(TelemetryFrame(timestampMs, sessionId, definition.name, buffer.long.toDouble(), timestampUs = timestampUs))
            }
            EntryKind.INT32 -> {
                requirePayloadSize(payloadLen, Integer.BYTES, definition)
                batcher.add(TelemetryFrame(timestampMs, sessionId, definition.name, buffer.int.toDouble(), timestampUs = timestampUs))
            }
            EntryKind.BOOLEAN -> {
                requirePayloadSize(payloadLen, 1, definition)
                batcher.add(
                    TelemetryFrame(
                        timestampMs,
                        sessionId,
                        definition.name,
                        if (payloadBytes[0].toInt() != 0) 1.0 else 0.0,
                        timestampUs = timestampUs,
                    )
                )
            }
            EntryKind.UTF8_TEXT -> {
                val text = decodeUtf8Strict(payloadBytes, 0, payloadLen, "${definition.type} value for ${definition.name}", utf8Decoder)
                batcher.add(TelemetryFrame(timestampMs, sessionId, definition.name, 0.0, text, timestampUs = timestampUs))
            }
            EntryKind.TAGGED_BASE64 -> {
                batcher.add(
                    TelemetryFrame(
                        timestampMs,
                        sessionId,
                        definition.name,
                        0.0,
                        taggedBase64(definition.type, payloadBytes, payloadLen),
                        timestampUs = timestampUs,
                    )
                )
            }
            EntryKind.DOUBLE_ARRAY -> {
                val count = requireFixedArrayCount(payloadLen, Double.SIZE_BYTES, definition)
                for (index in 0 until count) {
                    batcher.add(
                        TelemetryFrame(timestampMs, sessionId, definition.subKey(index), buffer.double, timestampUs = timestampUs)
                    )
                }
            }
            EntryKind.FLOAT_ARRAY -> {
                val count = requireFixedArrayCount(payloadLen, Float.SIZE_BYTES, definition)
                for (index in 0 until count) {
                    batcher.add(
                        TelemetryFrame(timestampMs, sessionId, definition.subKey(index), buffer.float.toDouble(), timestampUs = timestampUs)
                    )
                }
            }
            EntryKind.INT64_ARRAY -> {
                val count = requireFixedArrayCount(payloadLen, Long.SIZE_BYTES, definition)
                for (index in 0 until count) {
                    batcher.add(
                        TelemetryFrame(timestampMs, sessionId, definition.subKey(index), buffer.long.toDouble(), timestampUs = timestampUs)
                    )
                }
            }
            EntryKind.BOOLEAN_ARRAY -> {
                requireArrayCount(payloadLen, definition)
                for (index in 0 until payloadLen) {
                    batcher.add(
                        TelemetryFrame(
                            timestampMs,
                            sessionId,
                            definition.subKey(index),
                            if (payloadBytes[index].toInt() != 0) 1.0 else 0.0,
                            timestampUs = timestampUs,
                        )
                    )
                }
            }
            EntryKind.STRING_ARRAY -> {
                var index = 0
                while (buffer.hasRemaining()) {
                    if (index >= MAX_ARRAY_ELEMENTS) {
                        throw IOException("WPILOG string array is too large for ${definition.name}")
                    }
                    val str = readLengthPrefixedString(buffer, "string array value", utf8Decoder)
                    batcher.add(TelemetryFrame(timestampMs, sessionId, definition.subKey(index), 0.0, str, timestampUs = timestampUs))
                    index++
                }
            }
            EntryKind.UNKNOWN -> Unit
        }
    }

    private fun requireFixedArrayCount(payloadLen: Int, elementSize: Int, definition: EntryDefinition): Int {
        if (payloadLen % elementSize != 0) {
            throw IOException("Invalid ${definition.type} payload size for ${definition.name}")
        }
        val count = payloadLen / elementSize
        requireArrayCount(count, definition)
        return count
    }

    private fun readLengthPrefixedString(buffer: ByteBuffer, field: String, utf8Decoder: CharsetDecoder): String {
        requireRemaining(buffer, Integer.BYTES, "$field length")
        val length = buffer.int.toLong() and UINT32_MASK
        if (length > MAX_STRING_BYTES || length > buffer.remaining()) {
            throw IOException("Invalid WPILOG $field length: $length")
        }
        val len = length.toInt()
        val pos = buffer.position()
        val decoded = decodeUtf8Strict(buffer.array(), buffer.arrayOffset() + pos, len, field, utf8Decoder)
        buffer.position(pos + len)
        return decoded
    }

    private fun skipLengthPrefixedString(buffer: ByteBuffer, field: String, utf8Decoder: CharsetDecoder) {
        readLengthPrefixedString(buffer, field, utf8Decoder)
    }

    private fun decodeUtf8Strict(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        field: String,
        utf8Decoder: CharsetDecoder,
    ): String = try {
        if (length == 0) {
            ""
        } else {
            utf8Decoder.reset()
            utf8Decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        }
    } catch (error: CharacterCodingException) {
        throw IOException("Invalid UTF-8 in WPILOG $field", error)
    }

    private fun taggedBase64(type: String, payloadBytes: ByteArray, payloadLen: Int): String {
        val exact = if (payloadLen == payloadBytes.size) payloadBytes else payloadBytes.copyOfRange(0, payloadLen)
        return "base64:$type:${Base64.getEncoder().encodeToString(exact)}"
    }

    private fun readUnsigned(buffer: ByteBuffer, size: Int): Long {
        var value = 0L
        for (index in 0 until size) {
            value = value or ((buffer.get().toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    private fun InputStream.readExactInto(target: ByteArray, size: Int, description: String) {
        var offset = 0
        while (offset < size) {
            val read = read(target, offset, size - offset)
            if (read < 0) throw EOFException("Truncated $description")
            offset += read
        }
    }

    private fun InputStream.skipExact(size: Long, description: String) {
        var remaining = size
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Truncated $description")
            }
        }
    }

    private fun requireRemaining(buffer: ByteBuffer, required: Int, field: String) {
        if (buffer.remaining() < required) throw EOFException("Truncated WPILOG $field")
    }

    private fun requireFullyConsumed(buffer: ByteBuffer, controlType: String) {
        if (buffer.hasRemaining()) throw IOException("Trailing bytes in WPILOG $controlType control record")
    }

    private fun requirePayloadSize(actual: Int, expected: Int, definition: EntryDefinition) {
        if (actual != expected) {
            throw IOException("Invalid ${definition.type} payload size for ${definition.name}: $actual")
        }
    }

    private fun requireArrayCount(count: Int, definition: EntryDefinition) {
        if (count > MAX_ARRAY_ELEMENTS) {
            throw IOException("WPILOG array is too large for ${definition.name}: $count elements")
        }
    }

    private enum class EntryKind {
        DOUBLE,
        FLOAT,
        INT64,
        INT32,
        BOOLEAN,
        UTF8_TEXT,
        TAGGED_BASE64,
        DOUBLE_ARRAY,
        FLOAT_ARRAY,
        INT64_ARRAY,
        BOOLEAN_ARRAY,
        STRING_ARRAY,
        UNKNOWN,
    }

    private fun classifyKind(type: String): EntryKind = when (type) {
        "double" -> EntryKind.DOUBLE
        "float" -> EntryKind.FLOAT
        "int64" -> EntryKind.INT64
        "int32" -> EntryKind.INT32
        "boolean" -> EntryKind.BOOLEAN
        "string", "json" -> EntryKind.UTF8_TEXT
        "raw", "msgpack", "protobuf", "struct" -> EntryKind.TAGGED_BASE64
        "double[]" -> EntryKind.DOUBLE_ARRAY
        "float[]" -> EntryKind.FLOAT_ARRAY
        "int64[]" -> EntryKind.INT64_ARRAY
        "boolean[]" -> EntryKind.BOOLEAN_ARRAY
        "string[]" -> EntryKind.STRING_ARRAY
        else -> if (
            type.startsWith("msgpack:") ||
            type.startsWith("proto:") ||
            type.startsWith("protobuf:") ||
            type.startsWith("struct:")
        ) {
            EntryKind.TAGGED_BASE64
        } else {
            EntryKind.UNKNOWN
        }
    }

    private class EntryDefinition(
        val name: String,
        val type: String,
        val kind: EntryKind,
    ) {
        private var cachedSubKeys: Array<String?>? = null

        fun subKey(index: Int): String {
            if (index >= CACHED_SUBKEY_LIMIT) return "$name/$index"
            var cache = cachedSubKeys
            if (cache == null) {
                cache = arrayOfNulls(CACHED_SUBKEY_LIMIT)
                cachedSubKeys = cache
            }
            var sub = cache[index]
            if (sub == null) {
                sub = "$name/$index"
                cache[index] = sub
            }
            return sub
        }
    }

    private companion object {
        val MAGIC = "WPILOG".toByteArray(Charsets.US_ASCII)
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val CACHED_SUBKEY_LIMIT = 64
        const val FILE_HEADER_SIZE = 12
        const val MIN_SUPPORTED_VERSION = 0x0100
        const val CONTROL_ENTRY_ID = 0L
        const val CONTROL_START = 0
        const val CONTROL_FINISH = 1
        const val CONTROL_SET_METADATA = 2
        const val RESERVED_DESCRIPTOR_BIT = 0x80
        const val MICROS_PER_MILLISECOND = 1_000L
        const val UINT32_MASK = 0xFFFF_FFFFL
        const val MAX_EXTRA_HEADER_BYTES = 16L * 1024 * 1024
        const val MAX_RECORD_BYTES = 64L * 1024 * 1024
        const val MAX_STRING_BYTES = 16L * 1024 * 1024
        const val MAX_ARRAY_ELEMENTS = 1_000_000
    }
}
