package com.ares.analytics.service

import com.ares.analytics.shared.models.AppWorkspaces
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvironmentServicePersistenceTest {
    @Test
    fun `workspace replace failure propagates and preserves the prior durable state`() = runTest {
        val directory = Files.createTempDirectory("ares-environment-atomic").toFile()
        try {
            val workspacesFile = directory.resolve("workspaces.json")
            val previous = AppWorkspaces(
                activeWorkspaceId = "old",
                workspaces = listOf(workspace("old", "23247")),
            )
            val previousBytes = Json.encodeToString(previous).toByteArray()
            writeSecrets(workspacesFile, previousBytes)
            var replacementAttempted = false
            val service = EnvironmentService(
                configPath = directory.resolve("config.json").absolutePath,
                workspacesPath = workspacesFile.absolutePath,
                secretsWriter = { file, bytes ->
                    writeSecrets(file, bytes) { temporary, destination ->
                        replacementAttempted = true
                        assertEquals(destination.parent, temporary.parent)
                        assertContentEquals(bytes, Files.readAllBytes(temporary))
                        throw IOException("injected workspace replace failure")
                    }
                },
            )

            assertFailsWith<IOException> {
                service.saveWorkspaces(
                    AppWorkspaces(
                        activeWorkspaceId = "new",
                        workspaces = listOf(workspace("new", "9999")),
                    ),
                )
            }

            assertTrue(replacementAttempted)
            assertContentEquals(previousBytes, workspacesFile.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun workspace(id: String, teamId: String) = WorkspaceConfig(
        id = id,
        teamId = teamId,
        seasonId = "2026",
        robotId = "robot",
        projectPath = ".",
        league = League.FRC,
    )
}
