package com.ares.analytics.service

import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdvancedAnalyticsServiceTest {
    @Test
    fun `report compares baselines and builds evidence backed insights`() = runTest {
        val directory = Files.createTempDirectory("ares-advanced-analytics").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            database.insertSessionSummary(summary("baseline", p95 = 10.0, voltage = 12.0, crossTrack = 0.10))
            database.insertSessionSummary(summary("current", p95 = 15.0, voltage = 10.0, crossTrack = 0.40))
            val frames = buildList {
                repeat(40) { index ->
                    val time = index * 50L
                    add(frame(time, "Hardware/Motors/fl/CurrentAmps", index.toDouble()))
                    add(frame(time, "Hardware/Motors/fl/Velocity", index * 2.0))
                    add(frame(time, "Robot/BatteryVoltage", 13.0 - index * 0.05))
                    add(frame(time, "ARES/Input/driveFrame/4", if (index % 2 == 0) 0.2 else 0.8))
                    add(frame(time, "ARES/Input/driveFrame/5", 0.3))
                    add(frame(time, "Drive/Pose_X", index * 0.05))
                    add(frame(time, "Drive/Pose_Y", index * 0.02))
                }
            }
            database.insertTelemetryFrames(frames)

            val service = AdvancedAnalyticsService(database)
            val report = service.analyze("current", listOf("baseline"))

            assertNotNull(report.comparison)
            assertTrue(report.regressions.any { it.metric == "p95 loop time" })
            assertTrue(report.correlations.any { it.rightTopic == "Robot/BatteryVoltage" })
            assertNotNull(report.driverScore)
            assertTrue(report.pathHeatmap.isNotEmpty())
            assertTrue(report.diagnostics.isNotEmpty())
            assertTrue(report.tuningSuggestions.all { it.confidence in 0.0..1.0 })
            assertTrue(service.renderDiagnosticMarkdown(report).contains("ARES analytics report"))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `physical drive frame inputs score like equivalent normalized gamepad inputs`() = runTest {
        val directory = Files.createTempDirectory("ares-driver-input-normalization").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            database.insertSessionSummary(summary("gamepad", p95 = 10.0, voltage = 12.0, crossTrack = 0.10))
            database.insertSessionSummary(summary("drive-frame", p95 = 10.0, voltage = 12.0, crossTrack = 0.10))
            val frames = buildList {
                repeat(40) { index ->
                    val time = index * 50L
                    val normalizedX = if (index % 2 == 0) 0.2 else 0.8
                    val normalizedY = 0.3
                    add(frame(time, "gamepad", "Gamepad1/LeftStickX", normalizedX))
                    add(frame(time, "gamepad", "Gamepad1/LeftStickY", normalizedY))
                    add(frame(time, "drive-frame", "ARES/Input/driveFrame/4", normalizedX * 4.0))
                    add(frame(time, "drive-frame", "ARES/Input/driveFrame/5", normalizedY * 4.0))
                }
            }
            database.insertTelemetryFrames(frames)

            val service = AdvancedAnalyticsService(database)
            val gamepad = assertNotNull(service.analyze("gamepad").driverScore)
            val driveFrame = assertNotNull(service.analyze("drive-frame").driverScore)

            assertEquals(gamepad.total, driveFrame.total, 1e-9)
            assertEquals(gamepad.smoothness, driveFrame.smoothness, 1e-9)
            assertEquals(gamepad.decisiveness, driveFrame.decisiveness, 1e-9)
            assertEquals(gamepad.consistency, driveFrame.consistency, 1e-9)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recent comparison never crosses team season or robot identity`() = runTest {
        val directory = Files.createTempDirectory("ares-baseline-isolation").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            listOf("current" to 500L, "same-robot" to 400L).forEach { (id, createdAt) ->
                database.insertSession(Session(id, "23247", "2026", "bot", createdAt))
            }
            database.insertSession(Session("other-robot", "23247", "2026", "other", 490L))
            database.insertSession(Session("other-team", "99999", "2026", "bot", 480L))
            database.insertSession(Session("other-season", "23247", "2025", "bot", 470L))
            database.insertSessionSummary(summary("current", p95 = 15.0, voltage = 11.0, crossTrack = 0.30).copy(createdAt = 500L))
            database.insertSessionSummary(summary("same-robot", p95 = 10.0, voltage = 12.0, crossTrack = 0.10).copy(createdAt = 400L))
            database.insertSessionSummary(summary("other-robot", p95 = 1.0, voltage = 13.0, crossTrack = 0.01).copy(robotId = "other", createdAt = 490L))
            database.insertSessionSummary(summary("other-team", p95 = 1.0, voltage = 13.0, crossTrack = 0.01).copy(teamId = "99999", createdAt = 480L))
            database.insertSessionSummary(summary("other-season", p95 = 1.0, voltage = 13.0, crossTrack = 0.01).copy(seasonId = "2025", createdAt = 470L))
            database.insertTelemetryFrames(listOf(frame(0L, "current", "Robot/BatteryVoltage", 11.0)))

            val result = AdvancedAnalyticsService(database).analyzeAgainstRecent("current", baselineCount = 10)
            val report = (result as OperationResult.Success).value

            assertEquals(listOf("same-robot"), assertNotNull(report.comparison).baselineSessionIds)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `explicit comparison IDs cannot cross workspace identity`() = runTest {
        val directory = Files.createTempDirectory("ares-explicit-baseline-isolation").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            database.insertSessionSummary(summary("current", p95 = 15.0, voltage = 11.0, crossTrack = 0.30))
            database.insertSessionSummary(
                summary("other-team", p95 = 1.0, voltage = 13.0, crossTrack = 0.01)
                    .copy(teamId = "99999"),
            )
            database.insertTelemetryFrames(listOf(frame(0L, "current", "Robot/BatteryVoltage", 11.0)))

            val report = AdvancedAnalyticsService(database).analyze("current", listOf("other-team"))

            assertEquals(null, report.comparison)
            assertTrue(report.regressions.isEmpty())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun summary(id: String, p95: Double, voltage: Double, crossTrack: Double) = SessionSummary(
        sessionId = id,
        teamId = "23247",
        seasonId = "2026",
        robotId = "bot",
        createdAt = 0,
        minBatteryVoltage = voltage,
        p95LoopTimeMs = p95,
        avgCrossTrackError = crossTrack,
        visionAcceptanceRate = 0.9
    )

    private fun frame(timestamp: Long, key: String, value: Double) = TelemetryFrame(
        timestampMs = timestamp,
        sessionId = "current",
        key = key,
        value = value
    )

    private fun frame(timestamp: Long, sessionId: String, key: String, value: Double) = TelemetryFrame(
        timestampMs = timestamp,
        sessionId = sessionId,
        key = key,
        value = value
    )
}
