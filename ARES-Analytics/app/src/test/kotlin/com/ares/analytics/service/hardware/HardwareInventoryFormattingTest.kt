package com.ares.analytics.service.hardware

import kotlin.test.Test
import kotlin.test.assertEquals

class HardwareInventoryFormattingTest {
    @Test
    fun `normal setup numbers retain compact integral and fractional values`() {
        val expected = mapOf(0.0 to "0", -0.0 to "0", 4096.0 to "4096", -42.0 to "-42", 0.125 to "0.125")
        expected.forEach { (number, text) -> assertEquals(text, formatSetupNumber(number)) }
    }

    @Test
    fun `positive Long boundary never appears as a saturated smaller integer`() {
        val upperExclusive = Math.scalb(1.0, 63)
        assertEquals(upperExclusive.toString(), formatSetupNumber(upperExclusive))
        assertEquals("9223372036854774784", formatSetupNumber(Math.nextDown(upperExclusive)))
        assertEquals(Math.nextUp(upperExclusive).toString(), formatSetupNumber(Math.nextUp(upperExclusive)))
    }

    @Test
    fun `negative boundary and exceptional magnitudes avoid integer saturation`() {
        val lowerInclusive = Long.MIN_VALUE.toDouble()
        assertEquals("-9223372036854775808", formatSetupNumber(lowerInclusive))
        val outside = listOf(Math.nextDown(lowerInclusive), Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        outside.forEach { assertEquals(it.toString(), formatSetupNumber(it)) }
    }
}
