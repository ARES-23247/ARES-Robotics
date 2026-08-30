package com.ares.analytics.service.versioncontrol

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProjectReviewTokenFactoryTest {
    private val factory = ProjectReviewTokenFactory()

    @Test
    fun `working tree token is deterministic and content bound`() {
        val root = Files.createTempDirectory("ares-review-token").toFile()
        val file = root.resolve(".ares/project.json").apply {
            parentFile.mkdirs()
            writeText("first")
        }
        val changes = listOf(ProjectChange(".ares/project.json", ProjectChangeKind.MODIFIED))

        val first = factory.workingTreeToken(root, changes)
        assertEquals(first, factory.workingTreeToken(root, changes))

        file.writeText("second")
        assertNotEquals(first, factory.workingTreeToken(root, changes))
    }

    @Test
    fun `restore token binds both commits and ordered change set`() {
        val changes = listOf(
            ProjectChange(".ares/project.json", ProjectChangeKind.MODIFIED),
            ProjectChange(".ares/routines/auto.json", ProjectChangeKind.ADDED),
        )
        val token = factory.restoreToken("local", "remote", changes)

        assertEquals(token, factory.restoreToken("local", "remote", changes))
        assertNotEquals(token, factory.restoreToken("remote", "local", changes))
        assertNotEquals(token, factory.restoreToken("local", "remote", changes.reversed()))
    }
}
