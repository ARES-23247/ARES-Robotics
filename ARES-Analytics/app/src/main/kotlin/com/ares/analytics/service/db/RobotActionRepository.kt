package com.ares.analytics.service.db

import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import org.duckdb.DuckDBConnection
import java.sql.Connection

/** Stores and retrieves the Redux action timeline associated with a recorded run. */
internal class RobotActionRepository(
    private val transactions: DatabaseTransactionCoordinator,
) {
    private val connection: Connection get() = transactions.writeConnection

    suspend fun insert(actions: List<RobotActionRecord>) = transactions.write {
        if (actions.isEmpty()) return@write
        withDuckDbAppenderTransaction(connection) {
            val duckConnection = connection.unwrap(DuckDBConnection::class.java)
            duckConnection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "robot_actions").use { appender ->
                for (action in actions) {
                    validate(action)
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
            }
        }
    }

    suspend fun getForSession(sessionId: String): List<RobotActionRecord> = transactions.read {
        val actions = mutableListOf<RobotActionRecord>()
        transactions.readConnection.prepareStatement(
            "SELECT timestamp_ms, session_id, run_id, robot_id, match_number, alliance, action_type, payload_json " +
                "FROM robot_actions WHERE session_id = ? ORDER BY timestamp_ms, run_id, action_type, payload_json, robot_id, match_number, alliance",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val action = RobotActionRecord(
                        timestampMs = rows.getLong("timestamp_ms"),
                        sessionId = rows.getString("session_id"),
                        runId = rows.getString("run_id"),
                        robotId = rows.getString("robot_id"),
                        matchNumber = rows.getInt("match_number"),
                        alliance = rows.getString("alliance"),
                        actionType = rows.getString("action_type"),
                        payloadJson = rows.getString("payload_json"),
                    )
                    validate(action)
                    actions += action
                }
            }
        }
        actions
    }

    private fun validate(action: RobotActionRecord) {
        require(action.timestampMs in 0L..MAX_SUPPORTED_TIMESTAMP_MS) { "Action timestamp is outside the supported domain" }
        require(action.sessionId.isNotBlank()) { "Action session ID must not be blank" }
    }
}
