package com.ares.analytics.service.db

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ReplayPoseSample(
    val x: Double,
    val y: Double,
    val headingRadians: Double,
    val timestampMs: Long = 0L,
)

/**
 * Selects one complete source across the requested window, then samples whole poses in order.
 * SQL keeps raw history inside DuckDB and transfers at most maxPoints triples to the JVM.
 */
internal class ReplayPoseRepository(private val transactions: DatabaseTransactionCoordinator) {
    suspend fun readWindowPageAfter(
        sessionId: String,
        startMs: Long,
        endMs: Long,
        after: TelemetryExportCursor?,
        limit: Int,
    ): List<TelemetryFrame> = transactions.readTelemetry(sessionId) {
        val targetConn = transactions.readConnectionFor(sessionId)
        val list = ArrayList<TelemetryFrame>(minOf(limit, 8_192))
        val sql = if (after == null) {
            "SELECT timestamp_ms, key, value, string_value, timestamp_us, sample_order " +
                "FROM telemetry_frames WHERE session_id = ? AND timestamp_ms BETWEEN ? AND ? " +
                "ORDER BY timestamp_us ASC, sample_order ASC, key ASC LIMIT ?"
        } else {
            "SELECT timestamp_ms, key, value, string_value, timestamp_us, sample_order " +
                "FROM telemetry_frames WHERE session_id = ? AND timestamp_ms BETWEEN ? AND ? " +
                "AND (timestamp_us > ? OR (timestamp_us = ? AND sample_order > ?) OR (timestamp_us = ? AND sample_order = ? AND key > ?)) " +
                "ORDER BY timestamp_us ASC, sample_order ASC, key ASC LIMIT ?"
        }
        targetConn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, sessionId)
            ps.setLong(idx++, startMs)
            ps.setLong(idx++, endMs)
            if (after != null) {
                ps.setLong(idx++, after.timestampUs)
                ps.setLong(idx++, after.timestampUs)
                ps.setLong(idx++, after.sampleOrder)
                ps.setLong(idx++, after.timestampUs)
                ps.setLong(idx++, after.sampleOrder)
                ps.setString(idx++, after.key)
            }
            ps.setInt(idx, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val sVal = rs.getString(4)
                    list.add(
                        TelemetryFrame(
                            timestampMs = rs.getLong(1),
                            sessionId = sessionId,
                            key = rs.getString(2),
                            value = rs.getDouble(3),
                            stringValue = if (rs.wasNull()) null else sVal,
                            timestampUs = rs.getLong(5),
                            sampleOrder = rs.getLong(6),
                        )
                    )
                }
            }
        }
        list
    }

    suspend fun countDistinctKeys(sessionId: String): Int = transactions.readTelemetry(sessionId) {
        transactions.readConnectionFor(sessionId).prepareStatement(
            "SELECT COUNT(DISTINCT key) FROM telemetry_frames WHERE session_id = ?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    suspend fun readDistinctTimestampsArray(sessionId: String): LongArray = transactions.readTelemetry(sessionId) {
        var buffer = LongArray(256)
        var size = 0
        transactions.readConnectionFor(sessionId).prepareStatement(
            "SELECT DISTINCT timestamp_ms FROM telemetry_frames WHERE session_id = ? ORDER BY timestamp_ms ASC"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                    buffer[size++] = rs.getLong(1)
                }
            }
        }
        if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    suspend fun read(sessionId: String, startMs: Long, endMs: Long, maxPoints: Int): List<ReplayPoseSample> {
        require(maxPoints in 2..10_000) { "Replay trace point budget must be in 2..10000" }
        require(endMs >= startMs) { "Replay trace end must not precede start" }
        if (sessionId.isBlank()) return emptyList()
        return transactions.readTelemetry(sessionId) {
            currentCoroutineContext().ensureActive()
            transactions.readConnectionFor(sessionId).prepareStatement(SQL).use { statement ->
                var parameter = 1
                for ((source, keys) in SOURCE_KEYS.withIndex()) {
                    for ((component, key) in keys.withIndex()) {
                        statement.setInt(parameter++, source)
                        statement.setInt(parameter++, component)
                        statement.setString(parameter++, key)
                    }
                }
                statement.setString(parameter++, sessionId)
                statement.setLong(parameter++, startMs)
                statement.setLong(parameter++, endMs)
                statement.setInt(parameter++, maxPoints - 1)
                statement.setInt(parameter, maxPoints)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            currentCoroutineContext().ensureActive()
                            add(
                                ReplayPoseSample(
                                    x = result.getDouble(1),
                                    y = result.getDouble(2),
                                    headingRadians = result.getDouble(3),
                                    timestampMs = result.getLong(4),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val SOURCE_KEYS = listOf(
            (0..9).map { "ARES/SimulatorPoseFrame/$it" },
            (0..2).map { "ARES/TruePose/$it" },
            (0..2).map { "ARES/EstimatedPose/$it" },
            listOf("Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading"),
            listOf("Drive/Pose_X", "Drive/Pose_Y", "Drive/Drive_Heading"),
        )

        // Keep invalid rows until completeness/uniqueness checks: discarding them earlier could
        // make an ambiguous timestamp look unique. sample_order is often per scalar, not per frame.
        val SQL = """
            WITH rules(source, component, key) AS (VALUES ${SOURCE_KEYS.flatten().joinToString { "(?, ?, ?)" }}),
            matching AS MATERIALIZED (
                SELECT r.source, r.component, t.timestamp_ms, t.timestamp_us, t.sample_order, t.value,
                    COALESCE(t.string_value IS NULL AND isfinite(t.value) AND
                        (r.component <> 9 OR (t.value BETWEEN 0 AND 9007199254740991
                            AND t.value = FLOOR(t.value))), FALSE) AS valid
                FROM telemetry_frames t JOIN rules r ON t.key = r.key
                WHERE t.session_id = ? AND t.timestamp_ms BETWEEN ? AND ?
            ),
            exact AS MATERIALIZED (
                SELECT source, MAX(timestamp_ms) AS timestamp_ms, timestamp_us, sample_order,
                    MAX(value) FILTER (WHERE component = 0) AS x,
                    MAX(value) FILTER (WHERE component = 1) AS y,
                    MAX(value) FILTER (WHERE component = 2) AS heading
                FROM matching GROUP BY source, timestamp_us, sample_order
                HAVING COUNT(*) = CASE WHEN source = 0 THEN 10 ELSE 3 END
                    AND COUNT(DISTINCT component) = COUNT(*) AND BOOL_AND(valid)
            ),
            unique_instants AS (
                SELECT source, MAX(timestamp_ms) AS timestamp_ms, timestamp_us, MAX(sample_order) AS sample_order,
                    MAX(value) FILTER (WHERE component = 0) AS x,
                    MAX(value) FILTER (WHERE component = 1) AS y,
                    MAX(value) FILTER (WHERE component = 2) AS heading
                FROM matching GROUP BY source, timestamp_us
                HAVING COUNT(*) = CASE WHEN source = 0 THEN 10 ELSE 3 END
                    AND COUNT(DISTINCT component) = COUNT(*) AND BOOL_AND(valid)
            ),
            poses AS MATERIALIZED (
                SELECT * FROM exact
                UNION ALL
                SELECT u.* FROM unique_instants u
                WHERE NOT EXISTS (SELECT 1 FROM exact e
                    WHERE e.source = u.source AND e.timestamp_us = u.timestamp_us)
            ),
            numbered AS MATERIALIZED (
                SELECT x, y, heading, timestamp_ms, ROW_NUMBER() OVER (ORDER BY timestamp_us, sample_order) AS ordinal
                FROM poses WHERE source = (SELECT MIN(source) FROM poses)
            ),
            buckets AS (
                SELECT ordinal, NTILE(?) OVER (ORDER BY ordinal) AS bucket
                FROM numbered WHERE ordinal > 1
            ),
            selected AS (
                SELECT MIN(ordinal) AS ordinal FROM numbered
                UNION ALL
                SELECT MAX(ordinal) FROM buckets GROUP BY bucket
            )
            SELECT n.x, n.y, n.heading, n.timestamp_ms FROM numbered n JOIN selected s USING (ordinal)
            ORDER BY n.ordinal LIMIT ?
        """.trimIndent()
    }
}
