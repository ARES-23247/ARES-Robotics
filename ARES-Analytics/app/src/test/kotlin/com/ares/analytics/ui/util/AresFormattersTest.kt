package com.ares.analytics.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AresFormattersTest {
    @Test
    fun `time formatter includes milliseconds`() {
        val formatted = AresFormatters.formatTimeMillis(1_725_000_000_123L)

        assertTrue(Regex("\\d{2}:\\d{2}:\\d{2}\\.123").matches(formatted), formatted)
    }

    @Test
    fun `date formatters have stable locale-independent shapes`() {
        val timestamp = 1_725_000_000_000L

        assertTrue(Regex("[A-Z][a-z]{2} \\d{2}, \\d{2}:\\d{2}").matches(AresFormatters.formatDateTimeShort(timestamp)))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(AresFormatters.formatDateTimeMinutes(timestamp)))
    }

    @Test
    fun `formatters return deterministic text for repeated timestamps`() {
        val timestamp = 1_725_000_000_123L

        assertEquals(AresFormatters.formatTimeMillis(timestamp), AresFormatters.formatTimeMillis(timestamp))
        assertEquals(AresFormatters.formatDateTimeShort(timestamp), AresFormatters.formatDateTimeShort(timestamp))
        assertEquals(AresFormatters.formatDateTimeMinutes(timestamp), AresFormatters.formatDateTimeMinutes(timestamp))
    }
}
