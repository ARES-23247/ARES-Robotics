@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui.components.dashboard

import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.XrpLinkService
import com.ares.analytics.service.XrpRequestedMode
import com.ares.analytics.ui.theme.AresTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrpSimulatorControlBarTest {

    @Test
    fun `live XRP Driver Station gates actions behind authorization toggles gamepad and launches selected autonomous`() {
        val tempDir = Files.createTempDirectory("xrp-ds-control-bar-test-").toFile()
        val aresDir = File(tempDir, ".ares").apply { mkdirs() }
        val routinesDir = File(aresDir, "routines").apply { mkdirs() }
        for ((routineId, routineName) in listOf("orbit-preview" to "Orbit preview", "forward-score" to "Forward Score")) {
            File(routinesDir, "$routineId.aresroutine").writeText(
                """
                {
                  "schemaVersion": 2,
                  "documentId": "$routineId",
                  "revision": 1,
                  "name": "$routineName",
                  "description": "Test XRP route",
                  "steps": [
                    {
                      "kind": "DRIVE_TO",
                      "stepId": "step-1",
                      "arguments": {},
                      "drive": {
                        "target": {"xMeters": 0.4, "yMeters": 0.1, "headingRadians": 0.0},
                        "motionPresetKey": "safe",
                        "markers": [],
                        "duringActionKeys": [],
                        "arrivalActionKeys": []
                      },
                      "children": [],
                      "elseChildren": []
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
        File(aresDir, "autonomous-catalog.json").writeText(
            """
            {
              "schemaVersion": 1,
              "projectId": "xrp-live-test",
              "revision": 1,
              "defaultEntryId": "orbit-preview",
              "entries": [
                {
                  "entryId": "orbit-preview",
                  "displayName": "Orbit preview",
                  "description": "Default preview",
                  "routineId": "orbit-preview",
                  "startingPose": {"xMeters": 0.0, "yMeters": 0.0, "headingRadians": 0.0},
                  "authoredAlliance": "RED",
                  "mirrorForOppositeAlliance": false,
                  "sortOrder": 0,
                  "enabled": true
                },
                {
                  "entryId": "forward-score",
                  "displayName": "Forward Score",
                  "description": "Drive forward to score",
                  "routineId": "forward-score",
                  "startingPose": {"xMeters": -0.5, "yMeters": 0.2, "headingRadians": 0.0},
                  "authoredAlliance": "RED",
                  "mirrorForOppositeAlliance": false,
                  "sortOrder": 1,
                  "enabled": true
                }
              ]
            }
            """.trimIndent(),
        )
        val database = DatabaseService(File(tempDir, "test.duckdb").absolutePath)
        val telemetry = Nt4ClientService(database)
        val server = ServerSocket(0)
        val xrpLink = XrpLinkService(telemetry) {
            object : Socket() {
                override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                    super.connect(InetSocketAddress("127.0.0.1", server.localPort), timeout)
                }
            }
        }
        val keyboardState = KeyboardDriveState()
        val holdPeer = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        val serverJob = scope.launch {
            server.accept().use { socket ->
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(
                    "{\"protocol\":\"ares-xrp/1\",\"type\":\"hello\",\"role\":\"robot\"," +
                        "\"projectId\":\"xrp-live-test\",\"canonicalContentSha256\":\"${"a".repeat(64)}\"," +
                        "\"contentSha256\":\"${"a".repeat(64)}\",\"drivetrainType\":\"differential\"," +
                        "\"boardType\":\"RP2350\"}\n",
                )
                writer.flush()
                holdPeer.await()
            }
        }

        try {
            runComposeUiTest {
                setContent {
                    val isConnected by xrpLink.isConnected.collectAsState()
                    AresTheme {
                        Surface {
                            XrpSimulatorControlBar(
                                xrpLink = xrpLink,
                                keyboardDriveState = keyboardState,
                                projectPath = tempDir.absolutePath,
                                isConnected = isConnected,
                                isSimulatorProcessRunning = false,
                                isLaunchPreparationRunning = false,
                                canLaunchSimulator = true,
                                simulatorLaunchDisabledReason = null,
                                onLaunchSimulator = {},
                                isLocalSimulator = false,
                                liveHostIp = "192.168.4.1",
                                gamepadConnected = true,
                            )
                        }
                    }
                }

                onNodeWithText("XRP Driver Station · Waiting for XRP @ 192.168.4.1:5811").assertIsDisplayed()
                onNodeWithTag("xrp-authorize-live-button").assertIsNotEnabled()
                onNodeWithTag("xrp-init-button").assertIsNotEnabled()
                onNodeWithTag("xrp-start-teleop-button").assertIsNotEnabled()
                onNodeWithTag("xrp-start-auto-button").assertIsNotEnabled()

                xrpLink.start("192.168.4.1", server.localPort, "xrp-live-test", "a".repeat(64))
                waitUntil(timeoutMillis = 3_000) {
                    xrpLink.isConnected.value &&
                        onAllNodesWithText("Orbit preview").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("XRP Driver Station · Connected to xrp-live-test @ 192.168.4.1").assertIsDisplayed()
                onNodeWithTag("xrp-authorize-live-button").assertIsEnabled()
                onNodeWithTag("xrp-init-button").assertIsNotEnabled()
                onNodeWithTag("xrp-start-teleop-button").assertIsNotEnabled()
                onNodeWithTag("xrp-start-auto-button").assertIsNotEnabled()

                onNodeWithTag("xrp-authorize-live-button").performClick()
                onNodeWithTag("xrp-disarm-live-button").assertIsDisplayed()
                onNodeWithTag("xrp-init-button").assertIsEnabled()
                onNodeWithTag("xrp-start-teleop-button").assertIsEnabled()
                onNodeWithTag("xrp-start-auto-button").assertIsEnabled()

                onNodeWithTag("xrp-input-mode-button").performClick()
                assertTrue(keyboardState.useGamepad)
                onNodeWithText("Input: Gamepad (Ready)").assertIsDisplayed()

                onNodeWithTag("xrp-auto-selector-button").performClick()
                onNodeWithTag("xrp-auto-option-forward-score").performClick()
                onNodeWithTag("xrp-start-auto-button").performClick()
                assertEquals(XrpRequestedMode.AUTONOMOUS, xrpLink.controlRequest.value.mode)
                assertEquals("forward-score", xrpLink.controlRequest.value.autonomousId)
                assertFalse(keyboardState.enabled)

                onNodeWithTag("xrp-start-teleop-button").performClick()
                assertEquals(XrpRequestedMode.TELEOP, xrpLink.controlRequest.value.mode)
                assertTrue(keyboardState.enabled)

                onNodeWithTag("xrp-disarm-live-button").performClick()
                assertFalse(xrpLink.liveControlAuthorized.value)
                assertFalse(keyboardState.enabled)
                assertEquals(XrpRequestedMode.STOPPED, xrpLink.controlRequest.value.mode)
            }
        } finally {
            holdPeer.complete(Unit)
            runBlocking {
                xrpLink.disposeAndJoin()
                telemetry.disposeAndJoin()
                database.closeAndJoin()
                runCatching { server.close() }
                serverJob.cancel()
                delay(20)
            }
            tempDir.deleteRecursively()
        }
    }
}
