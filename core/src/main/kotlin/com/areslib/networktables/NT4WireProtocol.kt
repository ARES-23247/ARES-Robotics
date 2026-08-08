package com.areslib.networktables

import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType
import java.io.ByteArrayOutputStream

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
        
        packer.packArrayHeader(1)
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
     * Supports both spec-compliant array-of-arrays `[[id, time, type, val], ...]`
     * and single flat array `[id, time, type, val]` legacy messages.
     */
    fun unpackMessageFrames(bytes: ByteArray): List<NT4ValueMessage> {
        val messages = mutableListOf<NT4ValueMessage>()
        if (bytes.isEmpty()) return messages
        
        try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            while (unpacker.hasNext()) {
                val nextFormat = unpacker.getNextFormat()
                if (nextFormat.valueType != ValueType.ARRAY) {
                    unpacker.skipValue()
                    continue
                }
                
                val arrayLen = unpacker.unpackArrayHeader()
                if (arrayLen == 0) continue
                
                val firstFormat = if (unpacker.hasNext()) unpacker.getNextFormat() else null
                
                if (arrayLen == 4 && firstFormat != null && firstFormat.valueType == ValueType.INTEGER) {
                    // Legacy single flat array: [id, timestamp, type, value]
                    val topicId = unpacker.unpackLong()
                    val timestampUs = unpacker.unpackLong()
                    val typeId = unpacker.unpackInt()
                    val value = unpackValue(unpacker)
                    messages.add(NT4ValueMessage(topicId, timestampUs, typeId, value))
                } else {
                    // Standard NT4 spec: outer array containing inner message arrays [[id, time, type, val], ...]
                    for (i in 0 until arrayLen) {
                        if (!unpacker.hasNext()) break
                        val format = unpacker.getNextFormat()
                        if (format.valueType == ValueType.ARRAY) {
                            val innerLen = unpacker.unpackArrayHeader()
                            if (innerLen == 4) {
                                val topicId = unpacker.unpackLong()
                                val timestampUs = unpacker.unpackLong()
                                val typeId = unpacker.unpackInt()
                                val value = unpackValue(unpacker)
                                messages.add(NT4ValueMessage(topicId, timestampUs, typeId, value))
                            } else {
                                unpacker.skipValue()
                            }
                        } else {
                            unpacker.skipValue()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // End of stream or malformed payload
        }
        return messages
    }

    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker, depth: Int = 0): Any? {
        if (depth > 4) return null  // Max recursion depth
        val format = unpacker.getNextFormat()
        return when (format.valueType) {
            ValueType.NIL -> { unpacker.unpackNil(); null }
            ValueType.BOOLEAN -> unpacker.unpackBoolean()
            ValueType.INTEGER -> unpacker.unpackLong()
            ValueType.FLOAT -> unpacker.unpackDouble()
            ValueType.STRING -> unpacker.unpackString()
            ValueType.ARRAY -> {
                val size = unpacker.unpackArrayHeader().coerceAtMost(256)
                val list = mutableListOf<Any?>()
                for (i in 0 until size) {
                    list.add(unpackValue(unpacker, depth + 1))
                }
                list
            }
            ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                unpacker.readPayload(len)
            }
            else -> { unpacker.skipValue(); null }
        }
    }
}
