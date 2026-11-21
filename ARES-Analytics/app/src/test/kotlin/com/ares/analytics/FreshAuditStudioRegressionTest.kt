package com.ares.analytics

import com.ares.analytics.desktop.DesktopStartupMachine
import com.ares.analytics.desktop.DesktopStartupState
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.strategy.PicklistTier
import com.ares.analytics.domain.strategy.RankedPicklistTeamRow
import com.ares.analytics.domain.strategy.TeamPicklistOverride
import com.ares.analytics.service.GenerativeAiService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.CommandPaletteMatchItem
import com.ares.analytics.ui.components.CommandPaletteUnifiedEntry
import com.ares.analytics.ui.components.buildUnifiedCommandPaletteEntries
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.ui.components.pathplanner.defaultFieldDimensionsMeters
import com.ares.analytics.ui.components.pathplanner.fieldCoordinateBounds
import com.ares.analytics.ui.components.pathplanner.fieldHeadingToCanvasAngleDeg
import com.ares.analytics.ui.components.pathplanner.getCanvasOffsetBase
import com.ares.analytics.ui.components.pathplanner.getRobotCoordBase
import com.ares.analytics.ui.components.pitVitalsBatteryThresholds
import com.ares.analytics.ui.components.strategy.computeSwappedTierPinOverrides
import com.ares.analytics.ui.components.strategy.matchBriefingKey
import com.ares.analytics.ui.components.strategy.selectActiveBriefingMatch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FreshAuditStudioRegressionTest {

    @Test
    fun `vertex token uri and identifier validators block ssrf and path injection`() {
        assertEquals(
            "https://oauth2.googleapis.com/token",
            GenerativeAiService.validateGoogleTokenUri("https://oauth2.googleapis.com/token"),
        )
        assertFailsWith<IllegalArgumentException> {
            GenerativeAiService.validateGoogleTokenUri("http://169.254.169.254/computeMetadata/v1/")
        }
        assertFailsWith<IllegalArgumentException> {
            GenerativeAiService.validateGoogleTokenUri("https://evil.example.com/token")
        }
        assertFailsWith<IllegalArgumentException> {
            GenerativeAiService.validateVertexIdentifier("../evil", "vertexProjectId")
        }
        assertFailsWith<IllegalArgumentException> {
            GenerativeAiService.validateModelId("gemini-2.5-flash?key=leak")
        }
    }

    @Test
    fun `field coordinate round trip and heading angles hold across FTC FRC and XRP`() {
        for (league in League.entries) {
            val (fieldW, fieldH) = defaultFieldDimensionsMeters(league)
            val bounds = fieldCoordinateBounds(fieldW, fieldH, league)
            val testPoints = listOf(
                Waypoint((bounds[0] + bounds[1]) / 2.0, (bounds[2] + bounds[3]) / 2.0),
                Waypoint(bounds[0] + (bounds[1] - bounds[0]) * 0.25, bounds[2] + (bounds[3] - bounds[2]) * 0.75),
                Waypoint(bounds[0] + (bounds[1] - bounds[0]) * 0.8, bounds[2] + (bounds[3] - bounds[2]) * 0.2),
            )
            for (pt in testPoints) {
                val offset = getCanvasOffsetBase(pt, 800f, 400f, fieldW, fieldH, league)
                val recovered = getRobotCoordBase(offset, 800f, 400f, fieldW, fieldH, league)
                assertTrue(abs(recovered.x - pt.x) < 1e-4, "$league x mismatch: $pt -> $recovered")
                assertTrue(abs(recovered.y - pt.y) < 1e-4, "$league y mismatch: $pt -> $recovered")
            }
        }
        assertEquals(-90.0, fieldHeadingToCanvasAngleDeg(0.0, League.FTC), 1e-9)
        assertEquals(0.0, fieldHeadingToCanvasAngleDeg(0.0, League.FRC), 1e-9)
        assertEquals(0.0, fieldHeadingToCanvasAngleDeg(0.0, League.XRP), 1e-9)
        assertEquals(-90.0, fieldHeadingToCanvasAngleDeg(90.0, League.FRC), 1e-9)
    }

    @Test
    fun `command palette generates unique keys for duplicate playoff match numbers and preserves screens beyond top 5 recent`() {
        val dupMatches = listOf(
            CommandPaletteMatchItem(1, "sf", "SF #1 (Set 1)", "Red vs Blue"),
            CommandPaletteMatchItem(1, "sf", "SF #1 (Set 2)", "Red vs Blue"),
        )
        val recents = NavigationTarget.entries.take(7)
        val entries = buildUnifiedCommandPaletteEntries(
            query = "",
            developerMode = true,
            recentTargets = recents,
            matchItems = dupMatches,
        )
        val keys = entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "Duplicate keys would crash Compose LazyColumn: $keys")

        val screenEntries = entries.filterIsInstance<CommandPaletteUnifiedEntry.ScreenDestination>().map { it.target }
        assertTrue(recents[5] in screenEntries, "6th recent screen must still appear in ScreenDestination list")
        assertTrue(recents[6] in screenEntries, "7th recent screen must still appear in ScreenDestination list")
    }

    @Test
    fun `strategy picklist pin swap and playoff match selection remain unambiguous`() {
        val topTier = PicklistTier.entries.first()
        val rows = listOf(
            RankedPicklistTeamRow(
                displayRank = 1,
                teamNumber = "100",
                compositePickScore = 90.0,
                opr = null,
                pitProfile = null,
                standSummary = null,
                override = TeamPicklistOverride(teamNumber = "100", tier = topTier, pinnedRank = 10),
                autoZoneSynergyLabel = "Compatible",
                hasAutoZoneCollision = false,
                reliabilityPenaltyPoints = 0.0,
                synergySummary = "Clean",
            ),
            RankedPicklistTeamRow(
                displayRank = 2,
                teamNumber = "200",
                compositePickScore = 85.0,
                opr = null,
                pitProfile = null,
                standSummary = null,
                override = TeamPicklistOverride(teamNumber = "200", tier = topTier, pinnedRank = 12),
                autoZoneSynergyLabel = "Compatible",
                hasAutoZoneCollision = false,
                reliabilityPenaltyPoints = 0.0,
                synergySummary = "Clean",
            ),
        )
        val swapped = computeSwappedTierPinOverrides(rows, idxInTier = 1, delta = -1, nowMs = 1000L)
        val byTeam = swapped.associateBy { it.teamNumber }
        assertNotNull(byTeam["200"]?.pinnedRank)
        assertNotNull(byTeam["100"]?.pinnedRank)
        assertTrue(byTeam["200"]!!.pinnedRank!! < byTeam["100"]!!.pinnedRank!!)

        val matches = listOf(
            MatchInfo(matchNumber = 1, compLevel = "qm", redAlliance = listOf("1"), blueAlliance = listOf("2")),
            MatchInfo(matchNumber = 1, compLevel = "sf", redAlliance = listOf("3"), blueAlliance = listOf("4")),
        )
        val selected = selectActiveBriefingMatch(
            combinedMatches = matches,
            selectedMatchKey = matchBriefingKey("sf", 1),
            selectedMatchNumber = 1,
            cleanOur = null,
        )
        assertEquals("sf", selected?.compLevel)
    }

    @Test
    fun `pit vitals thresholds honor 6V XRP pack voltage`() {
        val (xrpCrit, xrpWarn) = pitVitalsBatteryThresholds(League.XRP)
        assertEquals(4.7, xrpCrit, 1e-9)
        assertEquals(5.1, xrpWarn, 1e-9)
        val (frcCrit, frcWarn) = pitVitalsBatteryThresholds(League.FRC)
        assertEquals(11.2, frcCrit, 1e-9)
        assertEquals(12.0, frcWarn, 1e-9)
    }

    @Test
    fun `desktop startup machine keeps CLOSING absorbing under concurrent transitions`() {
        val machine = DesktopStartupMachine()
        machine.transitionTo(DesktopStartupState.OPENED)
        machine.transitionTo(DesktopStartupState.PRESENTED)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val gate = CountDownLatch(1)
            val futures = listOf(
                pool.submit {
                    gate.await()
                    repeat(500) { runCatching { machine.transitionTo(DesktopStartupState.SETTLED) } }
                },
                pool.submit {
                    gate.await()
                    repeat(500) { machine.recordWindowLoss() }
                },
                pool.submit {
                    gate.await()
                    machine.beginClosing()
                },
            )
            gate.countDown()
            futures.forEach { it.get() }
            assertEquals(DesktopStartupState.CLOSING, machine.state)
        } finally {
            pool.shutdownNow()
        }
    }
}
