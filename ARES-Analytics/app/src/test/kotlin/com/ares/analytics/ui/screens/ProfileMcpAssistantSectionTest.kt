@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.ares.analytics.ui.theme.AresTheme
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileMcpAssistantSectionTest {

    @Test
    fun `shows offline status toggles toolsets and renders config snippets for all 5 clients`() = runComposeUiTest {
        setContent {
            AresTheme {
                Surface {
                    ProfileMcpAssistantSection(
                        bridgeDescriptor = null,
                        activeProjectPath = "C:/robots/ftc-bot",
                        detectedLauncherCommand = "ares-robotics-studio" to listOf("--mcp-stdio"),
                    )
                }
            }
        }

        onNodeWithText("AI Coding Assistant (MCP)").assertIsDisplayed()
        onNodeWithText("Headless Ready (Offline)").assertIsDisplayed()

        onNodeWithTag("mcp-toolset-compact").performClick()
        onNodeWithTag("mcp-config-snippet").assert(
            SemanticsMatcher("contains --toolset compact") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString { it.text }
                    .contains("--toolset") &&
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .joinToString { it.text }
                        .contains("compact")
            },
        )

        onNodeWithTag("mcp-client-codex").performClick()
        onNodeWithTag("mcp-config-snippet").assert(
            SemanticsMatcher("contains TOML header") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString { it.text }
                    .contains("[mcp_servers.ares_project]")
            },
        )

        onNodeWithTag("mcp-client-claude").performClick()
        onNodeWithTag("mcp-config-snippet").assert(
            SemanticsMatcher("contains claude mcp add") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString { it.text }
                    .contains("claude mcp add ares-project")
            },
        )

        onNodeWithTag("mcp-client-gemini").performClick()
        onNodeWithTag("mcp-config-snippet").assert(
            SemanticsMatcher("contains gemini settings hint") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString { it.text }
                    .contains("settings.json")
            },
        )

        onNodeWithTag("mcp-client-vscode").performClick()
        onNodeWithTag("mcp-config-snippet").assert(
            SemanticsMatcher("contains vscode servers key") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString { it.text }
                    .contains("\"servers\"")
            },
        )
        assertTrue(true)
    }

    @Test
    fun `shows active loopback port badge when studio bridge is running`() = runComposeUiTest {
        setContent {
            AresTheme {
                Surface {
                    ProfileMcpAssistantSection(
                        bridgeDescriptor = McpBridgeDescriptor(
                            pid = 1234L,
                            port = 51423,
                            bearerToken = "deadbeef",
                            studioVersion = "7.0.79",
                            startedAtEpochMillis = 1_700_000_000_000L,
                        ),
                        detectedLauncherCommand = "ares-robotics-studio" to listOf("--mcp-stdio"),
                    )
                }
            }
        }

        onNodeWithText("Bridge Active · 127.0.0.1:51423").assertIsDisplayed()
    }
}
