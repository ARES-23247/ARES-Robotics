package com.ares.analytics.service

import com.ares.analytics.service.db.TelemetryExportCursor
import com.ares.analytics.service.db.TelemetryExportPreflight
import com.ares.analytics.service.db.TelemetryExportValueType
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Bounded, lossless telemetry exports.
 *
 * Every format is written to a sibling temporary file, force-flushed, and atomically installed.
 * Selected frames are preflighted in DuckDB with a cap + 1 query and then consumed in stable
 * keyset pages, so exports never materialize or sort a complete session in JVM memory.
 */
class ExportService(private val databaseService: DatabaseService) {
    private var beforeAtomicReplace: BeforeAtomicReplace = NO_OP_BEFORE_ATOMIC_REPLACE
    private var maximumSourceFrames: Int = MAX_EXPORT_SOURCE_FRAMES

    internal constructor(
        databaseService: DatabaseService,
        beforeAtomicReplace: BeforeAtomicReplace,
        maximumSourceFrames: Int = MAX_EXPORT_SOURCE_FRAMES,
    ) : this(databaseService) {
        require(maximumSourceFrames > 0) { "maximumSourceFrames must be positive" }
        this.beforeAtomicReplace = beforeAtomicReplace
        this.maximumSourceFrames = maximumSourceFrames
    }

    /**
     * Canonical lossless CSV form. A string sample retains both its authoritative string and its
     * numeric storage placeholder; `value_type` distinguishes an empty string from a numeric row.
     */
    suspend fun exportToCsvList(
        sessionId: String,
        selectedKeys: List<String>,
        destinationFile: File,
    ) = withContext(Dispatchers.IO) {
        val keys = normalizedKeys(selectedKeys)
        val preflight = preflight(sessionId, keys)
        writeFileAtomicallySuspending(destinationFile, beforeAtomicReplace) { temporary ->
            Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8).use { writer ->
                writer.writeLine(
                    "key,timestamp_ms,timestamp_us,sample_order,value_type,numeric_value,string_value",
                )
                streamFrames(sessionId, keys, preflight.boundedFrameCount) { frame ->
                    val valueType = if (frame.stringValue == null) "double" else "string"
                    writer.write(csvCell(frame.key, neutralizeFormula = false))
                    writer.write(",${frame.timestampMs},${frame.timestampUs},${frame.sampleOrder},$valueType,")
                    writer.write(frame.value.toString())
                    writer.write(','.code)
                    // Preserve the exact text. RFC 4180 quoting is reversible; formula-prefix
                    // rewriting is intentionally reserved for display-oriented table cells.
                    writer.write(frame.stringValue?.let { csvCell(it, neutralizeFormula = false) }.orEmpty())
                    writer.newLine()
                }
            }
        }
    }

    /** Writes a sample-and-hold wide CSV without retaining the selected source frames. */
    suspend fun exportToCsvTable(
        sessionId: String,
        selectedKeys: List<String>,
        destinationFile: File,
        samplingPeriodMs: Long? = null,
    ) = withContext(Dispatchers.IO) {
        val keys = normalizedKeys(selectedKeys)
        require(samplingPeriodMs == null || samplingPeriodMs > 0L) {
            "samplingPeriodMs must be positive when supplied"
        }
        val preflight = preflight(sessionId, keys)
        val minTime = preflight.minTimestampMs
        val maxTime = preflight.maxTimestampMs
        if (preflight.boundedFrameCount > 0L) {
            requireNotNull(minTime)
            requireNotNull(maxTime)
            require(minTime >= 0L && maxTime >= minTime) { "Export contains an invalid timestamp domain" }
            val spanMs = Math.subtractExact(maxTime, minTime)
            require(spanMs <= MAX_EXPORT_SPAN_MS) {
                "Export spans more than ${MAX_EXPORT_SPAN_MS / 86_400_000L} days"
            }
            samplingPeriodMs?.let { period ->
                val sampledRows = spanMs / period + 1L
                require(sampledRows <= MAX_EXPORT_ROWS.toLong()) {
                    "Export exceeds the $MAX_EXPORT_ROWS-row safety limit"
                }
            }
        }

        writeFileAtomicallySuspending(destinationFile, beforeAtomicReplace) { temporary ->
            Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8).use { writer ->
                writer.writeLine("timestamp_ms," + keys.joinToString(",", transform = ::csvCell))
                if (preflight.boundedFrameCount == 0L) return@use

                val keyIndices = keys.withIndex().associate { (index, key) -> key to index }
                val lastValues = arrayOfNulls<TableCell>(keys.size)
                var writtenRows = 0

                fun applyFrame(frame: TelemetryFrame) {
                    val index = keyIndices[frame.key] ?: return
                    val stringValue = frame.stringValue
                    lastValues[index] = if (stringValue == null) {
                        TableCell(frame.value.toString(), neutralizeFormula = false)
                    } else {
                        TableCell(stringValue, neutralizeFormula = true)
                    }
                }

                fun writeRow(timestampMs: Long) {
                    writtenRows++
                    require(writtenRows <= MAX_EXPORT_ROWS) {
                        "Export exceeds the $MAX_EXPORT_ROWS-row safety limit"
                    }
                    writer.write(timestampMs.toString())
                    for (cell in lastValues) {
                        writer.write(','.code)
                        if (cell != null) {
                            writer.write(csvCell(cell.value, cell.neutralizeFormula))
                        }
                    }
                    writer.newLine()
                }

                if (samplingPeriodMs == null) {
                    var currentTimestamp: Long? = null
                    streamFrames(sessionId, keys, preflight.boundedFrameCount) { frame ->
                        if (currentTimestamp != null && frame.timestampMs != currentTimestamp) {
                            writeRow(requireNotNull(currentTimestamp))
                        }
                        currentTimestamp = frame.timestampMs
                        applyFrame(frame)
                    }
                    currentTimestamp?.let(::writeRow)
                } else {
                    val period = requireNotNull(samplingPeriodMs)
                    val finalTimestamp = requireNotNull(maxTime)
                    var nextSample: Long? = requireNotNull(minTime)
                    var currentFrameTimestamp: Long? = null

                    fun writeNextSample() {
                        val timestamp = requireNotNull(nextSample)
                        writeRow(timestamp)
                        nextSample = if (finalTimestamp - timestamp >= period) {
                            Math.addExact(timestamp, period)
                        } else {
                            null
                        }
                    }

                    streamFrames(sessionId, keys, preflight.boundedFrameCount) { frame ->
                        if (currentFrameTimestamp != frame.timestampMs) {
                            if (currentFrameTimestamp != null && nextSample == currentFrameTimestamp) {
                                writeNextSample()
                            }
                            while (nextSample != null && requireNotNull(nextSample) < frame.timestampMs) {
                                writeNextSample()
                            }
                            currentFrameTimestamp = frame.timestampMs
                        }
                        applyFrame(frame)
                    }
                    if (currentFrameTimestamp != null && nextSample == currentFrameTimestamp) {
                        writeNextSample()
                    }
                    while (nextSample != null && requireNotNull(nextSample) <= finalTimestamp) {
                        writeNextSample()
                    }
                }
            }
        }
    }

    /**
     * Exports one WPILOG entry per topic. Numeric and string entries receive their correct WPILib
     * type and original microsecond timestamp. A topic that changes representation is rejected,
     * because a WPILOG entry has one immutable type for its lifetime.
     */
    suspend fun exportToWpiLog(
        sessionId: String,
        selectedKeys: List<String>,
        destinationFile: File,
    ) = withContext(Dispatchers.IO) {
        val keys = normalizedKeys(selectedKeys)
        val preflight = preflight(sessionId, keys)
        val valueTypes = databaseService.getTelemetryExportValueTypes(sessionId, keys)
        val mixedKeys = valueTypes.filterValues { it == TelemetryExportValueType.MIXED }.keys
        require(mixedKeys.isEmpty()) {
            "WPILOG export cannot represent mixed numeric/string topics: ${mixedKeys.sorted().joinToString()}"
        }

        writeFileAtomicallySuspending(destinationFile, beforeAtomicReplace) { temporary ->
            BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                output.write("WPILOG".toByteArray(StandardCharsets.US_ASCII))
                output.write(byteArrayOf(0x00, 0x01)) // Version 0x0100, little endian.
                output.write(byteArrayOf(0, 0, 0, 0))

                val entryIds = LinkedHashMap<String, Int>(keys.size)
                keys.forEachIndexed { index, key ->
                    val entryId = index + 1
                    entryIds[key] = entryId
                    val type = if (valueTypes[key] == TelemetryExportValueType.STRING) "string" else "double"
                    writeWpiRecord(output, WpiRecord(0, 0L, startPayload(entryId, key, type)))
                }

                streamFrames(sessionId, keys, preflight.boundedFrameCount) { frame ->
                    val entryId = requireNotNull(entryIds[frame.key])
                    val expectedType = valueTypes[frame.key] ?: TelemetryExportValueType.NUMERIC
                    val payload = when (expectedType) {
                        TelemetryExportValueType.NUMERIC -> {
                            require(frame.stringValue == null) { "Telemetry type changed during WPILOG export" }
                            ByteBuffer.allocate(Double.SIZE_BYTES)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putDouble(frame.value)
                                .array()
                        }

                        TelemetryExportValueType.STRING -> {
                            requireNotNull(frame.stringValue) { "Telemetry type changed during WPILOG export" }
                                .toByteArray(StandardCharsets.UTF_8)
                        }

                        TelemetryExportValueType.MIXED -> error("Mixed WPILOG type passed validation")
                    }
                    require(frame.timestampUs >= 0L) { "WPILOG export contains a negative timestamp" }
                    writeWpiRecord(output, WpiRecord(entryId, frame.timestampUs, payload))
                }
            }
        }
    }

    private suspend fun preflight(
        sessionId: String,
        keys: List<String>,
    ): TelemetryExportPreflight = databaseService.getTelemetryExportPreflight(
        sessionId,
        keys,
        maximumSourceFrames,
    ).also { result ->
        require(result.boundedFrameCount <= maximumSourceFrames.toLong()) {
            "Export exceeds the $maximumSourceFrames-frame safety limit"
        }
    }

    private suspend fun streamFrames(
        sessionId: String,
        keys: List<String>,
        expectedCount: Long,
        consume: (TelemetryFrame) -> Unit,
    ) {
        var cursor: TelemetryExportCursor? = null
        var consumed = 0L
        while (true) {
            val page = databaseService.getTelemetryExportPage(sessionId, keys, cursor, EXPORT_PAGE_SIZE)
            if (page.isEmpty()) break
            for (frame in page) {
                consume(frame)
                consumed++
                require(consumed <= maximumSourceFrames.toLong()) {
                    "Export exceeded the $maximumSourceFrames-frame safety limit while streaming"
                }
            }
            val last = page.last()
            cursor = TelemetryExportCursor(last.timestampUs, last.sampleOrder, last.key)
            if (page.size < EXPORT_PAGE_SIZE) break
        }
        check(consumed == expectedCount) {
            "Telemetry changed during export (preflight=$expectedCount, streamed=$consumed)"
        }
    }

    private fun startPayload(entryId: Int, key: String, type: String): ByteArray {
        val nameBytes = key.toByteArray(StandardCharsets.UTF_8)
        val typeBytes = type.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + 4 + nameBytes.size + 4 + typeBytes.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0.toByte()) // CONTROL_START
                putInt(entryId)
                putInt(nameBytes.size)
                put(nameBytes)
                putInt(typeBytes.size)
                put(typeBytes)
                putInt(0) // Empty metadata.
            }
            .array()
    }

    private fun writeWpiRecord(output: OutputStream, record: WpiRecord) {
        val entryBytes = encodeInteger(record.entry.toLong())
        val sizeBytes = encodeInteger(record.payload.size.toLong())
        val timestampBytes = encodeInteger(record.timestampMicro)
        var descriptor = (entryBytes.size - 1) and 0x3
        descriptor = descriptor or (((sizeBytes.size - 1) and 0x3) shl 2)
        descriptor = descriptor or (((timestampBytes.size - 1) and 0x7) shl 4)
        output.write(descriptor)
        output.write(entryBytes)
        output.write(sizeBytes)
        output.write(timestampBytes)
        output.write(record.payload)
    }

    private fun normalizedKeys(selectedKeys: List<String>): List<String> =
        selectedKeys.map(TelemetryMetricCatalog::normalizeTopic).distinct().also { keys ->
            require(keys.size <= MAX_EXPORT_KEYS) { "Export supports at most $MAX_EXPORT_KEYS telemetry keys" }
        }

    /** RFC 4180 escaping with optional spreadsheet formula neutralization for display cells. */
    private fun csvCell(raw: String, neutralizeFormula: Boolean = true): String {
        val firstMeaningful = raw.firstOrNull { !it.isWhitespace() }
        val neutralized = if (
            neutralizeFormula && firstMeaningful != null && firstMeaningful in FORMULA_PREFIXES
        ) {
            "'$raw"
        } else {
            raw
        }
        return if (neutralized.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${neutralized.replace("\"", "\"\"")}\""
        } else {
            neutralized
        }
    }

    private fun encodeInteger(value: Long): ByteArray {
        require(value >= 0L) { "WPILOG integers must be unsigned" }
        val bytes = ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        for (index in bytes.lastIndex downTo 1) {
            if (bytes[index] != 0.toByte()) return bytes.copyOfRange(0, index + 1)
        }
        return bytes.copyOfRange(0, 1)
    }

    private fun BufferedWriter.writeLine(value: String) {
        write(value)
        newLine()
    }

    private data class TableCell(val value: String, val neutralizeFormula: Boolean)
    private data class WpiRecord(val entry: Int, val timestampMicro: Long, val payload: ByteArray)

    private companion object {
        private const val MAX_EXPORT_KEYS = 256
        private const val MAX_EXPORT_SOURCE_FRAMES = 250_000
        private const val EXPORT_PAGE_SIZE = 5_000
        private const val MAX_EXPORT_ROWS = 1_000_000
        private const val MAX_EXPORT_SPAN_MS = 7L * 24L * 60L * 60L * 1000L
        private val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
    }
}
