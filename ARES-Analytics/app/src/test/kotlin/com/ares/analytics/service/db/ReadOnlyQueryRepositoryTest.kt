package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadOnlyQueryRepositoryTest {
    @Test
    fun `allows comments and string literals with semicolons while rejecting statement smuggling`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("ares-readonly-sql-")
        val database = DatabaseService(dir.resolve("telemetry.duckdb").toString())
        try {
            val literalResult = database.executeQueryRaw(
                """
                SELECT 'a; DROP TABLE sessions' AS note, 42 AS "drop" -- trailing comment after semicolon
                ; -- comment at end
                """.trimIndent(),
            )
            assertEquals(listOf("note", "drop"), literalResult.columns)
            assertEquals(listOf(listOf("a; DROP TABLE sessions", "42")), literalResult.rows)

            val blockCommentResult = database.executeQueryRaw("SELECT /* nested /* ; */ comment */ 7 AS ok")
            assertEquals(listOf(listOf("7")), blockCommentResult.rows)

            listOf(
                "SELECT 1; DROP TABLE sessions",
                "SELECT 1 /* ; */ ; SELECT 2",
                "SELECT 'a'; SELECT 2",
                "CHECKPOINT",
                "FORCE CHECKPOINT",
                "SELECT 1; CHECKPOINT",
                "SELECT 'unterminated",
                "SELECT /* unterminated comment",
                "SELECT \$\$unterminated",
            ).forEach { invalid ->
                assertFailsWith<IllegalArgumentException>(invalid) {
                    database.executeQueryRaw(invalid)
                }
            }

            assertFailsWith<IllegalArgumentException> {
                database.executeQueryWithParams("DELETE FROM sessions WHERE session_id = ?", listOf("s1"))
            }
            assertFailsWith<IllegalArgumentException> {
                database.executeQueryWithParams("SELECT 1; DELETE FROM sessions WHERE session_id = ?", listOf("s1"))
            }

            val paramResult = database.executeQueryWithParams("SELECT ? AS echoed", listOf("hello"))
            assertEquals(listOf(listOf("hello")), paramResult.rows)
        } finally {
            database.closeAndJoin()
        }

        try {
            assertFailsWith<SQLException> { database.executeQueryRaw("SELECT 1") }
            assertFailsWith<SQLException> { database.executeQueryWithParams("SELECT ?", listOf(1)) }
            assertFailsWith<SQLException> { database.getSessions() }
        } finally {
            dir.toFile().deleteRecursively()
        }
        Unit
    }
}
