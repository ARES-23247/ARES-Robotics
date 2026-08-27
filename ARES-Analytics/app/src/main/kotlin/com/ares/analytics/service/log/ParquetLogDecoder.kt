package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.db.DatabaseBackupExporter
import java.io.File

/**
 * DuckDB-backed adapter for direct Parquet telemetry import.
 *
 * Rows are ingested natively without materializing the full trace on the JVM heap. The source
 * session identity is ignored and every frame is assigned to the session created by
 * [com.ares.analytics.service.LogParserService].
 *
 * @param databaseService Primary DuckDB persistence service.
 *
 * @see CsvLogDecoder
 * @see WpiLogDecoder
 */
class ParquetLogDecoder(private val databaseService: DatabaseService) {

    /**
     * Parses an Apache Parquet binary log file into the telemetry pipeline.
     *
     * @param file Target `.parquet` file.
     * @param sessionId Session identifier string.
     * @return Imported row count and timestamp range.
     */
    suspend fun parseParquetLog(
        file: File,
        sessionId: String
    ): DatabaseBackupExporter.ParquetImportResult = databaseService.importParquetAsSession(file, sessionId)
}
