@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ares.analytics.domain.navigation.PitDeepLinkRequest
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.domain.pit.BatteryCheckinRecord
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.EventScheduleStore
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.screens.HardwareSelfTestWizard
import com.ares.analytics.ui.screens.MatchStrategyScreen
import com.ares.analytics.ui.theme.AresTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PitOpsAndMatchStrategyUiTest {

    private fun testWorkspace() = WorkspaceConfig(
        id = "ws-pit-ui-test",
        teamId = "23247",
        seasonId = "2026",
        robotId = "alpha",
        robotName = "Alpha Claw",
        league = League.FTC,
        projectPath = "",
        eventCode = "USMOSC",
    )

    private suspend fun seedDatabase(db: DatabaseService, ws: WorkspaceConfig) {
        db.upsertBatterySpec(BatterySpecRecord.defaultForLeague("BAT-07", ws.teamId, ws.seasonId, ws.robotId, ws.league))
        db.recordBatteryCheckin(
            BatteryCheckinRecord(
                checkinId = "chk-bat07",
                batteryId = "BAT-07",
                teamId = ws.teamId,
                seasonId = ws.seasonId,
                robotId = ws.robotId,
                restingVoltage = 13.08,
                beakResistanceMilliohms = 21.5,
                stateOfChargePercent = 99.0,
                inspectorInitials = "DK",
                notes = "Ready for Quals",
                recordedAtMs = 10_000L,
            )
        )
        val session = Session(
            sessionId = "q14-session-1",
            teamId = ws.teamId,
            seasonId = ws.seasonId,
            robotId = ws.robotId,
            createdAt = 20_000L,
            matchNumber = 14,
            allianceColor = "red",
            tags = listOf("bat:BAT-07"),
        )
        val summary = SessionSummary(
            sessionId = session.sessionId,
            teamId = ws.teamId,
            seasonId = ws.seasonId,
            robotId = ws.robotId,
            createdAt = 20_000L,
            durationMs = 150_000L,
            minBatteryVoltage = 11.75,
            avgBatteryResistance = 0.023,
        )
        db.insertSession(session)
        db.insertSessionSummary(summary)
        db.insertTelemetryFrames(
            listOf(
                TelemetryFrame(1_000L, session.sessionId, "/Robot/PoseX", 1.25),
                TelemetryFrame(1_000L, session.sessionId, "/Robot/PoseY", 2.40),
                TelemetryFrame(1_000L, session.sessionId, "/Robot/BatteryVoltage", 12.6),
                TelemetryFrame(2_000L, session.sessionId, "/Robot/PoseX", 2.15),
                TelemetryFrame(2_000L, session.sessionId, "/Robot/PoseY", 3.10),
                TelemetryFrame(2_000L, session.sessionId, "/Robot/BatteryVoltage", 12.1),
            )
        )
    }

    @Test
    fun `pit operations hub honors deep link focus and switches across all four tabs cleanly`() = runComposeUiTest {
        val root = Files.createTempDirectory("pit-ops-ui-test").toFile()
        val db = DatabaseService(root.resolve("pit.duckdb").path)
        val scheduleStore = EventScheduleStore(root.resolve("schedules"))
        val nt4 = Nt4ClientService(db)
        val ws = testWorkspace()
        runBlocking { seedDatabase(db, ws) }
        val matches = listOf(
            MatchInfo(matchNumber = 14, compLevel = "quals", redAlliance = listOf("23247", "4410"), blueAlliance = listOf("11115", "8802"))
        )
        val observedTabs = mutableListOf<PitOperationsSubTab>()
        var deepLink by mutableStateOf(
            PitDeepLinkRequest(
                tab = PitOperationsSubTab.BATTERY_FLEET,
                highlightedBatteryId = "BAT-07",
                nonce = 1,
            )
        )

        try {
            mainClock.autoAdvance = false
            setContent {
                AresTheme {
                    Surface {
                        HardwareSelfTestWizard(
                            nt4ClientService = nt4,
                            workspace = ws,
                            databaseService = db,
                            eventScheduleStore = scheduleStore,
                            matches = matches,
                            pitDeepLink = deepLink,
                            onActiveTabChanged = { observedTabs += it },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                mainClock.advanceTimeBy(100)
                onAllNodesWithText("BAT-07").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("BAT-07").assertIsDisplayed()
            onNodeWithText("Focused").assertIsDisplayed()
            assertTrue(PitOperationsSubTab.BATTERY_FLEET in observedTabs)

            onNodeWithText(PitOperationsSubTab.MATCH_QUEUE.label).performClick()
            waitUntil(timeoutMillis = 5_000L) {
                mainClock.advanceTimeBy(100)
                onAllNodesWithText("Quals #14").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Quals #14").assertIsDisplayed()

            onNodeWithText(PitOperationsSubTab.ALERT_THRESHOLDS.label).performClick()
            mainClock.advanceTimeBy(200)
            onNodeWithText(PitOperationsSubTab.READINESS.label).performClick()
            mainClock.advanceTimeBy(200)
            assertTrue(PitOperationsSubTab.ALERT_THRESHOLDS in observedTabs)
            assertTrue(PitOperationsSubTab.READINESS in observedTabs)

            val scene = ImageComposeScene(width = 1100, height = 760) {
                AresTheme {
                    Surface {
                        HardwareSelfTestWizard(
                            nt4ClientService = nt4,
                            workspace = ws,
                            databaseService = db,
                            eventScheduleStore = scheduleStore,
                            matches = matches,
                            pitDeepLink = PitDeepLinkRequest(
                                tab = PitOperationsSubTab.BATTERY_FLEET,
                                highlightedBatteryId = "BAT-07",
                                nonce = 2,
                            ),
                        )
                    }
                }
            }
            val encoded = assertNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
            val outFile = root.resolve("pit_battery_fleet_ui.png")
            outFile.writeBytes(encoded.bytes)
            assertTrue(outFile.exists() && outFile.length() > 10_000L, "Expected non-trivial Pit Battery Fleet PNG")
            scene.close()
        } finally {
            runBlocking { nt4.stop() }
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `match strategy screen reports selected match sub-context and triggers replay jump`() = runComposeUiTest {
        val root = Files.createTempDirectory("match-strategy-ui-test").toFile()
        val db = DatabaseService(root.resolve("strategy.duckdb").path)
        val exportService = ExportService(db)
        val ws = testWorkspace()
        runBlocking { seedDatabase(db, ws) }
        val matches = listOf(
            MatchInfo(matchNumber = 14, compLevel = "quals", redAlliance = listOf("23247", "4410"), blueAlliance = listOf("11115", "8802"))
        )
        val reportedSubContexts = mutableListOf<String?>()
        var openedReplaySessionId: String? = null

        try {
            mainClock.autoAdvance = false
            setContent {
                AresTheme {
                    Surface {
                        MatchStrategyScreen(
                            databaseService = db,
                            exportService = exportService,
                            workspace = ws,
                            matches = matches,
                            onOpenReplay = { id, _ -> openedReplaySessionId = id },
                            onSubContextChanged = { reportedSubContexts += it },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                mainClock.advanceTimeBy(100)
                onAllNodesWithText("Open Full Replay →").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(reportedSubContexts.any { it == "Match #14" }, "Expected subContext to report Match #14, saw $reportedSubContexts")
            onNodeWithText("Open Full Replay →").performClick()
            mainClock.advanceTimeBy(100)
            assertEquals("q14-session-1", openedReplaySessionId)

            val scene = ImageComposeScene(width = 1100, height = 760) {
                AresTheme {
                    Surface {
                        MatchStrategyScreen(
                            databaseService = db,
                            exportService = exportService,
                            workspace = ws,
                            matches = matches,
                        )
                    }
                }
            }
            val encoded = assertNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
            val outFile = root.resolve("match_strategy_ui.png")
            outFile.writeBytes(encoded.bytes)
            assertTrue(outFile.exists() && outFile.length() > 10_000L, "Expected non-trivial Match Strategy PNG")
            scene.close()
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
