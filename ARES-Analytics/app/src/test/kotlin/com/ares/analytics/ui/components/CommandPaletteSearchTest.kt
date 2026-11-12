package com.ares.analytics.ui.components

import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPaletteSearchTest {

    @Test
    fun `promoted notebook and match strategy routes appear in standard non-developer palette search`() {
        val notebookMatches = filterNavigationTargets("portfolio", developerMode = false)
        assertTrue(NavigationTarget.ENGINEERING_NOTEBOOK in notebookMatches)

        val strategyMatches = filterNavigationTargets("heatmap", developerMode = false)
        assertTrue(NavigationTarget.MATCH_STRATEGY in strategyMatches)
    }

    @Test
    fun `run search indexes match numbers alliances and cohort tags while hiding on empty query`() {
        val runs = buildCommandPaletteRunItems(
            listOf(
                Session(
                    sessionId = "sess-qual-12",
                    teamId = "12345",
                    seasonId = "2026",
                    robotId = "alpha",
                    createdAt = 2_000L,
                    tags = listOf("pid-v2", "fresh-battery"),
                    matchNumber = 12,
                    allianceColor = "red",
                ),
                Session(
                    sessionId = "sess-prac-01",
                    teamId = "12345",
                    seasonId = "2026",
                    robotId = "alpha",
                    createdAt = 1_000L,
                    tags = listOf("simulation"),
                    matchNumber = null,
                    allianceColor = null,
                ),
            )
        )

        assertTrue(filterCommandPaletteRuns("", runs).isEmpty())
        assertEquals(listOf("sess-qual-12"), filterCommandPaletteRuns("m#12", runs).map { it.sessionId })
        assertEquals(listOf("sess-qual-12"), filterCommandPaletteRuns("pid-v2", runs).map { it.sessionId })
        assertEquals(listOf("sess-prac-01"), filterCommandPaletteRuns("simulation", runs).map { it.sessionId })
    }

    @Test
    fun `notebook search indexes parsed titles tags authors and evidence references`() {
        val ws = IntegrationWorkspaceIdentity("12345", "2026", "alpha")
        val markdown = """
            #Intake Roller Compression Test
            _Tags: intake, compression, awards_
            
            ## Problem / Hypothesis
            Reduce ring slip during high-speed handoff.
        """.trimIndent().replace("#Intake", "# Intake")
        val hash = EngineeringNotebookHasher.sha256(
            entryId = "note-intake-1",
            revision = 2,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = ws,
            markdownBody = markdown,
            evidence = listOf(NotebookEvidenceReference("session", "session:sess-qual-12")),
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "student-kai",
            aiProvenance = null,
        )
        val entry = EngineeringNotebookEntry(
            entryId = "note-intake-1",
            revision = 2,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = ws,
            markdownBody = markdown,
            evidence = listOf(NotebookEvidenceReference("session", "session:sess-qual-12")),
            visibility = NotebookVisibility.TEAM,
            reviewState = NotebookReviewState.APPROVED,
            humanAuthorId = "student-kai",
            humanReviewerId = "mentor-sam",
            aiProvenance = null,
            contentHash = hash,
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
        )

        val items = buildCommandPaletteNotebookItems(listOf(entry))
        assertTrue(filterCommandPaletteNotebookEntries("   ", items).isEmpty())
        assertEquals("Intake Roller Compression Test", items.single().title)
        assertEquals(1, filterCommandPaletteNotebookEntries("compression", items).size)
        assertEquals(1, filterCommandPaletteNotebookEntries("student-kai", items).size)
        assertEquals(1, filterCommandPaletteNotebookEntries("sess-qual-12", items).size)
    }
}
