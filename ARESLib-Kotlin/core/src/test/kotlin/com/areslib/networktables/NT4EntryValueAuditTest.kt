package com.areslib.networktables

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class NT4EntryValueAuditTest {
    @Test fun `fromObject cannot truncate extended numbers`() {
        assertEquals(Long.MAX_VALUE, (NT4Value.fromObject(BigInteger.valueOf(Long.MAX_VALUE)) as NT4Value.LongVal).value)
        for (value in listOf(BigInteger.ONE.shiftLeft(64), BigDecimal("1.5"))) {
            assertFailsWith<IllegalArgumentException> { NT4Value.fromObject(value) }
        }
    }

    @Test fun `already typed values retain their type and snapshot`() {
        val value = NT4Value.DoubleArrayVal(doubleArrayOf(1.0, 2.0))
        assertSame(value, NT4Value.fromObject(value))
    }

    @Test fun `integer array conversion is exact and isolates both boundaries`() {
        val source = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)
        val value = NT4Value.fromObject(source) as NT4Value.LongArrayVal
        source[0] = 0; value.value[1] = 0
        assertContentEquals(longArrayOf(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()), value.value)
    }

    @Test fun `java nullable string array is rejected at snapshot construction`() {
        @Suppress("UNCHECKED_CAST") val values = arrayOf<String?>(null) as Array<String>
        assertFailsWith<IllegalArgumentException> { NT4Value.StringArrayVal(values) }
    }

    @Test fun `all scalar and array type identifiers agree with their value wrappers`() {
        val values = listOf(true, 1.0, 1L, 1f, "text", booleanArrayOf(true), doubleArrayOf(1.0), longArrayOf(1), floatArrayOf(1f), arrayOf("one"))
        for ((index, id) in listOf(0, 1, 2, 3, 4, 16, 17, 18, 19, 20).withIndex()) {
            assertEquals(NT4Value.fromId(id).typeString, NT4Value.fromObject(values[index]).typeString)
        }
        assertEquals(NT4Type.UNKNOWN, NT4Value.fromId(99))
        assertEquals("", NT4Value.fromObject(null).getAsObject())
    }

    @Test fun `every array wrapper preserves source getter equality and hash isolation`() {
        for (source in listOf(booleanArrayOf(true), doubleArrayOf(-0.0), longArrayOf(Long.MAX_VALUE), floatArrayOf(Float.MIN_VALUE), arrayOf("original"))) {
            val expected = java.lang.reflect.Array.get(source, 0)
            val value = NT4Value.fromObject(source)
            val hash = value.hashCode()
            fun change(array: Any) {
                when (array) {
                    is BooleanArray -> array[0] = false
                    is DoubleArray -> array[0] = 2.0
                    is LongArray -> array[0] = 0
                    is FloatArray -> array[0] = 0f
                    is Array<*> -> java.lang.reflect.Array.set(array, 0, "changed")
                }
            }
            change(source); change(value.getAsObject())
            assertEquals(expected, java.lang.reflect.Array.get(value.getAsObject(), 0))
            assertEquals(hash, value.hashCode())
            assertEquals(value, NT4Value.fromObject(value.getAsObject()))
            assertNotEquals(value, NT4Value.fromObject(source))
        }
    }

    @Test fun `entry retains newest timestamp while observing first placeholder value`() {
        val entry = NT4Entry(1, "probe", NT4Value.LongVal(0), hasValue = false)
        var calls = 0; entry.addListener { _, _, _ -> calls++ }
        assertTrue(entry.update(NT4Value.LongVal(0), 10))
        assertFalse(entry.update(NT4Value.LongVal(9), 9))
        assertFalse(entry.update(NT4Value.LongVal(0), 11))
        assertTrue(entry.update(NT4Value.LongVal(2), 11))
        assertEquals(2, calls); assertEquals(11L, entry.timestampUs)
        assertEquals(NT4Value.LongVal(2), entry.value)
    }

    @Test fun `listener mutation uses stable snapshot and isolates ordinary failures`() {
        val entry = NT4Entry(1, "probe", NT4Value.LongVal(0))
        val calls = mutableListOf<String>()
        val later = NT4EventListener { _, _, _ -> calls += "later" }
        val first = NT4EventListener { _, _, _ -> calls += "first"; entry.addListener(later); throw IllegalStateException("listener") }
        entry.addListener(first)
        entry.update(NT4Value.LongVal(1), 1); assertEquals(listOf("first"), calls)
        entry.removeListener(first)
        entry.update(NT4Value.LongVal(2), 2); assertEquals(listOf("first", "later"), calls)
    }

    @Test fun `authoritative replacement clears future client ownership without premature callback`() {
        val entry = NT4Entry(1, "probe", NT4Value.LongVal(1), timestampUs = 1000)
        var calls = 0; entry.addListener { _, _, _ -> calls++ }
        assertTrue(entry.replaceAuthoritatively(NT4Value.LongVal(2), 10))
        assertEquals(10L, entry.timestampUs); assertEquals(0, calls)
        entry.notifyListeners(NT4EventType.TOPIC_UPDATED, entry.value); assertEquals(1, calls)
    }
}
