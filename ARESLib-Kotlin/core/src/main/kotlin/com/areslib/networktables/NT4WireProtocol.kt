package com.areslib.networktables

import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType
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
 * NT4 wire encoder and bounded frame decoder. Arrays retain List values on this diagnostic/client
 * API. Float-typed input may use integer carriers for compatibility; integer topics require integers.
 */
object NT4WireProtocol {

    /**
     * Encodes a single topic payload into NT4 MsgPack binary array `[topicId, timestampUs, typeId, value]`.
     */
    fun encodeValueMessage(topicId: Long, timestampUs: Long, typeId: Int, value: Any?): ByteArray {
        return MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packArrayHeader(4)
            packer.packLong(topicId)
            packer.packLong(timestampUs)
            packer.packInt(typeId)
            NT4BinaryValueEncoder.pack(packer, typeId, value)
            packer.toByteArray()
        }
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
        return NT4ValueMessage(topicId, timestampUs, typeId, unpackValue(unpacker, typeId))
    }

    /** Validates each carrier while decoding; typed arrays need no second scan or nested lists. */
    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker, typeId: Int): Any = when (typeId) {
        0 -> unpacker.unpackBoolean()
        1, 3 -> when (unpacker.nextFormat.valueType) {
            ValueType.INTEGER -> unpacker.unpackLong()
            ValueType.FLOAT -> unpacker.unpackDouble()
            else -> throw IOException("NT4 floating value requires a numeric carrier")
        }
        2 -> unpacker.unpackLong()
        4 -> {
            val length = unpacker.unpackRawStringHeader()
            requireLength("string value", length, MAX_STRING_BYTES)
            String(unpacker.readPayload(length), Charsets.UTF_8)
        }
        5, 7, 8 -> {
            val length = unpacker.unpackBinaryHeader()
            requireLength("binary value", length, MAX_BINARY_BYTES)
            unpacker.readPayload(length)
        }
        in 16..20 -> {
            val length = unpacker.unpackArrayHeader()
            requireLength("array value", length, MAX_ARRAY_ELEMENTS)
            val values = ArrayList<Any>(length)
            // Child type IDs 0..4 are scalar, so the protocol permits exactly one array level.
            repeat(length) { values.add(unpackValue(unpacker, typeId - 16)) }
            values
        }
        else -> throw IOException("Unsupported NT4 type ID $typeId")
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
