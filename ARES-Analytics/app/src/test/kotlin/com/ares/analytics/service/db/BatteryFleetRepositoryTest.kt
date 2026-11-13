package com.ares.analytics.service.db

import com.ares.analytics.domain.pit.BatteryCheckinRecord
import com.ares.analytics.domain.pit.BatteryPoolStatus
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.domain.pit.ScheduledMatchItem
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.EventScheduleStore
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryFleetRepositoryTest {

    @Test
    fun `persists battery specs checkins and status with workspace isolation in DuckDB`() = runTest {
        val dir = Files.createTempDirectory("battery-repo-test").toFile()
        val db = DatabaseService(dir.resolve("fleet.duckdb").path)
        try {
            val specA = BatterySpecRecord(
                batteryId = "bat 01",
                teamId = "23247",
                seasonId = "2026",
                robotId = "bot-a",
                label = "Main Comp 01",
                nominalVoltage = 12.0,
                capacityAh = 3.0,
                status = BatteryPoolStatus.READY,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            )
            val otherWsSpec = specA.copy(batteryId = "BAT-99", teamId = "99999")
            db.upsertBatterySpec(specA)
            db.upsertBatterySpec(otherWsSpec)

            val wsASpecs = db.listBatterySpecsForWorkspace("23247", "2026", "bot-a")
            assertEquals(listOf("BAT-01"), wsASpecs.map { it.batteryId })

            db.updateBatteryPoolStatus("23247", "2026", "bot-a", "BAT-01", BatteryPoolStatus.IN_ROBOT, 2_000L)
            assertEquals(
                BatteryPoolStatus.IN_ROBOT,
                db.listBatterySpecsForWorkspace("23247", "2026", "bot-a").single().status,
            )

            db.recordBatteryCheckin(
                BatteryCheckinRecord(
                    checkinId = "chk-1",
                    batteryId = "bat 01",
                    teamId = "23247",
                    seasonId = "2026",
                    robotId = "bot-a",
                    restingVoltage = 13.05,
                    beakResistanceMilliohms = 24.5,
                    stateOfChargePercent = 98.0,
                    inspectorInitials = "DK",
                    recordedAtMs = 3_000L,
                )
            )
            val checkins = db.listBatteryCheckinsForWorkspace("23247", "2026", "bot-a")
            assertEquals(1, checkins.size)
            assertEquals("BAT-01", checkins.single().batteryId)
            assertEquals(24.5, checkins.single().beakResistanceMilliohms)

            db.deleteBatterySpecFromWorkspace("23247", "2026", "bot-a", "BAT-01")
            assertTrue(db.listBatterySpecsForWorkspace("23247", "2026", "bot-a").isEmpty())
            assertEquals(1, db.listBatterySpecsForWorkspace("99999", "2026", "bot-a").size)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `offline event schedule store imports CSV upserts manual matches and reloads atomically`() = runTest {
        val dir = Files.createTempDirectory("schedule-store-test").toFile()
        try {
            val store = EventScheduleStore(dir.resolve("schedules"))
            val ws = WorkspaceConfig(
                id = "ftc-23247-2026",
                teamId = "23247",
                seasonId = "2026",
                robotId = "bot-a",
                league = League.FTC,
                projectPath = "",
            )
            val csvFile = dir.resolve("schedule.csv").apply {
                writeText("comp_level,match_number,red1,red2,blue1,blue2\nquals,1,23247,16417,11115,18225\n")
            }

            val imported = store.importScheduleFile(ws, csvFile)
            assertEquals(listOf(1), imported.map { it.matchNumber })

            val afterManual = store.upsertManualMatch(
                ws,
                ScheduledMatchItem(
                    matchNumber = 5,
                    compLevel = "quals",
                    redAlliance = listOf("11115", "9999"),
                    blueAlliance = listOf("23247", "16417"),
                ),
            )
            assertEquals(listOf(1, 5), afterManual.map { it.matchNumber })

            val reloaded = store.loadCachedMatches(ws)
            assertEquals(listOf(1, 5), reloaded.map { it.matchNumber })

            val afterRemove = store.removeMatch(ws, "quals", 1)
            assertEquals(listOf(5), afterRemove.map { it.matchNumber })
        } finally {
            dir.deleteRecursively()
        }
    }
}
