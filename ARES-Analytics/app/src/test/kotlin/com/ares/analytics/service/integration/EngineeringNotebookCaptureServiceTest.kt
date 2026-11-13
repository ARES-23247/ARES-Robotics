package com.ares.analytics.service.integration

import com.ares.analytics.domain.analysis.CohortSplitMode
import com.ares.analytics.domain.analysis.CohortStatisticsAnalyzer
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineeringNotebookCaptureServiceTest {

    private fun withCaptureService(
        block: suspend (DatabaseService, EngineeringNotebookCaptureService, java.io.File) -> Unit,
    ) = runTest {
        val root = Files.createTempDirectory("notebook-capture-test").toFile()
        val db = DatabaseService(root.resolve("test.duckdb").path)
        val export = ExportService(db)
        val clock = Clock.fixed(Instant.parse("2026-11-12T18:00:00Z"), ZoneOffset.UTC)
        val service = EngineeringNotebookCaptureService(db, export, clock)
        try {
            block(db, service, root)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private fun workspace(teamId: String = "12345", robotId: String = "bot-alpha") = WorkspaceConfig(
        id = "ws-$teamId",
        teamId = teamId,
        seasonId = "2026-into-the-deep",
        robotId = robotId,
        robotName = "Alpha Claw",
        league = League.FTC,
        projectPath = "",
    )

    @Test
    fun `saves sequential revisions enforces workspace isolation and approves revision cleanly`() = withCaptureService { _, service, _ ->
        val wsA = workspace(teamId = "12345", robotId = "bot-a")
        val wsB = workspace(teamId = "99999", robotId = "bot-b")

        val r1 = service.saveEditedEntry(
            workspace = wsA,
            entryId = "elevator-study",
            entryType = NotebookEntryType.ROBOT_ISSUE,
            markdownBody = "# Elevator Belt Tension\n\nToken ghp_abcdefghijklmnopqrstuvwxyz123456 should never persist.",
            evidence = listOf(NotebookEvidenceReference("session", "session:run-01", label = "Baseline run")),
            authorId = "student-maya",
        )
        assertEquals(1, r1.revision)
        assertEquals(NotebookReviewState.DRAFT, r1.reviewState)
        assertEquals(EngineeringNotebookHasher.sha256(r1), r1.contentHash)
        assertFalse(r1.markdownBody.contains("ghp_abcdef"), "Secrets must be redacted before notebook persistence")

        val r2 = service.saveEditedEntry(
            workspace = wsA,
            entryId = "elevator-study",
            entryType = NotebookEntryType.ROBOT_ISSUE,
            markdownBody = "# Elevator Belt Tension (v2)\n\n## Measured Evidence\n- Reduced belt backlash by 42%.",
            evidence = listOf(
                NotebookEvidenceReference("session", "session:run-01", label = "Baseline run"),
                NotebookEvidenceReference("session", "session:run-02", label = "Tensioned belt run"),
            ),
            authorId = "student-maya",
        )
        assertEquals(2, r2.revision)
        assertEquals(EngineeringNotebookHasher.sha256(r2), r2.contentHash)

        service.saveEditedEntry(
            workspace = wsB,
            entryId = "other-team-note",
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            markdownBody = "# Team 99999 Note",
            evidence = emptyList(),
        )

        val wsAList = service.listWorkspaceEntries(wsA)
        assertEquals(1, wsAList.size)
        assertEquals("elevator-study", wsAList.single().entryId)
        assertEquals(2, wsAList.single().revision)

        val revisions = service.listRevisions("elevator-study")
        assertEquals(listOf(1, 2), revisions.map { it.revision })

        val approved = service.approveRevision("elevator-study", revision = 2, reviewerId = "mentor-alex")
        assertEquals(NotebookReviewState.APPROVED, approved.reviewState)
        assertEquals("mentor-alex", approved.humanReviewerId)
        assertEquals(r2.contentHash, approved.contentHash)
    }

    @Test
    fun `captures cohort study and exports markdown and html season portfolios atomically`() = withCaptureService { db, service, root ->
        val ws = workspace()
        val s1 = Session("s1", ws.teamId, ws.seasonId, ws.robotId, 1_000L, durationMs = 30_000L, tags = listOf("pid-v1"), matchNumber = 1, allianceColor = "red")
        val s2 = Session("s2", ws.teamId, ws.seasonId, ws.robotId, 2_000L, durationMs = 30_000L, tags = listOf("pid-v1"), matchNumber = 2, allianceColor = "red")
        val s3 = Session("s3", ws.teamId, ws.seasonId, ws.robotId, 3_000L, durationMs = 30_000L, tags = listOf("pid-v2"), matchNumber = 3, allianceColor = "blue")
        val s4 = Session("s4", ws.teamId, ws.seasonId, ws.robotId, 4_000L, durationMs = 30_000L, tags = listOf("pid-v2"), matchNumber = 4, allianceColor = "blue")

        listOf(s1, s2, s3, s4).forEach { db.insertSession(it) }
        val summaries = mapOf(
            "s1" to SessionSummary(sessionId = "s1", teamId = ws.teamId, seasonId = ws.seasonId, robotId = ws.robotId, createdAt = 1_000L, durationMs = 30_000L, minBatteryVoltage = 11.4, avgCrossTrackError = 0.08, maxEkfDrift = 0.09, avgLoopTimeMs = 6.2, p95LoopTimeMs = 8.1),
            "s2" to SessionSummary(sessionId = "s2", teamId = ws.teamId, seasonId = ws.seasonId, robotId = ws.robotId, createdAt = 2_000L, durationMs = 30_000L, minBatteryVoltage = 11.6, avgCrossTrackError = 0.07, maxEkfDrift = 0.08, avgLoopTimeMs = 6.0, p95LoopTimeMs = 7.9),
            "s3" to SessionSummary(sessionId = "s3", teamId = ws.teamId, seasonId = ws.seasonId, robotId = ws.robotId, createdAt = 3_000L, durationMs = 30_000L, minBatteryVoltage = 12.1, avgCrossTrackError = 0.03, maxEkfDrift = 0.035, avgLoopTimeMs = 5.1, p95LoopTimeMs = 6.5),
            "s4" to SessionSummary(sessionId = "s4", teamId = ws.teamId, seasonId = ws.seasonId, robotId = ws.robotId, createdAt = 4_000L, durationMs = 30_000L, minBatteryVoltage = 12.3, avgCrossTrackError = 0.025, maxEkfDrift = 0.03, avgLoopTimeMs = 4.9, p95LoopTimeMs = 6.3),
        )
        summaries.values.forEach { db.insertSessionSummary(it) }

        val metric = CohortStatisticsAnalyzer.defaultMetrics().first { it.id == "cross_track_cm" }
        val comparison = CohortStatisticsAnalyzer.compareCohorts(
            sessions = listOf(s1, s2, s3, s4),
            summariesById = summaries,
            metric = metric,
            mode = CohortSplitMode.TAG_VS_TAG,
            primaryTag = "pid-v1",
            secondaryTag = "pid-v2",
        )

        val cohortEntry = service.captureCohortComparison(ws, comparison, authorId = "controls-lead")
        val singleEntry = service.captureSingleSessionSummary(ws, s4, summaries["s4"], authorId = "driver-lead")

        val entries = service.listWorkspaceEntries(ws)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.entryId == cohortEntry.entryId })
        assertTrue(entries.any { it.entryId == singleEntry.entryId })

        val mdFile = root.resolve("portfolio.md")
        val htmlFile = root.resolve("portfolio.html")
        service.exportWorkspacePortfolioMarkdown(ws, entries, mdFile)
        service.exportWorkspacePortfolioHtml(ws, entries, htmlFile)

        assertTrue(mdFile.isFile && mdFile.readText().contains("Alpha Claw"))
        assertTrue(mdFile.readText().contains("Cohort Distribution Study"))
        assertTrue(htmlFile.isFile && htmlFile.readText().contains("<!DOCTYPE html>"))
        assertTrue(htmlFile.readText().contains("Table of Contents"))
    }
}
