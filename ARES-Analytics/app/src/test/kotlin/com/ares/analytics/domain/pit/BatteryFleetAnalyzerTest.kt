package com.ares.analytics.domain.pit

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryFleetAnalyzerTest {

    @Test
    fun `canonical tag helpers normalize and replace bat tags without dropping cohort tags`() {
        assertEquals("BAT-01", normalizeBatteryId("bat:bat 01"))
        assertEquals("PACK-A", normalizeBatteryId("battery: pack a"))
        assertEquals("", normalizeBatteryId("   "))
        assertEquals("bat:BAT-01", formatBatterySessionTag("bat 01"))
        assertNull(formatBatterySessionTag("bat:"))

        val updated = replaceBatteryTag(listOf("quals", "bat:OLD-1", "intake-v2"), "bat 02")
        assertEquals(listOf("quals", "intake-v2", "bat:BAT-02"), updated)
        assertEquals("BAT-02", extractBatteryIdFromTags(updated))

        val cleared = replaceBatteryTag(updated, "")
        assertEquals(listOf("quals", "intake-v2"), cleared)
        assertNull(extractBatteryIdFromTags(cleared))
    }

    @Test
    fun `simulation runs are excluded from hardware cycles and resistance degradation grading`() {
        val specs = listOf(
            BatterySpecRecord.defaultForLeague("BAT-01", "23247", "2026", "bot", League.FTC),
        )
        val checkins = listOf(
            BatteryCheckinRecord(
                checkinId = "chk-1",
                batteryId = "BAT-01",
                teamId = "23247",
                seasonId = "2026",
                robotId = "bot",
                restingVoltage = 13.1,
                beakResistanceMilliohms = 22.0,
                recordedAtMs = 1_000L,
            )
        )
        val simSession = Session(
            sessionId = "sim-1",
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            createdAt = 2_000L,
            durationMs = 120_000L,
            tags = listOf("simulation", "bat:BAT-01"),
        )
        val hwSession = Session(
            sessionId = "hw-1",
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            matchNumber = 4,
            createdAt = 3_000L,
            durationMs = 150_000L,
            tags = listOf("bat:BAT-01"),
        )
        val summaries = listOf(
            // Simulation session with artificially terrible resistance must not downgrade hardware pack
            SessionSummary(sessionId = "sim-1", teamId = "23247", seasonId = "2026", robotId = "bot", createdAt = 2_000L, minBatteryVoltage = 8.1, avgBatteryResistance = 0.120),
            SessionSummary(sessionId = "hw-1", teamId = "23247", seasonId = "2026", robotId = "bot", createdAt = 3_000L, minBatteryVoltage = 11.7, avgBatteryResistance = 0.024),
        )

        val fleet = BatteryFleetAnalyzer.analyzeFleet(League.FTC, specs, checkins, listOf(simSession, hwSession), summaries)
        val pack = fleet.single()
        assertEquals(1, pack.hardwareCycleCount)
        assertEquals(1, pack.simulationRunCount)
        assertEquals(BatteryHealthGrade.COMPETITION_PRIME, pack.grade)
        assertEquals(11.7, pack.worstMinVoltage)
    }

    @Test
    fun `rising OLS resistance slope and retirement bounds prioritize healthier packs`() {
        val specs = listOf(
            BatterySpecRecord.defaultForLeague("BAT-A", "23247", "2026", "bot", League.FRC),
            BatterySpecRecord.defaultForLeague("BAT-B", "23247", "2026", "bot", League.FRC),
            BatterySpecRecord.defaultForLeague("BAT-C", "23247", "2026", "bot", League.FRC)
                .copy(status = BatteryPoolStatus.RETIRED),
        )
        val checkins = listOf(
            BatteryCheckinRecord("c1", "BAT-A", "23247", "2026", "bot", restingVoltage = 12.9, beakResistanceMilliohms = 13.0, recordedAtMs = 5_000L),
            BatteryCheckinRecord("c2", "BAT-C", "23247", "2026", "bot", restingVoltage = 13.0, beakResistanceMilliohms = 11.0, recordedAtMs = 5_000L),
        )
        // BAT-B climbs 14 -> 18 -> 23 -> 28 mOhm (~4.7 mOhm/run slope > FRC limit 2.0)
        val bRuns = listOf(14.0, 18.0, 23.0, 28.0).mapIndexed { i, mohm ->
            val sid = "hw-b-$i"
            val ts = (i + 1) * 1_000L
            Session(sid, "23247", "2026", "bot", createdAt = ts, tags = listOf("bat:BAT-B")) to
                SessionSummary(sessionId = sid, teamId = "23247", seasonId = "2026", robotId = "bot", createdAt = ts, minBatteryVoltage = 11.0 - i * 0.2, avgBatteryResistance = mohm / 1000.0)
        }
        val aSession = Session("hw-a-0", "23247", "2026", "bot", createdAt = 500L, tags = listOf("bat:BAT-A"))
        val aSummary = SessionSummary(sessionId = "hw-a-0", teamId = "23247", seasonId = "2026", robotId = "bot", createdAt = 500L, minBatteryVoltage = 11.2, avgBatteryResistance = 0.013)

        val fleet = BatteryFleetAnalyzer.analyzeFleet(
            league = League.FRC,
            specs = specs,
            checkins = checkins,
            sessions = bRuns.map { it.first } + aSession,
            summaries = bRuns.map { it.second } + aSummary,
        ).associateBy { it.batteryId }

        assertEquals(BatteryHealthGrade.COMPETITION_PRIME, fleet.getValue("BAT-A").grade)
        val degrading = fleet.getValue("BAT-B")
        assertEquals(BatteryHealthGrade.RETIRE_OR_INSPECT, degrading.grade)
        assertTrue((degrading.resistanceSlopeMilliohmsPerRun ?: 0.0) > 2.0)

        val recommended = BatteryFleetAnalyzer.recommendNextMatchBattery(fleet.values.toList())
        assertNotNull(recommended)
        assertEquals("BAT-A", recommended.batteryId)
    }
}
