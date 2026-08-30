package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RobotProjectReadinessServiceTest {
    @Test
    fun `missing metadata is an incomplete stage while malformed present metadata is invalid evidence`() = runTest {
        val root = Files.createTempDirectory("robot-studio-readiness").toFile()
        File(root, "TeamCode/src/main/java/fixture/Robot.kt").apply {
            parentFile.mkdirs()
            writeText("package fixture\nclass Robot")
        }
        val database = DatabaseService(File(root, "analytics.duckdb").path)
        val service = RobotProjectReadinessService(database)
        val config = WorkspaceConfig(
            id = "workspace",
            teamId = "23247",
            seasonId = "decode",
            robotId = "practice",
            robotName = "Practice Robot",
            projectPath = root.path,
            league = League.FTC,
        )
        try {
            val missing = service.inspect(config)
            assertFalse(missing.metadataPresent)
            assertTrue(missing.metadataErrors.isEmpty(), "A missing starter document is incomplete, not corrupt")

            File(root, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText("not-json")
            }
            val malformed = service.inspect(config)
            assertFalse(malformed.metadataPresent)
            assertTrue(malformed.metadataErrors.any { "project.json" in it })
            assertTrue(malformed.documentErrors.any { "project.json" in it })
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid workspace fails closed before claiming canonical readiness`() = runTest {
        val root = Files.createTempDirectory("robot-studio-invalid").toFile()
        val database = DatabaseService(File(root, "analytics.duckdb").path)
        try {
            val evidence = RobotProjectReadinessService(database).inspect(
                WorkspaceConfig(
                    id = "invalid",
                    teamId = "23247",
                    seasonId = "decode",
                    robotId = "missing",
                    projectPath = File(root, "does-not-exist").path,
                    league = League.FTC,
                ),
            )
            assertNotNull(evidence.projectError)
            assertFalse(evidence.metadataPresent)
            assertFalse(evidence.drivebaseNoCodeSupported)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
