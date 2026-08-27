package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CsvLogDecoderTest {

    @Test
    fun `exact timestamp header wins over earlier LoopTime and preserves source units`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-timestamp-header").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("robot.csv")
            csv.writeText(
                "LoopTimeMs,TimestampUs,Drive/Pose_X\n9999,1500,1.25\n10000,2500,1.50"
            )

            CsvLogDecoder(database).parseCsvLogNative(csv, "session")

            val poses = database.getTelemetryForKey("session", "Drive/Pose_X")
            assertEquals(listOf(1L, 2L), poses.map { it.timestampMs })
            assertEquals(listOf(1_500L, 2_500L), poses.map { it.timestampUs })
            assertEquals(listOf(9_999.0, 10_000.0), database.getTelemetryForKey("session", "LoopTimeMs").map { it.value })
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `multiple exact timestamp columns are rejected as ambiguous`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-ambiguous-time").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("ambiguous.csv")
            csv.writeText("TimestampMs,TimeUs,Drive/Pose_X\n1000,1000000,1.25")

            assertFailsWith<IllegalArgumentException> {
                CsvLogDecoder(database).parseCsvLogNative(csv, "session")
            }
            assertEquals(0L, database.countTelemetryFrames("session"))
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `unitless timestamp header is rejected`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-unitless-time").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("unitless.csv")
            csv.writeText("Timestamp,Drive/Pose_X\n1000,1.25")

            assertFailsWith<IllegalArgumentException> {
                CsvLogDecoder(database).parseCsvLogNative(csv, "session")
            }
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `native imports into one session continue sample order across files`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-session-order").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val first = tempDir.resolve("first.csv").apply {
                writeText("TimestampMs,Drive/Velocity\n1000,1.0")
            }
            val second = tempDir.resolve("second.csv").apply {
                writeText("TimestampMs,Drive/Velocity\n1000,2.0")
            }
            val decoder = CsvLogDecoder(database)

            decoder.parseCsvLogNative(first, "session")
            decoder.parseCsvLogNative(second, "session")

            val frames = database.getTelemetryForKey("session", "Drive/Velocity")
            assertEquals(listOf(1.0, 2.0), frames.map { it.value })
            assertEquals(listOf(0L, 1L), frames.map { it.sampleOrder })
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `native import expands logger extra fields into telemetry keys`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-decoder-test").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("robot.csv")
            csv.writeText(
                """
                    TimestampMs,Drive/Pose_X,_ExtraFieldsJson
                    1000,1.25,{}
                    1020,1.50,"{""Late/Current"":12.5,""Late/State"":""ready"",""Late/Enabled"":true}"
                """.trimIndent()
            )

            CsvLogDecoder(database).parseCsvLogNative(csv, "session-1")

            val current = database.getTelemetryForKey("session-1", "Late/Current").single()
            val state = database.getTelemetryForKey("session-1", "Late/State").single()
            val enabled = database.getTelemetryForKey("session-1", "Late/Enabled").single()

            assertEquals(12.5, current.value)
            assertEquals("ready", state.stringValue)
            assertEquals(1.0, enabled.value)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `CSV without a timestamp column is rejected`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-invalid-test").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("invalid.csv")
            csv.writeText("Drive/Pose_X,Robot/Enabled\n1.25,true")

            assertFailsWith<IllegalArgumentException> {
                CsvLogDecoder(database).parseCsvLogNative(csv, "session-1")
            }
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `malformed CSV fails without importing a valid prefix`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-truncated-test").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("truncated.csv")
            csv.writeText(
                """
                    TimestampMs,Drive/Pose_X
                    1000,1.25
                    1020,"unterminated
                """.trimIndent()
            )

            assertFailsWith<Exception> {
                CsvLogDecoder(database).parseCsvLogNative(csv, "session-1")
            }
            assertEquals(0L, database.countTelemetryFrames("session-1"))
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `native import assigns deterministic order to normalized duplicate samples`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-duplicate-samples").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("duplicates.csv")
            csv.writeText(
                """
                    TimestampMs,/Drive/Velocity,Drive/Velocity
                    1000,1.0,2.0
                    1000,3.0,4.0
                """.trimIndent()
            )

            CsvLogDecoder(database).parseCsvLogNative(csv, "session")

            val frames = database.getTelemetryForKey("session", "Drive/Velocity")
            assertEquals(listOf(0L, 1L, 2L, 3L), frames.map { it.sampleOrder }.sorted())
            assertEquals(setOf(1.0, 2.0, 3.0, 4.0), frames.map { it.value }.toSet())
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `streaming parser accepts RFC 4180 quoted commas quotes newlines and trailing cells`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-rfc4180").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("quoted.csv")
            csv.writeText(
                "\"Timestamp,ms\",\"Drive,State\",Empty\r\n" +
                    "1000,\"ready, \"\"go\"\"\r\nnow\",\r\n"
            )
            val batcher = FrameBatcher(database)

            CsvLogDecoder(database).parseCsvLogStreaming(csv, "session", batcher)
            batcher.flush()

            val state = database.getTelemetryForKey("session", "Drive,State").single()
            assertEquals("ready, \"go\"\r\nnow", state.stringValue)
            assertEquals(0, database.getTelemetryForKey("session", "Empty").size)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `native import rejects negative and overflowing timestamps atomically`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-invalid-timestamps").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            listOf("-1", Long.MAX_VALUE.toString()).forEachIndexed { index, timestamp ->
                val csv = tempDir.resolve("invalid-$index.csv")
                csv.writeText("TimestampMs,Drive/Pose_X\n$timestamp,1.0")
                assertFailsWith<Exception> {
                    CsvLogDecoder(database).parseCsvLogNative(csv, "session-$index")
                }
                assertEquals(0L, database.countTelemetryFrames("session-$index"))
            }
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `canonical long-form rejects a malformed row without importing its valid prefix`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-canonical-invalid").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("lossless.csv")
            csv.writeText(
                """
                    key,timestamp_ms,timestamp_us,sample_order,value_type,numeric_value,string_value
                    Drive/Velocity,1000,1000123,41,double,3.25,
                    Status/Mode,1000,1000123,42,double,0.0,armed
                """.trimIndent()
            )

            assertFailsWith<Exception> {
                CsvLogDecoder(database).parseCsvLogNative(csv, "session")
            }
            assertEquals(0L, database.countTelemetryFrames("session"))
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `paired run fixtures remain importable through the production decoder`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-paired-fixtures").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            listOf("golden-run-a.csv", "golden-run-b.csv").forEachIndexed { index, fixtureName ->
                val fixture = assertNotNull(
                    CsvLogDecoderTest::class.java.getResource("/run-comparison/$fixtureName")
                )
                val sessionId = "paired-$index"

                CsvLogDecoder(database).parseCsvLogNative(File(fixture.toURI()), sessionId)

                assertEquals(24L, database.countTelemetryFrames(sessionId))
            }
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `canonical metadata mixed into a wide CSV is rejected`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-canonical-mixed").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("mixed.csv")
            csv.writeText("timestamp_ms,key,Drive/Velocity\n1000,Drive/Velocity,3.25")

            assertFailsWith<IllegalArgumentException> {
                CsvLogDecoder(database).parseCsvLogNative(csv, "session")
            }
            assertEquals(0L, database.countTelemetryFrames("session"))
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `wide CSV with multiple telemetry columns populates DuckDB accurately with distinct signals`() = runTest {
        val tempDir = Files.createTempDirectory("ares-csv-wide-multi-signal").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            val csv = tempDir.resolve("multi_signal.csv")
            csv.writeText(
                "TimestampMs,Drive/Pose_X,Drive/Pose_Y,Drive/HeadingRad,Arm/AngleRad\n" +
                "1000,1.25,2.50,0.785,1.570\n" +
                "1020,1.30,2.55,0.790,1.580"
            )

            CsvLogDecoder(database).parseCsvLogNative(csv, "session")

            val posX = database.getTelemetryForKey("session", "Drive/Pose_X")
            val posY = database.getTelemetryForKey("session", "Drive/Pose_Y")
            val heading = database.getTelemetryForKey("session", "Drive/HeadingRad")
            val arm = database.getTelemetryForKey("session", "Arm/AngleRad")

            assertEquals(listOf(1.25, 1.30), posX.map { it.value })
            assertEquals(listOf(2.50, 2.55), posY.map { it.value })
            assertEquals(listOf(0.785, 0.790), heading.map { it.value })
            assertEquals(listOf(1.570, 1.580), arm.map { it.value })
            assertEquals(listOf(1000L, 1020L), posX.map { it.timestampMs })
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
