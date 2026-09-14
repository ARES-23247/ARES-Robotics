package com.ares.analytics.service.db

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ReplayPoseSample(val x: Double, val y: Double, val headingRadians: Double)

/**
 * Selects one complete source across the requested window, then samples whole poses in order.
 * SQL keeps raw history inside DuckDB and transfers at most maxPoints triples to the JVM.
 */
internal class ReplayPoseRepository(private val transactions: DatabaseTransactionCoordinator) {
    suspend fun read(sessionId: String, startMs: Long, endMs: Long, maxPoints: Int): List<ReplayPoseSample> {
        require(maxPoints in 2..10_000) { "Replay trace point budget must be in 2..10000" }
        require(endMs >= startMs) { "Replay trace end must not precede start" }
        if (sessionId.isBlank()) return emptyList()
        return transactions.read {
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
                            add(ReplayPoseSample(result.getDouble(1), result.getDouble(2), result.getDouble(3)))
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
                SELECT r.source, r.component, t.timestamp_us, t.sample_order, t.value,
                    COALESCE(t.string_value IS NULL AND isfinite(t.value) AND
                        (r.component <> 9 OR (t.value BETWEEN 0 AND 9007199254740991
                            AND t.value = FLOOR(t.value))), FALSE) AS valid
                FROM telemetry_frames t JOIN rules r ON t.key = r.key
                WHERE t.session_id = ? AND t.timestamp_ms BETWEEN ? AND ?
            ),
            exact AS MATERIALIZED (
                SELECT source, timestamp_us, sample_order,
                    MAX(value) FILTER (WHERE component = 0) AS x,
                    MAX(value) FILTER (WHERE component = 1) AS y,
                    MAX(value) FILTER (WHERE component = 2) AS heading
                FROM matching GROUP BY source, timestamp_us, sample_order
                HAVING COUNT(*) = CASE WHEN source = 0 THEN 10 ELSE 3 END
                    AND COUNT(DISTINCT component) = COUNT(*) AND BOOL_AND(valid)
            ),
            unique_instants AS (
                SELECT source, timestamp_us, MAX(sample_order) AS sample_order,
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
                SELECT x, y, heading, ROW_NUMBER() OVER (ORDER BY timestamp_us, sample_order) AS ordinal
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
            SELECT n.x, n.y, n.heading FROM numbered n JOIN selected s USING (ordinal)
            ORDER BY n.ordinal LIMIT ?
        """.trimIndent()
    }
}
