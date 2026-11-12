package com.ares.analytics.ui

import com.ares.analytics.domain.analysis.MatchDebriefReportBuilder
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.components.dashboard.ReplayDiagnosticKind
import com.ares.analytics.ui.components.dashboard.buildReplayDiagnosticBookmarks
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReplayTimelineBookmarkAndSliceTest {

    @Test
    fun `buildReplayDiagnosticBookmarks detects phase transitions current spikes and stick step sag`() {
        val frames = listOf(
            TelemetryFrame(1_000L, "s1", "Routine/ActiveState", 0.0, stringValue = "INTAKE_APPROACH"),
            TelemetryFrame(1_050L, "s1", "Hardware/BatteryVoltage", 12.6),
            TelemetryFrame(1_200L, "s1", "Driver/Throttle", 0.95),
            TelemetryFrame(1_320L, "s1", "Hardware/BatteryVoltage", 11.2),
            TelemetryFrame(2_000L, "s1", "Hardware/Motors/fl/CurrentAmps", 18.5),
            TelemetryFrame(2_600L, "s1", "Routine/ActiveState", 0.0, stringValue = "SCORE_BASKET"),
        )

        val bookmarks = buildReplayDiagnosticBookmarks(
            sessionId = "s1",
            league = League.FTC,
            frames = frames,
            alerts = emptyList(),
        )

        val phaseBookmarks = bookmarks.filter { it.kind == ReplayDiagnosticKind.PHASE_TRANSITION }
        assertEquals(2, phaseBookmarks.size)
        assertTrue(phaseBookmarks.any { it.label.contains("INTAKE_APPROACH") })
        assertTrue(phaseBookmarks.any { it.label.contains("SCORE_BASKET") })

        assertTrue(
            bookmarks.any { it.kind == ReplayDiagnosticKind.STICK_STEP_SAG && it.timestampMs == 1_320L },
            "Expected stick-step voltage sag chip at 1320 ms",
        )
        assertTrue(
            bookmarks.any { it.kind == ReplayDiagnosticKind.CURRENT_SPIKE && it.timestampMs == 2_000L },
            "Expected current spike chip at 2000 ms",
        )
    }

    @Test
    fun `exportWindowToCsvList and buildSliceMarkdownDebrief export bounded replay slice`() = runTest {
        val tempDir = Files.createTempDirectory("ares_slice_export_test").toFile()
        val dbFile = File(tempDir, "slice.duckdb")
        val database = DatabaseService(dbFile.absolutePath)
        val exportService = ExportService(database)
        try {
            val session = Session(
                sessionId = "match-slice-1",
                teamId = "23247",
                seasonId = "2026",
                robotId = "ares-bot",
                createdAt = 1_000L,
                durationMs = 5_000L,
            )
            database.insertSession(session)
            val allFrames = listOf(
                TelemetryFrame(1_000L, session.sessionId, "Robot/BatteryVoltage", 12.7),
                TelemetryFrame(2_000L, session.sessionId, "Robot/BatteryVoltage", 12.1),
                TelemetryFrame(2_500L, session.sessionId, "Robot/BatteryVoltage", 10.6),
                TelemetryFrame(2_600L, session.sessionId, "LoopTimeMs", 14.2),
                TelemetryFrame(2_700L, session.sessionId, "Hardware/Motors/fl/CurrentAmps", 16.4),
                TelemetryFrame(4_500L, session.sessionId, "Robot/BatteryVoltage", 12.4),
            )
            database.insertTelemetryFrames(allFrames)

            val csvFile = File(tempDir, "slice.csv")
            exportService.exportWindowToCsvList(
                sessionId = session.sessionId,
                startTimestampMs = 2_000L,
                endTimestampMs = 3_000L,
                destinationFile = csvFile,
            )
            val lines = csvFile.readLines()
            // Header + 4 frames inside [2000, 3000] (excluding 1000 and 4500)
            assertEquals(5, lines.size)
            assertTrue(lines.first().startsWith("key,timestamp_ms,"))
            assertTrue(lines.drop(1).none { it.contains(",1000,") || it.contains(",4500,") })

            val sliceFrames = database.getTelemetryRange(session.sessionId, 2_000L, 3_000L)
            val markdown = MatchDebriefReportBuilder.buildSliceMarkdownDebrief(
                sessionId = session.sessionId,
                league = League.FTC,
                sessionStartMs = 1_000L,
                inMs = 2_000L,
                outMs = 3_000L,
                sliceFrames = sliceFrames,
                sliceAlerts = listOf(
                    AlertRecord("a-slice", session.sessionId, "Battery/Low", 2_500L, 2_700L, 200L, 10.6, false)
                ),
                sliceAnnotations = listOf(
                    SessionAnnotation("n-slice", session.sessionId, "Heavy defense contact", 99_000L, playheadMs = 2_550L)
                ),
            )
            assertTrue(markdown.contains("ARES Replay Time-Slice Debrief"))
            assertTrue(markdown.contains("min `10.60 V`"))
            assertTrue(markdown.contains("Heavy defense contact"))
            assertTrue(markdown.contains("Battery/Low"))
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
