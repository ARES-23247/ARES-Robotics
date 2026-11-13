@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.ui.components.CommandPaletteBatteryItem
import com.ares.analytics.ui.components.CommandPaletteMatchItem
import com.ares.analytics.ui.components.CommandPaletteSurfaceContent
import com.ares.analytics.ui.components.SectionNavigationBar
import com.ares.analytics.ui.theme.AresTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandPaletteAndBreadcrumbUiTest {

    private fun selectedMatcher(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    @Test
    fun `command palette supports arrow navigation enter deep-jump and renders crisp Skia PNG`() = runComposeUiTest {
        var openedScreen: NavigationTarget? = null
        var openedBattery: String? = null
        var openedMatch: Int? = null
        var dismissedCount = 0

        setContent {
            AresTheme {
                Surface {
                    CommandPaletteSurfaceContent(
                        developerMode = false,
                        recentTargets = listOf(NavigationTarget.DASHBOARD, NavigationTarget.MATCH_STRATEGY),
                        batteryItems = listOf(
                            CommandPaletteBatteryItem(
                                batteryId = "BAT-03",
                                label = "Battery BAT-03",
                                subtitle = "Ready · 4 cycles · 21.0 mΩ · 13.05 V",
                                gradeLabel = "Competition Prime",
                                keywords = listOf("bat-03", "battery", "beak"),
                            )
                        ),
                        matchItems = listOf(
                            CommandPaletteMatchItem(
                                matchNumber = 19,
                                compLevel = "quals",
                                label = "Match #19 (quals)",
                                subtitle = "BLUE · Red [1115] vs Blue [23247]",
                                keywords = listOf("match 19", "match #19", "19"),
                            )
                        ),
                        onDismiss = { dismissedCount += 1 },
                        onNavigate = { openedScreen = it },
                        onOpenBattery = { openedBattery = it },
                        onOpenScheduledMatch = { openedMatch = it },
                    )
                }
            }
        }

        onNodeWithText("↑/↓ Select · Enter Open · Esc Close").assertIsDisplayed()
        onNodeWithTag("palette-entry-recent-DASHBOARD").assert(selectedMatcher("selected"))
        onNodeWithTag("palette-entry-recent-MATCH_STRATEGY").assert(selectedMatcher("unselected"))

        onNodeWithTag("command-palette-search").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        onNodeWithTag("palette-entry-recent-MATCH_STRATEGY").assert(selectedMatcher("selected"))

        onNodeWithTag("command-palette-search").performKeyInput {
            pressKey(Key.Enter)
        }
        assertEquals(NavigationTarget.MATCH_STRATEGY, openedScreen)
        assertEquals(1, dismissedCount)

        onNodeWithTag("command-palette-search").performTextInput("BAT-03")
        onNodeWithTag("palette-entry-battery-BAT-03").assert(selectedMatcher("selected"))
        onNodeWithTag("command-palette-search").performKeyInput {
            pressKey(Key.Enter)
        }
        assertEquals("BAT-03", openedBattery)
        assertEquals(2, dismissedCount)

        val tempDir = Files.createTempDirectory("palette-ui-snapshot").toFile()
        try {
            val scene = ImageComposeScene(width = 660, height = 560) {
                AresTheme {
                    Surface {
                        CommandPaletteSurfaceContent(
                            developerMode = false,
                            recentTargets = listOf(NavigationTarget.PIT_DIAGNOSTICS),
                            batteryItems = listOf(
                                CommandPaletteBatteryItem("BAT-03", "Battery BAT-03", "Ready · 21.0 mΩ", "Competition Prime")
                            ),
                            onDismiss = {},
                            onNavigate = {},
                        )
                    }
                }
            }
            val encoded = assertNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
            val outFile = tempDir.resolve("command_palette_ui.png")
            outFile.writeBytes(encoded.bytes)
            assertTrue(outFile.exists() && outFile.length() > 10_000L, "Expected non-trivial palette snapshot PNG")
            scene.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `contextual breadcrumbs and back forward buttons invoke expected navigation callbacks`() = runComposeUiTest {
        val clickedTargets = mutableListOf<NavigationTarget>()
        var backClicks = 0
        var forwardClicks = 0

        setContent {
            AresTheme {
                Surface {
                    SectionNavigationBar(
                        activeTarget = NavigationTarget.PIT_DIAGNOSTICS,
                        onNavigate = { clickedTargets += it },
                        subContextLabel = PitOperationsSubTab.BATTERY_FLEET.label,
                        canGoBack = true,
                        canGoForward = true,
                        onNavigateBack = { backClicks += 1 },
                        onNavigateForward = { forwardClicks += 1 },
                    )
                }
            }
        }

        onAllNodesWithText("Robot").onFirst().assertIsDisplayed()
        onNodeWithText("Pit Operations").assertIsDisplayed()
        onNodeWithText("Battery Fleet").assertIsDisplayed()

        onAllNodesWithText("Robot").onFirst().performClick()
        assertEquals(listOf(NavigationTarget.ROBOT_STUDIO), clickedTargets)

        onNodeWithContentDescription("Navigate back (Alt+Left)").performClick()
        onNodeWithContentDescription("Navigate forward (Alt+Right)").performClick()
        assertEquals(1, backClicks)
        assertEquals(1, forwardClicks)
    }
}
