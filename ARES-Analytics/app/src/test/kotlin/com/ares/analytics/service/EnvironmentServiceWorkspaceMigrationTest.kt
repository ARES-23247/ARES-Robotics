package com.ares.analytics.service

import com.ares.analytics.shared.AppWorkspaces
import com.ares.analytics.shared.League
import com.ares.analytics.shared.WorkspaceConfig
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentServiceWorkspaceMigrationTest {
    @Test
    fun `canonical project identity refreshes workspace display cache on load and save`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("ares-workspace-canonical-").toFile()
        try {
            val project = File(root, "Lightbot").apply { mkdirs() }
            File(project, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(
                    AresProjectMetadataCodec.encode(
                        AresProjectMetadataDocument(
                            projectId = "team23247-lightbot",
                            identity = AresProjectIdentityDocument(
                                teamId = "23247",
                                seasonId = "2026",
                                robotId = "lightbot",
                                displayName = "Lightbot",
                            ),
                            league = AresLeague.FTC,
                            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                            robotLengthMeters = 0.44,
                            robotWidthMeters = 0.44,
                            fieldLengthMeters = 3.6576,
                            fieldWidthMeters = 3.6576,
                        ),
                    ),
                )
            }
            File(project, "TeamCode/src/main/java/Robot.kt").apply {
                parentFile.mkdirs()
                writeText("class Robot")
            }
            val stale = WorkspaceConfig(
                id = "lightbot-workspace",
                teamId = "99999",
                seasonId = "old",
                robotId = "stale",
                robotName = "Stale name",
                projectPath = project.path,
                league = League.FRC,
            )
            val workspacesFile = File(root, "settings/workspaces.json").apply {
                parentFile.mkdirs()
                writeText(Json.encodeToString(AppWorkspaces(stale.id, listOf(stale))))
            }
            val service = EnvironmentService(
                configPath = File(root, "settings/config.json").path,
                workspacesPath = workspacesFile.path,
            )

            val loaded = service.loadWorkspaces().workspaces.single()
            assertEquals("23247", loaded.teamId)
            assertEquals("2026", loaded.seasonId)
            assertEquals("lightbot", loaded.robotId)
            assertEquals("Lightbot", loaded.robotName)
            assertEquals(League.FTC, loaded.league)

            service.saveConfig(loaded.copy(teamId = "11111", robotId = "wrong", league = League.FRC))
            val saved = service.loadWorkspaces().workspaces.single()
            assertEquals("23247", saved.teamId)
            assertEquals("lightbot", saved.robotId)
            assertEquals(League.FTC, saved.league)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `asset-only workspace migrates to the one matching robot repository`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("ares-workspace-migration-").toFile()
        try {
            val staleRoot = File(root, "ftc/ARES-FTC").apply { mkdirs() }
            File(staleRoot, "src/main/assets").mkdirs()

            val sourceRoot = File(root, "ares/ARES-FTC").apply { mkdirs() }
            File(sourceRoot, "TeamCode/src/main/java/Robot.kt").apply {
                parentFile.mkdirs()
                writeText("class Robot")
            }
            writeCanonicalIdentity(sourceRoot)

            val config = WorkspaceConfig(
                id = "robot",
                teamId = "23247",
                seasonId = "2026",
                robotId = "GoBilda",
                projectPath = staleRoot.path,
                league = League.FTC
            )
            val workspacesFile = File(root, "settings/workspaces.json").apply {
                parentFile.mkdirs()
                writeText(Json.encodeToString(AppWorkspaces("robot", listOf(config))))
            }

            val loaded = EnvironmentService(
                configPath = File(root, "settings/config.json").path,
                workspacesPath = workspacesFile.path
            ).loadWorkspaces()

            assertEquals(sourceRoot.canonicalPath, File(loaded.workspaces.single().projectPath).canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ambiguous matching repositories do not rewrite workspace`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("ares-workspace-ambiguous-").toFile()
        try {
            val staleRoot = File(root, "ftc/ARES-FTC").apply { mkdirs() }
            File(staleRoot, "src/main/assets").mkdirs()
            listOf("copy-one", "copy-two").forEach { folder ->
                val candidate = File(root, "$folder/ARES-FTC").apply { mkdirs() }
                File(candidate, "TeamCode/src/main/java/Robot.kt").apply {
                    parentFile.mkdirs()
                    writeText("class Robot")
                }
                writeCanonicalIdentity(candidate)
            }
            val config = WorkspaceConfig(
                id = "robot",
                teamId = "23247",
                seasonId = "2026",
                robotId = "GoBilda",
                projectPath = staleRoot.path,
                league = League.FTC
            )
            val workspacesFile = File(root, "settings/workspaces.json").apply {
                parentFile.mkdirs()
                writeText(Json.encodeToString(AppWorkspaces("robot", listOf(config))))
            }

            val loaded = EnvironmentService(
                configPath = File(root, "settings/config.json").path,
                workspacesPath = workspacesFile.path
            ).loadWorkspaces()

            assertEquals(staleRoot.path, loaded.workspaces.single().projectPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `incomplete directory without relocation evidence is not searched or rewritten`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("ares-workspace-no-migration-").toFile()
        try {
            val incompleteRoot = File(root, "empty/ARES-FTC").apply { mkdirs() }
            val matchingRoot = File(root, "ares/ARES-FTC").apply { mkdirs() }
            File(matchingRoot, "TeamCode/src/main/java/Robot.kt").apply {
                parentFile.mkdirs()
                writeText("class Robot")
            }
            writeCanonicalIdentity(matchingRoot)
            val config = WorkspaceConfig(
                id = "robot",
                teamId = "23247",
                seasonId = "2026",
                robotId = "GoBilda",
                projectPath = incompleteRoot.path,
                league = League.FTC
            )
            val workspacesFile = File(root, "settings/workspaces.json").apply {
                parentFile.mkdirs()
                writeText(Json.encodeToString(AppWorkspaces("robot", listOf(config))))
            }

            val loaded = EnvironmentService(
                configPath = File(root, "settings/config.json").path,
                workspacesPath = workspacesFile.path
            ).loadWorkspaces()

            assertEquals(incompleteRoot.path, loaded.workspaces.single().projectPath)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeCanonicalIdentity(root: File) {
        File(root, ".ares/project.json").apply {
            parentFile.mkdirs()
            writeText(
                AresProjectMetadataCodec.encode(
                    AresProjectMetadataDocument(
                        projectId = "team23247-gobilda",
                        identity = AresProjectIdentityDocument("23247", "2026", "GoBilda", "Test"),
                        league = AresLeague.FTC,
                        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                        robotLengthMeters = 0.44,
                        robotWidthMeters = 0.44,
                        fieldLengthMeters = 3.6576,
                        fieldWidthMeters = 3.6576,
                    ),
                ),
            )
        }
    }
}
