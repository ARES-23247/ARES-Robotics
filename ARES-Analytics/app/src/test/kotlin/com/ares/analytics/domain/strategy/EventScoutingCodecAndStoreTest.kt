package com.ares.analytics.domain.strategy

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.EventScoutingStore
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventScoutingCodecAndStoreTest {

    private fun testWorkspace() = WorkspaceConfig(
        id = "ws-scouting-test",
        teamId = "23247",
        seasonId = "2026",
        robotId = "alpha",
        robotName = "Alpha",
        projectPath = "",
        league = League.FTC,
        eventCode = "USMOSC",
    )

    @Test
    fun `parses TBA FRC FIRST FTC v2 and TOA match JSON payloads accurately`() {
        val tbaJson = """
            [
              {
                "comp_level": "qm",
                "match_number": 7,
                "alliances": {
                  "red": { "team_keys": ["frc254", "frc1678", "frc971"], "score": 148 },
                  "blue": { "team_keys": ["frc118", "frc148", "frc2056"], "score": 122 }
                },
                "score_breakdown": {
                  "red": { "foulPoints": 10, "autoPoints": 35, "teleopPoints": 83, "endGamePoints": 20 },
                  "blue": { "foulPoints": 5, "autoPoints": 30, "teleopPoints": 72, "endGamePoints": 15 }
                }
              },
              {
                "comp_level": "qm",
                "match_number": 8,
                "alliances": {
                  "red": { "team_keys": ["frc254", "frc118", "frc2056"], "score": -1 },
                  "blue": { "team_keys": ["frc1678", "frc971", "frc148"], "score": -1 }
                }
              }
            ]
        """.trimIndent()
        val parsedTba = EventScoutingCodecAndExporters.parseExternalMatchResultsJson(tbaJson, League.FRC)
        assertEquals(2, parsedTba.size)
        assertTrue(parsedTba[0].isPlayed)
        assertEquals(listOf("254", "1678", "971"), parsedTba[0].redTeams)
        assertEquals(138, parsedTba[0].redNonPenaltyScore())
        assertEquals(35, parsedTba[0].redAutoPoints)
        assertTrue(!parsedTba[1].isPlayed)

        val ftcJson = """
            {
              "matches": [
                {
                  "matchNumber": 4,
                  "tournamentLevel": "QUALIFICATION",
                  "scoreRedFinal": 112,
                  "scoreBlueFinal": 96,
                  "scoreRedFoul": 12,
                  "scoreBlueFoul": 0,
                  "scoreRedAuto": 34,
                  "scoreBlueAuto": 28,
                  "teams": [
                    { "teamNumber": 23247, "station": "Red1" },
                    { "teamNumber": 11115, "station": "Red2" },
                    { "teamNumber": 4410, "station": "Blue1" },
                    { "teamNumber": 8802, "station": "Blue2" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsedFtc = EventScoutingCodecAndExporters.parseExternalMatchResultsJson(ftcJson, League.FTC)
        assertEquals(1, parsedFtc.size)
        assertEquals(100, parsedFtc[0].redNonPenaltyScore())
        assertEquals(34, parsedFtc[0].redAutoPoints)
        assertEquals(listOf("23247", "11115"), parsedFtc[0].redTeams)

        val toaJson = """
            [
              {
                "match_number": 9,
                "match_name": "Quals 9",
                "red_score": 88,
                "blue_score": 74,
                "red_pen": 8,
                "blue_pen": 4,
                "participants": [
                  { "team_key": "23247", "station": 11 },
                  { "team_key": "11115", "station": 12 },
                  { "team_key": "4410", "station": 21 },
                  { "team_key": "8802", "station": 22 }
                ]
              }
            ]
        """.trimIndent()
        val parsedToa = EventScoutingCodecAndExporters.parseExternalMatchResultsJson(toaJson, League.FTC)
        assertEquals(1, parsedToa.size)
        assertEquals(80, parsedToa[0].redNonPenaltyScore())
        assertEquals(listOf("4410", "8802"), parsedToa[0].blueTeams)
    }

    @Test
    fun `merges multiple student scout files without clobbering and persists atomically with notebook capture`() = runBlocking {
        val dir = Files.createTempDirectory("scouting-store-test").toFile()
        try {
            val ws = testWorkspace()
            val store = EventScoutingStore(dir.resolve("scouting"))

            val aliceBundle = EventScoutingBundle(
                eventCode = "USMOSC",
                league = League.FTC,
                matchResults = listOf(
                    EventMatchResult(1, "quals", listOf("23247", "11115"), listOf("4410", "8802"), 90, 65, updatedAtMs = 1000L),
                    EventMatchResult(2, "quals", listOf("23247", "4410"), listOf("11115", "8802"), 72, 81, updatedAtMs = 1000L),
                    EventMatchResult(3, "quals", listOf("11115", "4410"), listOf("23247", "8802"), 96, 68, updatedAtMs = 1000L),
                ),
                pitProfiles = listOf(
                    PitTeamProfile("11115", drivetrain = ScoutedDrivetrain.MECANUM, preferredAutoZone = AutoStartZone.RIGHT, scoutAuthor = "Alice", updatedAtMs = 1000L),
                ),
                standObservations = listOf(
                    StandMatchObservation("11115", "quals", 5, autoSucceeded = true, estimatedCycles = 6, scoutAuthor = "Alice", updatedAtMs = 1000L),
                ),
            )
            store.saveBundle(ws, aliceBundle)

            val bobFile = dir.resolve("bob-scout.json")
            bobFile.writeText(
                EventScoutingCodecAndExporters.encodeBundleJson(
                    EventScoutingBundle(
                        eventCode = "USMOSC",
                        league = League.FTC,
                        pitProfiles = listOf(
                            PitTeamProfile("11115", drivetrain = ScoutedDrivetrain.SWERVE, preferredAutoZone = AutoStartZone.RIGHT, endgameCapability = EndgameTier.HIGH_CLIMB, scoutAuthor = "Bob", updatedAtMs = 2000L),
                        ),
                        standObservations = listOf(
                            StandMatchObservation("11115", "quals", 5, autoSucceeded = true, estimatedCycles = 7, scoutAuthor = "Bob", updatedAtMs = 2000L),
                        ),
                    ),
                ),
            )

            val (merged, summary) = store.importAndMergeFile(ws, bobFile)
            assertEquals(1, summary.mergedPitProfiles)
            assertEquals(1, summary.mergedStandObservations)
            assertEquals(setOf("Bob"), summary.distinctScoutAuthors)
            // Both Alice and Bob's Match 5 stand logs for Team 11115 survive!
            assertEquals(2, merged.standObservations.size)
            assertEquals(ScoutedDrivetrain.SWERVE, merged.pitProfiles.first { it.teamNumber == "11115" }.drivetrain)

            val reloaded = store.loadBundle(ws)
            assertEquals(2, reloaded.standObservations.size)

            val opr = OprAndSynergyAnalyzer.solveOprMetrics(League.FTC, reloaded.matchResults)
            val stand = OprAndSynergyAnalyzer.summarizeStandObservations(reloaded.standObservations)
            val ranked = OprAndSynergyAnalyzer.rankAlliancePicklist(
                ourTeamNumber = ws.teamId,
                ourPitProfile = null,
                oprSnapshot = opr,
                pitProfiles = reloaded.pitProfiles.associateBy { it.teamNumber },
                standSummaries = stand,
                overrides = emptyMap(),
                preset = PicklistWeightPreset.BALANCED_CAPTAIN,
            )
            val html = EventScoutingCodecAndExporters.buildPrintablePicklistHtml("USMOSC", ws.teamId, PicklistWeightPreset.BALANCED_CAPTAIN, opr.confidence, ranked)
            assertTrue(html.contains("@media print") && html.contains("11115"))
            val csv = EventScoutingCodecAndExporters.exportPicklistAndOprCsv(ranked, opr)
            assertTrue(csv.contains("rank,team,tier") && csv.contains("11115"))

            val db = DatabaseService(dir.resolve("nb.duckdb").path)
            val captureService = EngineeringNotebookCaptureService(db, ExportService(db))
            val briefing = OprAndSynergyAnalyzer.buildMatchupBriefing(
                matchNumber = 1,
                compLevel = "quals",
                redAlliance = listOf("23247", "11115"),
                blueAlliance = listOf("4410", "8802"),
                ourTeamNumber = ws.teamId,
                oprSnapshot = opr,
                pitProfiles = reloaded.pitProfiles.associateBy { it.teamNumber },
                standSummaries = stand,
            )
            val savedBriefing = captureService.capturePreMatchStrategyPlan(ws, briefing)
            val savedPicklist = captureService.captureAlliancePicklistRationale(ws, PicklistWeightPreset.BALANCED_CAPTAIN, opr.confidence, ranked)
            assertNotNull(savedBriefing.entryId)
            assertTrue(savedBriefing.markdownBody.contains("USMOSC"))
            assertTrue(savedPicklist.markdownBody.contains("Alliance Selection Picklist Rationale"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
