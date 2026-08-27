package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class JsonlLogDecoderTest {
    @Test
    fun `telemetry sink cancellation is rethrown instead of counted as a rejected line`() = runTest {
        val tempDb = File.createTempFile("jsonl_cancel", ".db")
        val log = File.createTempFile("telemetry_cancel", ".jsonl").apply {
            writeText("""{"timestampMs":1000,"Drive/Pose_X":1.5}""")
        }
        val database = DatabaseService(tempDb.absolutePath)
        val cancellation = CancellationException("stop import")
        try {
            val thrown = assertFailsWith<CancellationException> {
                JsonlLogDecoder(database).parseJsonlLog(log, "session") {
                    throw cancellation
                }
            }
            assertSame(cancellation, thrown)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `valid non-object line is skipped without stalling the reader`() = runTest {
        val tempDb = File.createTempFile("jsonl_decoder", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("telemetry", ".jsonl").apply {
            deleteOnExit()
            writeText("[]\n{\"timestampMs\":1000,\"Drive/Pose_X\":1.5}\n")
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            val batcher = FrameBatcher(database)
            withTimeout(2_000) {
                JsonlLogDecoder(database).parseJsonlLog(log, "session", batcher)
            }
            batcher.flush()

            val frames = database.getTelemetryForKey("session", "Drive/Pose_X")
            assertEquals(1, frames.size)
            assertEquals(1.5, frames.single().value)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `booleans are numeric and imported keys are normalized`() = runTest {
        val tempDb = File.createTempFile("jsonl_boolean", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("telemetry_boolean", ".jsonl").apply {
            deleteOnExit()
            writeText("""{"timestampMs":1000,"/Robot/Enabled":true}""")
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            val batcher = FrameBatcher(database)
            val accepted = JsonlLogDecoder(database).parseJsonlLog(log, "session", batcher)
            batcher.flush()

            val frame = database.getTelemetryForKey("session", "Robot/Enabled").single()
            assertEquals(1, accepted)
            assertEquals(1.0, frame.value)
            assertNull(frame.stringValue)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `log with no usable telemetry is rejected`() = runTest {
        val tempDb = File.createTempFile("jsonl_invalid", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("telemetry_invalid", ".jsonl").apply {
            deleteOnExit()
            writeText("[]\nnot-json\n{\"missingTimestamp\":1}\n")
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            val batcher = FrameBatcher(database)
            assertFailsWith<IllegalArgumentException> {
                JsonlLogDecoder(database).parseJsonlLog(log, "session", batcher)
            }
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `oversized action count is rejected before any database insert`() = runTest {
        val tempDb = File.createTempFile("jsonl_actions_count", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("actions_count", ".jsonl").apply {
            deleteOnExit()
            bufferedWriter().use { writer ->
                repeat(10_001) { index ->
                    writer.append(actionLine(index.toLong() + 1L)).append('\n')
                }
            }
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            assertFailsWith<IllegalArgumentException> {
                JsonlLogDecoder(database, maxActionRecords = 10_000)
                    .parseActionLogJsonl(log, "session")
            }
            assertEquals(0, database.getActionsForSession("session").size)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `oversized action line is rejected before any database insert`() = runTest {
        val tempDb = File.createTempFile("jsonl_actions_line", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("actions_line", ".jsonl").apply {
            deleteOnExit()
            writeText("x".repeat(1_048_577))
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            assertFailsWith<IllegalArgumentException> {
                JsonlLogDecoder(database).parseActionLogJsonl(log, "session")
            }
            assertEquals(0, database.getActionsForSession("session").size)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `invalid action timestamp domains are rejected without rows`() = runTest {
        val tempDb = File.createTempFile("jsonl_actions_time", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("actions_time", ".jsonl").apply {
            deleteOnExit()
            writeText(actionLine(-1L) + "\n" + actionLine(Long.MAX_VALUE))
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            assertFailsWith<IllegalArgumentException> {
                JsonlLogDecoder(database).parseActionLogJsonl(log, "session")
            }
            assertEquals(0, database.getActionsForSession("session").size)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `missing future and non-integer action schema versions reject transactionally`() = runTest {
        val tempDb = File.createTempFile("jsonl_actions_schema", ".db").apply { deleteOnExit() }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            val invalidSchemaFields = listOf(
                null,
                "\"schema_version\":2",
                "\"schema_version\":1.0",
                "\"schema_version\":\"1\"",
            )
            invalidSchemaFields.forEachIndexed { index, schemaField ->
                val sessionId = "schema-$index"
                val log = File.createTempFile("actions_schema_$index", ".jsonl").apply {
                    deleteOnExit()
                    writeText(actionLine(1L) + "\n" + actionLine(2L, schemaField))
                }

                assertFailsWith<IllegalArgumentException> {
                    JsonlLogDecoder(database).parseActionLogJsonl(log, sessionId)
                }
                assertEquals(0, database.getActionsForSession(sessionId).size)
            }
        } finally {
            database.close()
            tempDb.delete()
        }
    }

    @Test
    fun `multi-signal telemetry lines parse all keys with normalized path formatting`() = runTest {
        val tempDb = File.createTempFile("jsonl_multisignal", ".db").apply { deleteOnExit() }
        val log = File.createTempFile("telemetry_multisignal", ".jsonl").apply {
            deleteOnExit()
            writeText("""{"timestampMs":1000,"/Drive/Pose_X":1.5,"/Drive/Pose_Y":-2.0,"/Drive/Pose_Heading":0.785}""")
        }
        val database = DatabaseService(tempDb.absolutePath)
        try {
            val batcher = FrameBatcher(database)
            val count = JsonlLogDecoder(database).parseJsonlLog(log, "session", batcher)
            batcher.flush()

            assertEquals(3, count)
            assertEquals(1.5, database.getTelemetryForKey("session", "Drive/Pose_X").single().value)
            assertEquals(-2.0, database.getTelemetryForKey("session", "Drive/Pose_Y").single().value)
            assertEquals(0.785, database.getTelemetryForKey("session", "Drive/Pose_Heading").single().value)
        } finally {
            database.close()
            log.delete()
            tempDb.delete()
        }
    }

    private fun actionLine(
        timestampMs: Long,
        schemaField: String? = "\"schema_version\":1",
    ): String {
        val schemaPrefix = schemaField?.let { "$it," }.orEmpty()
        return """{$schemaPrefix"run_id":"run","robot_id":"robot","match_number":1,"alliance":"RED","type":"Drive","payload":{"timestampMs":$timestampMs}}"""
    }
}
