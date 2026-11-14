package com.ares.analytics.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AresMcpStdioServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    private fun withMcpClient(
        toolset: McpToolsetMode,
        bridgeDir: File,
        block: suspend (Client) -> Unit,
    ) = runBlocking {
        withTimeout(30_000L) {
            val clientToServerIn = PipedInputStream(1024 * 1024)
            val clientToServerOut = PipedOutputStream(clientToServerIn)
            val serverToClientIn = PipedInputStream(1024 * 1024)
            val serverToClientOut = PipedOutputStream(serverToClientIn)

            val server = AresMcpServerFactory.create(
                toolset = toolset,
                bridgeDir = bridgeDir,
                templateDir = resolveTemplateDir(),
            )
            val serverTransport = StdioServerTransport(
                input = clientToServerIn.asSource().buffered(),
                output = serverToClientOut.asSink().buffered(),
            )
            val clientTransport = StdioClientTransport(
                input = serverToClientIn.asSource().buffered(),
                output = clientToServerOut.asSink().buffered(),
            )

            val sessionJob = launch(Dispatchers.IO) {
                server.createSession(serverTransport)
            }
            val client = Client(
                clientInfo = Implementation(name = "ares-mcp-test-client", version = "1.0.0"),
                options = ClientOptions(),
            )
            client.connect(clientTransport)
            try {
                block(client)
            } finally {
                runCatching { client.close() }
                runCatching { clientTransport.close() }
                runCatching { serverTransport.close() }
                runCatching { server.close() }
                runCatching { clientToServerOut.close() }
                runCatching { serverToClientOut.close() }
                sessionJob.cancel()
            }
        }
    }

    @Test
    fun `cli dispatch ignores desktop args and passes verify smoke check`() {
        val bridgeDir = Files.createTempDirectory("ares-mcp-smoke-").toFile()
        try {
            assertNull(AresMcpMain.runIfMcpCommand(arrayOf("--verify-packaged-project", "foo")))
            val exitCode = AresMcpMain.runIfMcpCommand(
                arrayOf("--verify-mcp-smoke", "--bridge-dir", bridgeDir.absolutePath),
            )
            assertEquals(0, exitCode)
        } finally {
            bridgeDir.deleteRecursively()
        }
    }

    @Test
    fun `full toolset exposes 12 tools resources templates prompts and executes e2e workflow`() {
        val tempRoot = Files.createTempDirectory("ares-mcp-e2e-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projectDir = File(tempRoot, "robot-ftc")
        try {
            withMcpClient(McpToolsetMode.FULL, bridgeDir) { client ->
                val toolNames = client.listTools().tools.map { it.name }.toSet()
                assertEquals(
                    setOf(
                        "ares_project_init",
                        "ares_project_inspect",
                        "ares_project_validate",
                        "ares_subsystem_mutate",
                        "ares_drivetrain_mutate",
                        "ares_superstructure_mutate",
                        "ares_routine_mutate",
                        "ares_controls_mutate",
                        "ares_tuning_mutate",
                        "ares_codegen_reconcile",
                        "ares_kotlin_scaffold",
                        "ares_code_audit",
                    ),
                    toolNames,
                )

                val resourceUris = client.listResources().resources.map { it.uri }.toSet()
                assertEquals(
                    setOf(
                        "ares://conventions/coordinates-and-safety",
                        "ares://conventions/overview",
                        "ares://project/summary",
                        "ares://project/catalog",
                        "ares://verification/latest",
                    ),
                    resourceUris,
                )

                val templateUris = client.listResourceTemplates(ListResourceTemplatesRequest())
                    .resourceTemplates
                    .map { it.uriTemplate }
                    .toSet()
                assertEquals(
                    setOf(
                        "ares://schemas/{documentKind}",
                        "ares://cookbook/{league}/{pattern}",
                    ),
                    templateUris,
                )

                val safetyDoc = (
                    client.readResource(
                        ReadResourceRequest(ReadResourceRequestParams(uri = "ares://conventions/coordinates-and-safety")),
                    ).contents.first() as TextResourceContents
                    ).text
                assertTrue(safetyDoc.contains("CCW"))

                val subsystemSchema = (
                    client.readResource(
                        ReadResourceRequest(ReadResourceRequestParams(uri = "ares://schemas/subsystem")),
                    ).contents.first() as TextResourceContents
                    ).text
                assertTrue(subsystemSchema.contains(".ares/subsystems"))

                val cookbookDoc = (
                    client.readResource(
                        ReadResourceRequest(ReadResourceRequestParams(uri = "ares://cookbook/ftc/hardware-io")),
                    ).contents.first() as TextResourceContents
                    ).text
                assertTrue(cookbookDoc.contains("RobotClock"))

                val prompts = client.listPrompts().prompts.map { it.name }.toSet()
                assertEquals(
                    setOf(
                        "ares-new-subsystem",
                        "ares-build-autonomous",
                        "ares-migrate-hand-authored",
                        "ares-audit-readiness",
                    ),
                    prompts,
                )

                val newSubsystemPrompt = client.getPrompt(
                    GetPromptRequest(
                        GetPromptRequestParams(
                            name = "ares-new-subsystem",
                            arguments = mapOf("subsystemId" to "intake"),
                        ),
                    ),
                )
                assertTrue(newSubsystemPrompt.messages.isNotEmpty())

                val initCall = client.callTool(
                    name = "ares_project_init",
                    arguments = mapOf(
                        "targetDir" to projectDir.absolutePath,
                        "league" to "FTC",
                        "displayName" to "MCP Test FTC Bot",
                        "teamId" to "23247",
                        "robotId" to "AresTestBot",
                        "initGit" to true,
                    ),
                )
                assertFalse(initCall.isError == true)
                val initJson = json.parseToJsonElement((initCall.content.first() as TextContent).text).jsonObject
                assertEquals("FTC", initJson["league"]?.jsonPrimitive?.content)

                val inspectCall = client.callTool(
                    name = "ares_project_inspect",
                    arguments = mapOf("projectPath" to projectDir.absolutePath),
                )
                assertFalse(inspectCall.isError == true)
                val revision = json.parseToJsonElement((inspectCall.content.first() as TextContent).text)
                    .jsonObject["revision"]?.jsonPrimitive?.content
                assertNotNull(revision)

                val activeSummary = (
                    client.readResource(
                        ReadResourceRequest(ReadResourceRequestParams(uri = "ares://project/summary")),
                    ).contents.first() as TextResourceContents
                    ).text
                assertTrue(activeSummary.contains("FTC"))

                val activeCatalog = (
                    client.readResource(
                        ReadResourceRequest(ReadResourceRequestParams(uri = "ares://project/catalog")),
                    ).contents.first() as TextResourceContents
                    ).text
                assertTrue(activeCatalog.contains("tuningKeys"))

                val verificationDoc = (
                    client.readResource(
                        ReadResourceRequest(ReadResourceRequestParams(uri = "ares://verification/latest")),
                    ).contents.first() as TextResourceContents
                    ).text
                assertTrue(verificationDoc.contains("\"valid\": true") || verificationDoc.contains("\"valid\":true"))

                val driveMutate = client.callTool(
                    name = "ares_drivetrain_mutate",
                    arguments = mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "maxLinearSpeedMps" to 3.4,
                        "expectedRevision" to revision,
                    ),
                )
                assertFalse(driveMutate.isError == true)

                val codegenPreview = client.callTool(
                    name = "ares_codegen_reconcile",
                    arguments = mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "mode" to "PREVIEW",
                    ),
                )
                val previewText = (codegenPreview.content.first() as TextContent).text
                assertFalse(codegenPreview.isError == true, "codegenPreview failed: $previewText")
                val previewToken = json.parseToJsonElement(previewText)
                    .jsonObject["confirmationToken"]?.let { el ->
                        runCatching { el.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
                    }

                val applyArgs = buildMap<String, Any?> {
                    put("projectPath", projectDir.absolutePath)
                    put("mode", "APPLY")
                    if (previewToken != null) put("confirmationToken", previewToken)
                }
                val codegenApply = client.callTool(
                    name = "ares_codegen_reconcile",
                    arguments = applyArgs,
                )
                val applyText = (codegenApply.content.first() as TextContent).text
                assertFalse(codegenApply.isError == true, "codegenApply failed: $applyText")

                val scaffoldApply = client.callTool(
                    name = "ares_kotlin_scaffold",
                    arguments = mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "league" to "FTC",
                        "kind" to "HARDWARE_IO",
                        "name" to "IntakeHardwareIo",
                        "mode" to "APPLY",
                    ),
                )
                assertFalse(scaffoldApply.isError == true)

                val auditCall = client.callTool(
                    name = "ares_code_audit",
                    arguments = mapOf("projectPath" to projectDir.absolutePath),
                )
                assertFalse(auditCall.isError == true)
                val auditObj = json.parseToJsonElement((auditCall.content.first() as TextContent).text).jsonObject
                assertEquals("0", auditObj["errorCount"]?.jsonPrimitive?.content)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `compact toolset exposes 7 consolidated tools and mutates descriptors via router`() {
        val tempRoot = Files.createTempDirectory("ares-mcp-compact-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projectDir = File(tempRoot, "robot-frc")
        try {
            withMcpClient(McpToolsetMode.COMPACT, bridgeDir) { client ->
                val toolNames = client.listTools().tools.map { it.name }.toSet()
                assertEquals(
                    setOf(
                        "ares_project_init",
                        "ares_project_inspect",
                        "ares_project_validate",
                        "ares_descriptor_mutate",
                        "ares_codegen_reconcile",
                        "ares_kotlin_scaffold",
                        "ares_code_audit",
                    ),
                    toolNames,
                )

                val initCall = client.callTool(
                    name = "ares_project_init",
                    arguments = mapOf(
                        "targetDir" to projectDir.absolutePath,
                        "league" to "FRC",
                        "displayName" to "Compact FRC Bot",
                        "initGit" to true,
                    ),
                )
                assertFalse(initCall.isError == true)

                val driveMutate = client.callTool(
                    name = "ares_descriptor_mutate",
                    arguments = mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "descriptorKind" to "DRIVETRAIN",
                        "maxLinearSpeedMps" to 4.1,
                    ),
                )
                assertFalse(driveMutate.isError == true)

                val tuningOverlay = client.callTool(
                    name = "ares_descriptor_mutate",
                    arguments = mapOf(
                        "projectPath" to projectDir.absolutePath,
                        "descriptorKind" to "TUNING",
                        "target" to "LOCAL_OVERLAY",
                        "documentId" to "comp-overlay",
                        "assignments" to listOf(
                            mapOf("keyOrUid" to "frc.starter.path.velocity-scale", "doubleValue" to 0.65),
                        ),
                    ),
                )
                assertFalse(tuningOverlay.isError == true)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
