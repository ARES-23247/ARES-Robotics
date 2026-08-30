package com.ares.analytics.service

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.ForensicsRequest
import com.ares.analytics.shared.models.ForensicsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** AI interpretation of telemetry evidence. Sync and cloud-storage concerns are deliberately absent. */
class AiDiagnosticsService(
    private val generativeAiService: GenerativeAiService,
    private val databaseService: DatabaseService,
) {
    suspend fun requestForensics(request: ForensicsRequest): ForensicsResponse = withContext(Dispatchers.IO) {
        val prompt = """
            You are ARES Pit Forensics AI, a diagnostic copilot for FTC/FRC robotics teams.
            Analyze the telemetry packet below. Distinguish correlation from proven cause.

            Respond ONLY with this JSON shape:
            {
              "probableRootCause": "Detailed description of what may have failed and why",
              "confidenceScore": 0.85,
              "cascadingNodesAffected": ["node_id"],
              "hardwareFaultLocus": {"failedNodeId":"node_id","interruptedLinkId":null},
              "recommendedActions": ["Evidence-based check"]
            }

            Data Packet:
            ${AppJson.encodeToString(ForensicsRequest.serializer(), request)}
        """.trimIndent()
        val response = generativeAiService.requestStructuredJson(prompt).stripJsonFence()
        runCatching { AppJson.decodeFromString<ForensicsResponse>(response) }.getOrElse {
            ForensicsResponse(
                probableRootCause = "AI produced unparseable diagnostics: $response",
                confidenceScore = 0.0,
                cascadingNodesAffected = emptyList(),
                hardwareFaultLocus = null,
                recommendedActions = listOf("Retry diagnostics", "Inspect the recorded evidence manually"),
            )
        }
    }

    suspend fun requestChatCoach(
        request: ForensicsRequest,
        userQuestion: String,
        chatHistory: List<Pair<String, String>> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val history = chatHistory.joinToString("\n") { (role, text) ->
            if (role == "user") "Student: $text" else "Coach: $text"
        }
        generativeAiService.requestText(
            """
                You are ARES Pit Coach, helping an FTC/FRC student interpret recorded evidence.
                Be concise and actionable. Clearly distinguish correlation from proven cause.

                Team: ${request.teamId}
                Session: ${request.sessionId}
                Triggered rules: ${request.alerts.joinToString { it.ruleKey }}

                Conversation:
                $history

                Student question: $userQuestion
            """.trimIndent()
        )
    }

    suspend fun requestSqlAnalysis(userQuestion: String): String = withContext(Dispatchers.IO) {
        val sqlEnvelope = generativeAiService.requestStructuredJson(
            """
                You are ARES SQL Data Analyst. Generate one read-only DuckDB query for the question.

                Available tables:
                sessions(session_id, team_id, season_id, robot_id, created_at, duration_ms, tags,
                         match_number, alliance_color)
                session_summaries(session_id, team_id, season_id, robot_id, created_at, duration_ms,
                         min_battery_voltage, max_ekf_drift, avg_loop_time_ms, p95_loop_time_ms,
                         motor_current_averages, vision_acceptance_rate, avg_cross_track_error,
                         avg_battery_resistance, max_motor_temps, avg_vision_latency_ms)
                alerts(alert_id, session_id, rule_key, trigger_timestamp_ms, resolve_timestamp_ms,
                         duration_ms, peak_value, triaged)

                Return only {"sql":"SELECT ..."}. WITH is allowed when it remains read-only.
                Do not use file, URL, extension, mutation, DDL, pragma, attach, or copy operations.

                Question: $userQuestion
            """.trimIndent()
        ).stripJsonFence()
        val sql = runCatching {
            AppJson.parseToJsonElement(sqlEnvelope).jsonObject["sql"]?.jsonPrimitive?.content
                ?: error("No SQL generated")
        }.getOrElse {
            return@withContext "I was unable to formulate a query for that question."
        }
        val result = try {
            databaseService.executeAiQuery(sql)
        } catch (blocked: IllegalArgumentException) {
            return@withContext "Security Error: ${blocked.message ?: "The generated query was blocked."}"
        } catch (failure: Exception) {
            return@withContext "Failed to execute the generated query:\n```sql\n$sql\n```\nError: ${failure.message}"
        }
        generativeAiService.requestText(
            """
                You are ARES SQL Data Analyst. Answer the student's question using only the query
                evidence below. State when the data supports correlation rather than causation.

                Question: $userQuestion
                Query: $sql
                Columns: ${result.columns.joinToString()}
                Rows:
                ${result.rows.joinToString("\n") { row -> row.joinToString() }}
            """.trimIndent()
        )
    }
}

private fun String.stripJsonFence(): String =
    replace(Regex("```(?:json)?\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL), "$1").trim()
