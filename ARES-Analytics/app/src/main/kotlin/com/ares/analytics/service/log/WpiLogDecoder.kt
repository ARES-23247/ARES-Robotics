package com.ares.analytics.service.log

import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.TelemetryFrame
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Decodes the WPILib DataLog (`.wpilog`) wire format. */
class WpiLogDecoder {

    suspend fun parseWpiLog(file: File, sessionId: String, batcher: FrameBatcher) {
        FileInputStream(file).use { input ->
            val fixedHeader = input.readExact(FILE_HEADER_SIZE, "WPILOG header")
            if (!fixedHeader.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw IOException("Invalid WPILOG magic in ${file.name}")
            }

            val header = ByteBuffer.wrap(fixedHeader).order(ByteOrder.LITTLE_ENDIAN)
            header.position(MAGIC.size)
            val version = header.short.toInt() and 0xFFFF
            if (version < MIN_SUPPORTED_VERSION) {
                throw IOException("Invalid WPILOG version 0x${version.toString(16).padStart(4, '0')}")
            }
            val extraHeaderSize = header.int.toLong() and UINT32_MASK
            if (extraHeaderSize > MAX_EXTRA_HEADER_BYTES || extraHeaderSize > file.length() - FILE_HEADER_SIZE) {
                throw IOException("Invalid WPILOG extra-header size: $extraHeaderSize")
            }
            input.skipExact(extraHeaderSize, "WPILOG extra header")

            val entries = mutableMapOf<Long, EntryDefinition>()
            var remaining = file.length() - FILE_HEADER_SIZE - extraHeaderSize
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
                val variableHeader = ByteBuffer.wrap(
                    input.readExact(variableHeaderSize, "WPILOG record header")
                ).order(ByteOrder.LITTLE_ENDIAN)
                remaining -= variableHeaderSize

                val entryId = readUnsigned(variableHeader, entryIdSize)
                val payloadSize = readUnsigned(variableHeader, payloadSizeSize)
                val timestampMicros = readUnsigned(variableHeader, timestampSize)
                if (timestampMicros < 0) {
                    throw IOException("WPILOG timestamp exceeds signed 64-bit range")
                }
                if (payloadSize > MAX_RECORD_BYTES || payloadSize > remaining) {
                    throw IOException("Invalid WPILOG record payload size: $payloadSize")
                }
                val payload = input.readExact(payloadSize.toInt(), "WPILOG record payload")
                remaining -= payloadSize

                if (entryId == CONTROL_ENTRY_ID) {
                    decodeControlRecord(payload, entries)
                } else {
                    entries[entryId]?.let { definition ->
                        decodeDataRecord(
                            definition = definition,
                            payload = payload,
                            timestampMs = timestampMicros / MICROS_PER_MILLISECOND,
                            timestampUs = timestampMicros,
                            sessionId = sessionId,
                            batcher = batcher
                        )
                    }
                }
            }
        }
    }

    private fun decodeControlRecord(payload: ByteArray, entries: MutableMap<Long, EntryDefinition>) {
        if (payload.isEmpty()) throw IOException("Empty WPILOG control record")
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        when (buffer.get().toInt() and 0xFF) {
            CONTROL_START -> {
                requireRemaining(buffer, Integer.BYTES, "start entry id")
                val entryId = buffer.int.toLong() and UINT32_MASK
                if (entryId == CONTROL_ENTRY_ID) throw IOException("WPILOG data entry id cannot be zero")
                val name = readLengthPrefixedString(buffer, "entry name")
                val type = readLengthPrefixedString(buffer, "entry type")
                readLengthPrefixedString(buffer, "entry metadata")
                requireFullyConsumed(buffer, "start")
                entries[entryId] = EntryDefinition(name, type)
            }

            CONTROL_FINISH -> {
                requireRemaining(buffer, Integer.BYTES, "finish entry id")
                entries.remove(buffer.int.toLong() and UINT32_MASK)
                requireFullyConsumed(buffer, "finish")
            }

            CONTROL_SET_METADATA -> {
                requireRemaining(buffer, Integer.BYTES, "metadata entry id")
                buffer.int
                readLengthPrefixedString(buffer, "entry metadata")
                requireFullyConsumed(buffer, "metadata")
            }

            else -> throw IOException("Unknown WPILOG control record type")
        }
    }

    private suspend fun decodeDataRecord(
        definition: EntryDefinition,
        payload: ByteArray,
        timestampMs: Long,
        timestampUs: Long,
        sessionId: String,
        batcher: FrameBatcher
    ) {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        suspend fun addNumber(key: String, value: Double) {
            batcher.add(TelemetryFrame(timestampMs, sessionId, key, value, timestampUs = timestampUs))
        }
        suspend fun addString(key: String, value: String) {
            batcher.add(TelemetryFrame(timestampMs, sessionId, key, 0.0, value, timestampUs = timestampUs))
        }

        when (definition.type) {
            "raw" -> addString(definition.name, taggedBase64(definition.type, payload))
            "boolean" -> {
                requirePayloadSize(payload, 1, definition)
                addNumber(definition.name, if (payload[0].toInt() != 0) 1.0 else 0.0)
            }
            "int64" -> {
                requirePayloadSize(payload, Long.SIZE_BYTES, definition)
                addNumber(definition.name, buffer.long.toDouble())
            }
            "int32" -> {
                // Retained for third-party producers; WPILib's standard integer type is int64.
                requirePayloadSize(payload, Integer.BYTES, definition)
                addNumber(definition.name, buffer.int.toDouble())
            }
            "float" -> {
                requirePayloadSize(payload, Float.SIZE_BYTES, definition)
                addNumber(definition.name, buffer.float.toDouble())
            }
            "double" -> {
                requirePayloadSize(payload, Double.SIZE_BYTES, definition)
                addNumber(definition.name, buffer.double)
            }
            "string", "json" ->
                addString(definition.name, decodeUtf8Strict(payload, "${definition.type} value for ${definition.name}"))
            "msgpack", "protobuf", "struct" ->
                addString(definition.name, taggedBase64(definition.type, payload))
            "boolean[]" -> {
                requireArrayCount(payload.size, definition)
                payload.forEachIndexed { index, value ->
                    addNumber("${definition.name}/$index", if (value.toInt() != 0) 1.0 else 0.0)
                }
            }
            "int64[]" -> decodeFixedArray(buffer, Long.SIZE_BYTES, definition) { index ->
                addNumber("${definition.name}/$index", buffer.long.toDouble())
            }
            "float[]" -> decodeFixedArray(buffer, Float.SIZE_BYTES, definition) { index ->
                addNumber("${definition.name}/$index", buffer.float.toDouble())
            }
            "double[]" -> decodeFixedArray(buffer, Double.SIZE_BYTES, definition) { index ->
                addNumber("${definition.name}/$index", buffer.double)
            }
            "string[]" -> {
                var index = 0
                while (buffer.hasRemaining()) {
                    if (index >= MAX_ARRAY_ELEMENTS) {
                        throw IOException("WPILOG string array is too large for ${definition.name}")
                    }
                    addString("${definition.name}/$index", readLengthPrefixedString(buffer, "string array value"))
                    index++
                }
            }
            else -> when {
                definition.type.startsWith("msgpack:") ||
                    definition.type.startsWith("proto:") ||
                    definition.type.startsWith("protobuf:") ||
                    definition.type.startsWith("struct:") ->
                    addString(definition.name, taggedBase64(definition.type, payload))
                else -> Unit // Unknown custom schemas cannot be decoded without their metadata.
            }
        }
    }

    private suspend inline fun decodeFixedArray(
        buffer: ByteBuffer,
        elementSize: Int,
        definition: EntryDefinition,
        consume: suspend (Int) -> Unit
    ) {
        if (buffer.remaining() % elementSize != 0) {
            throw IOException("Invalid ${definition.type} payload size for ${definition.name}")
        }
        val count = buffer.remaining() / elementSize
        requireArrayCount(count, definition)
        repeat(count) { consume(it) }
    }

    private fun readLengthPrefixedString(buffer: ByteBuffer, field: String): String {
        requireRemaining(buffer, Integer.BYTES, "$field length")
        val length = buffer.int.toLong() and UINT32_MASK
        if (length > MAX_STRING_BYTES || length > buffer.remaining()) {
            throw IOException("Invalid WPILOG $field length: $length")
        }
        val bytes = ByteArray(length.toInt())
        buffer.get(bytes)
        return decodeUtf8Strict(bytes, field)
    }

    private fun decodeUtf8Strict(bytes: ByteArray, field: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IOException("Invalid UTF-8 in WPILOG $field", error)
    }

    private fun taggedBase64(type: String, payload: ByteArray): String =
        "base64:$type:${Base64.getEncoder().encodeToString(payload)}"

    private fun readUnsigned(buffer: ByteBuffer, size: Int): Long {
        var value = 0L
        repeat(size) { index ->
            value = value or ((buffer.get().toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    private fun FileInputStream.readExact(size: Int, description: String): ByteArray {
        val bytes = ByteArray(size)
        if (readNBytes(bytes, 0, size) != size) throw EOFException("Truncated $description")
        return bytes
    }

    private fun FileInputStream.skipExact(size: Long, description: String) {
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

    private fun requirePayloadSize(payload: ByteArray, expected: Int, definition: EntryDefinition) {
        if (payload.size != expected) {
            throw IOException("Invalid ${definition.type} payload size for ${definition.name}: ${payload.size}")
        }
    }

    private fun requireArrayCount(count: Int, definition: EntryDefinition) {
        if (count > MAX_ARRAY_ELEMENTS) {
            throw IOException("WPILOG array is too large for ${definition.name}: $count elements")
        }
    }

    private data class EntryDefinition(val name: String, val type: String)

    private companion object {
        val MAGIC = "WPILOG".toByteArray(Charsets.US_ASCII)
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
