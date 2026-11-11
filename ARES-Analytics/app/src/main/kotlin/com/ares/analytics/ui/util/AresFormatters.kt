package com.ares.analytics.ui.util

import com.ares.analytics.ui.components.core.AresFileChooserLauncher
import com.ares.analytics.ui.components.core.AresFileChooserMode
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
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

    private const val MIN_SAFE_EPOCH_MILLIS = -62167219200000L // 0001-01-01
    private const val MAX_SAFE_EPOCH_MILLIS = 253402300799000L  // 9999-12-31

    private fun safeInstant(epochMillis: Long): Instant =
        Instant.ofEpochMilli(epochMillis.coerceIn(MIN_SAFE_EPOCH_MILLIS, MAX_SAFE_EPOCH_MILLIS))

    fun formatTimeMillis(epochMillis: Long): String =
        timeMillis.format(safeInstant(epochMillis))

    fun formatDateTimeShort(epochMillis: Long): String =
        dateTimeShort.format(safeInstant(epochMillis))

    fun formatDateTimeMinutes(epochMillis: Long): String =
        dateTimeMinutes.format(safeInstant(epochMillis))

    fun formatDateTimeSeconds(epochMillis: Long): String =
        dateTimeSeconds.format(safeInstant(epochMillis))

    fun formatTimeHoursMinutes(epochMillis: Long): String =
        timeHoursMinutes.format(safeInstant(epochMillis))

    fun formatCompactTimestamp(epochMillis: Long): String =
        compactTimestamp.format(safeInstant(epochMillis))

    fun formatCompactTimestampHyphen(epochMillis: Long): String =
        compactTimestampHyphen.format(safeInstant(epochMillis))

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1023.95) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1023.95) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    fun formatRate(bytesPerSec: Double): String {
        if (!bytesPerSec.isFinite() || bytesPerSec <= 0.0) return "0 B/s"
        if (bytesPerSec < 1023.5) return String.format(Locale.US, "%.0f B/s", bytesPerSec)
        val kb = bytesPerSec / 1024.0
        if (kb < 1023.95) return String.format(Locale.US, "%.1f KB/s", kb)
        val mb = kb / 1024.0
        if (mb < 1023.95) return String.format(Locale.US, "%.1f MB/s", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB/s", gb)
    }

    fun formatDecimal(value: Double, decimals: Int = 2, fallback: String = "—"): String =
        if (value.isFinite()) String.format(Locale.US, "%.${decimals}f", value) else fallback
}

/** Returns this [Double] if finite (neither NaN nor infinite), or [fallback] otherwise. */
internal fun Double.finiteOr(fallback: Double = 0.0): Double =
    if (isFinite()) this else fallback

/** Returns this [Double] coerced to [min]..[max] if finite, or [fallback] coerced to [min]..[max] otherwise. */
internal fun Double.finiteCoerceIn(min: Double, max: Double, fallback: Double = min): Double =
    (if (isFinite()) this else fallback).coerceIn(min, max)

/** Copies plain text to the system AWT clipboard, returning true when successful. */
internal fun copyTextToSystemClipboard(text: String): Boolean = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
        java.awt.datatransfer.StringSelection(text),
        null,
    )
    true
}.getOrDefault(false)
