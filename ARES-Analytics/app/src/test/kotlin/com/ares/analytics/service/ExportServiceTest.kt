package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.service.log.CsvLogDecoder
import com.ares.analytics.service.log.WpiLogDecoder
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ExportServiceTest class.
 */
class ExportServiceTest {

    @Test
    /**
     * testExportToCsvList fun.
     */
    fun testExportToCsvList() = runTest {
        val tempDb = File.createTempFile("export_test_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val exportService = ExportService(databaseService)
        val sessionId = "session-csv-list"
        val frames = listOf(
            TelemetryFrame(1000L, sessionId, "/test/motor1", 1.5),
            TelemetryFrame(2000L, sessionId, "/test/motor1", 2.5)
        )
        databaseService.insertTelemetryFrames(frames)
        val tempCsv = File.createTempFile("export_list", ".csv").apply { deleteOnExit() }
        exportService.exportToCsvList(sessionId, listOf("/test/motor1"), tempCsv)
        val lines = tempCsv.readLines()
        assertEquals(3, lines.size)
        assertEquals(
            "key,timestamp_ms,timestamp_us,sample_order,value_type,numeric_value,string_value",
            lines[0],
        )
        assertEquals("test/motor1,1000,1000000,1,double,1.5,", lines[1])
        assertEquals("test/motor1,2000,2000000,2,double,2.5,", lines[2])

        tempCsv.delete()
        tempDb.delete()
    }

    @Test
    /**
     * testExportToCsvTable fun.
     */
    fun testExportToCsvTable() = runTest {
        val tempDb = File.createTempFile("export_test_db_2", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val exportService = ExportService(databaseService)
        val sessionId = "session-csv-table"
        val frames = listOf(
            TelemetryFrame(1000L, sessionId, "/test/motor1", 1.5),
            TelemetryFrame(1000L, sessionId, "/test/motor2", 0.5),
            TelemetryFrame(2000L, sessionId, "/test/motor1", 2.5)
        )
        databaseService.insertTelemetryFrames(frames)
        val tempCsv = File.createTempFile("export_table", ".csv").apply { deleteOnExit() }
        exportService.exportToCsvTable(sessionId, listOf("/test/motor1", "/test/motor2"), tempCsv)
        val lines = tempCsv.readLines()
        assertEquals(3, lines.size)
        assertEquals("timestamp_ms,test/motor1,test/motor2", lines[0])
        assertEquals("1000,1.5,0.5", lines[1])
        assertEquals("2000,2.5,0.5", lines[2]) // sample and hold fills motor2 with 0.5

        tempCsv.delete()
        tempDb.delete()
    }

    @Test
    fun `CSV topic names are escaped and spreadsheet formulas are neutralized`() = runTest {
        val tempDb = File.createTempFile("export_csv_escape_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sessionId = "session-csv-escape"
        val hostileKey = "=HYPERLINK(\"https://example.invalid\"),line\r\nnext"
        databaseService.insertTelemetryFrames(listOf(TelemetryFrame(1_000L, sessionId, hostileKey, 1.0)))
        val listFile = File.createTempFile("export_escape_list", ".csv").apply { deleteOnExit() }
        val tableFile = File.createTempFile("export_escape_table", ".csv").apply { deleteOnExit() }
        val export = ExportService(databaseService)

        export.exportToCsvList(sessionId, listOf(hostileKey), listFile)
        export.exportToCsvTable(sessionId, listOf(hostileKey), tableFile)

        val losslessEscaped = "\"=HYPERLINK(\"\"https://example.invalid\"\"),line\r\nnext\""
        val displayEscaped = "\"'=HYPERLINK(\"\"https://example.invalid\"\"),line\r\nnext\""
        assertTrue(listFile.readText().contains("$losslessEscaped,1000,1000000,"))
        assertTrue(tableFile.readText().contains("timestamp_ms,$displayEscaped"))
        databaseService.close()
    }

    @Test
    fun `sampled CSV export terminates at the supported timestamp boundary`() = runTest {
        val tempDb = File.createTempFile("export_overflow_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sessionId = "session-overflow"
        databaseService.insertTelemetryFrames(
            listOf(
                TelemetryFrame(MAX_SUPPORTED_TIMESTAMP_MS - 1L, sessionId, "Drive/Pose_X", 1.0),
                TelemetryFrame(MAX_SUPPORTED_TIMESTAMP_MS, sessionId, "Drive/Pose_X", 2.0),
            ),
        )
        val destination = File.createTempFile("export_overflow", ".csv").apply { deleteOnExit() }

        ExportService(databaseService).exportToCsvTable(
            sessionId,
            listOf("Drive/Pose_X"),
            destination,
            samplingPeriodMs = 1L,
        )

        assertEquals(
            listOf(
                "timestamp_ms,Drive/Pose_X",
                "${MAX_SUPPORTED_TIMESTAMP_MS - 1L},1.0",
                "$MAX_SUPPORTED_TIMESTAMP_MS,2.0",
            ),
            destination.readLines(),
        )
        databaseService.close()
    }

    @Test
    fun `CSV export rejects implausibly long sessions before generating rows`() = runTest {
        val tempDb = File.createTempFile("export_span_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sessionId = "session-span"
        databaseService.insertTelemetryFrames(
            listOf(
                TelemetryFrame(0L, sessionId, "Drive/Pose_X", 1.0),
                TelemetryFrame(8L * 24L * 60L * 60L * 1000L, sessionId, "Drive/Pose_X", 2.0),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ExportService(databaseService).exportToCsvTable(
                sessionId,
                listOf("Drive/Pose_X"),
                File.createTempFile("export_span", ".csv").apply { deleteOnExit() },
                samplingPeriodMs = 1L,
            )
        }
        databaseService.close()
    }

    @Test
    /**
     * testExportToWpiLog fun.
     */
    fun testExportToWpiLog() = runTest {
        val tempDb = File.createTempFile("export_test_db_3", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val exportService = ExportService(databaseService)
        val sessionId = "session-wpilog"
        val frames = listOf(
            TelemetryFrame(1000L, sessionId, "/test/motor1", 1.5)
        )
        databaseService.insertTelemetryFrames(frames)
        val tempWpiLog = File.createTempFile("export_wpilog", ".wpilog").apply { deleteOnExit() }
        exportService.exportToWpiLog(sessionId, listOf("/test/motor1"), tempWpiLog)
        val bytes = tempWpiLog.readBytes()
        assertTrue(bytes.size > 12)
        // Check for WPILOG header (WPILOG)
        val header = String(bytes.copyOfRange(0, 6), Charsets.UTF_8)
        assertEquals("WPILOG", header)

        tempWpiLog.delete()
        tempDb.delete()
    }

    @Test
    fun `lossless CSV and WPILOG retain text sub-millisecond time and source order`() = runTest {
        val sourceDbFile = File.createTempFile("export_lossless_source", ".db").apply { deleteOnExit() }
        val decodedDbFile = File.createTempFile("export_lossless_decoded", ".db").apply { deleteOnExit() }
        val source = DatabaseService(sourceDbFile.absolutePath)
        val decoded = DatabaseService(decodedDbFile.absolutePath)
        try {
            val sessionId = "lossless-source"
            source.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(
                        timestampMs = 1_000L,
                        sessionId = sessionId,
                        key = "Status/Mode",
                        value = 0.0,
                        stringValue = "armed, ready",
                        timestampUs = 1_000_123L,
                        sampleOrder = 41L,
                    ),
                    TelemetryFrame(
                        timestampMs = 1_000L,
                        sessionId = sessionId,
                        key = "Drive/Velocity",
                        value = 3.25,
                        timestampUs = 1_000_123L,
                        sampleOrder = 42L,
                    ),
                ),
            )
            val csv = File.createTempFile("export_lossless", ".csv").apply { deleteOnExit() }
            val wpilog = File.createTempFile("export_lossless", ".wpilog").apply { deleteOnExit() }
            val exporter = ExportService(source)

            exporter.exportToCsvList(
                sessionId,
                listOf("Status/Mode", "Drive/Velocity"),
                csv,
            )
            val csvText = csv.readText()
            assertTrue(csvText.contains("Status/Mode,1000,1000123,41,string,0.0,\"armed, ready\""))
            assertTrue(csvText.contains("Drive/Velocity,1000,1000123,42,double,3.25,"))
            assertTrue(
                csvText.indexOf("Status/Mode") < csvText.indexOf("Drive/Velocity"),
                "CSV rows must follow timestamp_us/sample_order rather than selected-key order",
            )
            CsvLogDecoder(decoded).parseCsvLogNative(csv, "lossless-csv-decoded")
            val csvDecodedFrames = decoded.getTelemetryExportPage(
                "lossless-csv-decoded",
                listOf("Status/Mode", "Drive/Velocity"),
                after = null,
                limit = 10,
            )
            assertEquals(listOf("Status/Mode", "Drive/Velocity"), csvDecodedFrames.map { it.key })
            assertEquals(listOf(1_000L, 1_000L), csvDecodedFrames.map { it.timestampMs })
            assertEquals(listOf(1_000_123L, 1_000_123L), csvDecodedFrames.map { it.timestampUs })
            assertEquals(listOf(41L, 42L), csvDecodedFrames.map { it.sampleOrder })
            assertEquals(listOf("armed, ready", null), csvDecodedFrames.map { it.stringValue })
            assertEquals(listOf(0.0, 3.25), csvDecodedFrames.map { it.value })

            exporter.exportToWpiLog(
                sessionId,
                listOf("Status/Mode", "Drive/Velocity"),
                wpilog,
            )
            val batcher = FrameBatcher(decoded, batchSize = 10)
            WpiLogDecoder().parseWpiLog(wpilog, "lossless-decoded", batcher)
            batcher.flush()
            val decodedFrames = decoded.getTelemetryExportPage(
                "lossless-decoded",
                listOf("Status/Mode", "Drive/Velocity"),
                after = null,
                limit = 10,
            )
            assertEquals(listOf("Status/Mode", "Drive/Velocity"), decodedFrames.map { it.key })
            assertEquals(listOf(1_000_123L, 1_000_123L), decodedFrames.map { it.timestampUs })
            assertEquals("armed, ready", decodedFrames[0].stringValue)
            assertEquals(3.25, decodedFrames[1].value)
        } finally {
            source.close()
            decoded.close()
        }
    }

    @Test
    fun `bounded SQL preflight rejects cap plus one before replacing destination`() = runTest {
        val dbFile = File.createTempFile("export_bounded", ".db").apply { deleteOnExit() }
        val database = DatabaseService(dbFile.absolutePath)
        try {
            val sessionId = "bounded"
            database.insertTelemetryFrames(
                (1L..3L).map { order ->
                    TelemetryFrame(order, sessionId, "Drive/Pose_X", order.toDouble(), sampleOrder = order)
                },
            )
            val preflight = database.getTelemetryExportPreflight(
                sessionId,
                listOf("Drive/Pose_X"),
                maximumFrames = 2,
            )
            assertEquals(3L, preflight.boundedFrameCount, "Preflight retains only the cap + 1 sentinel")
            val destination = File.createTempFile("export_bounded", ".csv").apply {
                writeText("previous")
                deleteOnExit()
            }

            val failure = assertFailsWith<IllegalArgumentException> {
                ExportService(database, NO_OP_BEFORE_ATOMIC_REPLACE, maximumSourceFrames = 2)
                    .exportToCsvList(sessionId, listOf("Drive/Pose_X"), destination)
            }
            assertTrue(failure.message.orEmpty().contains("2-frame safety limit"))
            assertEquals("previous", destination.readText())
        } finally {
            database.close()
        }
    }

    @Test
    fun `CSV and WPILOG replace failure preserves the prior destination`() = runTest {
        val dbFile = File.createTempFile("export_atomic", ".db").apply { deleteOnExit() }
        val database = DatabaseService(dbFile.absolutePath)
        try {
            val sessionId = "atomic"
            database.insertTelemetryFrames(
                listOf(TelemetryFrame(1L, sessionId, "Drive/Pose_X", 1.0, sampleOrder = 1L)),
            )
            val exporter = ExportService(database, { _, _ -> throw IOException("injected replace failure") })
            val directory = java.nio.file.Files.createTempDirectory("export-atomic-destinations-").toFile()
            try {
                suspend fun verifyPreserved(name: String, export: suspend (File) -> Unit) {
                    val destination = File(directory, name).apply { writeText("previous-$name") }
                    assertFailsWith<IOException> { export(destination) }
                    assertEquals("previous-$name", destination.readText())
                }
                verifyPreserved("list.csv") { file ->
                    exporter.exportToCsvList(sessionId, listOf("Drive/Pose_X"), file)
                }
                verifyPreserved("table.csv") { file ->
                    exporter.exportToCsvTable(sessionId, listOf("Drive/Pose_X"), file)
                }
                verifyPreserved("trace.wpilog") { file ->
                    exporter.exportToWpiLog(sessionId, listOf("Drive/Pose_X"), file)
                }
            } finally {
                directory.deleteRecursively()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `WPILOG rejects a topic that changes between numeric and string values`() = runTest {
        val dbFile = File.createTempFile("export_mixed_type", ".db").apply { deleteOnExit() }
        val database = DatabaseService(dbFile.absolutePath)
        try {
            val sessionId = "mixed"
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1L, sessionId, "Status/Value", 1.0, sampleOrder = 1L),
                    TelemetryFrame(2L, sessionId, "Status/Value", 0.0, "ready", sampleOrder = 2L),
                ),
            )
            val destination = File.createTempFile("mixed", ".wpilog").apply {
                writeText("previous")
                deleteOnExit()
            }

            val failure = assertFailsWith<IllegalArgumentException> {
                ExportService(database).exportToWpiLog(
                    sessionId,
                    listOf("Status/Value"),
                    destination,
                )
            }
            assertTrue(failure.message.orEmpty().contains("mixed numeric/string"))
            assertEquals("previous", destination.readText())
        } finally {
            database.close()
        }
    }
}
