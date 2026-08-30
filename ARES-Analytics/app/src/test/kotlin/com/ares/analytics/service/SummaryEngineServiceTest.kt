package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SummaryEngineServiceTest class.
 */
class SummaryEngineServiceTest {

    @Test
    /**
     * testGenerateSummary fun.
     */
    fun testGenerateSummary() = runTest {
        val tempDb = File.createTempFile("summary_db_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val driverAnalysisService = DriverAnalysisService(databaseService, sysIdService)
        var draftedSessionId: String? = null
        val summaryEngine = SummaryEngineService(
            databaseService,
            sysIdService,
            driverAnalysisService,
            onSummaryPersisted = { summary, alerts ->
                draftedSessionId = summary.sessionId
                assertTrue(alerts.isEmpty())
            },
        )
        val session = Session(
            sessionId = "summary-session",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            createdAt = 1000L,
            durationMs = 120000L,
            tags = listOf("teleop", "battery-A")
        )

        // Insert mock telemetry frames
        val frames = listOf(
            TelemetryFrame(1100L, session.sessionId, "/Battery/Voltage", 12.6),
            TelemetryFrame(1200L, session.sessionId, "/Battery/Voltage", 11.2),
            TelemetryFrame(1300L, session.sessionId, "/Battery/Voltage", 0.0), // noise to be ignored
            TelemetryFrame(1300L, session.sessionId, "Hardware/Motors/fl/Voltage", 2.0),
            TelemetryFrame(1100L, session.sessionId, "/Drive/EkfDrift", 0.05),
            TelemetryFrame(1200L, session.sessionId, "/Drive/EkfDrift", -0.25),
            TelemetryFrame(1200L, session.sessionId, "/Drive/EKF_X", 14.0),
            TelemetryFrame(1100L, session.sessionId, "/Robot/LoopTimeMs", 20.0),
            TelemetryFrame(1200L, session.sessionId, "/Robot/LoopTimeMs", 24.0),
            TelemetryFrame(1300L, session.sessionId, "/Robot/LoopTimeMs", 18.0),
            TelemetryFrame(1100L, session.sessionId, "/Drive/MotorFL/Current", 8.0),
            TelemetryFrame(1200L, session.sessionId, "/Drive/MotorFL/Current", 12.0),
            TelemetryFrame(1200L, session.sessionId, "Hardware/Motors/fr/CurrentAmps", 7.0),
            TelemetryFrame(1100L, session.sessionId, "/Vision/AcceptanceRate", 0.95),
            TelemetryFrame(1200L, session.sessionId, "/Vision/AcceptanceRate", 0.85),
            TelemetryFrame(1200L, session.sessionId, "Path/Error_CrossTrack", -0.15),
            TelemetryFrame(1200L, session.sessionId, "/Robot/OpMode", 0.0, "AUTO")
        )

        databaseService.insertTelemetryFrames(frames)
        val summary = summaryEngine.generateSummary(session)

        assertEquals("summary-session", summary.sessionId)
        assertEquals(11.2, summary.minBatteryVoltage)
        assertEquals(0.25, summary.maxEkfDrift)
        assertEquals(20.67, summary.avgLoopTimeMs, 0.01)
        assertEquals(10.0, summary.motorCurrentAverages["MotorFL"])
        assertEquals(7.0, summary.motorCurrentAverages["fr"])
        assertEquals(0.90, summary.visionAcceptanceRate, 0.01)
        assertEquals(0.15, summary.avgCrossTrackError, 0.001)
        assertEquals(3, summary.tags.size)
        assertTrue(summary.tags.contains("battery-A"))
        assertTrue(summary.tags.contains("AUTO"))

        // Verify summary was saved in DB
        val saved = databaseService.getSessionSummary(session.sessionId)
        assertTrue(saved != null)
        assertEquals(11.2, saved.minBatteryVoltage)
        assertEquals("summary-session", draftedSessionId)
        databaseService.close()
        tempDb.delete()
    }

    @Test
    fun testGenerateSummaryEmptySessionReturnsSafeDefaults() = runTest {
        val tempDb = File.createTempFile("summary_empty_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val driverAnalysisService = DriverAnalysisService(databaseService, sysIdService)
        val summaryEngine = SummaryEngineService(databaseService, sysIdService, driverAnalysisService)
        val session = Session(
            sessionId = "empty-session",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            createdAt = 5000L,
            durationMs = 0L,
            tags = emptyList()
        )

        val summary = summaryEngine.generateSummary(session)
        assertEquals("empty-session", summary.sessionId)
        assertEquals(12.0, summary.minBatteryVoltage)
        assertEquals(0.0, summary.maxEkfDrift)
        assertEquals(0.0, summary.avgLoopTimeMs)
        assertEquals(0.0, summary.p95LoopTimeMs)
        assertTrue(summary.motorCurrentAverages.isEmpty())
        assertEquals(0.0, summary.visionAcceptanceRate)
        assertEquals(0.0, summary.avgCrossTrackError)

        databaseService.close()
        tempDb.delete()
    }

    @Test
    fun `non-finite live samples cannot poison persisted summary json`() = runTest {
        val tempDb = File.createTempFile("summary_non_finite", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        try {
            val summaryEngine = SummaryEngineService(
                databaseService,
                SysIdService(databaseService),
                DriverAnalysisService(databaseService, SysIdService(databaseService)),
            )
            val session = Session("non-finite", "23247", "2026", "ares-bot", 1_000L)
            databaseService.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1_100L, session.sessionId, "Robot/LoopTimeMs", Double.NaN),
                    TelemetryFrame(1_100L, session.sessionId, "Hardware/Motors/fl/CurrentAmps", Double.NaN),
                    TelemetryFrame(1_100L, session.sessionId, "Vision/LatencyMs", Double.POSITIVE_INFINITY),
                )
            )

            val summary = summaryEngine.generateSummary(session)
            val persisted = databaseService.getSessionSummary(session.sessionId)

            assertTrue(summary.avgLoopTimeMs.isFinite())
            assertTrue(summary.avgVisionLatencyMs.isFinite())
            assertTrue(summary.motorCurrentAverages.values.all(Double::isFinite))
            assertEquals(summary, persisted)
        } finally {
            databaseService.close()
            tempDb.delete()
        }
    }

    @Test
    fun testBatterySagCalculationWithActiveHighCurrentDrawSamples() = runTest {
        val tempDb = File.createTempFile("summary_battery_sag_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val driverAnalysisService = DriverAnalysisService(databaseService, sysIdService)
        val summaryEngine = SummaryEngineService(databaseService, sysIdService, driverAnalysisService)
        val session = Session(
            sessionId = "battery-sag-session",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            createdAt = 1000L,
            durationMs = 60000L,
            tags = listOf("match", "battery-pack-1")
        )

        // Telemetry frames modeling battery sag under high current draw
        // dv / di calculations:
        // t=1000: V=12.6V, I=2.0A (resting / baseline)
        // t=1100: V=11.6V, I=22.0A -> dV=-1.0V, dI=+20.0A -> R = 1.0/20.0 = 0.050 Ohm
        // t=1200: V=10.1V, I=52.0A -> dV=-1.5V, dI=+30.0A -> R = 1.5/30.0 = 0.050 Ohm
        // t=1300: V=12.5V, I=4.0A  -> dV=+2.4V, dI=-48.0A -> R = 2.4/48.0 = 0.050 Ohm
        // t=1400: V=12.5V, I=4.2A  -> quiescent noise (dI=0.2A <= 0.5A threshold, ignored)
        val frames = listOf(
            TelemetryFrame(1000L, session.sessionId, "/Battery/Voltage", 12.6),
            TelemetryFrame(1000L, session.sessionId, "/Battery/Current", 2.0),

            TelemetryFrame(1100L, session.sessionId, "/Battery/Voltage", 11.6),
            TelemetryFrame(1100L, session.sessionId, "/Battery/Current", 22.0),

            TelemetryFrame(1200L, session.sessionId, "/Battery/Voltage", 10.1),
            TelemetryFrame(1200L, session.sessionId, "/Battery/Current", 52.0),

            TelemetryFrame(1300L, session.sessionId, "/Battery/Voltage", 12.5),
            TelemetryFrame(1300L, session.sessionId, "/Battery/Current", 4.0),

            TelemetryFrame(1400L, session.sessionId, "/Battery/Voltage", 12.5),
            TelemetryFrame(1400L, session.sessionId, "/Battery/Current", 4.2)
        )

        databaseService.insertTelemetryFrames(frames)
        val summary = summaryEngine.generateSummary(session)

        assertEquals("battery-sag-session", summary.sessionId)
        assertEquals(10.1, summary.minBatteryVoltage, 0.001)
        assertEquals(0.050, summary.avgBatteryResistance, 0.001)

        databaseService.close()
        tempDb.delete()
    }

    @Test
    fun testEkfAndAutonomousTrackingDiagnostics() = runTest {
        val tempDb = File.createTempFile("summary_ekf_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val driverAnalysisService = DriverAnalysisService(databaseService, sysIdService)
        val summaryEngine = SummaryEngineService(databaseService, sysIdService, driverAnalysisService)
        val session = Session(
            sessionId = "ekf-diagnostic-session",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            createdAt = 2000L,
            durationMs = 60000L,
            tags = listOf("autonomous")
        )

        val frames = listOf(
            TelemetryFrame(2100L, session.sessionId, "Vision/EKF_NIS", 1.85),
            TelemetryFrame(2200L, session.sessionId, "Vision/EKF_NIS", 2.10),
            TelemetryFrame(2300L, session.sessionId, "Vision/EKF_NIS", 1.95),
            TelemetryFrame(2100L, session.sessionId, "Vision/Pose_X", 1.00),
            TelemetryFrame(2100L, session.sessionId, "Vision/Pose_Y", 2.00),
            TelemetryFrame(2100L, session.sessionId, "Drive/Pose_X", 1.01),
            TelemetryFrame(2100L, session.sessionId, "Drive/Pose_Y", 2.01),
            TelemetryFrame(2100L, session.sessionId, "Path/CrossTrackError", 0.02),
            TelemetryFrame(2200L, session.sessionId, "Path/CrossTrackError", -0.03),
            TelemetryFrame(2300L, session.sessionId, "Path/CrossTrackError", 0.025),
        )

        databaseService.insertTelemetryFrames(frames)
        val summary = summaryEngine.generateSummary(session)

        assertTrue(summary.tags.contains("EKFOptimal"), "Expected EKFOptimal tag in summary.tags: ${summary.tags}")

        val rawFrameCount = databaseService.countTelemetryFrames(session.sessionId)
        val avgNisFrame = databaseService.getAnalysisDiagnostics(session.sessionId)
            .firstOrNull { it.key == "Diagnostics/EKF/AvgNIS" }
        assertTrue(avgNisFrame != null, "Expected Diagnostics/EKF/AvgNIS frame to be inserted")
        assertEquals(1.966, avgNisFrame.value, 0.01)

        val crossTrackRmseFrame = databaseService.getAnalysisDiagnostics(session.sessionId)
            .firstOrNull { it.key == "Diagnostics/Auto/CrossTrackRMSE" }
        assertTrue(crossTrackRmseFrame != null, "Expected Diagnostics/Auto/CrossTrackRMSE frame to be inserted")
        assertTrue(crossTrackRmseFrame.value < 0.05)

        summaryEngine.generateSummary(session)
        assertEquals(rawFrameCount, databaseService.countTelemetryFrames(session.sessionId))
        assertEquals(
            databaseService.getAnalysisDiagnostics(session.sessionId).map { it.key }.distinct().size,
            databaseService.getAnalysisDiagnostics(session.sessionId).size,
            "Regenerating a summary must replace derived diagnostics instead of appending duplicates",
        )

        databaseService.close()
        tempDb.delete()
    }
}
