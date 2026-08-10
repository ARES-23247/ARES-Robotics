package com.areslib.networktables

import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Single NT4 value update message payload.
 */
data class NT4ValueMessage(
    val topicId: Long,
    val timestampUs: Long,
    val typeId: Int,
    val value: Any?
)

/**
 * Spec-compliant NT4 wire-protocol encoder and decoder using MsgPack stream buffers.
 */
object NT4WireProtocol {

    /**
     * Encodes a single topic payload into NT4 MsgPack binary array `[topicId, timestampUs, typeId, value]`.
     */
    fun encodeValueMessage(topicId: Long, timestampUs: Long, typeId: Int, value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)
        
        packer.packArrayHeader(4)
        packer.packLong(topicId)
        packer.packLong(timestampUs)
        packer.packInt(typeId)
        
        when (value) {
            is Boolean -> packer.packBoolean(value)
            is Number -> packer.packDouble(value.toDouble())
            is String -> packer.packString(value)
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            else -> packer.packNil()
        }
        
        packer.flush()
        return out.toByteArray()
    }

    /**
     * Unpacks incoming binary MsgPack payload into a list of [NT4ValueMessage] objects.
     * An NT4 WebSocket frame is a MessagePack stream of four-element arrays. Multiple updates
     * are concatenated in that stream; they are not wrapped in another array.
     */
    fun unpackMessageFrames(bytes: ByteArray): List<NT4ValueMessage> {
        if (bytes.isEmpty()) return emptyList()
        if (bytes.size > MAX_FRAME_BYTES) return emptyList()
        val messages = ArrayList<NT4ValueMessage>()
        
        try {
            MessagePack.newDefaultUnpacker(bytes).use { unpacker ->
                while (unpacker.hasNext()) {
                    if (unpacker.getNextFormat().valueType != ValueType.ARRAY) {
                        throw IOException("NT4 frame root must be an array")
                    }

                    val arrayLen = unpacker.unpackArrayHeader()
                    if (arrayLen != 4) throw IOException("NT4 update tuple must have 4 elements, got $arrayLen")
                    requireLength("message count", messages.size + 1, MAX_MESSAGES_PER_FRAME)
                    messages.add(unpackTuple(unpacker))
                }
            }
        } catch (_: Exception) {
            // Reject the complete WebSocket frame. Returning already-decoded prefixes would
            // apply a partial transaction when a later tuple is malformed.
            return emptyList()
        }
        return messages
    }

    private fun unpackTuple(unpacker: org.msgpack.core.MessageUnpacker): NT4ValueMessage {
        val topicId = unpacker.unpackLong()
        val timestampUs = unpacker.unpackLong()
        val typeId = unpacker.unpackInt()
        return NT4ValueMessage(topicId, timestampUs, typeId, unpackValue(unpacker))
    }

    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker, depth: Int = 0): Any? {
        if (depth > 4) {
            throw IOException("NT4 value nesting exceeds maximum depth")
        }
        val format = unpacker.getNextFormat()
        return when (format.valueType) {
            ValueType.NIL -> { unpacker.unpackNil(); null }
            ValueType.BOOLEAN -> unpacker.unpackBoolean()
            ValueType.INTEGER -> unpacker.unpackLong()
            ValueType.FLOAT -> unpacker.unpackDouble()
            ValueType.STRING -> {
                val len = unpacker.unpackRawStringHeader()
                requireLength("string value", len, MAX_STRING_BYTES)
                val payload = ByteArray(len)
                unpacker.readPayload(payload)
                String(payload, Charsets.UTF_8)
            }
            ValueType.ARRAY -> {
                val declaredSize = unpacker.unpackArrayHeader()
                requireLength("array value", declaredSize, MAX_ARRAY_ELEMENTS)
                val retainedSize = declaredSize.coerceAtMost(256)
                val list = ArrayList<Any?>(retainedSize)
                for (i in 0 until declaredSize) {
                    if (i < retainedSize) {
                        list.add(unpackValue(unpacker, depth + 1))
                    } else {
                        // Oversized inputs are truncated for bounded memory use, but every
                        // element must be skipped to keep subsequent frames synchronized.
                        unpacker.skipValue()
                    }
                }
                list
            }
            ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                requireLength("binary value", len, MAX_BINARY_BYTES)
                unpacker.readPayload(len)
            }
            else -> { unpacker.skipValue(); null }
        }
    }

    private fun requireLength(label: String, length: Int, maximum: Int) {
        if (length < 0 || length > maximum) throw IOException("NT4 $label length $length exceeds $maximum")
    }

    internal const val MAX_MESSAGES_PER_FRAME = 1024
    internal const val MAX_ARRAY_ELEMENTS = 4096
    internal const val MAX_STRING_BYTES = 65_536
    internal const val MAX_BINARY_BYTES = 1_048_576
    internal const val MAX_FRAME_BYTES = 4_194_304
}
