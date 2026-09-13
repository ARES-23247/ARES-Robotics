package com.areslib.networktables

import org.msgpack.core.MessagePacker

/** Shared synchronous value writer for server-owned buffers and standalone message encoding. */
internal object NT4BinaryValueEncoder {
    fun pack(packer: MessagePacker, typeId: Int, value: Any?) {
        when (typeId) {
            0 -> { require(value is Boolean); packer.packBoolean(value) }
            1 -> { require(value is Number); packer.packDouble(value.toDouble()) }
            2 -> { require(value is Number); packer.packLong(value.toNT4LongExact()) }
            3 -> { require(value is Number); packer.packFloat(value.toFloat()) }
            4 -> { require(value is String); packer.packString(value) }
            // Retain the server's legacy binary aliases, while standard NT4 binary uses ID 5.
            5, 7, 8 -> {
                require(value is ByteArray)
                packer.packBinaryHeader(value.size); packer.writePayload(value)
            }
            16 -> {
                require(value is BooleanArray)
                packer.packArrayHeader(value.size)
                for (element in value) packer.packBoolean(element)
            }
            17 -> {
                require(value is DoubleArray)
                packer.packArrayHeader(value.size)
                for (element in value) packer.packDouble(element)
            }
            18 -> when (value) {
                is LongArray -> {
                    packer.packArrayHeader(value.size)
                    for (element in value) packer.packLong(element)
                }
                is IntArray -> {
                    packer.packArrayHeader(value.size)
                    for (element in value) packer.packLong(element.toLong())
                }
                else -> throw IllegalArgumentException("NT4 integer arrays require LongArray or IntArray")
            }
            19 -> {
                require(value is FloatArray)
                packer.packArrayHeader(value.size)
                for (element in value) packer.packFloat(element)
            }
            20 -> {
                require(value is Array<*>)
                packer.packArrayHeader(value.size)
                for (element in value) {
                    require(element is String)
                    packer.packString(element)
                }
            }
            else -> throw IllegalArgumentException("Unsupported NT4 value type ID: $typeId")
        }
    }
}
