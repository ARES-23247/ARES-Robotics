package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class ParquetLogDecoderTest {

    @Test
    fun `native import remaps source rows to caller session and preserves strings`() = runBlocking {
        val sourceDbFile = tempDatabaseFile("parquet-source")
        val targetDbFile = tempDatabaseFile("parquet-target")
        val parquet = File.createTempFile("ares-telemetry-", ".parquet")
        val source = DatabaseService(sourceDbFile.absolutePath)
        val target = DatabaseService(targetDbFile.absolutePath)
        try {
            source.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(100L, "source-session", "Drive/Pose_X", 1.25),
                    TelemetryFrame(250L, "source-session", "Robot/Mode", 0.0, "AUTO")
                )
            )
            source.exportSessionToParquet("source-session", parquet)

            val result = ParquetLogDecoder(target).parseParquetLog(parquet, "imported-session")
            val imported = target.getTelemetryRange("imported-session", 0L, Long.MAX_VALUE)

            assertEquals(2L, result.frameCount)
            assertEquals(100L, result.minTimestampMs)
            assertEquals(250L, result.maxTimestampMs)
            assertEquals(setOf("imported-session"), imported.map { it.sessionId }.toSet())
            assertEquals("AUTO", imported.single { it.key == "Robot/Mode" }.stringValue)
            assertEquals(1.25, imported.single { it.key == "Drive/Pose_X" }.value)
        } finally {
            source.close()
            target.close()
            parquet.delete()
            sourceDbFile.delete()
            targetDbFile.delete()
        }
    }

    @Test
    fun `parquet import preserves sample order and microsecond timestamps`() = runBlocking {
        val sourceDbFile = tempDatabaseFile("parquet-source-order")
        val targetDbFile = tempDatabaseFile("parquet-target-order")
        val parquet = File.createTempFile("ares-telemetry-order-", ".parquet")
        val source = DatabaseService(sourceDbFile.absolutePath)
        val target = DatabaseService(targetDbFile.absolutePath)
        try {
            source.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(100L, "src", "Drive/Velocity", 2.5, timestampUs = 100_123L, sampleOrder = 1L),
                    TelemetryFrame(120L, "src", "Drive/Velocity", 2.6, timestampUs = 120_456L, sampleOrder = 2L)
                )
            )
            source.exportSessionToParquet("src", parquet)

            val result = ParquetLogDecoder(target).parseParquetLog(parquet, "dest")
            val imported = target.getTelemetryForKey("dest", "Drive/Velocity")

            assertEquals(2L, result.frameCount)
            assertEquals(listOf(100_123L, 120_456L), imported.map { it.timestampUs })
            assertEquals(listOf(1L, 2L), imported.map { it.sampleOrder })
        } finally {
            source.close()
            target.close()
            parquet.delete()
            sourceDbFile.delete()
            targetDbFile.delete()
        }
    }

    private fun tempDatabaseFile(prefix: String): File =
        File.createTempFile(prefix, ".duckdb").also { it.delete() }
}
