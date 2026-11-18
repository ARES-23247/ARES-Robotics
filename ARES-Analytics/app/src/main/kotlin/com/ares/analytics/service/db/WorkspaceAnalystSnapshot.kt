package com.ares.analytics.service.db

import com.ares.analytics.shared.models.WorkspaceConfig
import java.sql.Connection

/** Only these projections leave the desktop database. Model SQL runs in a separate in-memory database. */
internal object WorkspaceAnalystSnapshot {
    private val metricColumns = mapOf(
        "min_battery_voltage" to "battery", "max_ekf_drift" to "drift",
        "avg_loop_time_ms" to "loop", "p95_loop_time_ms" to "loop_p95",
        "vision_acceptance_rate" to "acceptance", "avg_cross_track_error" to "cross_track",
        "avg_battery_resistance" to "resistance", "avg_vision_latency_ms" to "latency",
    )
    private const val identity = "session_id,team_id,season_id,robot_id,created_at,duration_ms"
    private val tables = mapOf(
        "sessions" to "$identity,tags,match_number,alliance_color",
        "session_summaries" to "$identity,motor_current_averages,max_motor_temps," + metricColumns.keys.joinToString(","),
        "alerts" to "alert_id,session_id,rule_key,trigger_timestamp_ms,resolve_timestamp_ms,duration_ms,peak_value,triaged",
    )

    fun copy(source: Connection, isolated: Connection, workspace: WorkspaceConfig) {
        for ((table, columns) in tables) {
            val projection = columns.split(',').joinToString { column ->
                val metric = metricColumns[column]
                if (metric == null) "t.$column" else
                    """CASE WHEN t.observed_metrics IS NULL THEN NULLIF(t.$column, 0)
                       WHEN json_contains(t.observed_metrics, '"$metric"') THEN t.$column END AS $column"""
            }
            val sameOwner = if (table == "session_summaries") {
                "AND t.team_id = s.team_id AND t.season_id = s.season_id AND t.robot_id = s.robot_id"
            } else ""
            val sql = """SELECT $projection FROM $table t JOIN sessions s ON t.session_id = s.session_id
                WHERE s.team_id = ? AND s.season_id = ? AND s.robot_id = ? AND s.import_state = 'COMPLETE' $sameOwner"""
            source.prepareStatement(sql).use { select ->
                select.setString(1, workspace.teamId)
                select.setString(2, workspace.seasonId)
                select.setString(3, workspace.robotId)
                select.executeQuery().use { rows ->
                    val metadata = rows.metaData
                    val indexes = 1..metadata.columnCount
                    val definition = indexes.joinToString { metadata.getColumnName(it) + " " + metadata.getColumnTypeName(it) }
                    isolated.createStatement().use { it.execute("CREATE TABLE $table ($definition)") }
                    val placeholders = indexes.joinToString { "?" }
                    isolated.prepareStatement("INSERT INTO $table VALUES ($placeholders)").use { insert ->
                        var pending = 0
                        while (rows.next()) {
                            for (column in indexes) insert.setObject(column, rows.getObject(column))
                            insert.addBatch()
                            if (++pending == 256) {
                                insert.executeBatch()
                                pending = 0
                            }
                        }
                        if (pending > 0) insert.executeBatch()
                    }
                }
            }
        }
    }
}
