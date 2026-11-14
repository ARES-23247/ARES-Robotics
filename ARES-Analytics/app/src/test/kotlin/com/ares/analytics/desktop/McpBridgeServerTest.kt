package com.ares.analytics.desktop

import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.shared.mcp.MCP_BRIDGE_FILE_NAME
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.ares.analytics.shared.mcp.McpBridgeHealthResponse
import com.ares.analytics.shared.mcp.McpBridgeReloadRequest
import com.ares.analytics.shared.mcp.McpBridgeReloadResponse
import com.areslib.controls.ControllerInputPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpBridgeServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun copySampleProject(destRoot: File) {
        val fixtureDir = listOf(
            File("src/test/resources/packaged-runtime-project"),
            File("app/src/test/resources/packaged-runtime-project"),
        ).first { it.isDirectory }
        fixtureDir.copyRecursively(destRoot, overwrite = true)
    }

    @Test
    fun `start publishes bridge descriptor enforces bearer auth reloads open project and cleans up on close`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-bridge-server-test-").toFile()
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val projectDir = File(tempRoot, "project")
        copySampleProject(projectDir)

        val session = ProjectSession()
        val initialSnapshot = session.snapshot(projectDir.absolutePath, ControllerInputPlatform.FTC)
        val initialHash = initialSnapshot.revision.canonicalContentSha256

        val bridgeServer = McpBridgeServer(
            projectSession = session,
            bridgeDirProvider = { bridgeDir },
            studioVersion = "7.0.79-test",
        )
        val client = HttpClient(CIO)
        try {
            try {
                bridgeServer.start()
                val bridgeFile = File(bridgeDir, MCP_BRIDGE_FILE_NAME)
                assertTrue(bridgeFile.isFile)
                val descriptor = json.decodeFromString(McpBridgeDescriptor.serializer(), bridgeFile.readText())
                assertEquals("7.0.79-test", descriptor.studioVersion)
                assertNotNull(bridgeServer.descriptorState.value)

                // Reject unauthorized calls
                val unauth = client.get("http://127.0.0.1:${descriptor.port}/v1/mcp/health") {
                    header(HttpHeaders.Authorization, "Bearer wrong-token")
                }
                assertEquals(HttpStatusCode.Unauthorized, unauth.status)

                // Authorized health probe reports loaded project
                val healthResp = client.get("http://127.0.0.1:${descriptor.port}/v1/mcp/health") {
                    header(HttpHeaders.Authorization, "Bearer ${descriptor.bearerToken}")
                }
                assertEquals(HttpStatusCode.OK, healthResp.status)
                val healthPayload = json.decodeFromString(McpBridgeHealthResponse.serializer(), healthResp.bodyAsText())
                assertTrue(healthPayload.ok)
                assertTrue(healthPayload.projectLoaded)
                assertEquals(initialHash, healthPayload.projectRevision)

                // Mutate canonical metadata on disk and trigger reload via bridge
                val metaFile = File(projectDir, ".ares/project.json")
                metaFile.writeText(metaFile.readText().replace("Smoke", "Reloaded Smoke"))

                val reloadResp = client.post("http://127.0.0.1:${descriptor.port}/v1/mcp/session/reload") {
                    header(HttpHeaders.Authorization, "Bearer ${descriptor.bearerToken}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            McpBridgeReloadRequest.serializer(),
                            McpBridgeReloadRequest(projectRoot = projectDir.absolutePath, reason = "unit-test"),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.OK, reloadResp.status)
                val reloadPayload = json.decodeFromString(McpBridgeReloadResponse.serializer(), reloadResp.bodyAsText())
                assertTrue(reloadPayload.reloaded)
                assertNotNull(reloadPayload.currentRevision)
                assertNotEquals(initialHash, reloadPayload.currentRevision)
            } finally {
                client.close()
                bridgeServer.close()
            }
            assertFalse(File(bridgeDir, MCP_BRIDGE_FILE_NAME).exists())
            assertNull(bridgeServer.descriptorState.value)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
