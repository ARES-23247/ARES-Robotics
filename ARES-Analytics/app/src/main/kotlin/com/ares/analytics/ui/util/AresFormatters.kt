package com.ares.analytics.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Centralized, thread-safe date/time formatters used by Studio lists and telemetry consoles.
 */
internal object AresFormatters {

    private val timeMillis: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val dateTimeShort: DateTimeFormatter = DateTimeFormatter
        .ofPattern("MMM dd, HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val dateTimeMinutes: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val dateTimeSeconds: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val timeHoursMinutes: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val compactTimestamp: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val compactTimestampHyphen: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    fun formatTimeMillis(epochMillis: Long): String =
        timeMillis.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeShort(epochMillis: Long): String =
        dateTimeShort.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeMinutes(epochMillis: Long): String =
        dateTimeMinutes.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeSeconds(epochMillis: Long): String =
        dateTimeSeconds.format(Instant.ofEpochMilli(epochMillis))

    fun formatTimeHoursMinutes(epochMillis: Long): String =
        timeHoursMinutes.format(Instant.ofEpochMilli(epochMillis))

    fun formatCompactTimestamp(epochMillis: Long): String =
        compactTimestamp.format(Instant.ofEpochMilli(epochMillis))

    fun formatCompactTimestampHyphen(epochMillis: Long): String =
        compactTimestampHyphen.format(Instant.ofEpochMilli(epochMillis))

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    fun formatRate(bytesPerSec: Double): String {
        if (!bytesPerSec.isFinite() || bytesPerSec <= 0.0) return "0 B/s"
        if (bytesPerSec < 1024.0) return String.format(Locale.US, "%.0f B/s", bytesPerSec)
        val kb = bytesPerSec / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB/s", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB/s", mb)
    }
}
