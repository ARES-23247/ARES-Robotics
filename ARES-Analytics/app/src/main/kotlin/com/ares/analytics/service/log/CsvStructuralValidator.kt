package com.ares.analytics.service.log

import java.io.File
import java.io.FileInputStream

/**
 * Allocation-free structural validation for CSV files delegated to a native database importer.
 *
 * DuckDB owns type inference and conversion. This validator owns ARES's stricter atomic-import
 * boundary: malformed quoting, oversized records, and field-count drift are rejected before the
 * database transaction starts.
 */
internal object CsvStructuralValidator {
    private const val BUFFER_BYTES = 64 * 1024

    fun validate(
        file: File,
        expectedColumns: Int,
        maxColumns: Int,
        maxFieldBytes: Int,
        maxRecordBytes: Int,
    ) {
        require(expectedColumns in 1..maxColumns) { "CSV contains an invalid header" }
        FileInputStream(file).buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            var inQuotes = false
            var pendingQuote = false
            var atFieldStart = true
            var fields = 1
            var recordBytes = 0
            var fieldBytes = 0
            var recordHasInput = false
            var previousWasCr = false

            fun finishRecord() {
                require(fields == expectedColumns) {
                    "CSV row has $fields fields, expected $expectedColumns"
                }
                fields = 1
                recordBytes = 0
                fieldBytes = 0
                atFieldStart = true
                recordHasInput = false
            }

            fun consumeOutside(value: Int) {
                when (value) {
                    '"'.code -> {
                        require(atFieldStart) { "Quote must begin a CSV field" }
                        inQuotes = true
                        pendingQuote = false
                        atFieldStart = false
                        recordHasInput = true
                    }
                    ','.code -> {
                        require(fields < maxColumns) { "CSV contains too many columns" }
                        fields++
                        fieldBytes = 0
                        atFieldStart = true
                        recordHasInput = true
                    }
                    '\n'.code -> if (!previousWasCr) finishRecord()
                    '\r'.code -> finishRecord()
                    else -> {
                        atFieldStart = false
                        recordHasInput = true
                        fieldBytes++
                        require(fieldBytes <= maxFieldBytes) { "CSV field exceeds the size limit" }
                    }
                }
                previousWasCr = value == '\r'.code
            }

            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (index in 0 until read) {
                    val value = buffer[index].toInt() and 0xff
                    recordBytes++
                    require(recordBytes <= maxRecordBytes) { "CSV record exceeds the size limit" }
                    if (inQuotes) {
                        when {
                            value == '"'.code && pendingQuote -> {
                                pendingQuote = false // Escaped quote: ""
                                fieldBytes++
                            }
                            value == '"'.code -> pendingQuote = true
                            pendingQuote -> {
                                inQuotes = false
                                pendingQuote = false
                                consumeOutside(value)
                            }
                            else -> {
                                fieldBytes++
                                require(fieldBytes <= maxFieldBytes) { "CSV field exceeds the size limit" }
                            }
                        }
                    } else {
                        consumeOutside(value)
                    }
                }
            }

            require(!inQuotes || pendingQuote) { "CSV ends inside a quoted field" }
            if (recordHasInput || fields > 1) finishRecord()
        }
    }
}
