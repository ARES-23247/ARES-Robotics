package com.ares.analytics.service

import com.ares.analytics.service.log.HootDecoderService
import com.ares.analytics.service.log.appendBoundedProcessLine
import com.ares.analytics.service.log.cleanupFailedHootImport
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HootDecoderServiceTest class.
 */
class HootDecoderServiceTest {

    @Test
    fun `cancelled hoot import removes its staged owner and telemetry immediately`() = runTest {
        val tempDb = File.createTempFile("hoot_cancel_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val decoder = HootDecoderService(
            databaseService,
            SummaryEngineService(
                databaseService,
                sysIdService,
                DriverAnalysisService(databaseService, sysIdService),
            ),
            sysIdService,
        )
        val stagedSession = Session("cancelled-hoot", "23247", "2026", "test", 1L)
        val convertedCsv = File.createTempFile("cancelled_hoot", ".csv").apply {
            writeText("time,Signal\n0.0,1.0\n0.02,2.0")
        }
        try {
            databaseService.insertImportSession(stagedSession)
            decoder.parseAndInsertTelemetry(convertedCsv, stagedSession.sessionId)

            cleanupFailedHootImport(
                databaseService,
                stagedSession.sessionId,
                CancellationException("student cancelled"),
            )

            assertEquals(0L, databaseService.countTelemetryFrames(stagedSession.sessionId))
            assertEquals(
                "0",
                databaseService.executeQueryRaw(
                    "SELECT COUNT(*) AS total FROM sessions WHERE session_id = 'cancelled-hoot'",
                ).rows.single().single(),
            )
        } finally {
            databaseService.close()
            convertedCsv.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `owlet diagnostic output is bounded while its pipe continues to drain`() {
        val output = StringBuffer()

        assertTrue(appendBoundedProcessLine(output, "abcd", 6))
        assertTrue(!appendBoundedProcessLine(output, "overflow", 6))
        assertEquals(6, output.length)
        assertEquals("abcd\no", output.toString())
    }

    @Test
    fun `1000 microsecond interval is one millisecond for explicit and inferred units`() = runTest {
        val tempDb = File.createTempFile("hoot_units_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val decoder = HootDecoderService(
            databaseService,
            SummaryEngineService(
                databaseService,
                sysIdService,
                DriverAnalysisService(databaseService, sysIdService)
            ),
            sysIdService
        )
        val explicit = File.createTempFile("hoot_explicit_us", ".csv").apply {
            writeText("time_us,Signal\n0,1.0\n1000,2.0")
        }
        val inferred = File.createTempFile("hoot_inferred_us", ".csv").apply {
            writeText("time,Signal\n0,1.0\n1000,2.0")
        }
        try {
            assertEquals(1L, decoder.parseAndInsertTelemetry(explicit, "explicit").second)
            assertEquals(1L, decoder.parseAndInsertTelemetry(inferred, "inferred").second)
            assertEquals(
                listOf(0L, 1L),
                databaseService.getTelemetryForKey("explicit", "Signal").map { it.timestampMs }
            )
            assertEquals(
                listOf(0L, 1L),
                databaseService.getTelemetryForKey("inferred", "Signal").map { it.timestampMs }
            )
        } finally {
            databaseService.close()
            explicit.delete()
            inferred.delete()
            tempDb.delete()
        }
    }

    @Test
    /**
     * testParseAndInsertTelemetry fun.
     */
    fun testParseAndInsertTelemetry() = runTest {
        val tempDb = File.createTempFile("hoot_db_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val driverAnalysisService = DriverAnalysisService(databaseService, sysIdService)
        val summaryEngineService = SummaryEngineService(databaseService, sysIdService, driverAnalysisService)
        val hootDecoderService = HootDecoderService(databaseService, summaryEngineService, sysIdService)

        // Create a mock CSV file mimicking owlet's output (timestamps in seconds)
        val tempCsv = File.createTempFile("hoot_test_log", ".csv").apply { deleteOnExit() }
        tempCsv.writeText(
            """
            time, /Drive/MotorFL/Voltage, /Drive/MotorFL/Velocity, /Drive/MotorFL/Current
            0.0, 1.0, 2.0, 3.0
            0.02, 1.1, 2.1, 3.1
            0.04, 1.2, 2.2, 3.2
            """.trimIndent()
        )
        val sessionId = "test-hoot-session"
        val (firstTime, lastTime, keys) = hootDecoderService.parseAndInsertTelemetry(tempCsv, sessionId)

        // Verify bounds (0.0s -> 0ms, 0.04s -> 40ms)
        assertEquals(0L, firstTime)
        assertEquals(40L, lastTime)

        // Verify keys
        assertTrue(keys.contains("/Drive/MotorFL/Voltage"))
        assertTrue(keys.contains("/Drive/MotorFL/Velocity"))
        assertTrue(keys.contains("/Drive/MotorFL/Current"))

        // Query database to verify values are correctly batch-inserted
        val voltages = databaseService.getTelemetryForKey(sessionId, "/Drive/MotorFL/Voltage")
        assertEquals(3, voltages.size)
        assertEquals(0L, voltages[0].timestampMs)
        assertEquals(1.0, voltages[0].value, 0.001)
        assertEquals(20L, voltages[1].timestampMs)
        assertEquals(1.1, voltages[1].value, 0.001)
        assertEquals(40L, voltages[2].timestampMs)
        assertEquals(1.2, voltages[2].value, 0.001)

        tempCsv.delete()
        tempDb.delete()
    }
}
