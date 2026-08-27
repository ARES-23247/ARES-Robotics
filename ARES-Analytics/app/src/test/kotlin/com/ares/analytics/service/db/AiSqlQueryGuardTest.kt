package com.ares.analytics.service.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiSqlQueryGuardTest {
    @Test
    fun `allows bounded analysis over documented summary tables`() {
        val sql = """
            SELECT s.match_number, ROUND(AVG(ss.avg_loop_time_ms), 2) AS mean_loop
            FROM sessions s
            JOIN session_summaries ss ON ss.session_id = s.session_id
            WHERE s.team_id = '23247' AND s.match_number IN (1, 2, 3)
            GROUP BY s.match_number
            ORDER BY s.match_number
            LIMIT 20
        """.trimIndent()

        assertEquals(sql, AiSqlQueryGuard.validate(sql))
    }

    @Test
    fun `rejects every local file and external scan primitive`() {
        listOf(
            "SELECT * FROM read_text('C:/Users/user/.ssh/id_rsa')",
            "SELECT * FROM read_blob('C:/Users/user/secret.bin')",
            "SELECT * FROM read_csv_auto('C:/Users/user/private.csv')",
            "SELECT * FROM read_parquet('C:/Users/user/private.parquet')",
            "SELECT * FROM glob('C:/Users/user/*')",
            "SELECT * FROM 'C:/Users/user/private.csv'",
            "SELECT * FROM range(1000000000000)",
        ).forEach { sql ->
            assertFailsWith<IllegalArgumentException>(sql) { AiSqlQueryGuard.validate(sql) }
        }
    }

    @Test
    fun `rejects alternate statements subqueries cross joins and undocumented tables`() {
        listOf(
            "WITH leaked AS (SELECT * FROM sessions) SELECT * FROM leaked",
            "SELECT * FROM sessions UNION SELECT * FROM alerts",
            "SELECT * FROM sessions CROSS JOIN alerts",
            "SELECT * FROM sessions, alerts",
            "SELECT * FROM (SELECT * FROM sessions) nested",
            "SELECT * FROM telemetry_frames",
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM sessions; SELECT * FROM alerts",
            "SELECT * FROM sessions -- hide a second statement",
        ).forEach { sql ->
            assertFailsWith<IllegalArgumentException>(sql) { AiSqlQueryGuard.validate(sql) }
        }
    }
}
