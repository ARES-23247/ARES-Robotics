package com.ares.analytics.service.db

import com.ares.analytics.service.BeforeAtomicReplace
import com.ares.analytics.service.ImportReport
import com.ares.analytics.service.NO_OP_BEFORE_ATOMIC_REPLACE
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.sql.Connection

internal enum class CloudImportStage { TELEMETRY, SUMMARY, SESSION, ANCILLARY }

internal data class CloudSessionAncillaryData(
    val actions: List<RobotActionRecord>,
    val annotations: List<SessionAnnotation>,
    val alerts: List<AlertRecord>,
    val consoleMessages: List<ConsoleMessage>,
    val analysisDiagnostics: List<AnalysisDiagnostic>,
    val importReports: List<ImportReport>,
)

/**
 * Service managing database import and export operations for historical telemetry log persistence.
 *
 * Utilizes DuckDB's native vectorized Parquet integration (`read_parquet`) to bulk load match log frames
 * into the main relational database without row-by-row JDBC overhead.
 *
 * ### Database Schema Targets:
 * - Table: `telemetry_frames`
 * - Columns: `timestamp_ms` (BIGINT, ms), `session_id` (VARCHAR), `key` (VARCHAR), `value` (DOUBLE), `string_value` (VARCHAR)
 *
 * ### Thread Safety & Performance Guarantees:
 * Thread-safe. Synchronizes database transactions using an asynchronous [dbMutex] and executes IO operations
 * on [Dispatchers.IO]. Vectorized Parquet queries run in native C++ DuckDB code, maintaining minimal heap allocation in JVM.
 *
 * @param conn Active JDBC connection to the DuckDB instance.
 * @param dbMutex Asynchronous mutual exclusion lock preventing concurrent write transactions on the database connection.
 *
 * @see DatabaseSchemaInitializer
 * @see TelemetryRepository
 * @see RunEvidenceRepository
 */
class DatabaseBackupExporter(
    private val conn: Connection,
    private val dbMutex: Mutex
) {
    internal var cloudImportFailureInjector: ((CloudImportStage) -> Unit)? = null
    internal var exportReplaceFailureInjector: BeforeAtomicReplace? = null
    /** Result of importing a Parquet trace under a caller-owned session identity. */
    data class ParquetImportResult(
        val frameCount: Long,
        val minTimestampMs: Long?,
        val maxTimestampMs: Long?
    )

    /**
     * Helper suspend function executing a database operation under mutual exclusion lock on the IO thread context.
     *
     * @param T Return type of the database operation block.
     * @param block Lambda containing thread-unsafe JDBC database calls.
     * @return Result of [block] execution.
     */
    private suspend fun <T> withDbLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        dbMutex.withLock { block() }
    }

    /**
     * Bulk imports telemetry frames from an Apache Parquet binary file directly into DuckDB's `telemetry_frames` table.
     *
     * Replaces matching imported records explicitly before appending. The telemetry fact table is
     * intentionally index-free, so idempotence is enforced at this cold import boundary rather
     * than by maintaining a global primary-key ART index on every live sample.
     *
     * @param file Target `.parquet` log file containing serialized telemetry records.
     * @throws java.sql.SQLException If DuckDB encounters a file read or table insertion error.
     */
    suspend fun importParquet(file: File) = withDbLock {
        val absolutePath = sqlLiteral(file.canonicalPath.replace("\\", "/"))
        val columns = parquetColumns(absolutePath)
        val required = setOf("timestamp_ms", "session_id", "key", "value")
        require((required - columns).isEmpty()) {
            "Parquet telemetry log is missing required columns: ${(required - columns).sorted().joinToString()}"
        }
        val stringExpression = if ("string_value" in columns) "CAST(string_value AS VARCHAR)" else "NULL"
        val timestampMsExpression = "TRY_CAST(timestamp_ms AS BIGINT)"
        val timestampUsExpression = timestampUsExpression(columns)
        val sampleOrderExpression = sampleOrderExpression(columns)
        val normalizedKeyExpression = normalizedKeyExpression()
        val numericValueExpression = "TRY_CAST(value AS DOUBLE)"
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            validateParquetFrames(absolutePath, columns, requireSourceSession = true)
            conn.createStatement().use { st ->
                st.execute(
                    """
                    DELETE FROM telemetry_frames AS target
                    USING (
                        SELECT CAST(session_id AS VARCHAR) AS session_id,
                            $normalizedKeyExpression AS key,
                            $timestampUsExpression AS timestamp_us
                        FROM read_parquet('$absolutePath')
                    ) AS source
                    WHERE target.session_id = source.session_id
                        AND target.key = source.key
                        AND target.timestamp_us = source.timestamp_us
                    """.trimIndent()
                )
                st.execute("""
                    INSERT INTO telemetry_frames
                        (timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order)
                    SELECT $timestampMsExpression, CAST(session_id AS VARCHAR),
                        $normalizedKeyExpression,
                        $numericValueExpression, $stringExpression,
                        $timestampUsExpression, $sampleOrderExpression
                    FROM read_parquet('$absolutePath')
                """.trimIndent())
            }
            conn.commit()
        } catch (error: Exception) {
            conn.rollback()
            throw error
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    /**
     * Imports a user-selected Parquet telemetry trace and remaps every row to [sessionId].
     *
     * Required source columns are `timestamp_ms`, `key`, and `value`; `string_value` is optional.
     * A source `session_id` is deliberately ignored so imported backups cannot overwrite or merge
     * with an unrelated local session. Schema validation occurs before the transaction writes rows.
     */
    suspend fun importParquetAsSession(file: File, sessionId: String): ParquetImportResult =
        importParquetBundle(file, sessionId, null, null)

    suspend fun importCloudSessionAtomically(
        file: File,
        summary: SessionSummary,
        session: Session
    ): ParquetImportResult {
        require(summary.sessionId == session.sessionId) { "Cloud session and summary identities differ" }
        return importParquetBundle(file, session.sessionId, summary, session, null)
    }

    internal suspend fun importCloudSessionBundleAtomically(
        file: File,
        summary: SessionSummary,
        session: Session,
        ancillaryData: CloudSessionAncillaryData,
    ): ParquetImportResult {
        require(summary.sessionId == session.sessionId) { "Cloud session and summary identities differ" }
        require(ancillaryData.actions.all { it.sessionId == session.sessionId }) {
            "Cloud bundle contains actions from another session"
        }
        require(ancillaryData.annotations.all { it.sessionId == session.sessionId }) {
            "Cloud bundle contains annotations from another session"
        }
        require(ancillaryData.alerts.all { it.sessionId == session.sessionId }) {
            "Cloud bundle contains alerts from another session"
        }
        require(ancillaryData.analysisDiagnostics.all { it.sessionId == session.sessionId }) {
            "Cloud bundle contains diagnostics from another session"
        }
        require(ancillaryData.importReports.all { it.sessionId == session.sessionId }) {
            "Cloud bundle contains import reports from another session"
        }
        return importParquetBundle(file, session.sessionId, summary, session, ancillaryData)
    }

    private suspend fun importParquetBundle(
        file: File,
        sessionId: String,
        summary: SessionSummary?,
        session: Session?,
        ancillaryData: CloudSessionAncillaryData? = null,
    ): ParquetImportResult = withDbLock {
        require(file.isFile) { "Parquet log does not exist: ${file.absolutePath}" }
        require(file.extension.equals("parquet", ignoreCase = true)) {
            "Expected a .parquet telemetry log: ${file.name}"
        }

        val safePath = sqlLiteral(file.canonicalPath.replace("\\", "/"))
        val columns = parquetColumns(safePath)
        val required = setOf("timestamp_ms", "key", "value")
        val missing = required - columns
        require(missing.isEmpty()) {
            "Parquet telemetry log is missing required columns: ${missing.sorted().joinToString()}"
        }

        val safeSessionId = sqlLiteral(sessionId)
        val stringExpression = if ("string_value" in columns) {
            "CAST(string_value AS VARCHAR)"
        } else {
            "NULL"
        }
        val timestampMsExpression = "TRY_CAST(timestamp_ms AS BIGINT)"
        val timestampUsExpression = timestampUsExpression(columns)
        val sampleOrderExpression = sampleOrderExpression(columns)
        val normalizedKeyExpression = normalizedKeyExpression()
        val numericValueExpression = "TRY_CAST(value AS DOUBLE)"
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            validateParquetFrames(safePath, columns, requireSourceSession = false)
            if (ancillaryData != null) {
                val tables = arrayOf(
                    "telemetry_frames",
                    "analysis_diagnostics",
                    "session_annotations",
                    "alerts",
                    "console_messages",
                    "robot_actions",
                    "session_import_reports",
                    "session_summaries",
                    "sessions",
                )
                tables.forEach { table ->
                    conn.prepareStatement("DELETE FROM $table WHERE session_id = ?").use { statement ->
                        statement.setString(1, sessionId)
                        statement.executeUpdate()
                    }
                }
            }
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    DELETE FROM telemetry_frames AS target
                    USING (
                        SELECT
                            '$safeSessionId' AS session_id,
                            $normalizedKeyExpression AS key,
                            $timestampUsExpression AS timestamp_us,
                            $sampleOrderExpression AS sample_order
                        FROM read_parquet('$safePath')
                    ) AS source
                    WHERE target.session_id = source.session_id
                        AND target.key = source.key
                        AND target.timestamp_us = source.timestamp_us
                        AND target.sample_order = source.sample_order
                    """.trimIndent()
                )
            }
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO telemetry_frames
                        (timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order)
                    SELECT
                        $timestampMsExpression,
                        '$safeSessionId',
                        $normalizedKeyExpression,
                        $numericValueExpression,
                        $stringExpression,
                        $timestampUsExpression,
                        $sampleOrderExpression
                    FROM read_parquet('$safePath')
                    """.trimIndent()
                )
            }
            val result = conn.prepareStatement(
                "SELECT COUNT(*), MIN(timestamp_ms), MAX(timestamp_ms) FROM telemetry_frames WHERE session_id = ?"
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    val count = rows.getLong(1)
                    val min = rows.getLong(2).takeUnless { rows.wasNull() }
                    val max = rows.getLong(3).takeUnless { rows.wasNull() }
                    ParquetImportResult(count, min, max)
                }
            }
            if (summary != null && session != null) {
                cloudImportFailureInjector?.invoke(CloudImportStage.TELEMETRY)
                insertSessionSummaryLocked(summary)
                cloudImportFailureInjector?.invoke(CloudImportStage.SUMMARY)
                insertSessionLocked(session)
                cloudImportFailureInjector?.invoke(CloudImportStage.SESSION)
                if (ancillaryData != null) {
                    insertAncillaryLocked(sessionId, ancillaryData)
                }
                cloudImportFailureInjector?.invoke(CloudImportStage.ANCILLARY)
            }
            conn.commit()
            result
        } catch (error: Exception) {
            conn.rollback()
            throw error
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    private fun insertSessionLocked(session: Session) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sessions (session_id, team_id, season_id, robot_id, created_at, duration_ms, tags, match_number, alliance_color) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, session.sessionId)
            statement.setString(2, session.teamId)
            statement.setString(3, session.seasonId)
            statement.setString(4, session.robotId)
            statement.setLong(5, session.createdAt)
            statement.setLong(6, session.durationMs)
            statement.setString(7, Json.encodeToString(session.tags))
            session.matchNumber?.let { statement.setLong(8, it.toLong()) }
                ?: statement.setNull(8, java.sql.Types.BIGINT)
            statement.setString(9, session.allianceColor)
            statement.executeUpdate()
        }
    }

    private fun insertSessionSummaryLocked(summary: SessionSummary) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO session_summaries (session_id, team_id, season_id, robot_id, created_at, duration_ms, min_battery_voltage, max_ekf_drift, avg_loop_time_ms, p95_loop_time_ms, motor_current_averages, vision_acceptance_rate, avg_cross_track_error, avg_battery_resistance, max_motor_temps, avg_vision_latency_ms, tags, match_number, alliance_color) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, summary.sessionId)
            statement.setString(2, summary.teamId)
            statement.setString(3, summary.seasonId)
            statement.setString(4, summary.robotId)
            statement.setLong(5, summary.createdAt)
            statement.setLong(6, summary.durationMs)
            statement.setDouble(7, summary.minBatteryVoltage)
            statement.setDouble(8, summary.maxEkfDrift)
            statement.setDouble(9, summary.avgLoopTimeMs)
            statement.setDouble(10, summary.p95LoopTimeMs)
            statement.setString(11, Json.encodeToString(summary.motorCurrentAverages))
            statement.setDouble(12, summary.visionAcceptanceRate)
            statement.setDouble(13, summary.avgCrossTrackError)
            statement.setDouble(14, summary.avgBatteryResistance)
            statement.setString(15, Json.encodeToString(summary.maxMotorTemps))
            statement.setDouble(16, summary.avgVisionLatencyMs)
            statement.setString(17, Json.encodeToString(summary.tags))
            summary.matchNumber?.let { statement.setLong(18, it.toLong()) }
                ?: statement.setNull(18, java.sql.Types.BIGINT)
            statement.setString(19, summary.allianceColor)
            statement.executeUpdate()
        }
    }

    private fun insertAncillaryLocked(sessionId: String, data: CloudSessionAncillaryData) {
        conn.prepareStatement(
            "INSERT INTO robot_actions (timestamp_ms, session_id, run_id, robot_id, match_number, alliance, action_type, payload_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            data.actions.forEach { action ->
                statement.setLong(1, action.timestampMs)
                statement.setString(2, sessionId)
                statement.setString(3, action.runId)
                statement.setString(4, action.robotId)
                statement.setInt(5, action.matchNumber)
                statement.setString(6, action.alliance)
                statement.setString(7, action.actionType)
                statement.setString(8, action.payloadJson)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO session_annotations (annotation_id, session_id, text, created_at, author_id) VALUES (?, ?, ?, ?, ?)"
        ).use { statement ->
            data.annotations.forEach { annotation ->
                statement.setString(1, annotation.annotationId)
                statement.setString(2, sessionId)
                statement.setString(3, annotation.text)
                statement.setLong(4, annotation.createdAt)
                statement.setString(5, annotation.authorId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO alerts (alert_id, session_id, rule_key, trigger_timestamp_ms, resolve_timestamp_ms, duration_ms, peak_value, triaged) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            data.alerts.forEach { alert ->
                statement.setString(1, alert.alertId)
                statement.setString(2, sessionId)
                statement.setString(3, alert.ruleKey)
                statement.setLong(4, alert.triggerTimestampMs)
                alert.resolveTimestampMs?.let { statement.setLong(5, it) }
                    ?: statement.setNull(5, java.sql.Types.BIGINT)
                statement.setLong(6, alert.durationMs)
                statement.setDouble(7, alert.peakValue)
                statement.setLong(8, if (alert.triaged) 1L else 0L)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO console_messages (timestamp_ms, session_id, text, severity) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            data.consoleMessages.forEach { message ->
                statement.setLong(1, message.timestampMs)
                statement.setString(2, sessionId)
                statement.setString(3, message.text)
                statement.setString(4, message.severity)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO analysis_diagnostics (session_id, key, value, string_value) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            data.analysisDiagnostics.distinctBy { it.key.removePrefix("/") }.forEach { diagnostic ->
                statement.setString(1, sessionId)
                statement.setString(2, diagnostic.key.removePrefix("/"))
                statement.setDouble(3, diagnostic.value)
                statement.setString(4, diagnostic.stringValue)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO session_import_reports (session_id, source_sha256, source_name, report_json) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            data.importReports.distinctBy { it.sourceSha256 to it.sourceName }.forEach { report ->
                statement.setString(1, sessionId)
                statement.setString(2, report.sourceSha256)
                statement.setString(3, report.sourceName)
                statement.setString(4, AppJson.encodeToString(report))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun parquetColumns(safePath: String): Set<String> =
        conn.createStatement().use { statement ->
            statement.executeQuery("DESCRIBE SELECT * FROM read_parquet('$safePath')").use { result ->
                buildSet {
                    while (result.next()) add(result.getString(1).lowercase())
                }
            }
        }

    /**
     * Validates the complete native Parquet projection while returning at most one row to the JVM.
     * This runs inside the same transaction as the subsequent delete/upsert so a rejected file can
     * never leave a partial replacement behind.
     */
    private fun validateParquetFrames(
        safePath: String,
        columns: Set<String>,
        requireSourceSession: Boolean
    ) {
        val sourceSessionExpression = if (requireSourceSession) {
            "TRY_CAST(session_id AS VARCHAR)"
        } else {
            "'remapped-session'"
        }
        val sourceSessionInvalid = if (requireSourceSession) {
            "source_session_id IS NULL OR"
        } else {
            ""
        }
        conn.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT 1
                FROM (
                    SELECT
                        TRY_CAST(timestamp_ms AS BIGINT) AS source_timestamp_ms,
                        ${timestampUsExpression(columns)} AS source_timestamp_us,
                        ${sampleOrderExpression(columns)} AS source_sample_order,
                        TRY_CAST(value AS DOUBLE) AS numeric_value,
                        ${normalizedKeyExpression()} AS normalized_key,
                        $sourceSessionExpression AS source_session_id
                    FROM read_parquet('$safePath')
                ) AS candidate
                WHERE
                    $sourceSessionInvalid
                    source_timestamp_ms IS NULL
                    OR source_timestamp_ms NOT BETWEEN 0 AND $MAX_SUPPORTED_TIMESTAMP_MS
                    OR source_timestamp_us IS NULL
                    OR source_timestamp_us < 0
                    OR source_timestamp_us // 1000 <> source_timestamp_ms
                    OR source_sample_order IS NULL
                    OR source_sample_order < 0
                    OR numeric_value IS NULL
                    OR NOT isfinite(numeric_value)
                    OR normalized_key IS NULL
                    OR NOT REGEXP_MATCHES(normalized_key, '\S')
                LIMIT 1
                """.trimIndent()
            ).use { rows ->
                require(!rows.next()) {
                    "Parquet telemetry log contains values outside the TelemetryFrame invariant domain"
                }
            }
        }
    }

    private fun timestampUsExpression(columns: Set<String>): String =
        if ("timestamp_us" in columns) {
            "TRY_CAST(timestamp_us AS BIGINT)"
        } else {
            "TRY_CAST(TRY_CAST(timestamp_ms AS HUGEINT) * 1000 AS BIGINT)"
        }

    private fun sampleOrderExpression(columns: Set<String>): String =
        if ("sample_order" in columns) "TRY_CAST(sample_order AS BIGINT)" else "ROW_NUMBER() OVER () - 1"

    private fun normalizedKeyExpression(): String =
        "TRIM(REGEXP_REPLACE(TRIM(TRY_CAST(key AS VARCHAR)), '^/+', ''))"

    /**
     * Exports one session through a narrowly scoped, internally escaped COPY statement.
     * General raw-SQL execution intentionally remains read-only and must not be used for export.
     */
    suspend fun exportSessionToParquet(sessionId: String, destinationFile: File) = withDbLock {
        writeFileAtomically(
            destinationFile,
            exportReplaceFailureInjector ?: NO_OP_BEFORE_ATOMIC_REPLACE,
        ) { temporary ->
            // DuckDB COPY refuses to overwrite. The sibling path was uniquely reserved by the
            // atomic writer and is recreated immediately by COPY, then checked and fsynced.
            Files.delete(temporary.toPath())
            val safePath = sqlLiteral(temporary.absolutePath.replace("\\", "/"))
            val safeSessionId = sqlLiteral(sessionId)
            conn.createStatement().use { statement ->
                statement.execute(
                    "COPY (SELECT * FROM telemetry_frames WHERE session_id = '$safeSessionId') " +
                        "TO '$safePath' (FORMAT PARQUET, COMPRESSION ZSTD, ROW_GROUP_SIZE 100000)"
                )
            }
        }
    }

    /**
     * Exports multiple historical sessions into a single ZIP archive containing individual Parquet files.
     *
     * @param sessionIds List of session IDs to include in the backup.
     * @param zipFile Target `.zip` file for the exported archive.
     */
    suspend fun exportSessionsToZip(sessionIds: List<String>, zipFile: File) = withDbLock {
        writeFileAtomically(
            zipFile,
            exportReplaceFailureInjector ?: NO_OP_BEFORE_ATOMIC_REPLACE,
        ) { temporaryZip ->
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(temporaryZip).buffered()).use { zos ->
                for (sessionId in sessionIds) {
                    val parquetPath = Files.createTempFile(
                        temporaryZip.toPath().parent,
                        ".ares-export-",
                        ".parquet",
                    )
                    val temporaryParquet = parquetPath.toFile()
                    try {
                        Files.delete(parquetPath)
                        val absolutePath = sqlLiteral(temporaryParquet.absolutePath.replace("\\", "/"))
                        val safeSessionId = sqlLiteral(sessionId)
                        conn.createStatement().use { statement ->
                            statement.execute(
                                "COPY (SELECT * FROM telemetry_frames WHERE session_id = '$safeSessionId') " +
                                    "TO '$absolutePath' (FORMAT PARQUET)"
                            )
                        }

                        val safeEntryName = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        zos.putNextEntry(java.util.zip.ZipEntry("$safeEntryName.parquet"))
                        temporaryParquet.inputStream().use { input ->
                            input.copyTo(zos, bufferSize = 256 * 1024)
                        }
                        zos.closeEntry()
                    } finally {
                        Files.deleteIfExists(parquetPath)
                    }
                }
            }
        }
    }

    private fun sqlLiteral(value: String): String = value.replace("'", "''")
}
