package com.ares.analytics.mcp

import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldDocument
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Wave2ConfigCatalogAndFieldTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `mutates project metadata action catalog and autonomous catalog with rollback on broken routine ref`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-config-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projectDir = File(tempRoot, "ftc-config-bot")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            engine.initProject(projectDir, "FTC", displayName = "Config Bot", initGit = true)
            val metaRes = mutators.configAndField.mutateProjectConfig(
                projectRoot = projectDir,
                configKindRaw = "PROJECT_METADATA",
                operationRaw = "PATCH",
                patchJson = """{"authoringModel":"HYBRID"}""",
            )
            assertEquals(".ares/project.json", metaRes.filePath)
            assertEquals(
                AresProjectAuthoringModel.HYBRID,
                AresProjectMetadataCodec.decode(File(projectDir, ".ares/project.json").readText()).authoringModel,
            )

            val actionAdd = mutators.configAndField.mutateProjectConfig(
                projectRoot = projectDir,
                configKindRaw = "ACTION_CATALOG",
                operationRaw = "PATCH",
                patchJson = """
                    {
                      "actions": [
                        {
                          "key": "custom.intake.pulse",
                          "displayName": "Pulse Intake",
                          "description": "Pulses the intake roller cleanly.",
                          "category": "Custom",
                          "allowedContexts": ["TELEOP", "AUTONOMOUS"]
                        }
                      ]
                    }
                """.trimIndent(),
            )
            assertTrue(actionAdd.documentJson.orEmpty().contains("custom.intake.pulse"))

            val beforeAutoCatalog = File(projectDir, ".ares/autonomous-catalog.json").readText()
            assertFailsWith<Exception> {
                mutators.configAndField.mutateProjectConfig(
                    projectRoot = projectDir,
                    configKindRaw = "AUTONOMOUS_CATALOG",
                    operationRaw = "PATCH",
                    patchJson = """
                        {
                          "entries": [
                            {
                              "entryId": "broken-auto",
                              "displayName": "Broken Auto",
                              "routineId": "nonexistent-routine-id",
                              "authoredAlliance": "RED"
                            }
                          ]
                        }
                    """.trimIndent(),
                )
            }
            assertEquals(beforeAutoCatalog, File(projectDir, ".ares/autonomous-catalog.json").readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `mutates canonical league field paths with apriltag import and backup history`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-field-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val ftcDir = File(tempRoot, "ftc-field-bot")
        val frcDir = File(tempRoot, "frc-field-bot")
        val engine = HeadlessProjectEngine(StudioBridgeClient(bridgeDir), templateDir = resolveTemplateDir())
        val mutators = HeadlessProjectMutators(engine)
        try {
            engine.initProject(ftcDir, "FTC", displayName = "FTC Field", initGit = true)
            val ftcPreset = mutators.configAndField.mutateField(
                projectRoot = ftcDir,
                operationRaw = "INSTALL_PRESET",
                presetName = "VISION_CALIBRATION_TARGET_1M",
            )
            assertEquals("TeamCode/src/main/assets/paths/field.json", ftcPreset.filePath)
            val ftcFieldFile = File(ftcDir, ftcPreset.filePath)
            assertTrue(ftcFieldFile.isFile)
            assertEquals(FieldType.FTC, RobotFieldDocument.decode(ftcFieldFile.readText()).fieldType)

            // Apply a WPILib-style AprilTag JSON import and confirm .ares/history/fields backup was written
            val wpilibTagsJson = """
                {
                  "tags": [
                    {
                      "ID": 12,
                      "pose": {
                        "translation": { "x": 1.0, "y": 0.25, "z": 0.30 },
                        "rotation": { "quaternion": { "W": 0.0, "X": 0.0, "Y": 0.0, "Z": 1.0 } }
                      }
                    }
                  ],
                  "field": { "length": 3.6576, "width": 3.6576 }
                }
            """.trimIndent()
            val imported = mutators.configAndField.mutateField(
                projectRoot = ftcDir,
                operationRaw = "IMPORT_APRILTAGS",
                aprilTagSourceJson = wpilibTagsJson,
            )
            assertNotNull(imported.backupPath)
            assertTrue(File(ftcDir, imported.backupPath).isFile)
            assertEquals(12, RobotFieldDocument.decode(ftcFieldFile.readText()).apriltags.single().id)

            engine.initProject(frcDir, "FRC", displayName = "FRC Field", initGit = true)
            val frcPreset = mutators.configAndField.mutateField(
                projectRoot = frcDir,
                operationRaw = "INSTALL_PRESET",
                presetName = "THREE_CONE_SLALOM_COURSE",
            )
            assertEquals("src/main/deploy/paths/field.json", frcPreset.filePath)
            assertEquals(3, RobotFieldDocument.decode(File(frcDir, frcPreset.filePath).readText()).obstacles.size)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `compact mode descriptor mutate routes PROJECT_CONFIG and FIELD over stdio MCP`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-w2-compact-route-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projectDir = File(tempRoot, "robot-frc")
        withTimeout(30_000L) {
            val c2sIn = PipedInputStream(1024 * 1024)
            val c2sOut = PipedOutputStream(c2sIn)
            val s2cIn = PipedInputStream(1024 * 1024)
            val s2cOut = PipedOutputStream(s2cIn)
            val server = AresMcpServerFactory.create(McpToolsetMode.COMPACT, bridgeDir, resolveTemplateDir())
            val job = launch(Dispatchers.IO) {
                server.createSession(StdioServerTransport(c2sIn.asSource().buffered(), s2cOut.asSink().buffered()))
            }
            val client = Client(Implementation("w2-compact-client", "1.0.0"), ClientOptions())
            val clientTransport = StdioClientTransport(s2cIn.asSource().buffered(), c2sOut.asSink().buffered())
            client.connect(clientTransport)
            try {
                assertFalse(
                    client.callTool(
                        "ares_project_init",
                        mapOf("targetDir" to projectDir.absolutePath, "league" to "FRC", "displayName" to "Compact W2", "initGit" to true),
                    ).isError == true,
                )
                val cfgRes = client.callTool(
                    "ares_descriptor_mutate",
                    mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "descriptorKind" to "PROJECT_CONFIG",
                        "operation" to "PATCH",
                        "patchJson" to """{"authoringModel":"HYBRID"}""",
                    ),
                )
                assertFalse(
                    cfgRes.isError == true,
                    "PROJECT_CONFIG failed: ${(cfgRes.content.firstOrNull() as? TextContent)?.text}",
                )
                val fldRes = client.callTool(
                    "ares_descriptor_mutate",
                    mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "descriptorKind" to "FIELD",
                        "operation" to "INSTALL_PRESET",
                        "presetName" to "LEAGUE_DEFAULT",
                    ),
                )
                assertFalse(
                    fldRes.isError == true,
                    "FIELD failed: ${(fldRes.content.firstOrNull() as? TextContent)?.text}",
                )
                val fieldPath = json.parseToJsonElement((fldRes.content.first() as TextContent).text)
                    .jsonObject["filePath"]?.jsonPrimitive?.content
                assertEquals("src/main/deploy/paths/field.json", fieldPath)
            } finally {
                runCatching { client.close() }
                runCatching { server.close() }
                job.cancel()
                tempRoot.deleteRecursively()
            }
        }
    }
}
