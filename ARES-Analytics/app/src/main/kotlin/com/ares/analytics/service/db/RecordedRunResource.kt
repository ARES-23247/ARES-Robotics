package com.ares.analytics.service.db

import com.ares.analytics.service.metricEvidence
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.areslib.project.AresProjectIdentityDocument
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.ResultSet

/** Every detail query shares the same database snapshot as its workspace membership check. */
internal class RecordedRunResource(
    private val transactions: DatabaseTransactionCoordinator,
    private val metadata: SessionMetadataRepository,
) {
    suspend fun read(scope: AresProjectIdentityDocument, id: String?): JsonObject? = transactions.readMetadata {
        val conn = transactions.metadataReadConnection
        conn.inReadSnapshot {
            val filter = if (id == null) "" else "AND session_id = ?"
            val sessions = conn.prepareStatement("""
                SELECT session_id, created_at, duration_ms, count(*) OVER () AS total FROM sessions
                WHERE team_id = ? AND season_id = ? AND robot_id = ? AND import_state = 'COMPLETE' $filter
                ORDER BY created_at DESC, session_id LIMIT 100
            """).use { query ->
                query.setString(1, scope.teamId)
                query.setString(2, scope.seasonId)
                query.setString(3, scope.robotId)
                if (id != null) query.setString(4, id)
                query.executeQuery().use { rows ->
                    var total = 0
                    val runs = buildJsonArray {
                        while (rows.next()) {
                            total = rows.getInt("total")
                            add(buildJsonObject {
                                put("sessionId", rows.getString("session_id"))
                                put("createdAtMs", rows.getLong("created_at"))
                                put("durationMs", rows.getLong("duration_ms"))
                            })
                        }
                    }
                    total to runs
                }
            }
            if (id != null && sessions.first == 0) return@inReadSnapshot null
            buildJsonObject {
                put("teamId", scope.teamId)
                put("seasonId", scope.seasonId)
                put("robotId", scope.robotId)
                if (id == null) {
                    put("totalRuns", sessions.first)
                    put("runs", sessions.second)
                } else {
                    sessions.second.single().jsonObject.forEach { (key, value) -> put(key, value) }
                    put("metrics", conn.forSession("SELECT * FROM session_summaries WHERE session_id = ?", id) { rows ->
                        val summary = if (rows.next()) with(metadata) { rows.toSessionSummary() }.takeIf {
                            it.teamId == scope.teamId && it.seasonId == scope.seasonId && it.robotId == scope.robotId
                        } else null
                        summary.metricEvidence()
                    })
                    conn.forSession("SELECT min(timestamp_ms), max(timestamp_ms) FROM telemetry_frames WHERE session_id = ?", id) { rows ->
                        rows.next()
                        put("firstSampleMs", rows.getObject(1) as? Long)
                        put("lastSampleMs", rows.getObject(2) as? Long)
                    }
                    conn.forSession("""SELECT key, count(*) OVER (), numeric_samples, low, high FROM (
                        SELECT key, count(value) FILTER (WHERE string_value IS NULL AND isfinite(value)) AS numeric_samples,
                            min(value) FILTER (WHERE string_value IS NULL AND isfinite(value)) AS low,
                            max(value) FILTER (WHERE string_value IS NULL AND isfinite(value)) AS high
                        FROM telemetry_frames WHERE session_id = ? GROUP BY key) ORDER BY key LIMIT 256""", id) { rows ->
                        var total = 0
                        putJsonArray("topics") {
                            while (rows.next()) {
                                total = rows.getInt(2)
                                val key = rows.getString(1)
                                add(buildJsonObject {
                                    put("key", key)
                                    put("numericSamples", rows.getLong(3))
                                    put("minimum", rows.getObject(4) as? Double)
                                    put("maximum", rows.getObject(5) as? Double)
                                    put("unit", TelemetryMetricCatalog.metricFor(key)?.unit?.name ?: "unknown")
                                })
                            }
                        }
                        put("totalTopics", total)
                    }
                    conn.forSession("""SELECT source_sha256, count(*) OVER () FROM
                        (SELECT DISTINCT source_sha256 FROM session_import_reports WHERE session_id = ?)
                        ORDER BY source_sha256 LIMIT 64""", id) { rows ->
                        var total = 0
                        putJsonArray("sourceSha256") {
                            while (rows.next()) {
                                total = rows.getInt(2)
                                add(rows.getString(1))
                            }
                        }
                        put("totalSources", total)
                    }
                }
            }
        }
    }

    private fun <T> Connection.forSession(sql: String, id: String, read: (ResultSet) -> T): T =
        prepareStatement(sql).use { query ->
            query.setString(1, id)
            query.executeQuery().use(read)
        }
}
