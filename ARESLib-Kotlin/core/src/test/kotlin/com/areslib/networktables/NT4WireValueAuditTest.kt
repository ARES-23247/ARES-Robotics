package com.areslib.networktables

import org.junit.jupiter.api.Test
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import kotlin.test.*

class NT4WireValueAuditTest {
    private fun encoded(type: Int, value: Any?) = NT4WireProtocol.encodeValueMessage(7, 123456789, type, value)

    @Test fun `integer encoding preserves full signed precision and compact width`() {
        for (value in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993, -9_007_199_254_740_993, 0, 127)) {
            val bytes = encoded(2, value)
            MessagePack.newDefaultUnpacker(bytes).use { reader ->
                assertEquals(4, reader.unpackArrayHeader()); assertEquals(7L, reader.unpackLong())
                assertEquals(123456789L, reader.unpackLong()); assertEquals(2, reader.unpackInt())
                assertEquals(value, reader.unpackLong()); assertFalse(reader.hasNext())
            }
            assertEquals(value, NT4WireProtocol.unpackMessageFrames(bytes).single().value)
        }
        assertTrue(encoded(2, 1L).size < encoded(1, 1.0).size)
    }

    @Test fun `float wire width and special values preserve declared type`() {
        for (value in floatArrayOf(-0.0f, Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN)) {
            MessagePack.newDefaultUnpacker(encoded(3, value)).use { reader ->
                reader.unpackArrayHeader(); repeat(3) { reader.skipValue() }
                assertEquals(MessageFormat.FLOAT32, reader.nextFormat)
                assertEquals(value.toBits(), reader.unpackFloat().toBits())
            }
        }
    }

    @Test fun `all supported arrays encode values rather than nil`() {
        val values = listOf(
            16 to booleanArrayOf(true, false), 17 to doubleArrayOf(-0.0, 1.25),
            18 to longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE), 19 to floatArrayOf(0.5f, -2f),
            20 to arrayOf("bracket]", "café", "🚀")
        )
        for ((type, value) in values) {
            val decoded = NT4WireProtocol.unpackMessageFrames(encoded(type, value)).single().value as List<*>
            when (value) {
                is BooleanArray -> assertEquals(value.toList(), decoded)
                is DoubleArray -> assertEquals(value.toList(), decoded)
                is LongArray -> assertEquals(value.toList(), decoded)
                is FloatArray -> assertEquals(value.map(Float::toDouble), decoded)
                is Array<*> -> assertEquals(value.toList(), decoded)
            }
        }
    }

    @Test fun `outbound mismatches reject instead of truncating or encoding nil`() {
        for ((type, value) in listOf(0 to 1, 2 to 1.5, 4 to true, 16 to intArrayOf(1), 18 to doubleArrayOf(1.5), 99 to 2)) {
            assertFailsWith<IllegalArgumentException> { encoded(type, value) }
        }
        assertFailsWith<IllegalArgumentException> { encoded(1, null) }
    }

    @Test fun `binary payload is owned and remains distinct from numeric arrays`() {
        val bytes = byteArrayOf(0, 1, -1)
        val encoded = encoded(5, bytes); bytes[0] = 99
        val value = NT4WireProtocol.unpackMessageFrames(encoded).single().value as ByteArray
        assertContentEquals(byteArrayOf(0, 1, -1), value)
    }

    @Test fun `wrong inbound types reject entire concatenated frame`() {
        val valid = encoded(1, 2.5)
        val invalid = MessagePack.newDefaultBufferPacker().use { p ->
            p.packArrayHeader(4); p.packLong(8); p.packLong(100); p.packInt(2); p.packDouble(1.5); p.toByteArray()
        }
        assertTrue(NT4WireProtocol.unpackMessageFrames(valid + invalid).isEmpty())
    }

    @Test fun `heterogeneous and nested value arrays cannot masquerade as typed arrays`() {
        for (nested in listOf(false, true)) {
            val bytes = MessagePack.newDefaultBufferPacker().use { p ->
                p.packArrayHeader(4); p.packLong(8); p.packLong(100); p.packInt(18)
                p.packArrayHeader(2); p.packLong(1)
                if (nested) { p.packArrayHeader(1); p.packLong(2) } else p.packString("2")
                p.toByteArray()
            }
            assertTrue(NT4WireProtocol.unpackMessageFrames(bytes).isEmpty())
        }
    }

    @Test fun `empty frames invalid tuples and exact transport bounds fail closed`() {
        assertTrue(NT4WireProtocol.unpackMessageFrames(byteArrayOf()).isEmpty())
        assertTrue(NT4WireProtocol.unpackMessageFrames(byteArrayOf(0x91.toByte(), 0)).isEmpty())
        assertTrue(NT4WireProtocol.unpackMessageFrames(ByteArray(NT4WireProtocol.MAX_FRAME_BYTES + 1)).isEmpty())
        assertEquals(listOf(2.5, 3.5), NT4WireProtocol.unpackMessageFrames(encoded(1, 2.5) + encoded(1, 3.5)).map { it.value })
    }

    @Test fun `shared writer interoperates with server typed decoder for every exposed value family`() {
        val server = NT4Server(java.net.InetSocketAddress("127.0.0.1", 0), org.java_websocket.drafts.Draft_6455())
        val values = listOf(true, -0.0, Long.MAX_VALUE, Float.MIN_VALUE, "café", booleanArrayOf(), doubleArrayOf(1.5), longArrayOf(Long.MIN_VALUE), floatArrayOf(2f), arrayOf("text"))
        val entries = values.mapIndexed { index, value -> NT4Entry(index + 1, "probe/$index", NT4Value.fromObject(value), timestampUs = 100) }
        val encoded = server.encodeNT4Messages(100, entries)
        val messages = server.decodeNT4Messages(encoded)
        assertEquals(entries.size, messages.size)
        for (index in entries.indices) {
            assertEquals(entries[index].value, NT4Value.fromObject(messages[index].dataValue))
            assertEquals(100L, messages[index].timestamp)
        }
    }
}
