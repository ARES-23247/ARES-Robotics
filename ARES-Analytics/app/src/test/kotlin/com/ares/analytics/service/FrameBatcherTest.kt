package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals

class FrameBatcherTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun database() = DatabaseService(temporary.root.resolve("frames.duckdb").absolutePath)
    private fun frame(time: Long, key: String = "Drive/X") = TelemetryFrame(time, "session", key, 1.0)

    @Test
    fun `batch size must be positive`() {
        val database = database()
        try {
            for (size in listOf(0, -1, Int.MIN_VALUE)) {
                assertFailsWith<IllegalArgumentException> { FrameBatcher(database, size) }
            }
        } finally { database.close() }
    }

    @Test
    fun `failed transform does not alter accepted frame statistics`() = runBlocking {
        val database = database()
        try {
            val batcher = FrameBatcher(database, 2) { key ->
                require(key != "bad") { "rejected key" }
                key
            }
            batcher.add(frame(100))
            for (time in listOf(0L, 200L)) {
                assertFailsWith<IllegalArgumentException> { batcher.add(frame(time, "bad")) }
                assertEquals(100, batcher.minTimestamp)
                assertEquals(100, batcher.maxTimestamp)
                assertEquals(1, batcher.frameCount)
            }
            batcher.flush()
            assertEquals(1, database.countTelemetryFrames("session"))
        } finally { database.close() }
    }



    @Test
    fun `batch threshold flush and final flush preserve bounds count and transformed keys`() = runTest {
        val tempDir = Files.createTempDirectory("ares-frame-batcher").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val batcher = FrameBatcher(
                databaseService = database,
                batchSize = 2,
                keyTransform = { it.removePrefix("/").uppercase() }
            )

            batcher.add(TelemetryFrame(500, "session", "/drive/x", 1.0))
            batcher.add(TelemetryFrame(100, "session", "/drive/y", 2.0))
            batcher.add(TelemetryFrame(900, "session", "/drive/z", 3.0))

            assertEquals(3, batcher.frameCount)
            assertEquals(100L, batcher.minTimestamp)
            assertEquals(900L, batcher.maxTimestamp)
            assertEquals(2, database.countTelemetryFrames("session"))

            batcher.flush()
            batcher.flush()

            assertEquals(3, batcher.frameCount)
            assertEquals(3, database.countTelemetryFrames("session"))
            assertEquals(3.0, database.getTelemetryForKey("session", "DRIVE/Z").single().value)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `multi-session frame batching isolates data across sessions and handles empty flushes`() = runTest {
        val tempDir = Files.createTempDirectory("ares-batcher-multi-session").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val batcher = FrameBatcher(
                databaseService = database,
                batchSize = 5,
                keyTransform = { it.removePrefix("/") }
            )

            // Flush before any data is added - verify zero error on empty batch
            batcher.flush()
            assertEquals(0, batcher.frameCount)

            // Insert frames for two different sessions
            batcher.add(TelemetryFrame(100, "session_1", "/Drive/Pose_X", 1.5))
            batcher.add(TelemetryFrame(100, "session_2", "/Drive/Pose_X", 4.2))
            batcher.flush()

            assertEquals(1, database.countTelemetryFrames("session_1"))
            assertEquals(1, database.countTelemetryFrames("session_2"))
            assertEquals(1.5, database.getTelemetryForKey("session_1", "Drive/Pose_X").single().value)
            assertEquals(4.2, database.getTelemetryForKey("session_2", "Drive/Pose_X").single().value)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
