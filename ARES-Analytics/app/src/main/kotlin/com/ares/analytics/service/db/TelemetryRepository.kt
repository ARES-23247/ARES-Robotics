package com.ares.analytics.service.db

import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicLong

data class TelemetryExportPreflight(
    /** Exact count when within the caller's cap, otherwise cap + 1. */
    val boundedFrameCount: Long,
    val minTimestampMs: Long?,
    val maxTimestampMs: Long?,
)

enum class TelemetryExportValueType {
    NUMERIC,
    STRING,
    MIXED,
}

data class TelemetryExportCursor(
    val timestampUs: Long,
    val sampleOrder: Long,
    val key: String,
)

/**
 * Owns telemetry ingestion, time-series queries, filtering, retention, and export pagination.
 *
 * Provides thread-safe transaction execution over DuckDB JDBC connections, utilizing DuckDB's native Appender C++ API
 * (`insertTelemetryFramesAppender`) for bulk frame ingest (~10-100x faster than traditional JDBC SQL batches).
 *
 * ### Physical Units & Storage Targets:
 * - Timestamps: Milliseconds ($ms$)
 * - Telemetry keys: Normalized NT4 paths (`"Drive/Pose_X"`, `"Hardware/Motors/fl/Power"`)
 * - Battery Voltage metrics: Volts ($V$)
 * - Motor Current metrics: Amperes ($A$)
 * - Loop timing: Milliseconds ($ms$)
 * - Vision latency: Milliseconds ($ms$)
 * - EKF position drift / cross-track error: Meters ($m$)
 *
 * ### Thread Safety & Performance Guarantees:
 * Thread-safe suspend functions use the shared [DatabaseTransactionCoordinator].
 * Appender operations stream raw memory arrays to DuckDB C++ native buffers with zero JVM heap fragmentation.
 *
 * @see DatabaseSchemaInitializer
 * @see DatabaseBackupExporter
 */
internal class TelemetryRepository(
    private val transactions: DatabaseTransactionCoordinator,
) {
    private val conn: Connection get() = transactions.writeConnection
    private val readConn: Connection get() = transactions.readConnection
    private val ephemeralConn: Connection get() = transactions.ephemeralWriteConnection
    private val nextSampleOrder = AtomicLong()

    private fun readConnectionFor(sessionId: String): Connection = transactions.readConnectionFor(sessionId)

    private fun storageOrder(frame: TelemetryFrame): Long =
        if (frame.sampleOrder != 0L) frame.sampleOrder else nextSampleOrder.incrementAndGet()
    /**
     * Executes a raw database operation safely under [dbMutex] on [Dispatchers.IO].
     *
     * @param T Result type of the transaction block.
     * @param block Database execution logic.
     * @return Result produced by [block].
     */
    private suspend fun <T> withDbLock(block: suspend () -> T): T = transactions.write(block)

    private suspend fun <T> withReadLock(block: suspend () -> T): T = transactions.read(block)

    suspend fun executeNativeCsvImport(sql: String) = withDbLock {
        if (!sql.trim().uppercase().startsWith("INSERT INTO TELEMETRY_FRAMES")) {
            throw IllegalArgumentException("executeNativeCsvImport only allows INSERT INTO telemetry_frames")
        }
        conn.createStatement().use { it.execute(sql) }
    }

    suspend fun insertTelemetryFrames(frames: List<TelemetryFrame>) = withDbLock {
        if (frames.isEmpty()) return@withDbLock

        // A single channel flush can straddle the moment a recording starts or stops.
        // Route every row by its own session identity instead of trusting frame[0].
        val liveFrames = frames.filter { it.sessionId == "live-telemetry" }
        val persistentFrames = frames.filter { it.sessionId != "live-telemetry" }
        if (persistentFrames.isNotEmpty()) insertTelemetryFrames(conn, persistentFrames)
        if (liveFrames.isNotEmpty()) insertTelemetryFrames(ephemeralConn, liveFrames)
    }

    private fun insertTelemetryFrames(targetConn: Connection, frames: List<TelemetryFrame>) {
        val previousAutoCommit = targetConn.autoCommit
        if (previousAutoCommit) targetConn.autoCommit = false
        try {
            // Both persistent imports and the ephemeral live timeline are append-only. The schema
            // includes sample_order in its identity, so repeated source timestamps remain distinct
            // without INSERT OR REPLACE. Using the native Appender avoids thousands of parsed JDBC
            // transactions during dense simulator sessions.
            insertTelemetryFramesAppender(targetConn, frames)
            if (previousAutoCommit) targetConn.commit()
        } catch (e: Exception) {
            if (previousAutoCommit) runCatching { targetConn.rollback() }
            throw e
        } finally {
            if (previousAutoCommit) targetConn.autoCommit = true
        }
    }

    /**
     * High-performance bulk insert using DuckDB's native Appender API.
     * Bypasses SQL parsing and writes directly to columnar storage.
     * ~10-100x faster than JDBC PreparedStatement batch for bulk imports.
     *
     * IMPORTANT: Must be called under withDbLock or from a single-writer context.
     * Does not support INSERT OR REPLACE — assumes no duplicate keys (safe for imports).
     */
    private fun insertTelemetryFramesAppender(targetConn: Connection, frames: List<TelemetryFrame>) {
        val duckConn = targetConn.unwrap(DuckDBConnection::class.java)
        val appender = duckConn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "telemetry_frames")
        try {
            for (frame in frames) {
                appender.beginRow()
                appender.append(frame.timestampMs)
                appender.append(frame.sessionId)
                appender.append(TelemetryMetricCatalog.normalizeTopic(frame.key))
                appender.append(frame.value)
                // DuckDBAppender maps a nullable String to SQL NULL. Empty string is a
                // legitimate telemetry value and must remain distinguishable from null.
                appender.append(frame.stringValue)
                appender.append(frame.timestampUs)
                appender.append(storageOrder(frame))
                appender.endRow()
            }
            appender.flush()
        } finally {
            appender.close()
            // CHECKPOINT intentionally NOT run per batch — a per-batch WAL fsync dominated
            // import time. Checkpointing is now caller/timer-controlled by
            // [DatabaseTransactionCoordinator.checkpoint]
            // (DatabaseService runs it on a periodic timer; connection close still flushes).
        }
    }

    /**
     * Returns the (min, max) timestamp range for a given session's telemetry frames,
     * or null if no frames exist. Used after DuckDB native CSV import to compute
     * session duration without holding frames in application memory.
     */
    suspend fun getSessionTimestampRange(sessionId: String): Pair<Long, Long>? = withReadLock {
        readConnectionFor(sessionId).prepareStatement("SELECT MIN(timestamp_ms), MAX(timestamp_ms) FROM telemetry_frames WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val min = rs.getLong(1)
                    val max = rs.getLong(2)
                    if (rs.wasNull()) null else Pair(min, max)
                } else null
            }
        }
    }

    suspend fun getTelemetryRange(sessionId: String, startMs: Long, endMs: Long): List<TelemetryFrame> = withReadLock {
        val targetConn = readConnectionFor(sessionId)
        val list = mutableListOf<TelemetryFrame>()
        targetConn.prepareStatement("SELECT * FROM telemetry_frames WHERE session_id = ? AND timestamp_ms BETWEEN ? AND ? ORDER BY timestamp_us ASC, sample_order ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, startMs)
            ps.setLong(3, endMs)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toTelemetryFrame())
            }
        }
        list
    }

    /**
     * Returns the most recent value for every topic strictly before [timestampMs].
     * Replay uses this as the latched-state baseline when it loads a bounded window.
     */
    suspend fun getLatestTelemetryBefore(sessionId: String, timestampMs: Long): List<TelemetryFrame> = withReadLock {
        val targetConn = readConnectionFor(sessionId)
        val list = mutableListOf<TelemetryFrame>()
        targetConn.prepareStatement(
            """
            SELECT timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order
            FROM telemetry_frames
            WHERE session_id = ? AND timestamp_ms < ?
            QUALIFY ROW_NUMBER() OVER (PARTITION BY key ORDER BY timestamp_us DESC, sample_order DESC) = 1
            ORDER BY timestamp_us ASC, sample_order ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, timestampMs)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toTelemetryFrame())
            }
        }
        list
    }

    suspend fun getTelemetryRangeBatched(sessionId: String, startMs: Long, endMs: Long, limit: Long, offset: Long): List<TelemetryFrame> = withReadLock {
        val targetConn = readConnectionFor(sessionId)
        val list = mutableListOf<TelemetryFrame>()
        targetConn.prepareStatement("SELECT * FROM telemetry_frames WHERE session_id = ? AND timestamp_ms BETWEEN ? AND ? ORDER BY timestamp_us ASC, sample_order ASC LIMIT ? OFFSET ?").use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, startMs)
            ps.setLong(3, endMs)
            ps.setLong(4, limit)
            ps.setLong(5, offset)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toTelemetryFrame())
            }
        }
        list
    }

    suspend fun countTelemetryFrames(sessionId: String): Long = withReadLock {
        readConnectionFor(sessionId).prepareStatement("SELECT COUNT(*) FROM telemetry_frames WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    suspend fun getTelemetryForKey(sessionId: String, key: String): List<TelemetryFrame> = withReadLock {
        val list = mutableListOf<TelemetryFrame>()
        readConnectionFor(sessionId).prepareStatement("SELECT * FROM telemetry_frames WHERE session_id = ? AND key = ? ORDER BY timestamp_us ASC, sample_order ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, TelemetryMetricCatalog.normalizeTopic(key))
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toTelemetryFrame())
            }
        }
        list
    }

    /**
     * Returns a plot-ready numeric series bounded by [maxPoints]. DuckDB performs min/max
     * aggregation per viewport bucket so short spikes remain visible without materializing the
     * complete session in the desktop process.
     */
    suspend fun getTelemetrySeries(
        sessionId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        maxPoints: Int
    ): List<TelemetryFrame> = withReadLock {
        require(endMs >= startMs) { "endMs must be greater than or equal to startMs" }
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        val bucketCount = (maxPoints / 2).coerceAtLeast(1)
        val durationMs = (endMs - startMs + 1).coerceAtLeast(1)
        val bucketWidthMs = kotlin.math.ceil(durationMs.toDouble() / bucketCount).toLong().coerceAtLeast(1)
        val normalizedKey = TelemetryMetricCatalog.normalizeTopic(key)
        val points = mutableListOf<TelemetryFrame>()
        val sql = """
            WITH bucketed AS (
                SELECT
                    FLOOR((timestamp_ms - ?) / ?)::BIGINT AS bucket_id,
                    MIN(value) AS min_value,
                    ARG_MIN(timestamp_ms, value) AS min_timestamp_ms,
                    ARG_MIN(timestamp_us, value) AS min_timestamp_us,
                    ARG_MIN(sample_order, value) AS min_sample_order,
                    MAX(value) AS max_value,
                    ARG_MAX(timestamp_ms, value) AS max_timestamp_ms,
                    ARG_MAX(timestamp_us, value) AS max_timestamp_us,
                    ARG_MAX(sample_order, value) AS max_sample_order
                FROM telemetry_frames
                WHERE session_id = ? AND key = ? AND timestamp_ms BETWEEN ? AND ?
                GROUP BY bucket_id
            ), plot_points AS (
                SELECT min_timestamp_ms AS timestamp_ms, min_value AS value,
                       min_timestamp_us AS timestamp_us, min_sample_order AS sample_order
                FROM bucketed
                UNION ALL
                SELECT max_timestamp_ms, max_value, max_timestamp_us, max_sample_order
                FROM bucketed
                WHERE max_timestamp_us <> min_timestamp_us
                   OR max_sample_order <> min_sample_order
                   OR max_value <> min_value
            )
            SELECT timestamp_ms, value, timestamp_us, sample_order
            FROM plot_points
            ORDER BY timestamp_us ASC, sample_order ASC
            LIMIT ?
        """.trimIndent()
        readConnectionFor(sessionId).prepareStatement(sql).use { ps ->
            ps.setLong(1, startMs)
            ps.setLong(2, bucketWidthMs)
            ps.setString(3, sessionId)
            ps.setString(4, normalizedKey)
            ps.setLong(5, startMs)
            ps.setLong(6, endMs)
            ps.setInt(7, maxPoints)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    points.add(
                        TelemetryFrame(
                            timestampMs = rs.getLong("timestamp_ms"),
                            sessionId = sessionId,
                            key = normalizedKey,
                            value = rs.getDouble("value"),
                            timestampUs = rs.getLong("timestamp_us"),
                            sampleOrder = rs.getLong("sample_order")
                        )
                    )
                }
            }
        }
        points
    }

    /** A bounded, ordered table page for a set of visible telemetry columns. */
    suspend fun getTelemetryPageForKeys(
        sessionId: String,
        keys: List<String>,
        startMs: Long,
        endMs: Long,
        limit: Int,
        offset: Long
    ): List<TelemetryFrame> = withReadLock {
        require(endMs >= startMs) { "endMs must be greater than or equal to startMs" }
        require(limit in 1..50_000) { "limit must be between 1 and 50000" }
        require(offset >= 0) { "offset must not be negative" }
        if (keys.isEmpty()) return@withReadLock emptyList()

        val normalizedKeys = keys.distinct().map(TelemetryMetricCatalog::normalizeTopic)
        val placeholders = normalizedKeys.joinToString(",") { "?" }
        val sql = """
            SELECT * FROM telemetry_frames
            WHERE session_id = ?
              AND key IN ($placeholders)
              AND timestamp_ms BETWEEN ? AND ?
            ORDER BY timestamp_us ASC, sample_order ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val frames = mutableListOf<TelemetryFrame>()
        readConnectionFor(sessionId).prepareStatement(sql).use { ps ->
            var parameter = 1
            ps.setString(parameter++, sessionId)
            normalizedKeys.forEach { ps.setString(parameter++, it) }
            ps.setLong(parameter++, startMs)
            ps.setLong(parameter++, endMs)
            ps.setInt(parameter++, limit)
            ps.setLong(parameter, offset)
            ps.executeQuery().use { rs ->
                while (rs.next()) frames.add(rs.toTelemetryFrame())
            }
        }
        frames
    }

    /**
     * Performs the export size/range preflight inside DuckDB without materializing the selected
     * session. The inner LIMIT makes an over-limit result stop at [maximumFrames] + 1 rows.
     */
    suspend fun getTelemetryExportPreflight(
        sessionId: String,
        keys: List<String>,
        maximumFrames: Int,
    ): TelemetryExportPreflight = withReadLock {
        require(maximumFrames in 1 until Int.MAX_VALUE) { "maximumFrames must be positive" }
        if (keys.isEmpty()) {
            return@withReadLock TelemetryExportPreflight(0L, null, null)
        }
        val normalizedKeys = keys.map(TelemetryMetricCatalog::normalizeTopic).distinct()
        val placeholders = normalizedKeys.joinToString(",") { "?" }
        val sql = """
            SELECT COUNT(*), MIN(timestamp_ms), MAX(timestamp_ms)
            FROM (
                SELECT timestamp_ms
                FROM telemetry_frames
                WHERE session_id = ? AND key IN ($placeholders)
                LIMIT ?
            ) AS bounded_export
        """.trimIndent()
        readConnectionFor(sessionId).prepareStatement(sql).use { statement ->
            var parameter = 1
            statement.setString(parameter++, sessionId)
            normalizedKeys.forEach { statement.setString(parameter++, it) }
            statement.setInt(parameter, maximumFrames + 1)
            statement.executeQuery().use { result ->
                check(result.next()) { "Telemetry export preflight did not return a result" }
                val count = result.getLong(1)
                val minValue = result.getLong(2)
                val minTimestamp = if (result.wasNull()) null else minValue
                val maxValue = result.getLong(3)
                val maxTimestamp = if (result.wasNull()) null else maxValue
                TelemetryExportPreflight(count, minTimestamp, maxTimestamp)
            }
        }
    }

    /** Returns the stored representation for each selected topic; MIXED cannot be one WPILOG entry. */
    suspend fun getTelemetryExportValueTypes(
        sessionId: String,
        keys: List<String>,
    ): Map<String, TelemetryExportValueType> = withReadLock {
        if (keys.isEmpty()) return@withReadLock emptyMap()
        val normalizedKeys = keys.map(TelemetryMetricCatalog::normalizeTopic).distinct()
        val placeholders = normalizedKeys.joinToString(",") { "?" }
        val sql = """
            SELECT key,
                   MAX(CASE WHEN string_value IS NULL THEN 1 ELSE 0 END) AS has_numeric,
                   MAX(CASE WHEN string_value IS NOT NULL THEN 1 ELSE 0 END) AS has_string
            FROM telemetry_frames
            WHERE session_id = ? AND key IN ($placeholders)
            GROUP BY key
        """.trimIndent()
        val valueTypes = LinkedHashMap<String, TelemetryExportValueType>()
        readConnectionFor(sessionId).prepareStatement(sql).use { statement ->
            var parameter = 1
            statement.setString(parameter++, sessionId)
            normalizedKeys.forEach { statement.setString(parameter++, it) }
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val hasNumeric = result.getInt("has_numeric") != 0
                    val hasString = result.getInt("has_string") != 0
                    valueTypes[result.getString("key")] = when {
                        hasNumeric && hasString -> TelemetryExportValueType.MIXED
                        hasString -> TelemetryExportValueType.STRING
                        else -> TelemetryExportValueType.NUMERIC
                    }
                }
            }
        }
        valueTypes
    }

    /**
     * Reads one stable keyset page ordered by the complete export cursor. The cursor columns are
     * unique because telemetry_frames is keyed by (session_id, key, timestamp_us, sample_order).
     */
    suspend fun getTelemetryExportPage(
        sessionId: String,
        keys: List<String>,
        after: TelemetryExportCursor?,
        limit: Int,
    ): List<TelemetryFrame> = withReadLock {
        require(limit in 1..50_000) { "limit must be between 1 and 50000" }
        if (keys.isEmpty()) return@withReadLock emptyList()
        val normalizedKeys = keys.map(TelemetryMetricCatalog::normalizeTopic).distinct()
        val placeholders = normalizedKeys.joinToString(",") { "?" }
        val cursorPredicate = if (after == null) {
            ""
        } else {
            """
                AND (
                    timestamp_us > ?
                    OR (timestamp_us = ? AND sample_order > ?)
                    OR (timestamp_us = ? AND sample_order = ? AND key > ?)
                )
            """.trimIndent()
        }
        val sql = """
            SELECT timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order
            FROM telemetry_frames
            WHERE session_id = ? AND key IN ($placeholders)
            $cursorPredicate
            ORDER BY timestamp_us ASC, sample_order ASC, key ASC
            LIMIT ?
        """.trimIndent()
        val frames = ArrayList<TelemetryFrame>(limit)
        readConnectionFor(sessionId).prepareStatement(sql).use { statement ->
            var parameter = 1
            statement.setString(parameter++, sessionId)
            normalizedKeys.forEach { statement.setString(parameter++, it) }
            if (after != null) {
                statement.setLong(parameter++, after.timestampUs)
                statement.setLong(parameter++, after.timestampUs)
                statement.setLong(parameter++, after.sampleOrder)
                statement.setLong(parameter++, after.timestampUs)
                statement.setLong(parameter++, after.sampleOrder)
                statement.setString(parameter++, after.key)
            }
            statement.setInt(parameter, limit)
            statement.executeQuery().use { result ->
                while (result.next()) frames.add(result.toTelemetryFrame())
            }
        }
        frames
    }

    suspend fun getTelemetryForFilters(
        sessionId: String,
        keys: List<String>,
        prefixes: List<String>,
        maxFrames: Int,
        maxFramesPerTopic: Int,
    ): List<TelemetryFrame> = withReadLock {
        require(maxFrames > 0) { "Maximum diagnostic frame count must be positive" }
        require(maxFramesPerTopic > 0) { "Maximum diagnostic frames per topic must be positive" }
        val list = mutableListOf<TelemetryFrame>()
        val predicate = StringBuilder("session_id = ?")
        val conditions = mutableListOf<String>()
        if (keys.isNotEmpty()) {
            val placeholders = keys.joinToString(",") { "?" }
            conditions.add("key IN ($placeholders)")
        }
        if (prefixes.isNotEmpty()) {
            val likeConditions = prefixes.joinToString(" OR ") { "LOWER(key) LIKE LOWER(?)" }
            conditions.add("($likeConditions)")
        }
        if (conditions.isEmpty()) return@withReadLock list
        predicate.append(" AND (").append(conditions.joinToString(" OR ")).append(")")
        val query = """
            WITH matching AS (
                SELECT *,
                    ROW_NUMBER() OVER (PARTITION BY key ORDER BY timestamp_us, sample_order) AS topic_row,
                    COUNT(*) OVER (PARTITION BY key) AS topic_count
                FROM telemetry_frames
                WHERE $predicate
            ), sampled AS (
                SELECT * FROM matching
                WHERE topic_count <= ?
                   OR topic_row = 1
                   OR topic_row = topic_count
                   OR MOD(
                       topic_row - 1,
                       GREATEST(
                           1,
                           CAST(CEIL((topic_count - 1)::DOUBLE / GREATEST(1, ? - 1)) AS BIGINT)
                       )
                   ) = 0
            )
            SELECT timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order
            FROM sampled
            ORDER BY timestamp_us ASC, sample_order ASC
            LIMIT ?
        """.trimIndent()

        readConnectionFor(sessionId).prepareStatement(query).use { ps ->
            ps.setString(1, sessionId)
            var idx = 2
            for (k in keys) {
                ps.setString(idx++, TelemetryMetricCatalog.normalizeTopic(k))
            }
            for (p in prefixes) {
                ps.setString(idx++, TelemetryMetricCatalog.normalizeTopic(p))
            }
            ps.setInt(idx++, maxFramesPerTopic)
            ps.setInt(idx++, maxFramesPerTopic)
            ps.setInt(idx, maxFrames)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toTelemetryFrame())
            }
        }
        list
    }

    suspend fun getDistinctTelemetryKeys(sessionId: String): List<String> = withReadLock {
        val keys = mutableListOf<String>()
        readConnectionFor(sessionId).prepareStatement(
            "SELECT DISTINCT key FROM telemetry_frames WHERE session_id = ? ORDER BY key"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) keys.add(rs.getString(1))
            }
        }
        keys
    }

    suspend fun getTelemetryForKeyPatterns(sessionId: String, patterns: List<String>): List<TelemetryFrame> =
        getTelemetryForFilters(sessionId, emptyList(), patterns, 100_000, 2_048)

    suspend fun getDistinctTimestamps(sessionId: String): List<Long> = withReadLock {
        val list = mutableListOf<Long>()
        readConnectionFor(sessionId).prepareStatement("SELECT DISTINCT timestamp_ms FROM telemetry_frames WHERE session_id = ? ORDER BY timestamp_ms ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.getLong(1))
            }
        }
        list
    }

    suspend fun countTimestampGaps(sessionId: String, minimumGapMs: Long): Long = withReadLock {
        require(minimumGapMs >= 0L) { "Minimum timestamp gap must not be negative" }
        readConnectionFor(sessionId).prepareStatement(
            """
            SELECT COUNT(*)
            FROM (
                SELECT timestamp_ms - LAG(timestamp_ms) OVER (ORDER BY timestamp_ms) AS gap_ms
                FROM (
                    SELECT DISTINCT timestamp_ms
                    FROM telemetry_frames
                    WHERE session_id = ?
                ) ordered_timestamps
            ) gaps
            WHERE gap_ms > ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, minimumGapMs)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }
    }

    suspend fun deleteTelemetryFrames(sessionId: String) = withDbLock {
        // Route by sessionId to the correct connection: "live-telemetry" frames live in
        // ephemeralConn (see insertTelemetryFrames routing). Deleting on `conn` would be a
        // silent no-op and let the ephemeral buffer grow unbounded.
        val targetConn = if (sessionId == "live-telemetry") ephemeralConn else conn
        targetConn.prepareStatement("DELETE FROM telemetry_frames WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
    }

    suspend fun pruneTelemetryFrames(sessionId: String, cutoffMs: Long) = withDbLock {
        // Same routing as deleteTelemetryFrames / getTelemetryRange: live frames are in
        // ephemeralConn, so the 5-min live prune must target that connection.
        val targetConn = if (sessionId == "live-telemetry") ephemeralConn else conn
        targetConn.prepareStatement("DELETE FROM telemetry_frames WHERE session_id = ? AND timestamp_ms < ?").use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, cutoffMs)
            ps.executeUpdate()
        }
    }

    private fun ResultSet.toTelemetryFrame(): TelemetryFrame {
        val sVal = getString("string_value")
        val sValFinal = if (wasNull()) null else sVal
        return TelemetryFrame(
            timestampMs = getLong("timestamp_ms"),
            sessionId = getString("session_id"),
            key = getString("key"),
            value = getDouble("value"),
            stringValue = sValFinal,
            timestampUs = getLong("timestamp_us"),
            sampleOrder = getLong("sample_order")
        )
    }

    suspend fun getTelemetryDensity(sessionId: String, buckets: Int = 100): List<Float> = withDbLock {
        val activeConn = if (sessionId == "live-telemetry") ephemeralConn else conn
        var minTime = 0L
        var maxTime = 0L
        activeConn.prepareStatement("SELECT MIN(timestamp_ms), MAX(timestamp_ms) FROM telemetry_frames WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    minTime = rs.getLong(1)
                    maxTime = rs.getLong(2)
                }
            }
        }

        if (minTime == maxTime || maxTime == 0L) {
            return@withDbLock List(buckets) { 0f }
        }
        val duration = maxTime - minTime
        val bucketSize = duration.toDouble() / buckets
        val bucketCounts = LongArray(buckets)
        activeConn.prepareStatement("""
            SELECT CAST((timestamp_ms - ?) / ? AS INTEGER) as bucket_idx, COUNT(*) as cnt
            FROM telemetry_frames
            WHERE session_id = ?
            GROUP BY bucket_idx
        """.trimIndent()).use { ps ->
            ps.setLong(1, minTime)
            ps.setDouble(2, bucketSize)
            ps.setString(3, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val idx = rs.getInt(1).coerceIn(0, buckets - 1)
                    val cnt = rs.getLong(2)
                    bucketCounts[idx] += cnt
                }
            }
        }
        val maxCount = bucketCounts.maxOrNull() ?: 1L
        if (maxCount == 0L) {
             return@withDbLock List(buckets) { 0f }
        }

        bucketCounts.map { it.toFloat() / maxCount }
    }

}
