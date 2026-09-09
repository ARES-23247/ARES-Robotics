package com.ares.analytics.service.db

import java.sql.Connection

/**
 * Peak-normalized sample counts over equal-width time intervals. Interior bins are half-open;
 * the final bin includes the maximum timestamp. A single instant occupies the first bin.
 * Bounds and counts come from one SQL statement/snapshot. Only aggregated bins enter JVM memory.
 */
internal fun loadTelemetryDensity(connection: Connection, sessionId: String, buckets: Int): List<Float> {
    require(buckets in 1..10_000) { "Telemetry density requires between 1 and 10000 buckets" }
    val counts = LongArray(buckets)
    connection.prepareStatement(
        """
        WITH samples AS (SELECT timestamp_ms FROM telemetry_frames WHERE session_id = ?),
        bounds AS (SELECT MIN(timestamp_ms) AS lo, MAX(timestamp_ms) AS hi FROM samples)
        SELECT CASE WHEN hi = lo THEN 0 ELSE
            LEAST(?, ((CAST(timestamp_ms AS HUGEINT) - lo) * ?) // (CAST(hi AS HUGEINT) - lo))
            END AS bucket_idx, COUNT(*) AS cnt
        FROM samples CROSS JOIN bounds
        GROUP BY bucket_idx
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setInt(2, buckets - 1)
        statement.setInt(3, buckets)
        statement.executeQuery().use { rows ->
            while (rows.next()) counts[rows.getInt(1)] = rows.getLong(2)
        }
    }
    val peak = counts.max()
    return if (peak == 0L) List(buckets) { 0f } else counts.map { (it.toDouble() / peak).toFloat() }
}
