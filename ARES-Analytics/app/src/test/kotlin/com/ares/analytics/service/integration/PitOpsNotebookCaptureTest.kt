package com.ares.analytics.service.integration

import com.ares.analytics.domain.pit.BatteryCheckinRecord
import com.ares.analytics.domain.pit.BatteryFleetAnalyzer
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitOpsNotebookCaptureTest {

    private fun workspace() = WorkspaceConfig(
        id = "ws-pit-ops",
        teamId = "23247",
        seasonId = "2026-into-the-deep",
        robotId = "alpha-bot",
        robotName = "Alpha Claw",
        league = League.FTC,
        projectPath = "",
    )

    @Test
    fun `captures pit readiness receipt and battery fleet audit with SHA-256 evidence and revisions`() = runTest {
        val root = Files.createTempDirectory("pit-ops-notebook-test").toFile()
        val db = DatabaseService(root.resolve("pit.duckdb").path)
        val export = ExportService(db)
        val service = EngineeringNotebookCaptureService(
            db,
            export,
            Clock.fixed(Instant.parse("2026-11-12T18:30:00Z"), ZoneOffset.UTC),
        )
        try {
            val ws = workspace()
            val receipt = service.capturePitReadinessReceipt(
                workspace = ws,
                projectLabel = "Alpha Claw FTC",
                matchBadge = "Qual #12 · BLUE · BAT-01",
                batteryId = "BAT-01",
                connected = true,
                streamLive = true,
                observedSignalsSummary = listOf("Battery: 12.9 V", "Drive FL: 1.2 A"),
                warningSignalsSummary = emptyList(),
                checkedCrewItems = listOf("Bumpers & team number", "Main breaker strain relief"),
                uncheckedCrewItems = emptyList(),
                receiptMarkdown = "# Pit Readiness Receipt\nQual #12",
                authorId = "pit-lead",
            )
            assertEquals(1, receipt.revision)
            assertEquals(NotebookEntryType.ENGINEERING_NOTE, receipt.entryType)
            assertTrue(receipt.entryId.startsWith("pit-readiness-"))
            assertTrue(receipt.markdownBody.contains("## Measured Telemetry Evidence"))
            assertTrue(receipt.markdownBody.contains("bat:BAT-01"))
            assertTrue(receipt.evidence.any { it.kind == "pit_receipt" && !it.sha256.isNullOrBlank() })
            assertTrue(receipt.evidence.any { it.kind == "battery_pack" && it.referenceId == "battery:BAT-01" })

            // Second capture of the same match receipt advances revision cleanly
            val receiptRev2 = service.capturePitReadinessReceipt(
                workspace = ws,
                projectLabel = "Alpha Claw FTC",
                matchBadge = "Qual #12 · BLUE · BAT-01",
                batteryId = "BAT-01",
                connected = true,
                streamLive = true,
                observedSignalsSummary = listOf("Battery: 13.0 V", "Drive FL: 1.1 A"),
                warningSignalsSummary = emptyList(),
                checkedCrewItems = listOf("Bumpers & team number", "Main breaker strain relief"),
                uncheckedCrewItems = emptyList(),
                receiptMarkdown = "# Pit Readiness Receipt\nQual #12",
                authorId = "pit-lead",
            )
            assertEquals(receipt.entryId, receiptRev2.entryId)
            assertEquals(2, receiptRev2.revision)

            val s1 = Session("q12-run", ws.teamId, ws.seasonId, ws.robotId, 5_000L, matchNumber = 12, tags = listOf("bat:BAT-01"))
            val sum1 = SessionSummary(sessionId = "q12-run", teamId = ws.teamId, seasonId = ws.seasonId, robotId = ws.robotId, createdAt = 5_000L, minBatteryVoltage = 11.8, avgBatteryResistance = 0.025)
            val summaries = BatteryFleetAnalyzer.analyzeFleet(
                league = ws.league,
                specs = listOf(BatterySpecRecord.defaultForLeague("BAT-01", ws.teamId, ws.seasonId, ws.robotId, ws.league)),
                checkins = listOf(BatteryCheckinRecord("c1", "BAT-01", ws.teamId, ws.seasonId, ws.robotId, 13.0, 24.0, 99.0, "DK", "", 4_000L)),
                sessions = listOf(s1),
                summaries = listOf(sum1),
            )
            val recommended = BatteryFleetAnalyzer.recommendNextMatchBattery(summaries)
            val fleetEntry = service.captureBatteryFleetSummary(ws, summaries, recommended?.batteryId, authorId = "pit-lead")
            assertEquals(1, fleetEntry.revision)
            assertTrue(fleetEntry.entryId.startsWith("battery-fleet-"))
            assertTrue(fleetEntry.markdownBody.contains("BAT-01"))
            assertTrue(fleetEntry.evidence.any { it.kind == "battery_fleet_snapshot" && !it.sha256.isNullOrBlank() })
            assertTrue(fleetEntry.evidence.any { it.kind == "session" && it.referenceId == "session:q12-run" })
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
