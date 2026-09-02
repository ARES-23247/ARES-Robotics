package com.ares.analytics.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Centralized, thread-safe date/time and physical unit formatters for ARES-Analytics.
 * Eliminates per-item formatting allocations during live telemetry and log streaming.
 */
object AresFormatters {

    val TIME_MILLIS: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    val TIME_SECONDS: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss", Locale.US)
        .withZone(ZoneId.systemDefault())

    val TIME_SHORT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    val DATE_TIME_SHORT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("MMM dd, HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    val DATE_TIME_FULL: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        .withZone(ZoneId.systemDefault())

    val DATE_TIME_MINUTES: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    fun formatTimeMillis(epochMillis: Long): String =
        TIME_MILLIS.format(Instant.ofEpochMilli(epochMillis))

    fun formatTimeSeconds(epochMillis: Long): String =
        TIME_SECONDS.format(Instant.ofEpochMilli(epochMillis))

    fun formatTimeShort(epochMillis: Long): String =
        TIME_SHORT.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeShort(epochMillis: Long): String =
        DATE_TIME_SHORT.format(Instant.ofEpochMilli(epochMillis))

    fun formatDateTimeMinutes(epochMillis: Long): String =
        DATE_TIME_MINUTES.format(Instant.ofEpochMilli(epochMillis))

    fun formatVoltage(volts: Double?): String =
        if (volts != null && !volts.isNaN()) String.format(Locale.US, "%.2f V", volts) else "--"

    fun formatCurrent(amps: Double?): String =
        if (amps != null && !amps.isNaN()) String.format(Locale.US, "%.1f A", amps) else "--"

    fun formatLoopTime(ms: Double?): String =
        if (ms != null && !ms.isNaN()) String.format(Locale.US, "%.1f ms", ms) else "--"

    fun formatHz(ms: Double?): String =
        if (ms != null && ms > 0.0 && !ms.isNaN()) String.format(Locale.US, "%.0f Hz", 1000.0 / ms) else "--"

    fun formatHeadingDeg(rad: Double?): String =
        if (rad != null && !rad.isNaN()) String.format(Locale.US, "%.1f°", Math.toDegrees(rad)) else "--"
}
