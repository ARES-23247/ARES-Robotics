package com.ares.analytics.service

import com.ares.analytics.shared.models.AppWorkspaces
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
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

class EnvironmentServiceCanonicalIdentityTest {
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
                            runtimeOptions = com.areslib.project.AresRuntimeOptionsDocument(
                                ftc = com.areslib.project.AresFtcRuntimeOptionsDocument(),
                            ),
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
}
