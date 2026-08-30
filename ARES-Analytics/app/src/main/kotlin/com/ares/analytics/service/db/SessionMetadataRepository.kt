package com.ares.analytics.service.db

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.*
import com.areslib.telemetry.schema.HardwareTopology
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet

/**
 * Persists the session aggregate: identity, summaries, review metadata, alerts, topology, and
 * console evidence. High-volume telemetry samples and robot actions belong to separate repositories.
 */
internal class SessionMetadataRepository(
    private val transactions: DatabaseTransactionCoordinator,
) {
    private val conn: Connection get() = transactions.writeConnection
    private val readConn: Connection get() = transactions.readConnection
    private val statementCache = java.util.concurrent.ConcurrentHashMap<String, java.sql.PreparedStatement>()

    private suspend fun <T> withDbLock(block: suspend () -> T): T = transactions.write(block)
    private suspend fun <T> withReadLock(block: suspend () -> T): T = transactions.read(block)

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

    /** Participates in a transaction already owned by another repository coordinator. */
    internal fun upsertCompletedSessionWithinTransaction(session: Session) {
        upsertSession(session, IMPORT_STATE_COMPLETE)
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


    fun dispose() {
        statementCache.values.forEach { statement -> runCatching { statement.close() } }
        statementCache.clear()
    }

    private companion object {
        const val IMPORT_STATE_IMPORTING = "IMPORTING"
        const val IMPORT_STATE_COMPLETE = "COMPLETE"
    }
}
