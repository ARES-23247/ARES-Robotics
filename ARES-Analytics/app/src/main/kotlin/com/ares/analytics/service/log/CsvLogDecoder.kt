package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.GZIPInputStream

/**
 * Service for decoding CSV-formatted telemetry log files into DuckDB database frames.
 *
 * Implements two ingestion pipelines:
 * 1. **Native DuckDB `UNPIVOT` Ingestion** ([parseCsvLogNative]): Uses `read_csv_auto` to unpivot wide CSV columns directly into long-format `telemetry_frames` table, executing 10–50× faster than streaming JVM parsers without object allocation.
 * 2. **Streaming Ingestion** ([parseCsvLogStreaming]): Uses a bounded [FrameBatcher] channel to parse multi-file or custom-prefixed CSV logs safely within JVM heap constraints.
 *
 * ### Physical Units & Schema Mapping:
 * - Timestamps: Milliseconds ($ms$), automatically detected from `"time"` or `"timestamp"` column headers.
 * - Double values: Floating-point numeric metrics (Voltage $V$, Current $A$, Velocity $m/s$, Angles $rad$).
 * - Boolean values: Converted to numeric `1.0` (true) / `0.0` (false).
 * - String values: Stored in `string_value` column if non-numeric string data is present.
 *
 * ### Thread Safety & Performance Guarantees:
 * Suspend functions execute asynchronously on `Dispatchers.IO`. Native DuckDB unpivot operates in native memory space.
 *
 * @param databaseService Primary DuckDB database management service.
 *
 * @see JsonlLogDecoder
 * @see WpiLogDecoder
 * @see FrameBatcher
 */
class CsvLogDecoder(private val databaseService: DatabaseService) {

    private enum class CsvSchema {
        WIDE,
        CANONICAL_LONG,
    }

    private enum class TimestampUnit(val microsPerUnit: BigDecimal) {
        SECONDS(BigDecimal("1000000")),
        MILLISECONDS(BigDecimal("1000")),
        MICROSECONDS(BigDecimal.ONE),
        NANOSECONDS(BigDecimal("0.001"))
    }

    private data class TimestampColumn(
        val index: Int,
        val name: String,
        val unit: TimestampUnit
    )

    private companion object {
        const val EXTRA_FIELDS_COLUMN = "_ExtraFieldsJson"
        const val MAX_CSV_BYTES = 512L * 1024L * 1024L
        const val MAX_CSV_COLUMNS = 4_096
        const val MAX_CSV_FIELD_CHARS = 1_048_576
        const val MAX_CSV_RECORD_CHARS = 4 * 1_048_576
        const val MAX_SUPPORTED_TIMESTAMP_US = MAX_SUPPORTED_TIMESTAMP_MS * 1_000L + 999L

        val CANONICAL_LONG_HEADERS = setOf(
            "key",
            "timestamp_ms",
            "timestamp_us",
            "sample_order",
            "value_type",
            "numeric_value",
            "string_value",
        )
        val CANONICAL_LONG_MARKERS = setOf(
            "key",
            "sampleorder",
            "valuetype",
            "numericvalue",
            "stringvalue",
        )

        val TIMESTAMP_HEADERS = mapOf(
            "times" to TimestampUnit.SECONDS,
            "timestamps" to TimestampUnit.SECONDS,
            "timesecond" to TimestampUnit.SECONDS,
            "timeseconds" to TimestampUnit.SECONDS,
            "timestampsecond" to TimestampUnit.SECONDS,
            "timestampseconds" to TimestampUnit.SECONDS,
            "timems" to TimestampUnit.MILLISECONDS,
            "timestampms" to TimestampUnit.MILLISECONDS,
            "timemillisecond" to TimestampUnit.MILLISECONDS,
            "timemilliseconds" to TimestampUnit.MILLISECONDS,
            "timestampmillisecond" to TimestampUnit.MILLISECONDS,
            "timestampmilliseconds" to TimestampUnit.MILLISECONDS,
            "timeus" to TimestampUnit.MICROSECONDS,
            "timestampus" to TimestampUnit.MICROSECONDS,
            "timemicrosecond" to TimestampUnit.MICROSECONDS,
            "timemicroseconds" to TimestampUnit.MICROSECONDS,
            "timestampmicrosecond" to TimestampUnit.MICROSECONDS,
            "timestampmicroseconds" to TimestampUnit.MICROSECONDS,
            "timens" to TimestampUnit.NANOSECONDS,
            "timestampns" to TimestampUnit.NANOSECONDS,
            "timenanosecond" to TimestampUnit.NANOSECONDS,
            "timenanoseconds" to TimestampUnit.NANOSECONDS,
            "timestampnanosecond" to TimestampUnit.NANOSECONDS,
            "timestampnanoseconds" to TimestampUnit.NANOSECONDS
        )
    }

/**
     * Imports a CSV file directly into DuckDB using native `read_csv_auto` + `UNPIVOT`,
     * bypassing all Kotlin-side string parsing and TelemetryFrame object allocation.
     * This is ~10-50× faster than the streaming Kotlin parser for large CSV files.
     *
     * The CSV is expected to have a header row with one timestamp column (containing
     * "time" or "timestamp" in its name) and remaining columns as telemetry keys.
     */
    suspend fun parseCsvLogNative(file: File, sessionId: String) {
        require(file.isFile) { "CSV log does not exist: ${file.absolutePath}" }
        require(file.length() in 1L..MAX_CSV_BYTES) { "CSV log size is outside the supported range" }
        require(!file.name.endsWith(".gz", ignoreCase = true)) {
            "Compressed CSV logs use the bounded streaming importer"
        }
        val absolutePath = file.absolutePath.replace("\\", "/").replace("'", "''")

        // Detect the timestamp column name from the header
        val headers = boundedCsvReader(file).use { it.readRecord() }
            ?: throw IllegalArgumentException("CSV log ${file.name} is empty")
        if (detectSchema(headers, file.name) == CsvSchema.CANONICAL_LONG) {
            parseCanonicalLongFormNative(file, sessionId, absolutePath)
            return
        }
        val timestampColumn = resolveTimestampColumn(headers, file.name)
        val timeColumnName = timestampColumn.name
        val frameCountBefore = databaseService.countTelemetryFrames(sessionId)

        // Use DuckDB's native CSV reader with UNPIVOT to convert wide-format CSV
        // directly into the long-format telemetry_frames schema in a single SQL pass.
        // ARESDataLogger reserves _ExtraFieldsJson for keys that first appear after the
        // stable CSV header. Expand that object back into ordinary telemetry rows so late
        // keys remain first-class data throughout import, replay, and analysis.
        val escapedSessionId = sessionId.replace("'", "''")
        val escapedTimeCol = timeColumnName.replace("'", "''").replace("\"", "\"\"")
        val hasExtraFields = headers.any { it.trim().trim('"') == EXTRA_FIELDS_COLUMN }

        fun checkedTimestampUs(column: String): String {
            val numeric = "TRY_CAST($column AS DECIMAL(38, 9))"
            val scaled = "($numeric * ${timestampColumn.unit.microsPerUnit.toPlainString()})"
            return """
            CASE
                WHEN $numeric IS NOT NULL
                    AND $scaled BETWEEN 0 AND $MAX_SUPPORTED_TIMESTAMP_US
                    AND $scaled = FLOOR($scaled)
                    THEN CAST($scaled AS BIGINT)
                ELSE error('CSV timestamp is outside the supported domain')
            END
            """.trimIndent()
        }
        val rawTimestampUs = checkedTimestampUs("\"$escapedTimeCol\"")
        val rawTimestampMs = "CAST(FLOOR(($rawTimestampUs) / 1000) AS BIGINT)"
        val qualifiedTimestampUs = checkedTimestampUs("raw.\"$escapedTimeCol\"")
        val qualifiedTimestampMs = "CAST(FLOOR(($qualifiedTimestampUs) / 1000) AS BIGINT)"
        val sourceCtes = if (hasExtraFields) {
            """
                raw AS (
                    SELECT row_number() OVER () AS source_row, *
                    FROM read_csv_auto('$absolutePath', header=true, ignore_errors=false, all_varchar=true)
                ), regular_fields AS (
                    SELECT source_row, $rawTimestampMs AS timestamp_ms,
                        $rawTimestampUs AS timestamp_us, key AS source_key, value
                    FROM raw
                    UNPIVOT (
                        value FOR key IN (* EXCLUDE (source_row, "$escapedTimeCol", "$EXTRA_FIELDS_COLUMN"))
                    )
                ), extra_fields AS (
                    SELECT
                        raw.source_row,
                        $qualifiedTimestampMs AS timestamp_ms,
                        $qualifiedTimestampUs AS timestamp_us,
                        extra.key AS source_key,
                        json_extract_string(
                            TRY_CAST(NULLIF(raw."$EXTRA_FIELDS_COLUMN", '') AS JSON),
                            '/' || replace(replace(extra.key, '~', '~0'), '/', '~1')
                        ) AS value
                    FROM raw
                    CROSS JOIN UNNEST(
                        json_keys(TRY_CAST(NULLIF(raw."$EXTRA_FIELDS_COLUMN", '') AS JSON))
                    ) AS extra(key)
                ), all_fields AS (
                    SELECT * FROM regular_fields
                    UNION ALL
                    SELECT * FROM extra_fields
                )
            """.trimIndent()
        } else {
            """
                raw AS (
                    SELECT row_number() OVER () AS source_row, *
                    FROM read_csv_auto('$absolutePath', header=true, ignore_errors=false, all_varchar=true)
                ), all_fields AS (
                    SELECT source_row, $rawTimestampMs AS timestamp_ms,
                        $rawTimestampUs AS timestamp_us, key AS source_key, value
                    FROM raw
                    UNPIVOT (
                        value FOR key IN (* EXCLUDE (source_row, "$escapedTimeCol"))
                    )
                )
            """.trimIndent()
        }
        val importSql = """
            INSERT INTO telemetry_frames
                (timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order)
            WITH $sourceCtes,
            normalized AS (
                SELECT
                    source_row,
                    timestamp_ms,
                    timestamp_us,
                    source_key,
                    REGEXP_REPLACE(TRIM(source_key), '^/+', '') AS normalized_key,
                    value
                FROM all_fields
                WHERE value IS NOT NULL AND CAST(value AS VARCHAR) != ''
            ), existing_order AS (
                SELECT COALESCE(MAX(sample_order), -1) + 1 AS first_sample_order
                FROM telemetry_frames
                WHERE session_id = '$escapedSessionId'
            )
            SELECT
                timestamp_ms,
                '$escapedSessionId' AS session_id,
                normalized_key AS key,
                COALESCE(
                    CASE
                        WHEN LOWER(CAST(value AS VARCHAR)) = 'true' THEN 1.0
                        WHEN LOWER(CAST(value AS VARCHAR)) = 'false' THEN 0.0
                        ELSE TRY_CAST(value AS DOUBLE)
                    END,
                    0.0
                ) AS value,
                CASE
                    WHEN LOWER(CAST(value AS VARCHAR)) IN ('true', 'false') THEN NULL
                    WHEN TRY_CAST(value AS DOUBLE) IS NULL THEN CAST(value AS VARCHAR)
                END AS string_value,
                timestamp_us,
                existing_order.first_sample_order + ROW_NUMBER() OVER (
                    PARTITION BY timestamp_ms, normalized_key
                    ORDER BY source_row, source_key
                ) - 1 AS sample_order
            FROM normalized
            CROSS JOIN existing_order
        """.trimIndent()

        databaseService.executeNativeCsvImport(importSql)
        val importedFrames = databaseService.countTelemetryFrames(sessionId) - frameCountBefore
        require(importedFrames > 0L) { "CSV log ${file.name} contained no usable telemetry frames" }
    }

    /**
     * Streaming CSV parser used as a fallback for multi-file imports where
     * DuckDB native import can't apply per-file key prefixes. Uses a
     * [FrameBatcher] to maintain bounded memory usage.
     */
    suspend fun parseCsvLogStreaming(file: File, sessionId: String, batcher: FrameBatcher) {
        require(file.isFile && file.length() in 1L..MAX_CSV_BYTES) {
            "CSV log size is outside the supported range"
        }
        boundedCsvReader(file).use { reader ->
            val headers = reader.readRecord()?.map { it.trim() } ?: return
            require(detectSchema(headers, file.name) == CsvSchema.WIDE) {
                "Canonical lossless CSV must be imported through parseCsvLogNative"
            }
            val timestampColumn = resolveTimestampColumn(headers, file.name)
            val timeIndex = timestampColumn.index
            val extraFieldsIndex = headers.indexOfFirst { it.trim().trim('"') == EXTRA_FIELDS_COLUMN }
            var tokens = reader.readRecord()
            while (tokens != null) {
                require(tokens.size == headers.size) { "CSV row has ${tokens.size} fields, expected ${headers.size}" }
                val (timestampMs, timestampUs) = parseTimestamp(tokens[timeIndex], timestampColumn.unit)

                suspend fun addValue(key: String, rawValue: String) {
                    val normalizedKey = key.trim().trimStart('/')
                    if (normalizedKey.isEmpty() || rawValue.isEmpty()) return
                    val doubleVal = rawValue.toDoubleOrNull()
                    when {
                        doubleVal != null -> batcher.add(
                            TelemetryFrame(timestampMs, sessionId, normalizedKey, doubleVal, timestampUs = timestampUs)
                        )
                        rawValue.equals("true", ignoreCase = true) -> batcher.add(
                            TelemetryFrame(timestampMs, sessionId, normalizedKey, 1.0, timestampUs = timestampUs)
                        )
                        rawValue.equals("false", ignoreCase = true) -> batcher.add(
                            TelemetryFrame(timestampMs, sessionId, normalizedKey, 0.0, timestampUs = timestampUs)
                        )
                        else -> batcher.add(
                            TelemetryFrame(timestampMs, sessionId, normalizedKey, 0.0, rawValue, timestampUs = timestampUs)
                        )
                    }
                }

                for (j in tokens.indices) {
                    if (j == timeIndex) continue
                    val strValue = tokens[j]
                    if (j == extraFieldsIndex) {
                        if (strValue.isNotBlank()) {
                            val extras = Json.parseToJsonElement(strValue) as? JsonObject
                                ?: throw IllegalArgumentException("CSV extra fields must be a JSON object")
                            for ((key, value) in extras) {
                                val primitive = value as? JsonPrimitive ?: continue
                                addValue(key, primitive.content)
                            }
                        }
                    } else {
                        addValue(headers[j], strValue)
                    }
                }
                tokens = reader.readRecord()
            }
        }
    }

    /**
     * Imports the lossless long-form schema emitted by [com.ares.analytics.service.ExportService].
     * The validation predicate is evaluated by the same DuckDB INSERT statement, so one malformed
     * row aborts the complete import instead of leaving a valid prefix behind.
     */
    private suspend fun parseCanonicalLongFormNative(
        file: File,
        sessionId: String,
        absolutePath: String,
    ) {
        val escapedSessionId = sessionId.replace("'", "''")
        val frameCountBefore = databaseService.countTelemetryFrames(sessionId)
        val validRow = """
            "key" IS NOT NULL
            AND "key" != ''
            AND "key" = REGEXP_REPLACE(TRIM("key"), '^/+', '')
            AND REGEXP_MATCHES(COALESCE(timestamp_ms, ''), '^(0|[1-9][0-9]*)$')
            AND TRY_CAST(timestamp_ms AS HUGEINT) BETWEEN 0 AND $MAX_SUPPORTED_TIMESTAMP_MS
            AND REGEXP_MATCHES(COALESCE(timestamp_us, ''), '^(0|[1-9][0-9]*)$')
            AND TRY_CAST(timestamp_us AS HUGEINT) BETWEEN 0 AND $MAX_SUPPORTED_TIMESTAMP_US
            AND TRY_CAST(timestamp_us AS HUGEINT) // 1000 = TRY_CAST(timestamp_ms AS HUGEINT)
            AND REGEXP_MATCHES(COALESCE(sample_order, ''), '^(0|[1-9][0-9]*)$')
            AND TRY_CAST(sample_order AS BIGINT) IS NOT NULL
            AND TRY_CAST(numeric_value AS DOUBLE) IS NOT NULL
            AND isfinite(TRY_CAST(numeric_value AS DOUBLE))
            AND (
                value_type = 'string'
                OR (value_type = 'double' AND COALESCE(string_value, '') = '')
            )
        """.trimIndent()
        val importSql = """
            INSERT INTO telemetry_frames
                (timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order)
            WITH existing_order AS (
                SELECT COALESCE(MAX(sample_order), -1) + 1 AS first_sample_order
                FROM telemetry_frames
                WHERE session_id = '$escapedSessionId'
            ), raw AS (
                SELECT *
                FROM read_csv_auto(
                    '$absolutePath',
                    header=true,
                    ignore_errors=false,
                    all_varchar=true
                )
            )
            SELECT
                CASE
                    WHEN $validRow THEN CAST(timestamp_ms AS BIGINT)
                    ELSE error('Malformed canonical lossless CSV row')
                END,
                '$escapedSessionId',
                "key",
                CAST(numeric_value AS DOUBLE),
                CASE WHEN value_type = 'string' THEN COALESCE(string_value, '') ELSE NULL END,
                CAST(timestamp_us AS BIGINT),
                existing_order.first_sample_order + CAST(sample_order AS BIGINT)
            FROM raw
            CROSS JOIN existing_order
        """.trimIndent()

        databaseService.executeNativeCsvImport(importSql)
        val importedFrames = databaseService.countTelemetryFrames(sessionId) - frameCountBefore
        require(importedFrames > 0L) {
            "CSV log ${file.name} contained no usable telemetry frames"
        }
    }

    private fun detectSchema(headers: List<String>, fileName: String): CsvSchema {
        val exactHeaders = headers.mapIndexed { index, header ->
            if (index == 0) header.trimStart('\uFEFF') else header
        }
        if (exactHeaders.size == CANONICAL_LONG_HEADERS.size &&
            exactHeaders.toSet() == CANONICAL_LONG_HEADERS
        ) {
            return CsvSchema.CANONICAL_LONG
        }

        val canonicalized = exactHeaders.map(::canonicalHeader)
        val hasLongFormMarker = canonicalized.any { it in CANONICAL_LONG_MARKERS }
        val hasBothCanonicalTimestamps =
            "timestampms" in canonicalized && "timestampus" in canonicalized
        require(!hasLongFormMarker && !hasBothCanonicalTimestamps) {
            "CSV log $fileName mixes canonical long-form metadata with a wide telemetry schema"
        }
        return CsvSchema.WIDE
    }

    private fun resolveTimestampColumn(headers: List<String>, fileName: String): TimestampColumn {
        val matches = headers.mapIndexedNotNull { index, header ->
            TIMESTAMP_HEADERS[canonicalHeader(header)]?.let { unit ->
                TimestampColumn(index, header, unit)
            }
        }
        require(matches.isNotEmpty()) {
            "CSV log $fileName has no supported timestamp column with an explicit unit"
        }
        require(matches.size == 1) {
            "CSV log $fileName has ambiguous timestamp columns: ${matches.joinToString { it.name }}"
        }
        return matches.single()
    }

    private fun canonicalHeader(header: String): String = buildString(header.length) {
        for (character in header.trim().trimStart('\uFEFF').lowercase()) {
            when (character) {
                'µ', 'μ' -> append('u')
                in 'a'..'z', in '0'..'9' -> append(character)
            }
        }
    }

    private fun parseTimestamp(raw: String, unit: TimestampUnit): Pair<Long, Long> {
        val timestampUs = try {
            val source = raw.trim().toBigDecimalOrNull()
                ?: throw IllegalArgumentException("CSV row contains an invalid timestamp")
            source.multiply(unit.microsPerUnit)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("CSV timestamp is outside the supported domain", error)
        }
        require(timestampUs in 0L..MAX_SUPPORTED_TIMESTAMP_US) {
            "CSV timestamp is outside the supported domain"
        }
        return timestampUs / 1_000L to timestampUs
    }

    /** Opens plain or gzip CSV through the same decompressed-byte and record-size bounds. */
    private fun boundedCsvReader(file: File): BoundedCsvReader {
        require(file.isFile) { "CSV log does not exist: ${file.absolutePath}" }
        require(file.length() in 1L..MAX_CSV_BYTES) { "CSV log size is outside the supported range" }
        val raw = FileInputStream(file)
        val decoded: InputStream = try {
            if (file.name.endsWith(".csv.gz", ignoreCase = true)) GZIPInputStream(raw, 64 * 1024) else raw
        } catch (failure: Throwable) {
            raw.close()
            throw failure
        }
        val bounded = ExpandedSizeInputStream(decoded, MAX_CSV_BYTES)
        return BoundedCsvReader(InputStreamReader(bounded, Charsets.UTF_8))
    }

    private class ExpandedSizeInputStream(
        input: InputStream,
        private val limit: Long
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) record(1L)
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(bytes, offset, length)
            if (read > 0) record(read.toLong())
            return read
        }

        private fun record(bytes: Long) {
            count = Math.addExact(count, bytes)
            require(count <= limit) { "Expanded CSV exceeds the ${limit / (1024L * 1024L)} MiB safety limit" }
        }
    }

    private class BoundedCsvReader(reader: Reader) : Closeable {
        private val reader = if (reader is BufferedReader) reader else reader.buffered()
        private var pushedBack = NO_PUSHBACK

        fun readRecord(): List<String>? {
            val fields = ArrayList<String>()
            val field = StringBuilder()
            var inQuotes = false
            var sawInput = false
            var recordChars = 0

            while (true) {
                val codePoint = readChar()
                if (codePoint == -1) {
                    require(!inQuotes) { "CSV ends inside a quoted field" }
                    if (!sawInput && fields.isEmpty() && field.isEmpty()) return null
                    addField(fields, field)
                    return fields
                }
                sawInput = true
                recordChars++
                require(recordChars <= MAX_CSV_RECORD_CHARS) { "CSV record exceeds the size limit" }
                val char = codePoint.toChar()

                if (inQuotes) {
                    if (char == '"') {
                        val next = readChar()
                        if (next == '"'.code) {
                            appendBounded(field, '"')
                            recordChars++
                        } else {
                            inQuotes = false
                            pushedBack = next
                        }
                    } else {
                        appendBounded(field, char)
                    }
                    continue
                }

                when (char) {
                    '"' -> require(field.isEmpty()) { "Quote must begin a CSV field" }.also { inQuotes = true }
                    ',' -> addField(fields, field)
                    '\n' -> {
                        addField(fields, field)
                        return fields
                    }
                    '\r' -> {
                        val next = readChar()
                        if (next != '\n'.code) pushedBack = next
                        addField(fields, field)
                        return fields
                    }
                    else -> appendBounded(field, char)
                }
            }
        }

        private fun addField(fields: MutableList<String>, field: StringBuilder) {
            require(fields.size < MAX_CSV_COLUMNS) { "CSV contains too many columns" }
            fields += field.toString()
            field.setLength(0)
        }

        private fun appendBounded(field: StringBuilder, value: Char) {
            require(field.length < MAX_CSV_FIELD_CHARS) { "CSV field exceeds the size limit" }
            field.append(value)
        }

        private fun readChar(): Int {
            if (pushedBack != NO_PUSHBACK) {
                val value = pushedBack
                pushedBack = NO_PUSHBACK
                return value
            }
            return reader.read()
        }

        override fun close() = reader.close()

        private companion object {
            const val NO_PUSHBACK = -2
        }
    }
}
