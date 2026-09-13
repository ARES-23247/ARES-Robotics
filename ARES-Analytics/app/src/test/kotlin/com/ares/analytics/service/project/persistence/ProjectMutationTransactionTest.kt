package com.ares.analytics.service.project.persistence

import com.ares.analytics.util.Sha256
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectMutationTransactionTest {
    @Test
    fun `session recovery cannot roll back another live transaction`() = withProject { root ->
        val current = File(root, ".ares/tuning/test.arestuning").apply { parentFile.mkdirs(); writeText("before") }
        val written = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val recovering = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        lateinit var recovery: java.util.concurrent.Future<*>
        val mutation = executor.submit {
            ProjectMutationTransaction.run(root, "held-save", listOf(".ares/tuning/test.arestuning")) {
                current.writeText("committed value")
                written.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(written.await(5, TimeUnit.SECONDS))
            recovery = executor.submit { recovering.countDown(); ProjectMutationTransaction.recover(root) }
            assertTrue(recovering.await(5, TimeUnit.SECONDS))
            assertFailsWith<java.util.concurrent.TimeoutException> { recovery.get(200, TimeUnit.MILLISECONDS) }
        } finally {
            release.countDown()
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        mutation.get(1, TimeUnit.SECONDS)
        recovery.get(1, TimeUnit.SECONDS)
        assertEquals("committed value", current.readText())
    }

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

    @Test
    fun `truncated versioned manifest never changes current files or backups`() {
        val body = "V\t2\nS\t.ares/controls\nF\t.ares/controls/first\nF\t.ares/controls/second\n"
        val complete = sealManifest(body)
        val truncated = listOf(
            body.substringBefore("F\t"),
            body.substringBefore("F\t.ares/controls/second"),
            body,
            complete.dropLast(12),
            complete.dropLast(1),
        )
        truncated.forEach { manifest ->
            withInterruptedTransaction(manifest) { root, transaction ->
                assertFailsWith<IllegalArgumentException> { ProjectMutationTransaction.recover(root) }
                assertUnchanged(root, transaction, manifest)
            }
        }
    }

    @Test
    fun `checksum mismatch rejects recovery before restoring or deleting files`() {
        val body = "V\t2\nS\t.ares/controls\nF\t.ares/controls/first\nF\t.ares/controls/second\n"
        val manifest = sealManifest(body).replace("F\t.ares/controls/second\n", "")
        withInterruptedTransaction(manifest) { root, transaction ->
            assertFailsWith<IllegalArgumentException> { ProjectMutationTransaction.recover(root) }
            assertUnchanged(root, transaction, manifest)
        }
    }

    @Test
    fun `complete versioned manifest restores baseline and removes new files`() {
        val manifest = sealManifest("V\t2\nS\t.ares/controls\nF\t.ares/controls/first\nF\t.ares/controls/second\n")
        withInterruptedTransaction(manifest) { root, transaction ->
            ProjectMutationTransaction.recover(root)
            assertEquals("before-first", File(root, ".ares/controls/first").readText())
            assertEquals("before-second", File(root, ".ares/controls/second").readText())
            assertFalse(File(root, ".ares/controls/new").exists())
            assertFalse(transaction.exists())
        }
    }

    @Test
    fun `recovery recreates a scope whose current directory was deleted`() {
        val manifest = sealManifest("V\t2\nS\t.ares/controls\nF\t.ares/controls/first\nF\t.ares/controls/second\n")
        withInterruptedTransaction(manifest) { root, transaction ->
            listOf("first", "second", "new").forEach { name ->
                Files.delete(File(root, ".ares/controls/$name").toPath())
            }
            Files.delete(File(root, ".ares/controls").toPath())
            ProjectMutationTransaction.recover(root)
            assertEquals("before-first", File(root, ".ares/controls/first").readText())
            assertEquals("before-second", File(root, ".ares/controls/second").readText())
            assertFalse(transaction.exists())
        }
    }

    @Test
    fun `transaction publishes complete checksummed manifest before invoking mutation`() = withProject { root ->
        File(root, ".ares/controls/first").apply { parentFile.mkdirs(); writeText("before") }
        ProjectMutationTransaction.run(root, "save", listOf(".ares/controls")) {
            val transaction = File(root, ".ares/recovery/transactions").listFiles().orEmpty().single()
            val body = "V\t2\nS\t.ares/controls\nF\t.ares/controls/first\n"
            assertEquals(sealManifest(body), File(transaction, "manifest.tsv").readText())
            assertEquals("before", File(transaction, "baseline/.ares/controls/first").readText())
        }
    }

    @Test
    fun `unknown records and unsupported versions cannot be treated as legacy manifests`() {
        val body = "V\t2\nS\t.ares/controls\nF\t.ares/controls/first\nF\t.ares/controls/second\n"
        listOf(sealManifest(body + "X\tignored\n"), sealManifest(body.replace("V\t2", "V\t3")))
            .forEach { manifest ->
                withInterruptedTransaction(manifest) { root, transaction ->
                    assertFailsWith<IllegalArgumentException> { ProjectMutationTransaction.recover(root) }
                    assertUnchanged(root, transaction, manifest)
                }
            }
    }

    @Test
    fun `baseline outside declared scopes is rejected before mutation`() {
        val manifest = sealManifest("V\t2\nS\t.ares/controls/first\nF\t.ares/controls/first\nF\t.ares/controls/second\n")
        withInterruptedTransaction(manifest) { root, transaction ->
            assertFailsWith<IllegalArgumentException> { ProjectMutationTransaction.recover(root) }
            assertUnchanged(root, transaction, manifest)
        }
    }

    @Test
    fun `manifest delimiters in scopes are rejected before invoking mutation`() = withProject { root ->
        listOf("\t", "\n", "\r").forEach { delimiter ->
            assertFailsWith<IllegalArgumentException> {
                ProjectMutationTransaction.run(root, "invalid", listOf(".ares/controls$delimiter")) {
                    error("A scope with a manifest delimiter reached the mutation")
                }
            }
        }
        assertFalse(File(root, ".ares/recovery/transactions").exists())
    }

    @Test
    fun `a declared directory alias inside the project still permits rollback`() = withProject { root ->
        val current = File(root, ".ares/controls/first").apply { parentFile.mkdirs(); writeText("before") }
        val alias = File(root, ".ares/control-alias").toPath()
        linkDirectory(alias, current.parentFile.toPath())
        try {
            val failure = assertFailsWith<IllegalStateException> {
                ProjectMutationTransaction.run(root, "alias", listOf(".ares/control-alias")) {
                    current.writeText("after")
                    error("injected mutation failure")
                }
            }
            assertEquals("before", current.readText())
            assertTrue(failure.suppressed.isEmpty(), "Rollback failed: ${failure.suppressed.toList()}")
            assertTrue(File(root, ".ares/recovery/transactions").listFiles().orEmpty().isEmpty())
        } finally { Files.deleteIfExists(alias) }
    }

    @Test
    fun `a linked descendant outside the declared real scope is rejected before mutation`() = withProject { root ->
        val current = File(root, ".ares/other/first").apply { parentFile.mkdirs(); writeText("before") }
        val link = File(root, ".ares/controls/linked").toPath()
        linkDirectory(link, current.parentFile.toPath())
        try {
            assertFailsWith<IllegalArgumentException> {
                ProjectMutationTransaction.run(root, "linked-child", listOf(".ares/controls")) {
                    current.writeText("after")
                }
            }
            assertEquals("before", current.readText())
            assertFalse(File(root, ".ares/recovery/transactions").exists())
        } finally { Files.deleteIfExists(link) }
    }

    private fun linkDirectory(link: Path, target: Path) {
        Files.createDirectories(link.parent)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Junction creation timed out")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            } finally { if (process.isAlive) process.destroyForcibly() }
        } else Files.createSymbolicLink(link, target)
    }

    private fun sealManifest(body: String): String = body + "H\t${Sha256.hex(body)}\n"

    private fun withInterruptedTransaction(manifest: String, block: (File, File) -> Unit) = withProject { root ->
        val transaction = File(root, ".ares/recovery/transactions/interrupted")
        listOf("first", "second").forEach { name ->
            File(root, ".ares/controls/$name").apply { parentFile.mkdirs(); writeText("after-$name") }
            File(transaction, "baseline/.ares/controls/$name").apply {
                parentFile.mkdirs()
                writeText("before-$name")
            }
        }
        File(root, ".ares/controls/new").writeText("new")
        File(transaction, "manifest.tsv").writeText(manifest)
        block(root, transaction)
    }

    private fun assertUnchanged(root: File, transaction: File, manifest: String) {
        listOf("first", "second").forEach { name ->
            assertEquals("after-$name", File(root, ".ares/controls/$name").readText())
            assertEquals("before-$name", File(transaction, "baseline/.ares/controls/$name").readText())
        }
        assertEquals("new", File(root, ".ares/controls/new").readText())
        assertEquals(manifest, File(transaction, "manifest.tsv").readText())
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
