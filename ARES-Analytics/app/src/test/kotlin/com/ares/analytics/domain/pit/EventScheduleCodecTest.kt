package com.ares.analytics.domain.pit

import com.ares.analytics.shared.models.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventScheduleCodecTest {

    @Test
    fun `parses CSV headers encodes native JSON and builds team match queue rows`() {
        val csv = """
            comp_level,match_number,red1,red2,blue1,blue2
            quals,1,23247,16417,11115,18225
            quals,2,11115,18225,23247,9999
            semis,1,23247,16417,9999,11115
        """.trimIndent()

        val parsed = EventScheduleCodec.parseCsv(csv)
        assertEquals(3, parsed.size)
        assertEquals("quals", parsed[0].compLevel)
        assertEquals(listOf("23247", "16417"), parsed[0].redAlliance)

        val encoded = EventScheduleCodec.encodeJson(parsed)
        val roundTripped = EventScheduleCodec.parseJson(encoded)
        assertEquals(parsed.map { it.matchNumber to it.compLevel }, roundTripped.map { it.matchNumber to it.compLevel })

        val playedSession = Session(
            sessionId = "q1-run",
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            matchNumber = 1,
            createdAt = 1_000L,
            tags = listOf("bat:BAT-01"),
        )
        val simMatch2 = Session(
            sessionId = "sim-q2",
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            matchNumber = 2,
            createdAt = 2_000L,
            tags = listOf("simulation"),
        )

        val rows = EventScheduleCodec.buildQueueRows(roundTripped, "23247", listOf(playedSession, simMatch2))
        assertEquals("red", rows[0].ourAlliance)
        assertEquals(listOf("23247".let { "16417" }), rows[0].partnerTeams)
        assertEquals(listOf("11115", "18225"), rows[0].opponentTeams)
        assertEquals(listOf("q1-run"), rows[0].linkedSessionIds)

        // Match 2 only has a simulation session, so it remains unplayed next match for team 23247
        assertEquals(2, rows[1].item.matchNumber)
        assertEquals("blue", rows[1].ourAlliance)
        assertTrue(rows[1].isNextUnplayedForTeam)
        assertTrue(rows[1].linkedSessionIds.isEmpty())
    }

    @Test
    fun `parses TBA TOA and FIRST FTC Events schedule JSON payloads`() {
        val ftcPayload = """
            {
              "schedule": [
                {
                  "matchNumber": 12,
                  "tournamentLevel": "QUALIFICATION",
                  "startTime": "2026-11-12T15:30:00Z",
                  "teams": [
                    {"teamNumber": 23247, "station": "Red1"},
                    {"teamNumber": 16417, "station": "Red2"},
                    {"teamNumber": 11115, "station": "Blue1"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val ftcParsed = EventScheduleCodec.parseJson(ftcPayload)
        assertEquals(1, ftcParsed.size)
        assertEquals("quals", ftcParsed.single().compLevel)
        assertEquals(12, ftcParsed.single().matchNumber)
        assertEquals(listOf("23247", "16417"), ftcParsed.single().redAlliance)
        assertEquals(listOf("11115"), ftcParsed.single().blueAlliance)

        val toaPayload = """
            [
              {
                "match_key": "2526-US-M1-Q004-1",
                "match_name": "Quals 4",
                "participants": [
                  {"team_key": "23247", "station": 11},
                  {"team_key": "11115", "station": 21}
                ]
              }
            ]
        """.trimIndent()
        val toaParsed = EventScheduleCodec.parseJson(toaPayload)
        assertEquals(1, toaParsed.size)
        assertEquals(4, toaParsed.single().matchNumber)
        assertEquals(listOf("23247"), toaParsed.single().redAlliance)
        assertEquals(listOf("11115"), toaParsed.single().blueAlliance)
    }
}
