@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.ares.analytics.domain.navigation.MatchStrategySubTab
import com.ares.analytics.domain.navigation.StrategyDeepLinkRequest
import com.ares.analytics.domain.pit.EventScheduleCodec
import com.ares.analytics.domain.pit.ScheduledMatchItem
import com.ares.analytics.domain.strategy.*
import com.ares.analytics.service.EventScoutingStore
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.CommandPaletteStrategyTeamItem
import com.ares.analytics.ui.components.CommandPaletteSurfaceContent
import com.ares.analytics.ui.components.pit.PitMatchQueueTab
import com.ares.analytics.ui.screens.MatchStrategyScreen
import com.ares.analytics.ui.theme.AresTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrategyScoutingAndPicklistUiTest {

    private fun workspace() = WorkspaceConfig(
        id = "ws-strategy-ui",
        teamId = "23247",
        seasonId = "2026",
        robotId = "alpha",
        robotName = "Alpha",
        league = League.FTC,
        projectPath = "",
        eventCode = "USMOSC",
    )

    private fun seededBundle() = EventScoutingBundle(
        eventCode = "USMOSC",
        league = League.FTC,
        matchResults = listOf(
            EventMatchResult(1, "quals", listOf("23247", "11115"), listOf("4410", "8802"), 98, 62, 6, 0),
            EventMatchResult(2, "quals", listOf("23247", "4410"), listOf("11115", "8802"), 76, 84, 0, 4),
            EventMatchResult(3, "quals", listOf("11115", "4410"), listOf("23247", "8802"), 94, 70, 0, 0),
        ),
        pitProfiles = listOf(
            PitTeamProfile("23247", drivetrain = ScoutedDrivetrain.SWERVE, preferredAutoZone = AutoStartZone.LEFT, endgameCapability = EndgameTier.HIGH_CLIMB),
            PitTeamProfile("11115", drivetrain = ScoutedDrivetrain.SWERVE, preferredAutoZone = AutoStartZone.LEFT, endgameCapability = EndgameTier.HIGH_CLIMB),
            PitTeamProfile("4410", drivetrain = ScoutedDrivetrain.MECANUM, preferredAutoZone = AutoStartZone.RIGHT, endgameCapability = EndgameTier.PARK),
        ),
        standObservations = listOf(
            StandMatchObservation("11115", "quals", 1, autoSucceeded = true, estimatedCycles = 7, endgameAchieved = EndgameTier.HIGH_CLIMB, scoutAuthor = "Alice"),
            StandMatchObservation("8802", "quals", 1, autoSucceeded = false, estimatedCycles = 2, diedOrCommsLost = true, scoutAuthor = "Bob"),
        ),
    )

    @Test
    fun `match strategy hub renders all four tabs OPR leaderboard collision alert and live picklist draft filter`() = runComposeUiTest {
        val dir = Files.createTempDirectory("strategy-ui-test").toFile()
        try {
            val ws = workspace()
            val store = EventScoutingStore(dir.resolve("scouting"))
            runBlocking { store.saveBundle(ws, seededBundle()) }
            val matches = listOf(
                MatchInfo(14, "quals", listOf("23247", "11115"), listOf("4410", "8802")),
            )

            setContent {
                AresTheme {
                    Surface {
                        MatchStrategyScreen(
                            eventScoutingStore = store,
                            workspace = ws,
                            matches = matches,
                            strategyDeepLink = StrategyDeepLinkRequest(
                                tab = MatchStrategySubTab.OPR_SCOUTING,
                                highlightedTeamNumber = "11115",
                                nonce = 1,
                            ),
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithText("OPR / NP-OPR / ccWM & Multi-Scout Analytics").assertIsDisplayed()
            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithText("Team #11115").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Team #11115").assertIsDisplayed()

            // Jump to Tab 2: Matchup Briefing
            onNodeWithText("Matchup Briefing").performClick()
            waitForIdle()
            onNodeWithText("*M#14").performClick()
            waitForIdle()
            onNodeWithText("Pre-Match Strategy Briefing — QUALS #14").assertIsDisplayed()
            onAllNodesWithText("Team 11115", substring = true).onFirst().assertIsDisplayed()
            onAllNodesWithText("Auto-Zone Collision Risk: Partner Team 11115", substring = true).onFirst().assertIsDisplayed()

            // Jump to Tab 3: Alliance Picklist and test live draft Picked + Hide Taken
            onNodeWithText("Alliance Picklist").performClick()
            waitForIdle()
            onNodeWithText("Live Alliance Selection Picklist — USMOSC").assertIsDisplayed()
            onAllNodesWithText("Picked").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithText("Hide Taken (1)").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Hide Taken (1)").performClick()
            waitForIdle()

            // Verify off-screen Skia PNG render stays crisp & non-blank
            val scene = ImageComposeScene(width = 1400, height = 900) {
                AresTheme {
                    Surface {
                        MatchStrategyScreen(
                            eventScoutingStore = store,
                            workspace = ws,
                            matches = matches,
                            strategyDeepLink = StrategyDeepLinkRequest(
                                tab = MatchStrategySubTab.ALLIANCE_PICKLIST,
                                nonce = 2,
                            ),
                        )
                    }
                }
            }
            try {
                val encoded = assertNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
                assertTrue(encoded.bytes.size > 10_000, "Rendered PNG was unexpectedly small: ${encoded.bytes.size} bytes")
            } finally {
                scene.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pit match queue briefing button emits exact match deep link`() = runComposeUiTest {
        var briefingMatch: Int? = null
        val rows = EventScheduleCodec.buildQueueRows(
            items = listOf(
                ScheduledMatchItem(19, "quals", listOf("23247", "11115"), listOf("4410", "8802")),
            ),
            teamId = "23247",
            sessions = emptyList(),
        )

        setContent {
            AresTheme {
                Surface {
                    PitMatchQueueTab(
                        workspace = workspace(),
                        rows = rows,
                        statusMessage = null,
                        onSyncApi = {},
                        onImportScheduleFile = {},
                        onUpsertManualMatch = {},
                        onRemoveMatch = { _, _ -> },
                        onStageInReadiness = {},
                        onOpenMatchStrategy = {},
                        onOpenMatchupBriefing = { briefingMatch = it },
                    )
                }
            }
        }

        onNodeWithText("Briefing").performClick()
        assertEquals(19, briefingMatch)
    }

    @Test
    fun `omnibox scouted team jump emits exact team deep link`() = runComposeUiTest {
        var jumpedTeam: String? = null
        setContent {
            AresTheme {
                Surface {
                    CommandPaletteSurfaceContent(
                        developerMode = false,
                        strategyTeams = listOf(
                            CommandPaletteStrategyTeamItem(
                                teamNumber = "11115",
                                label = "Team #11115 — Swerve",
                                subtitle = "42.0 NP-OPR · Auto: Right",
                                npOprLabel = "42.0 NP-OPR",
                                keywords = listOf("11115", "team 11115"),
                            ),
                        ),
                        onDismiss = {},
                        onNavigate = {},
                        onOpenScoutedTeam = { jumpedTeam = it },
                    )
                }
            }
        }
        onNodeWithTag("command-palette-search").performTextInput("11115")
        onNodeWithText("Team #11115 — Swerve").performClick()
        assertEquals("11115", jumpedTeam)
    }
}
