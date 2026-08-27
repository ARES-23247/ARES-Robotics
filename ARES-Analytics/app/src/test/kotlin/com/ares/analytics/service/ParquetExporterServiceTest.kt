package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParquetExporterServiceTest {

    @Test
    fun `exports through dedicated API and restores numeric and string values`() = runTest {
        val tempDir = Files.createTempDirectory("ares-parquet-export-test").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val sessionId = "session-with-'quote"
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, sessionId, "Drive/X", 2.5),
                    TelemetryFrame(1000, sessionId, "Robot/Mode", 0.0, "AUTO")
                )
            )
            val destination = tempDir.resolve("folder-with-'quote/session.parquet")

            ParquetExporterService(database).exportSessionToParquet(sessionId, destination)

            assertTrue(destination.isFile)
            assertTrue(destination.length() > 0)

            database.deleteTelemetryFrames(sessionId)
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, sessionId, "Drive/X", -1.0),
                    TelemetryFrame(1000, sessionId, "Robot/Mode", 0.0, "STALE")
                )
            )
            database.importParquet(destination)

            assertEquals(2.5, database.getTelemetryForKey(sessionId, "Drive/X").single().value)
            assertEquals("AUTO", database.getTelemetryForKey(sessionId, "Robot/Mode").single().stringValue)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
