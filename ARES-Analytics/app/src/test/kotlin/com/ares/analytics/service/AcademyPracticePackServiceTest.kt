package com.ares.analytics.service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcademyPracticePackServiceTest {
    @Test
    fun `practice pack installs under project and is idempotent`() {
        val project = projectDirectory()
        val service = AcademyPracticePackService(
            linkedMapOf(
                "baseline.csv" to "TimestampMs,Arm/CurrentAmps\n0,1.0\n",
                "README.md" to "Synthetic only\n",
            ),
        )

        val first = service.install(project)
        val second = service.install(project)

        assertFalse(first.reusedExistingFiles)
        assertTrue(second.reusedExistingFiles)
        assertEquals(File(project, ".ares/academy/practice-runs").canonicalFile, first.directory)
        assertEquals("TimestampMs,Arm/CurrentAmps\n0,1.0\n", File(first.directory, "baseline.csv").readText())
    }

    @Test
    fun `practice pack never replaces a changed existing file`() {
        val project = projectDirectory()
        val service = AcademyPracticePackService(mapOf("baseline.csv" to "reviewed\n"))
        val target = File(project, ".ares/academy/practice-runs/baseline.csv").apply {
            parentFile.mkdirs()
            writeText("student data\n")
        }

        assertFailsWith<IllegalStateException> { service.install(project) }
        assertEquals("student data\n", target.readText())
    }

    @Test
    fun `practice pack requires an ARES project`() {
        val folder = Files.createTempDirectory("academy-not-project").toFile()
        assertFailsWith<IllegalArgumentException> { AcademyPracticePackService().install(folder) }
    }

    private fun projectDirectory(): File = Files.createTempDirectory("academy-project").toFile().apply {
        File(this, ".ares").mkdirs()
    }
}
