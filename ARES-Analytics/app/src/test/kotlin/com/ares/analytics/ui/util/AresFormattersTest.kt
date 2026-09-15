package com.ares.analytics.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `concurrent formatting agrees with independently prepared calendar expectations`() {
        val timestamps = listOf(-1L, 0L, 951_782_400_123L, 1_725_000_000_123L, 2_147_483_648_000L)
        val formats = listOf("HH:mm:ss.SSS", "MMM dd, HH:mm", "yyyy-MM-dd HH:mm").map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }
        }
        // Prepare expectations on this thread; SimpleDateFormat itself is not shared with workers.
        val expected = timestamps.associateWith { timestamp -> formats.map { it.format(Date(timestamp)) } }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 200).map { index -> Callable {
                val timestamp = timestamps[index % timestamps.size]
                assertEquals(expected.getValue(timestamp), listOf(AresFormatters.formatTimeMillis(timestamp),
                    AresFormatters.formatDateTimeShort(timestamp), AresFormatters.formatDateTimeMinutes(timestamp)))
            } }
            executor.invokeAll(tasks, 5, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }
}
