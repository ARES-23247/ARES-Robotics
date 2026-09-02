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

    fun formatTimeMillis(epochMillis: Long): String =
        timeMillis.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeShort(epochMillis: Long): String =
        dateTimeShort.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeMinutes(epochMillis: Long): String =
        dateTimeMinutes.format(Instant.ofEpochMilli(epochMillis))
}
