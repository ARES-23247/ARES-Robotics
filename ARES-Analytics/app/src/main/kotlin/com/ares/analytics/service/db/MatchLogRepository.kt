package com.ares.analytics.service.db

import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.service.QueryResult
import com.ares.analytics.service.DatabaseMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.duckdb.DuckDBAppender
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
 * Primary repository interface for telemetry persistent storage, DuckDB vectorized queries, and match history.
 *
 * Provides thread-safe transaction execution over DuckDB JDBC connections, utilizing DuckDB's native Appender C++ API
 * (`insertTelemetryFramesAppender`, `insertRobotActionsBulk`) for bulk frame ingest (~10-100x faster than traditional JDBC SQL batches).
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
 * Thread-safe suspend functions executing DB transactions under mutual exclusion lock ([dbMutex]) on [Dispatchers.IO].
 * Appender operations stream raw memory arrays to DuckDB C++ native buffers with zero JVM heap fragmentation.
 *
 * @param conn Primary DuckDB connection bound to disk storage.
 * @param ephemeralConn In-memory DuckDB connection for high-throughput live telemetry buffers.
 * @param dbMutex Mutual exclusion coroutine lock controlling connection write concurrency.
 *
 * @see SchemaMigrationManager
 * @see DatabaseBackupExporter
 * @see QueryResult
 */
class MatchLogRepository(
    private val conn: Connection,
    private val readConn: Connection,
    private val ephemeralConn: Connection,
    private val ephemeralReadConn: Connection,
    private val dbMutex: Mutex,
    private val readMutex: Mutex,
    private val metrics: DatabaseMetrics
) {
    private val statementCache = java.util.concurrent.ConcurrentHashMap<String, java.sql.PreparedStatement>()
    private val nextSampleOrder = AtomicLong()
    private val readOnlyQueries = ReadOnlyQueryRepository(readConn, readMutex, metrics)

    /** Executes the deliberately restricted SQL subset exposed to the AI analyst. */
    suspend fun executeAiQuery(
        sql: String,
        rowLimit: Int = QueryResult.DEFAULT_RAW_QUERY_ROW_LIMIT,
    ): QueryResult = readOnlyQueries.executeRaw(AiSqlQueryGuard.validate(sql), rowLimit)

    private fun readConnectionFor(sessionId: String): Connection =
        if (sessionId == "live-telemetry") ephemeralReadConn else readConn

    private fun storageOrder(frame: TelemetryFrame): Long =
        if (frame.sampleOrder != 0L) frame.sampleOrder else nextSampleOrder.incrementAndGet()
    /**
     * Executes a raw database operation safely under [dbMutex] on [Dispatchers.IO].
     *
     * @param T Result type of the transaction block.
     * @param block Database execution logic.
     * @return Result produced by [block].
     */
    private suspend fun <T> withDbLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            dbMutex.withLock { block() }
        } finally {
            metrics.recordWrite(metrics.nowNanos() - started)
        }
    }

    private suspend fun <T> withReadLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            readMutex.withLock { block() }
        } finally {
            metrics.recordRead(metrics.nowNanos() - started)
        }
    }

    /**
     * Final teardown — closes and clears the [statementCache]. Call from [DatabaseService.close]
     * before closing the underlying connections so cached PreparedStatements don't leak.
     */
    fun dispose() {
        statementCache.values.forEach { runCatching { it.close() } }
        statementCache.clear()
    }

    suspend fun executeNativeCsvImport(sql: String) = withDbLock {
        if (!sql.trim().uppercase().startsWith("INSERT INTO TELEMETRY_FRAMES")) {
            throw IllegalArgumentException("executeNativeCsvImport only allows INSERT INTO telemetry_frames")
        }
        conn.createStatement().use { it.execute(sql) }
    }

    /**
     * Executes a custom read-only query while bounding the amount of data retained on the JVM heap.
     *
     * Relational queries are wrapped in an outer `LIMIT rowLimit + 1` so DuckDB itself does not return
     * an unbounded result. The extra row is used only to report truncation; it is never copied into
     * [QueryResult]. Cell and column limits protect projections a row-only limit would not address.
     */
    suspend fun executeQueryRaw(
        sql: String,
        rowLimit: Int = QueryResult.DEFAULT_RAW_QUERY_ROW_LIMIT
    ): QueryResult = readOnlyQueries.executeRaw(sql, rowLimit)

    /**
     * Execute a parameterized SQL query and return results as [QueryResult].
     * Use this for queries with user-provided values to prevent SQL injection.
     */
    suspend fun executeQueryWithParams(sql: String, params: List<Any>): QueryResult =
        readOnlyQueries.executeWithParams(sql, params)

    suspend fun insertSession(session: Session) = withDbLock {
        upsertSession(session, IMPORT_STATE_COMPLETE)
    }

    /** Creates the durable owner for a bounded, streaming import before its first data row. */
    suspend fun insertImportSession(session: Session) = withDbLock {
        upsertSession(session, IMPORT_STATE_IMPORTING)
    }

    private fun upsertSession(session: Session, importState: String) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sessions (session_id, team_id, season_id, robot_id, created_at, duration_ms, tags, match_number, alliance_color, import_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, session.sessionId)
            ps.setString(2, session.teamId)
            ps.setString(3, session.seasonId)
            ps.setString(4, session.robotId)
            ps.setLong(5, session.createdAt)
            ps.setLong(6, session.durationMs)
            ps.setString(7, Json.encodeToString(session.tags))
            session.matchNumber?.let { ps.setLong(8, it.toLong()) } ?: ps.setNull(8, java.sql.Types.BIGINT)
            ps.setString(9, session.allianceColor)
            ps.setString(10, importState)
            ps.executeUpdate()
        }
    }

    suspend fun getSessions(): List<Session> = withDbLock {
        val list = mutableListOf<Session>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM sessions WHERE import_state = '$IMPORT_STATE_COMPLETE' ORDER BY created_at DESC").use { rs ->
                while (rs.next()) list.add(rs.toSession())
            }
        }
        list
    }

    suspend fun getSessionsForWorkspace(
        teamId: String,
        seasonId: String,
        robotId: String,
    ): List<Session> = withReadLock {
        val sessions = mutableListOf<Session>()
        readConn.prepareStatement(
            """
            SELECT * FROM sessions
            WHERE import_state = ? AND team_id = ? AND season_id = ? AND robot_id = ?
            ORDER BY created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, IMPORT_STATE_COMPLETE)
            statement.setString(2, teamId)
            statement.setString(3, seasonId)
            statement.setString(4, robotId)
            statement.executeQuery().use { rows ->
                while (rows.next()) sessions += rows.toSession()
            }
        }
        sessions
    }

    /**
     * Finds an already-completed import only when the workspace identity and the complete set of
     * source hashes match. A file imported by another team/season/robot is never reused.
     */
    suspend fun findCompletedSessionBySourceHashes(
        teamId: String,
        seasonId: String,
        robotId: String,
        sourceHashes: Set<String>,
    ): Session? = withReadLock {
        if (sourceHashes.isEmpty()) return@withReadLock null
        val candidates = mutableListOf<Pair<Session, MutableSet<String>>>()
        readConn.prepareStatement(
            """
            SELECT s.*, r.source_sha256
            FROM sessions s
            JOIN session_import_reports r ON r.session_id = s.session_id
            WHERE s.import_state = ? AND s.team_id = ? AND s.season_id = ? AND s.robot_id = ?
            ORDER BY s.created_at DESC, s.session_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, IMPORT_STATE_COMPLETE)
            statement.setString(2, teamId)
            statement.setString(3, seasonId)
            statement.setString(4, robotId)
            statement.executeQuery().use { rows ->
                val bySession = linkedMapOf<String, Pair<Session, MutableSet<String>>>()
                while (rows.next()) {
                    val session = rows.toSession()
                    val entry = bySession.getOrPut(session.sessionId) { session to linkedSetOf() }
                    entry.second += rows.getString("source_sha256")
                }
                candidates += bySession.values
            }
        }
        candidates.firstOrNull { (_, hashes) -> hashes == sourceHashes }?.first
    }

    suspend fun deleteSession(sessionId: String) = withDbLock {
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            val sessionOwnedTables = arrayOf(
                "session_summaries",
                "telemetry_frames",
                "analysis_diagnostics",
                "session_import_reports",
                "session_annotations",
                "alerts",
                "console_messages",
                "robot_actions",
                "sessions"
            )
            for (table in sessionOwnedTables) {
                conn.prepareStatement("DELETE FROM $table WHERE session_id = ?").use { ps ->
                    ps.setString(1, sessionId)
                    ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    suspend fun insertSessionSummary(summary: SessionSummary) = withDbLock {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO session_summaries (session_id, team_id, season_id, robot_id, created_at, duration_ms, min_battery_voltage, max_ekf_drift, avg_loop_time_ms, p95_loop_time_ms, motor_current_averages, vision_acceptance_rate, avg_cross_track_error, avg_battery_resistance, max_motor_temps, avg_vision_latency_ms, tags, match_number, alliance_color) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, summary.sessionId)
            ps.setString(2, summary.teamId)
            ps.setString(3, summary.seasonId)
            ps.setString(4, summary.robotId)
            ps.setLong(5, summary.createdAt)
            ps.setLong(6, summary.durationMs)
            ps.setDouble(7, summary.minBatteryVoltage)
            ps.setDouble(8, summary.maxEkfDrift)
            ps.setDouble(9, summary.avgLoopTimeMs)
            ps.setDouble(10, summary.p95LoopTimeMs)
            ps.setString(11, Json.encodeToString(summary.motorCurrentAverages))
            ps.setDouble(12, summary.visionAcceptanceRate)
            ps.setDouble(13, summary.avgCrossTrackError)
            ps.setDouble(14, summary.avgBatteryResistance)
            ps.setString(15, Json.encodeToString(summary.maxMotorTemps))
            ps.setDouble(16, summary.avgVisionLatencyMs)
            ps.setString(17, Json.encodeToString(summary.tags))
            summary.matchNumber?.let { ps.setLong(18, it.toLong()) } ?: ps.setNull(18, java.sql.Types.BIGINT)
            ps.setString(19, summary.allianceColor)
            ps.executeUpdate()
        }
    }

    suspend fun getSessionSummary(sessionId: String): SessionSummary? = withReadLock {
        readConn.prepareStatement("SELECT * FROM session_summaries WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toSessionSummary() else null
            }
        }
    }

    suspend fun getAllSessionSummaries(): List<SessionSummary> = withDbLock {
        val list = mutableListOf<SessionSummary>()
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT ss.* FROM session_summaries ss
                JOIN sessions s ON s.session_id = ss.session_id
                WHERE s.import_state = '$IMPORT_STATE_COMPLETE'
                ORDER BY ss.created_at DESC
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) list.add(rs.toSessionSummary())
            }
        }
        list
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
            // import time. Checkpointing is now caller/timer-controlled via [checkpoint]
            // (DatabaseService runs it on a periodic timer; connection close still flushes).
        }
    }

    /**
     * Forces a WAL checkpoint on the persistent connection. Caller/timer-controlled so it
     * runs once per import job or periodically, not after every appender batch.
     */
    suspend fun checkpoint() = withDbLock {
        conn.createStatement().use { it.execute("CHECKPOINT") }
    }

    /**
     * High-performance bulk insert for RobotAction records using DuckDB's native Appender API.
     * Stores Redux-style action log entries from the robot's ActionLogger JSONL output.
     */
    suspend fun insertRobotActionsBulk(actions: List<com.ares.analytics.shared.models.RobotActionRecord>) = withDbLock {
        if (actions.isEmpty()) return@withDbLock
        val duckConn = conn.unwrap(DuckDBConnection::class.java)
        val appender = duckConn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "robot_actions")
        try {
            for (action in actions) {
                appender.beginRow()
                appender.append(action.timestampMs)
                appender.append(action.sessionId)
                appender.append(action.runId)
                appender.append(action.robotId)
                appender.append(action.matchNumber)
                appender.append(action.alliance)
                appender.append(action.actionType)
                appender.append(action.payloadJson)
                appender.endRow()
            }
            appender.flush()
        } finally {
            appender.close()
        }
    }

    /**
     * Retrieves all robot actions for a given session, ordered chronologically.
     */
    suspend fun getActionsForSession(sessionId: String): List<com.ares.analytics.shared.models.RobotActionRecord> = withDbLock {
        val list = mutableListOf<com.ares.analytics.shared.models.RobotActionRecord>()
        conn.prepareStatement(
            "SELECT timestamp_ms, session_id, run_id, robot_id, match_number, alliance, action_type, payload_json FROM robot_actions WHERE session_id = ? ORDER BY timestamp_ms, run_id, action_type, payload_json"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    list.add(com.ares.analytics.shared.models.RobotActionRecord(
                        timestampMs = rs.getLong("timestamp_ms"),
                        sessionId = rs.getString("session_id"),
                        runId = rs.getString("run_id"),
                        robotId = rs.getString("robot_id"),
                        matchNumber = rs.getInt("match_number"),
                        alliance = rs.getString("alliance"),
                        actionType = rs.getString("action_type"),
                        payloadJson = rs.getString("payload_json")
                    ))
                }
            }
        }
        list
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

    suspend fun getDiagnosticsTelemetry(sessionId: String): List<TelemetryFrame> = withDbLock {
        val list = mutableListOf<TelemetryFrame>()
        conn.prepareStatement("SELECT * FROM telemetry_frames WHERE session_id = ? AND key LIKE 'Diagnostics/%'").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toTelemetryFrame())
            }
        }
        val displayTimestamp = conn.prepareStatement(
            "SELECT COALESCE(MAX(timestamp_ms), (SELECT created_at FROM sessions WHERE session_id = ?), 0) " +
                "FROM telemetry_frames WHERE session_id = ?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, sessionId)
            ps.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
        conn.prepareStatement(
            "SELECT session_id, key, value, string_value FROM analysis_diagnostics WHERE session_id = ? ORDER BY key"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rows ->
                var sampleOrder = 1L
                while (rows.next()) {
                    list.add(
                        TelemetryFrame(
                            timestampMs = displayTimestamp,
                            sessionId = rows.getString("session_id"),
                            key = rows.getString("key"),
                            value = rows.getDouble("value"),
                            stringValue = rows.getString("string_value"),
                            sampleOrder = sampleOrder++,
                        )
                    )
                }
            }
        }
        list
    }

    /** Atomically replaces analyzer-owned results without modifying the raw telemetry timeline. */
    suspend fun replaceAnalysisDiagnostics(
        sessionId: String,
        diagnostics: List<AnalysisDiagnostic>,
    ) = withDbLock {
        require(diagnostics.all { it.sessionId == sessionId }) {
            "Every analysis diagnostic must belong to the replaced session"
        }
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            conn.prepareStatement("DELETE FROM analysis_diagnostics WHERE session_id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO analysis_diagnostics (session_id, key, value, string_value) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                diagnostics.distinctBy { TelemetryMetricCatalog.normalizeTopic(it.key) }.forEach { diagnostic ->
                    statement.setString(1, sessionId)
                    statement.setString(2, TelemetryMetricCatalog.normalizeTopic(diagnostic.key))
                    statement.setDouble(3, diagnostic.value)
                    statement.setString(4, diagnostic.stringValue)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            conn.commit()
        } catch (failure: Throwable) {
            conn.rollback()
            throw failure
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    suspend fun getAnalysisDiagnostics(sessionId: String): List<AnalysisDiagnostic> = withReadLock {
        val diagnostics = mutableListOf<AnalysisDiagnostic>()
        readConn.prepareStatement(
            "SELECT session_id, key, value, string_value FROM analysis_diagnostics WHERE session_id = ? ORDER BY key"
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    diagnostics.add(
                        AnalysisDiagnostic(
                            sessionId = rows.getString("session_id"),
                            key = rows.getString("key"),
                            value = rows.getDouble("value"),
                            stringValue = rows.getString("string_value"),
                        )
                    )
                }
            }
        }
        diagnostics
    }

    suspend fun replaceSessionImportReports(
        sessionId: String,
        reports: List<com.ares.analytics.service.ImportReport>,
    ) = withDbLock {
        require(reports.all { it.sessionId == sessionId }) {
            "Every import report must belong to the replaced session"
        }
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            replaceImportReports(sessionId, reports)
            conn.commit()
        } catch (failure: Throwable) {
            conn.rollback()
            throw failure
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    /** Atomically exposes a staged import together with its immutable source evidence. */
    suspend fun completeSessionImport(
        session: Session,
        reports: List<com.ares.analytics.service.ImportReport>,
    ) = withDbLock {
        require(reports.isNotEmpty()) { "A completed import requires source evidence" }
        require(reports.all { it.sessionId == session.sessionId }) {
            "Every import report must belong to the completed session"
        }
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            upsertSession(session, IMPORT_STATE_COMPLETE)
            replaceImportReports(session.sessionId, reports)
            conn.commit()
        } catch (failure: Throwable) {
            conn.rollback()
            throw failure
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    private fun replaceImportReports(
        sessionId: String,
        reports: List<com.ares.analytics.service.ImportReport>,
    ) {
        conn.prepareStatement("DELETE FROM session_import_reports WHERE session_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO session_import_reports (session_id, source_sha256, source_name, report_json) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            reports.distinctBy { it.sourceSha256 to it.sourceName }.forEach { report ->
                statement.setString(1, sessionId)
                statement.setString(2, report.sourceSha256)
                statement.setString(3, report.sourceName)
                statement.setString(4, AppJson.encodeToString(report))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    suspend fun getSessionImportReports(
        sessionId: String,
    ): List<com.ares.analytics.service.ImportReport> = withReadLock {
        val reports = mutableListOf<com.ares.analytics.service.ImportReport>()
        readConn.prepareStatement(
            "SELECT report_json FROM session_import_reports WHERE session_id = ? ORDER BY source_name, source_sha256"
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    reports.add(AppJson.decodeFromString(rows.getString("report_json")))
                }
            }
        }
        reports
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

    suspend fun insertAnnotation(annotation: SessionAnnotation) = withDbLock {
        conn.prepareStatement("INSERT OR REPLACE INTO session_annotations (annotation_id, session_id, text, created_at, author_id) VALUES (?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, annotation.annotationId)
            ps.setString(2, annotation.sessionId)
            ps.setString(3, annotation.text)
            ps.setLong(4, annotation.createdAt)
            ps.setString(5, annotation.authorId)
            ps.executeUpdate()
        }
    }

    suspend fun getAnnotations(sessionId: String): List<SessionAnnotation> = withDbLock {
        val list = mutableListOf<SessionAnnotation>()
        conn.prepareStatement("SELECT * FROM session_annotations WHERE session_id = ? ORDER BY created_at ASC, annotation_id ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toSessionAnnotation())
            }
        }
        list
    }

    suspend fun updateSessionTags(sessionId: String, tags: List<String>) = withDbLock {
        conn.prepareStatement("UPDATE sessions SET tags = ? WHERE session_id = ?").use { ps ->
            ps.setString(1, Json.encodeToString(tags))
            ps.setString(2, sessionId)
            ps.executeUpdate()
        }
        conn.prepareStatement("UPDATE session_summaries SET tags = ? WHERE session_id = ?").use { ps ->
            ps.setString(1, Json.encodeToString(tags))
            ps.setString(2, sessionId)
            ps.executeUpdate()
        }
    }

    suspend fun updateSessionMatchDetails(sessionId: String, matchNumber: Int?, allianceColor: String?) = withDbLock {
        conn.prepareStatement("UPDATE sessions SET match_number = ?, alliance_color = ? WHERE session_id = ?").use { ps ->
            if (matchNumber != null) ps.setLong(1, matchNumber.toLong()) else ps.setNull(1, java.sql.Types.BIGINT)
            ps.setString(2, allianceColor)
            ps.setString(3, sessionId)
            ps.executeUpdate()
        }
        conn.prepareStatement("UPDATE session_summaries SET match_number = ?, alliance_color = ? WHERE session_id = ?").use { ps ->
            if (matchNumber != null) ps.setLong(1, matchNumber.toLong()) else ps.setNull(1, java.sql.Types.BIGINT)
            ps.setString(2, allianceColor)
            ps.setString(3, sessionId)
            ps.executeUpdate()
        }
    }

    suspend fun associateSessionWithMatch(sessionId: String, matchNumber: Int, allianceColor: String, opponentTeams: List<String>) {
        updateSessionMatchDetails(sessionId, matchNumber, allianceColor)
    }

    suspend fun insertAlert(alert: AlertRecord) = withDbLock {
        conn.prepareStatement("INSERT OR REPLACE INTO alerts (alert_id, session_id, rule_key, trigger_timestamp_ms, resolve_timestamp_ms, duration_ms, peak_value, triaged) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, alert.alertId)
            ps.setString(2, alert.sessionId)
            ps.setString(3, alert.ruleKey)
            ps.setLong(4, alert.triggerTimestampMs)
            alert.resolveTimestampMs?.let { ps.setLong(5, it) }
                ?: ps.setNull(5, java.sql.Types.BIGINT)
            ps.setLong(6, alert.durationMs)
            ps.setDouble(7, alert.peakValue)
            ps.setLong(8, if (alert.triaged) 1L else 0L)
            ps.executeUpdate()
        }
    }

    suspend fun getAlerts(sessionId: String): List<AlertRecord> = withDbLock {
        val list = mutableListOf<AlertRecord>()
        conn.prepareStatement("SELECT * FROM alerts WHERE session_id = ? ORDER BY trigger_timestamp_ms ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toAlertRecord())
            }
        }
        list
    }

    suspend fun insertTopology(topology: HardwareTopology) = withDbLock {
        conn.prepareStatement("INSERT OR REPLACE INTO cached_topologies (robot_id, topology_json) VALUES (?, ?)").use { ps ->
            ps.setString(1, topology.robotId)
            ps.setString(2, Json.encodeToString(topology))
            ps.executeUpdate()
        }
    }

    suspend fun getTopology(robotId: String): HardwareTopology? = withDbLock {
        conn.prepareStatement("SELECT topology_json FROM cached_topologies WHERE robot_id = ?").use { ps ->
            ps.setString(1, robotId)
            ps.executeQuery().use { rs ->
                if (rs.next()) Json.decodeFromString(rs.getString("topology_json")) else null
            }
        }
    }

    private inline fun <T> executeBatchInsert(
        targetConn: Connection,
        items: List<T>,
        sql: String,
        batchSize: Int = 10000,
        crossinline bind: (java.sql.PreparedStatement, T) -> Unit
    ) {
        targetConn.autoCommit = false
        try {
            val cacheKey = "${targetConn.hashCode()}_$sql"
            val ps = statementCache.getOrPut(cacheKey) { targetConn.prepareStatement(sql) }
            items.chunked(batchSize).forEach { chunk ->
                for (item in chunk) {
                    bind(ps, item)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            targetConn.commit()
        } catch (e: Exception) {
            targetConn.rollback()
            throw e
        } finally {
            targetConn.autoCommit = true
        }
    }

    suspend fun insertConsoleMessages(messages: List<ConsoleMessage>, sessionId: String) = withDbLock {
        executeBatchInsert(conn, messages, "INSERT OR REPLACE INTO console_messages (timestamp_ms, session_id, text, severity) VALUES (?, ?, ?, ?)") { ps, msg ->
            ps.setLong(1, msg.timestampMs)
            ps.setString(2, sessionId)
            ps.setString(3, msg.text)
            ps.setString(4, msg.severity)
        }
    }

    suspend fun getConsoleMessages(sessionId: String): List<ConsoleMessage> = withDbLock {
        val list = mutableListOf<ConsoleMessage>()
        conn.prepareStatement("SELECT * FROM console_messages WHERE session_id = ? ORDER BY timestamp_ms ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.toConsoleMessage())
            }
        }
        list
    }

    // --- ResultSet Mappers ---

    private fun ResultSet.toSession(): Session {
        val matchNum = getLong("match_number")
        val matchNumFinal = if (wasNull()) null else matchNum.toInt()

        return Session(
            sessionId = getString("session_id"),
            teamId = getString("team_id"),
            seasonId = getString("season_id"),
            robotId = getString("robot_id"),
            createdAt = getLong("created_at"),
            durationMs = getLong("duration_ms"),
            tags = Json.decodeFromString(getString("tags") ?: "[]"),
            matchNumber = matchNumFinal,
            allianceColor = getString("alliance_color")
        )
    }

    private fun ResultSet.toSessionSummary(): SessionSummary {
        val matchNum = getLong("match_number")
        val matchNumFinal = if (wasNull()) null else matchNum.toInt()

        return SessionSummary(
            sessionId = getString("session_id"),
            teamId = getString("team_id"),
            seasonId = getString("season_id"),
            robotId = getString("robot_id"),
            createdAt = getLong("created_at"),
            durationMs = getLong("duration_ms"),
            minBatteryVoltage = getDouble("min_battery_voltage"),
            maxEkfDrift = getDouble("max_ekf_drift"),
            avgLoopTimeMs = getDouble("avg_loop_time_ms"),
            p95LoopTimeMs = getDouble("p95_loop_time_ms"),
            motorCurrentAverages = Json.decodeFromString(getString("motor_current_averages") ?: "{}"),
            visionAcceptanceRate = getDouble("vision_acceptance_rate"),
            avgCrossTrackError = getDouble("avg_cross_track_error"),
            avgBatteryResistance = getDouble("avg_battery_resistance"),
            maxMotorTemps = Json.decodeFromString(getString("max_motor_temps") ?: "{}"),
            avgVisionLatencyMs = getDouble("avg_vision_latency_ms"),
            tags = Json.decodeFromString(getString("tags") ?: "[]"),
            matchNumber = matchNumFinal,
            allianceColor = getString("alliance_color")
        )
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

    private fun ResultSet.toSessionAnnotation(): SessionAnnotation {
        return SessionAnnotation(
            annotationId = getString("annotation_id"),
            sessionId = getString("session_id"),
            text = getString("text"),
            createdAt = getLong("created_at"),
            authorId = getString("author_id")
        )
    }

    private fun ResultSet.toAlertRecord(): AlertRecord {
        val rTime = getLong("resolve_timestamp_ms")
        val rTimeFinal = if (wasNull()) null else rTime

        return AlertRecord(
            alertId = getString("alert_id"),
            sessionId = getString("session_id"),
            ruleKey = getString("rule_key"),
            triggerTimestampMs = getLong("trigger_timestamp_ms"),
            resolveTimestampMs = rTimeFinal,
            durationMs = getLong("duration_ms"),
            peakValue = getDouble("peak_value"),
            triaged = getLong("triaged") != 0L
        )
    }

    private fun ResultSet.toConsoleMessage(): ConsoleMessage {
        return ConsoleMessage(
            timestampMs = getLong("timestamp_ms"),
            text = getString("text"),
            severity = getString("severity")
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

    private companion object {
        private const val IMPORT_STATE_IMPORTING = "IMPORTING"
        private const val IMPORT_STATE_COMPLETE = "COMPLETE"
    }

}
