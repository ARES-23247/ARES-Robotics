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
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(AresFormatters.formatDateTimeSeconds(timestamp)))
        assertTrue(Regex("\\d{2}:\\d{2}").matches(AresFormatters.formatTimeHoursMinutes(timestamp)))
        assertTrue(Regex("\\d{8}_\\d{6}").matches(AresFormatters.formatCompactTimestamp(timestamp)))
        assertTrue(Regex("\\d{8}-\\d{6}").matches(AresFormatters.formatCompactTimestampHyphen(timestamp)))
    }

    @Test
    fun `concurrent formatting agrees with independently prepared calendar expectations`() {
        val timestamps = listOf(-1L, 0L, 951_782_400_123L, 1_725_000_000_123L, 2_147_483_648_000L)
        val formats = listOf(
            "HH:mm:ss.SSS",
            "MMM dd, HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "HH:mm",
            "yyyyMMdd_HHmmss",
            "yyyyMMdd-HHmmss",
        ).map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getDefault() }
        }
        // Prepare expectations on this thread; SimpleDateFormat itself is not shared with workers.
        val expected = timestamps.associateWith { timestamp -> formats.map { it.format(Date(timestamp)) } }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 200).map { index -> Callable {
                val timestamp = timestamps[index % timestamps.size]
                assertEquals(expected.getValue(timestamp), listOf(
                    AresFormatters.formatTimeMillis(timestamp),
                    AresFormatters.formatDateTimeShort(timestamp),
                    AresFormatters.formatDateTimeMinutes(timestamp),
                    AresFormatters.formatDateTimeSeconds(timestamp),
                    AresFormatters.formatTimeHoursMinutes(timestamp),
                    AresFormatters.formatCompactTimestamp(timestamp),
                    AresFormatters.formatCompactTimestampHyphen(timestamp),
                ))
            } }
            executor.invokeAll(tasks, 5, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `formatBytes produces correct units and boundaries`() {
        assertEquals("0 B", AresFormatters.formatBytes(-10L))
        assertEquals("0 B", AresFormatters.formatBytes(0L))
        assertEquals("512 B", AresFormatters.formatBytes(512L))
        assertEquals("1.0 KB", AresFormatters.formatBytes(1024L))
        assertEquals("1.5 KB", AresFormatters.formatBytes(1536L))
        assertEquals("1.0 MB", AresFormatters.formatBytes(1024L * 1024L))
        assertEquals("2.5 GB", AresFormatters.formatBytes((2.5 * 1024L * 1024L * 1024L).toLong()))
    }

    @Test
    fun `formatRate produces human readable transfer rates`() {
        assertEquals("0 B/s", AresFormatters.formatRate(-5.0))
        assertEquals("0 B/s", AresFormatters.formatRate(0.0))
        assertEquals("0 B/s", AresFormatters.formatRate(Double.NaN))
        assertEquals("500 B/s", AresFormatters.formatRate(500.0))
        assertEquals("1.5 KB/s", AresFormatters.formatRate(1536.0))
        assertEquals("3.0 MB/s", AresFormatters.formatRate(3.0 * 1024.0 * 1024.0))
    }
}
