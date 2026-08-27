package com.areslib.networktables

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class NT4ValueOwnershipTest {
    @Test
    fun `array values snapshot publisher-owned buffers`() {
        val publisherBuffer = doubleArrayOf(1.0, 2.0)
        val first = NT4Value.fromObject(publisherBuffer)

        publisherBuffer[0] = 9.0
        val second = NT4Value.fromObject(publisherBuffer)

        assertContentEquals(doubleArrayOf(1.0, 2.0), first.getAsObject() as DoubleArray)
        assertContentEquals(doubleArrayOf(9.0, 2.0), second.getAsObject() as DoubleArray)
        assertFalse(first == second)
    }

    @Test
    fun `array getters do not expose network table storage`() {
        val value = NT4Value.DoubleArrayVal(doubleArrayOf(3.0, 4.0))

        (value.getAsObject() as DoubleArray)[0] = -1.0
        value.value[1] = -2.0

        assertContentEquals(doubleArrayOf(3.0, 4.0), value.getAsObject() as DoubleArray)
    }
}
