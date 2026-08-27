package com.ares.analytics.service.project.persistence

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectMutationTransactionTest {
    @Test
    fun `failed multi-document mutation restores exact baseline and removes partial files`() = withProject { root ->
        val existing = File(root, ".ares/routines/existing.aresroutine").apply {
            parentFile.mkdirs()
            writeText("before")
        }
        val created = File(root, ".ares/autonomous-catalog.json")

        assertFailsWith<IllegalStateException> {
            ProjectMutationTransaction.run(
                root,
                "routine-save",
                listOf(".ares/routines", ".ares/autonomous-catalog.json"),
            ) {
                existing.writeText("after")
                created.writeText("partial")
                error("injected second-document failure")
            }
        }

        assertEquals("before", existing.readText())
        assertFalse(created.exists())
        assertTrue(File(root, ".ares/recovery/transactions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `next session recovery rolls back an interrupted uncommitted transaction`() = withProject { root ->
        val current = File(root, ".ares/controls/teleop.arescontrols").apply {
            parentFile.mkdirs()
            writeText("partially-written")
        }
        val transaction = File(root, ".ares/recovery/transactions/interrupted")
        File(transaction, "baseline/.ares/controls/teleop.arescontrols").apply {
            parentFile.mkdirs()
            writeText("baseline")
        }
        File(transaction, "manifest.tsv").writeText(
            "S\t.ares/controls\nF\t.ares/controls/teleop.arescontrols\n",
        )
        File(root, ".ares/controls/new.arescontrols").writeText("partial-new")

        ProjectMutationTransaction.recover(root)

        assertEquals("baseline", current.readText())
        assertFalse(File(root, ".ares/controls/new.arescontrols").exists())
        assertFalse(transaction.exists())
    }

    @Test
    fun `committed transaction survives interrupted cleanup`() = withProject { root ->
        val current = File(root, ".ares/routines/kept.aresroutine").apply {
            parentFile.mkdirs()
            writeText("committed")
        }
        val transaction = File(root, ".ares/recovery/transactions/committed")
        transaction.mkdirs()
        File(transaction, "COMMITTED").writeText("committed\n")

        ProjectMutationTransaction.recover(root)

        assertEquals("committed", current.readText())
        assertFalse(transaction.exists())
    }

    private fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("project-transaction-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
