package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectArchiveExporterTest {
    private val temporaryDirectory = Files.createTempDirectory("ares-project-archive-test")
    private val exporter = ProjectArchiveExporter()

    @Test
    fun `portable archive excludes credentials git metadata and build caches`() = runBlocking {
        val root = canonicalProject("archive-project")
        File(root, "TeamCode/src/main/kotlin").mkdirs()
        File(root, "TeamCode/src/main/kotlin/Robot.kt").writeText("class Robot")
        File(root, ".git/objects").mkdirs()
        File(root, ".git/objects/object").writeText("git metadata")
        File(root, "TeamCode/build/classes").mkdirs()
        File(root, "TeamCode/build/classes/generated.class").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "credentials.json").writeText("never export")
        val destination = temporaryDirectory.resolve("robot-export.aresproject.zip").toFile()

        val result = exporter.export(root.path, destination.path)

        assertEquals(destination.canonicalPath, result.destinationPath)
        assertEquals(listOf("credentials.json"), result.skippedSensitivePaths)
        ZipFile(destination).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(".ares/project.json" in entries)
            assertTrue("TeamCode/src/main/kotlin/Robot.kt" in entries)
            assertFalse(entries.any { it.startsWith(".git/") })
            assertFalse(entries.any { "/build/" in it || it.startsWith("build/") })
            assertFalse("credentials.json" in entries)
        }
    }

    @Test
    fun `portable archive must be saved outside the robot project`() = runBlocking {
        val root = canonicalProject("archive-destination-project")
        val destination = File(root, "backup.zip")

        val failure = assertFailsWith<IllegalArgumentException> {
            exporter.export(root.path, destination.path)
        }

        assertContains(failure.message.orEmpty(), "outside the robot project")
        assertFalse(destination.exists())
    }

    @Test
    fun `portable archive excludes local transient data and recovery transactions`() = runBlocking {
        val root = canonicalProject("archive-local-recovery-project")
        File(root, ".ares/local/tuning").mkdirs()
        File(root, ".ares/local/tuning/overlay.json").writeText("{\"overlay\":true}")
        File(root, ".ares/local/verification/run-1").mkdirs()
        File(root, ".ares/local/verification/run-1/report.json").writeText("{\"verified\":true}")
        File(root, ".ares/recovery/transactions/tx-123").mkdirs()
        File(root, ".ares/recovery/transactions/tx-123/manifest.tsv").writeText("V\t2\n")
        File(root, ".ares/recovery/subsystems").mkdirs()
        File(root, ".ares/recovery/subsystems/arm.aressubsystem").writeText("{\"subsystem\":true}")
        File(root, ".ares/.project-mutation-transaction").writeText("lock")
        File(root, "TeamCode/src/main/kotlin").mkdirs()
        File(root, "TeamCode/src/main/kotlin/Robot.kt").writeText("class Robot")
        val destination = temporaryDirectory.resolve("robot-clean-export.aresproject.zip").toFile()

        val result = exporter.export(root.path, destination.path)

        ZipFile(destination).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(".ares/project.json" in entries)
            assertTrue("TeamCode/src/main/kotlin/Robot.kt" in entries)
            assertFalse(entries.any { it.startsWith(".ares/local/") })
            assertFalse(entries.any { it.startsWith(".ares/recovery/") })
            assertFalse(entries.any { it.startsWith(".ares/.") })
        }
    }

    private fun canonicalProject(name: String): File = temporaryDirectory.resolve(name).toFile().apply {
        File(this, ".ares").mkdirs()
        File(this, ".ares/project.json").writeText("{}")
    }
}

