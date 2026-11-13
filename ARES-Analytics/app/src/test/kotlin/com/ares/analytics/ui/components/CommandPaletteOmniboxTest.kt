package com.ares.analytics.ui.components

import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.domain.pit.BatteryFleetAnalyzer
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPaletteOmniboxTest {

    @Test
    fun `blank query builds zero-state recents while filterCommandPaletteRuns preserves empty blank behavior`() {
        val runs = buildCommandPaletteRunItems(
            listOf(
                Session("sess-1", "23247", "2026", "alpha", 2_000L, matchNumber = 12, allianceColor = "blue"),
            )
        )
        val notes = buildCommandPaletteNotebookItems(
            listOf(
                EngineeringNotebookEntry(
                    entryId = "note-1",
                    revision = 1,
                    entryType = NotebookEntryType.ENGINEERING_NOTE,
                    workspace = IntegrationWorkspaceIdentity(
                        teamId = "23247",
                        seasonId = "2026",
                        robotId = "alpha",
                    ),
                    markdownBody = "# Intake Tune\nVerified roller speed",
                    reviewState = NotebookReviewState.DRAFT,
                    contentHash = "sha256:test-note-1",
                    createdAtMs = 1_000L,
                    updatedAtMs = 3_000L,
                )
            )
        )
        assertTrue(filterCommandPaletteRuns("", runs).isEmpty())
        assertTrue(filterCommandPaletteNotebookEntries("", notes).isEmpty())

        val batteries = buildCommandPaletteBatteryItems(
            BatteryFleetAnalyzer.analyzeFleet(
                league = League.FTC,
                specs = listOf(BatterySpecRecord.defaultForLeague("BAT-02", "23247", "2026", "alpha", League.FTC)),
                checkins = emptyList(),
                sessions = emptyList(),
                summaries = emptyList(),
            )
        )
        val matches = buildCommandPaletteMatchItems(
            matches = listOf(MatchInfo(matchNumber = 12, compLevel = "quals", redAlliance = listOf("23247"), blueAlliance = listOf("11115"))),
            teamId = "23247",
        )

        val blankEntries = buildUnifiedCommandPaletteEntries(
            query = "",
            developerMode = false,
            recentTargets = listOf(NavigationTarget.MATCH_STRATEGY, NavigationTarget.PIT_DIAGNOSTICS),
            projectItems = defaultRobotStudioStageItems(),
            batteryItems = batteries,
            matchItems = matches,
            runItems = runs,
            notebookItems = notes,
        )
        assertTrue(blankEntries.any { it is CommandPaletteUnifiedEntry.RecentDestination && it.target == NavigationTarget.MATCH_STRATEGY })
        assertTrue(blankEntries.any { it is CommandPaletteUnifiedEntry.RunReplay && it.isRecent })
        assertTrue(blankEntries.any { it is CommandPaletteUnifiedEntry.NotebookNote && it.isRecent })
        assertTrue(blankEntries.any { it is CommandPaletteUnifiedEntry.BatteryPack && it.item.batteryId == "BAT-02" })
        assertTrue(blankEntries.any { it is CommandPaletteUnifiedEntry.ScheduledMatch && it.item.matchNumber == 12 })
        assertTrue(blankEntries.any { it is CommandPaletteUnifiedEntry.PitTab && it.item.tab == PitOperationsSubTab.BATTERY_FLEET })
    }

    @Test
    fun `query matches battery packs, scheduled matches, pit tabs, and distinct screen keys`() {
        val batteries = buildCommandPaletteBatteryItems(
            BatteryFleetAnalyzer.analyzeFleet(
                league = League.FTC,
                specs = listOf(BatterySpecRecord.defaultForLeague("BAT-09", "23247", "2026", "alpha", League.FTC)),
                checkins = emptyList(),
                sessions = emptyList(),
                summaries = emptyList(),
            )
        )
        val matches = buildCommandPaletteMatchItems(
            matches = listOf(MatchInfo(matchNumber = 27, compLevel = "quals", redAlliance = listOf("23247"), blueAlliance = listOf("4410"))),
            teamId = "23247",
        )

        val batHits = buildUnifiedCommandPaletteEntries(query = "BAT-09", developerMode = false, batteryItems = batteries)
        assertTrue(batHits.firstOrNull() is CommandPaletteUnifiedEntry.BatteryPack)
        assertEquals("BAT-09", (batHits.first() as CommandPaletteUnifiedEntry.BatteryPack).item.batteryId)

        val matchHits = buildUnifiedCommandPaletteEntries(query = "Match #27", developerMode = false, matchItems = matches)
        assertTrue(matchHits.firstOrNull() is CommandPaletteUnifiedEntry.ScheduledMatch)
        assertEquals(27, (matchHits.first() as CommandPaletteUnifiedEntry.ScheduledMatch).item.matchNumber)

        val blankKeys = buildUnifiedCommandPaletteEntries(
            query = "",
            developerMode = false,
            recentTargets = listOf(NavigationTarget.TUNING),
        ).map { it.key }
        assertEquals(blankKeys.size, blankKeys.distinct().size)
    }
}
