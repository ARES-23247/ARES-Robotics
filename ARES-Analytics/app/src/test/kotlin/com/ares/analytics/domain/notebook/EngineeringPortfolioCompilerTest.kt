package com.ares.analytics.domain.notebook

import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineeringPortfolioCompilerTest {
    private val workspace = IntegrationWorkspaceIdentity("23247", "2026", "ares-bot")

    @Test
    fun `structured notebook sections round trip cleanly through markdown`() {
        val original = StructuredNotebookSections(
            title = "Elevator kP Step-Response Iteration",
            problemHypothesis = "Elevator settled 18 mm short under battery load.",
            changeImplemented = "Increased elevator kP from 0.080 to 0.088 in Local Sim.",
            measuredEvidence = "Paired run p95 tracking error dropped from 19.2 mm to 8.4 mm `[session:sim-02]`.",
            conclusionNextSteps = "Accept simulation candidate and schedule restrained pit check.",
            tags = listOf("elevator", "pid", "simulation"),
        )

        val markdown = NotebookSectionParser.renderMarkdown(original)
        val parsed = NotebookSectionParser.parseMarkdown(markdown)

        assertEquals(original.title, parsed.title)
        assertEquals(original.problemHypothesis, parsed.problemHypothesis)
        assertEquals(original.changeImplemented, parsed.changeImplemented)
        assertEquals(original.measuredEvidence, parsed.measuredEvidence)
        assertEquals(original.conclusionNextSteps, parsed.conclusionNextSteps)
        assertEquals(original.tags, parsed.tags)
    }

    @Test
    fun `legacy session draft headings map into structured sections without losing content`() {
        val legacyMarkdown = """
            # Session run-alpha analysis

            ## Observations

            - Minimum battery voltage: 11.4 V. `[metric:min-battery]`
            - P95 loop time: 9.2 ms. `[metric:p95-loop]`

            ## Interpretation and next steps

            Inspect intake rollers before Qual 4.
        """.trimIndent()

        val parsed = NotebookSectionParser.parseMarkdown(legacyMarkdown)
        assertEquals("Session run-alpha analysis", parsed.title)
        assertTrue(parsed.measuredEvidence.contains("11.4 V"))
        assertTrue(parsed.conclusionNextSteps.contains("Qual 4"))
    }

    @Test
    fun `portfolio compiler generates season progression and escapes untrusted html`() {
        val sessions = listOf(
            Session("run-1", "23247", "2026", "ares-bot", createdAt = 1_000L, durationMs = 30_000L, matchNumber = 1),
            Session("run-2", "23247", "2026", "ares-bot", createdAt = 9_000L, durationMs = 30_000L, matchNumber = 8),
        )
        val summaries = listOf(
            SessionSummary(
                sessionId = "run-1",
                teamId = "23247",
                seasonId = "2026",
                robotId = "ares-bot",
                createdAt = 1_000L,
                durationMs = 30_000L,
                minBatteryVoltage = 10.6,
                avgLoopTimeMs = 11.4,
                p95LoopTimeMs = 18.0,
                maxEkfDrift = 0.095,
                avgCrossTrackError = 0.062,
                visionAcceptanceRate = 0.72,
            ),
            SessionSummary(
                sessionId = "run-2",
                teamId = "23247",
                seasonId = "2026",
                robotId = "ares-bot",
                createdAt = 9_000L,
                durationMs = 30_000L,
                minBatteryVoltage = 11.8,
                avgLoopTimeMs = 7.9,
                p95LoopTimeMs = 11.2,
                maxEkfDrift = 0.038,
                avgCrossTrackError = 0.024,
                visionAcceptanceRate = 0.93,
            ),
        )
        val body = "# Intake Sensor Upgrade <script>alert(1)</script>\n\n## Measured Telemetry Evidence\n\n- Vision acceptance rose to **93.0%**."
        val evidence = listOf(
            NotebookEvidenceReference("session", "session:run-2", sha256 = "a".repeat(64), label = "Match #8 telemetry"),
        )
        val hash = EngineeringNotebookHasher.sha256(
            entryId = "note-1",
            revision = 2,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = workspace,
            markdownBody = body,
            evidence = evidence,
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "lead-student",
        )
        val entry = EngineeringNotebookEntry(
            entryId = "note-1",
            revision = 2,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = workspace,
            markdownBody = body,
            evidence = evidence,
            visibility = NotebookVisibility.TEAM,
            reviewState = NotebookReviewState.APPROVED,
            humanAuthorId = "lead-student",
            humanReviewerId = "mentor-1",
            contentHash = hash,
            createdAtMs = 2_000L,
            updatedAtMs = 9_500L,
        )

        val md = EngineeringPortfolioCompiler.compileMarkdownPortfolio(
            workspaceName = "BioBuzz",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            league = League.FTC,
            entries = listOf(entry),
            sessions = sessions,
            summaries = summaries,
            generatedAtMs = 10_000L,
        )
        assertTrue(md.contains("Season Quantitative Progression (Match #1 → Match #8)"))
        assertTrue(md.contains("Min Battery Voltage"))
        assertTrue(md.contains(hash))

        val html = EngineeringPortfolioCompiler.compileHtmlPortfolio(
            workspaceName = "BioBuzz",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            league = League.FTC,
            entries = listOf(entry),
            sessions = sessions,
            summaries = summaries,
            generatedAtMs = 10_000L,
        )
        assertTrue(html.contains("@media print"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(!html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("Match #8"))
    }
}
