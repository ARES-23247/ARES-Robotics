package com.ares.analytics.domain.strategy

import com.ares.analytics.shared.models.League
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OprAndSynergyAnalyzerTest {

    @Test
    fun `full-rank balanced FTC schedule recovers exact NP-OPR and strips penalty points`() {
        val trueNp = mapOf("101" to 20.0, "102" to 35.0, "103" to 15.0, "104" to 10.0)
        val pairings = listOf(
            listOf("101", "102") to listOf("103", "104"),
            listOf("101", "103") to listOf("102", "104"),
            listOf("101", "104") to listOf("102", "103"),
            listOf("103", "104") to listOf("101", "102"),
            listOf("102", "104") to listOf("101", "103"),
            listOf("102", "103") to listOf("101", "104"),
        )
        val matches = pairings.mapIndexed { idx, (red, blue) ->
            val redNp = red.sumOf { trueNp.getValue(it) }.toInt()
            val blueNp = blue.sumOf { trueNp.getValue(it) }.toInt()
            val redFoul = if ("101" in red) 6 else 0
            val blueFoul = if ("101" in blue) 4 else 0
            EventMatchResult(
                matchNumber = idx + 1,
                compLevel = "quals",
                redTeams = red,
                blueTeams = blue,
                redTotalScore = redNp + redFoul,
                blueTotalScore = blueNp + blueFoul,
                redFoulPointsAwarded = redFoul,
                blueFoulPointsAwarded = blueFoul,
                redAutoPoints = redNp / 3,
                blueAutoPoints = blueNp / 3,
            )
        }

        val snapshot = OprAndSynergyAnalyzer.solveOprMetrics(League.FTC, matches)
        assertEquals(OprSampleConfidence.FULL_RANK, snapshot.confidence)
        assertEquals(6, snapshot.playedMatchCount)
        assertEquals(4, snapshot.teamCount)

        trueNp.forEach { (team, expected) ->
            val m = snapshot.metricsByTeam.getValue(team)
            assertTrue(abs(m.npOpr - expected) < 0.02, "Expected NP-OPR ~$expected for team $team, got ${m.npOpr}")
            assertTrue(abs((m.npOpr - m.dpr) - m.ccwm) < 1e-6)
        }
        val m101 = snapshot.metricsByTeam.getValue("101")
        assertTrue(m101.totalOpr > m101.npOpr + 0.5, "Total OPR should reflect awarded penalty points while NP-OPR strips them")
        assertTrue(m101.autoOpr != null && m101.autoOpr!! > 0.0)
    }

    @Test
    fun `underdetermined early schedule uses mean-centered regularization without NaN`() {
        val earlyMatches = listOf(
            EventMatchResult(1, "quals", listOf("1001", "1002"), listOf("1003", "1004"), 80, 40),
            EventMatchResult(2, "quals", listOf("2001", "2002"), listOf("2003", "2004"), 70, 30),
        )
        val snapshot = OprAndSynergyAnalyzer.solveOprMetrics(League.FTC, earlyMatches)
        assertEquals(OprSampleConfidence.EARLY_SAMPLE, snapshot.confidence)
        assertEquals(8, snapshot.teamCount)
        snapshot.metricsByTeam.values.forEach { metric ->
            assertTrue(metric.npOpr.isFinite() && metric.ccwm.isFinite())
        }
        assertTrue(snapshot.metricsByTeam.getValue("1001").npOpr > snapshot.metricsByTeam.getValue("2003").npOpr)
    }

    @Test
    fun `picklist ranking honors auto-zone collision preset weights DNP and pins`() {
        val snapshot = EventOprSnapshot(
            league = League.FTC,
            playedMatchCount = 10,
            teamCount = 4,
            confidence = OprSampleConfidence.FULL_RANK,
            metricsByTeam = mapOf(
                "23247" to TeamOprMetrics("23247", 5, 42.0, 40.0, 20.0, 20.0, 12.0, 20.0, 8.0, 75.0),
                "11115" to TeamOprMetrics("11115", 5, 44.0, 42.0, 18.0, 24.0, 14.0, 20.0, 8.0, 78.0),
                "4410" to TeamOprMetrics("4410", 5, 43.0, 41.0, 19.0, 22.0, 13.0, 20.0, 8.0, 76.0),
                "8802" to TeamOprMetrics("8802", 5, 25.0, 24.0, 20.0, 4.0, 5.0, 10.0, 9.0, 52.0),
            ),
        )
        val ourPit = PitTeamProfile("23247", preferredAutoZone = AutoStartZone.LEFT, endgameCapability = EndgameTier.HIGH_CLIMB)
        val pits = mapOf(
            "23247" to ourPit,
            // Shares LEFT -> collision
            "11115" to PitTeamProfile("11115", preferredAutoZone = AutoStartZone.LEFT, endgameCapability = EndgameTier.HIGH_CLIMB),
            // Opposite RIGHT -> complements
            "4410" to PitTeamProfile("4410", preferredAutoZone = AutoStartZone.RIGHT, endgameCapability = EndgameTier.HIGH_CLIMB),
            "8802" to PitTeamProfile("8802", preferredAutoZone = AutoStartZone.FLEXIBLE, primaryRole = RobotRole.DEFENSE_SPECIALIST),
        )
        val stands = mapOf(
            "11115" to TeamStandScoutingSummary("11115", 2, 1.0, 6.0, 1.0, 0, 0.0, 1.0, 4.5, emptyList(), setOf("A")),
            "4410" to TeamStandScoutingSummary("4410", 2, 1.0, 5.5, 1.0, 0, 0.0, 2.0, 4.5, emptyList(), setOf("A")),
            "8802" to TeamStandScoutingSummary("8802", 2, 0.5, 2.0, 1.0, 0, 0.0, 5.0, 5.0, emptyList(), setOf("A")),
        )

        val secondPickRows = OprAndSynergyAnalyzer.rankAlliancePicklist(
            ourTeamNumber = "23247",
            ourPitProfile = ourPit,
            oprSnapshot = snapshot,
            pitProfiles = pits,
            standSummaries = stands,
            overrides = emptyMap(),
            preset = PicklistWeightPreset.COMPLEMENTARY_SECOND_PICK,
        )
        assertFalse(secondPickRows.any { it.teamNumber == "23247" }, "Our own team should be excluded from pick candidates")
        val row11115 = secondPickRows.first { it.teamNumber == "11115" }
        val row4410 = secondPickRows.first { it.teamNumber == "4410" }
        assertTrue(row11115.hasAutoZoneCollision)
        assertFalse(row4410.hasAutoZoneCollision)
        assertEquals("4410", secondPickRows.first().teamNumber, "Complementary auto lane should outrank slightly higher OPR collision")

        val withDnpAndPin = OprAndSynergyAnalyzer.rankAlliancePicklist(
            ourTeamNumber = "23247",
            ourPitProfile = ourPit,
            oprSnapshot = snapshot,
            pitProfiles = pits,
            standSummaries = stands,
            overrides = mapOf(
                "4410" to TeamPicklistOverride("4410", tier = PicklistTier.DO_NOT_PICK),
                "8802" to TeamPicklistOverride("8802", tier = PicklistTier.TIER_1_FIRST_PICK, pinnedRank = 1),
            ),
            preset = PicklistWeightPreset.BALANCED_CAPTAIN,
        )
        assertEquals("8802", withDnpAndPin.first().teamNumber)
        assertEquals("4410", withDnpAndPin.last().teamNumber)

        val briefing = OprAndSynergyAnalyzer.buildMatchupBriefing(
            matchNumber = 14,
            compLevel = "quals",
            redAlliance = listOf("23247", "11115"),
            blueAlliance = listOf("4410", "8802"),
            ourTeamNumber = "23247",
            oprSnapshot = snapshot,
            pitProfiles = pits,
            standSummaries = stands,
        )
        assertTrue(briefing.partnerAutoZoneWarning?.contains("11115") == true)
        assertTrue(briefing.projectedRedNpScore > briefing.projectedBlueNpScore)
    }
}
