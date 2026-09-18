package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectArchiveExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val temporaryDirectory get() = temporaryFolder.root.toPath()
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
        val canonicalDocuments = mapOf(
            ".ares/tuning/simulation.arestuning" to "canonical tuning",
            ".ares/subsystems/arm.aressubsystem" to "canonical subsystem",
            ".ares/routines/score.aresroutine" to "canonical routine",
            ".ares/field.json" to "canonical field",
        )
        canonicalDocuments.forEach { (path, content) ->
            File(root, path).apply { parentFile.mkdirs(); writeText(content) }
        }

        val result = exporter.export(root.path, destination.path)

        ZipFile(destination).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(canonicalDocuments.keys + setOf(".ares/project.json", "TeamCode/src/main/kotlin/Robot.kt"), entries)
            assertEquals(entries.size, result.fileCount)
            canonicalDocuments.forEach { (path, content) ->
                assertEquals(content, zip.getInputStream(zip.getEntry(path)).bufferedReader().use { it.readText() })
            }
            assertTrue(".ares/project.json" in entries)
            assertTrue("TeamCode/src/main/kotlin/Robot.kt" in entries)
            assertFalse(entries.any { it.startsWith(".ares/local/") })
            assertFalse(entries.any { it.startsWith(".ares/recovery/") })
            assertFalse(entries.any { it.startsWith(".ares/.") })
        }
        val repeated = temporaryDirectory.resolve("repeated.aresproject.zip").toFile()
        exporter.export(root.path, repeated.path)
        assertTrue(destination.readBytes().contentEquals(repeated.readBytes()), "Unchanged project exports must be deterministic")
    }

    @Test
    fun `safe extraction restores exported project files and normalizes gradlew`() = runBlocking {
        val root = canonicalProject("archive-extract-source")
        File(root, "TeamCode/src/main/kotlin").mkdirs()
        File(root, "TeamCode/src/main/kotlin/Robot.kt").writeText("class Robot")
        File(root, "gradlew").writeBytes("#!/bin/sh\r\necho running\r\n".toByteArray())
        val archive = temporaryDirectory.resolve("robot-export.aresproject.zip").toFile()
        exporter.export(root.path, archive.path)

        val targetDir = temporaryDirectory.resolve("extracted-project").toFile()
        val result = exporter.extract(archive.path, targetDir.path)

        assertEquals(targetDir.canonicalPath, result.canonicalPath)
        assertTrue(File(targetDir, ".ares/project.json").isFile)
        assertEquals("class Robot", File(targetDir, "TeamCode/src/main/kotlin/Robot.kt").readText())
        val gradlew = File(targetDir, "gradlew")
        assertTrue(gradlew.isFile)
        assertFalse(gradlew.readBytes().contains('\r'.code.toByte()))
    }

    @Test
    fun `extraction rejects missing canonical project identity`() = runBlocking {
        val archive = temporaryDirectory.resolve("invalid-no-project.zip").toFile()
        java.util.zip.ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
            zip.write("some text".toByteArray())
            zip.closeEntry()
        }
        val targetDir = temporaryDirectory.resolve("extracted-invalid").toFile()

        val failure = assertFailsWith<IllegalStateException> {
            exporter.extract(archive.path, targetDir.path)
        }
        assertContains(failure.message.orEmpty(), "missing its canonical ARES project identity")
        assertFalse(targetDir.exists(), "Rejected extraction must not publish a partial project")
    }

    @Test
    fun `extraction rejects path traversal entries`() = runBlocking {
        val archive = temporaryDirectory.resolve("invalid-traversal.zip").toFile()
        java.util.zip.ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(".ares/project.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("../escaped.txt"))
            zip.write("escaped".toByteArray())
            zip.closeEntry()
        }
        val targetDir = temporaryDirectory.resolve("extracted-traversal").toFile()

        val failure = assertFailsWith<IllegalStateException> {
            exporter.extract(archive.path, targetDir.path)
        }
        assertContains(failure.message.orEmpty(), "unsupported relative path segment")
        assertFalse(temporaryDirectory.resolve("escaped.txt").toFile().exists())
        assertFalse(targetDir.exists(), "A rejected later entry must roll back earlier entries")

        val valid = canonicalProject("retry-source")
        val retryArchive = temporaryDirectory.resolve("retry.zip").toFile()
        exporter.export(valid.path, retryArchive.path)
        exporter.extract(retryArchive.path, targetDir.path)
        assertTrue(File(targetDir, ".ares/project.json").isFile, "Retry should not require manual cleanup")
    }

    @Test
    fun `extraction rejects non-empty destination`() = runBlocking {
        val root = canonicalProject("archive-non-empty-source")
        val archive = temporaryDirectory.resolve("robot-export-non-empty.zip").toFile()
        exporter.export(root.path, archive.path)

        val targetDir = temporaryDirectory.resolve("target-non-empty").toFile().apply {
            mkdirs()
            File(this, "pre-existing.txt").writeText("existing")
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            exporter.extract(archive.path, targetDir.path)
        }
        assertContains(failure.message.orEmpty(), "not empty")
        assertEquals("existing", File(targetDir, "pre-existing.txt").readText())
    }

    @Test
    fun `extraction rejects reserved device names`() = runBlocking {
        val archive = temporaryDirectory.resolve("invalid-reserved.zip").toFile()
        java.util.zip.ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(".ares/project.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("AUX.txt"))
            zip.write("device".toByteArray())
            zip.closeEntry()
        }
        val targetDir = temporaryDirectory.resolve("extracted-reserved").toFile()

        val failure = assertFailsWith<IllegalStateException> {
            exporter.extract(archive.path, targetDir.path)
        }
        assertContains(failure.message.orEmpty(), "reserved device name")
    }

    @Test
    fun `rejected extraction preserves an existing empty destination`() = runBlocking {
        val target = temporaryDirectory.resolve("existing-empty").toFile().apply { mkdirs() }
        val archive = archive("missing-identity", "readme.txt" to "not a project".toByteArray())
        assertFailsWith<IllegalStateException> { exporter.extract(archive.path, target.path) }
        assertTrue(target.isDirectory)
        assertTrue(requireNotNull(target.list()).isEmpty())
    }

    @Test
    fun `ambiguous portable paths are rejected without overwriting earlier files`() = runBlocking {
        listOf("/absolute.txt", "folder//file.txt", ".ares/PROJECT.json", "notes.txt.", "notes.txt:extra").forEachIndexed { index, path ->
            val archive = archive("alias-$index", ".ares/project.json" to "original".toByteArray(), path to "replacement".toByteArray())
            val target = temporaryDirectory.resolve("alias-target-$index").toFile()
            assertFailsWith<IllegalStateException>(path) { exporter.extract(archive.path, target.path) }
            assertFalse(target.exists(), path)
        }
    }

    @Test
    fun `cancellation during archive reads leaves no partial project or staging files`() = runBlocking {
        val payload = ByteArray(64 * 1024).also { java.util.Random(19).nextBytes(it) }
        val archive = archive("cancel", ".ares/project.json" to "{}".toByteArray(), "data.bin" to payload)
        val target = temporaryDirectory.resolve("cancel-target").toFile()
        val cancellation = Job()
        var bytesRead = 0
        val cancellingExporter = ProjectArchiveExporter { source ->
            object : FilterInputStream(source.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    bytesRead += count.coerceAtLeast(0)
                    if (bytesRead >= 8_192) cancellation.cancel()
                    return count
                }
            }
        }
        val originalNames = requireNotNull(temporaryFolder.root.list()).toSet()
        assertFailsWith<CancellationException> {
            withContext(cancellation) { cancellingExporter.extract(archive.path, target.path) }
        }
        assertTrue(bytesRead >= 8_192, "Cancel only after extraction has begun")
        assertTrue(bytesRead < payload.size, "Cancellation must stop further archive IO")
        assertFalse(target.exists())
        assertEquals(originalNames, requireNotNull(temporaryFolder.root.list()).toSet())
    }

    @Test
    fun `extraction publishes into an existing empty destination`() = runBlocking {
        val target = temporaryDirectory.resolve("publish-empty").toFile().apply { mkdirs() }
        val archive = archive("valid-empty", ".ares/project.json" to "{}".toByteArray())
        exporter.extract(archive.path, target.path)
        assertEquals("{}", File(target, ".ares/project.json").readText())
    }

    @Test
    fun `extraction never removes files created at destination while reading archive`() = runBlocking {
        for (preExisting in listOf(false, true)) {
            val target = temporaryDirectory.resolve("concurrent-$preExisting").toFile()
            if (preExisting) target.mkdirs()
            val archive = archive("concurrent-$preExisting", ".ares/project.json" to "{}".toByteArray())
            val concurrentExporter = ProjectArchiveExporter { source ->
                target.mkdirs()
                File(target, "team-notes.txt").writeText("another writer")
                source.inputStream()
            }
            assertFailsWith<java.io.IOException> { concurrentExporter.extract(archive.path, target.path) }
            assertEquals("another writer", File(target, "team-notes.txt").readText())
            assertFalse(File(target, ".ares").exists())
            assertFalse(requireNotNull(temporaryFolder.root.list()).any { it.contains(".ares-extract-") })
        }
    }

    @Test
    fun `archive directory payload is rejected instead of bypassing file limits`() = runBlocking {
        val archive = archive("directory-payload", ".ares/project.json" to "{}".toByteArray(), "folder/" to byteArrayOf(1))
        val target = temporaryDirectory.resolve("directory-payload").toFile()
        val failure = assertFailsWith<IllegalStateException> { exporter.extract(archive.path, target.path) }
        assertContains(failure.message.orEmpty(), "directory entries must not contain file data")
        assertFalse(target.exists())
    }

    @Test
    fun `archive truncated between entries cannot publish a partial project`() = runBlocking {
        val archive = archive("truncated", ".ares/project.json" to "{}".toByteArray(), "Robot.kt" to "class Robot".toByteArray())
        val bytes = archive.readBytes()
        val localHeader = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val secondHeader = (1 until bytes.size - 3).first { offset ->
            localHeader.indices.all { bytes[offset + it] == localHeader[it] }
        }
        archive.writeBytes(bytes.copyOf(secondHeader))
        val target = temporaryDirectory.resolve("truncated").toFile()
        assertFailsWith<java.util.zip.ZipException> { exporter.extract(archive.path, target.path) }
        assertFalse(target.exists())
        assertFalse(requireNotNull(temporaryFolder.root.list()).any { it.contains(".ares-extract-") })
    }

    @Test
    fun `oversized expanded file is rejected and earlier entries are rolled back`() = runBlocking {
        val archive = temporaryDirectory.resolve("oversized.zip").toFile()
        java.util.zip.ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(".ares/project.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("large.bin"))
            val chunk = ByteArray(64 * 1024)
            repeat(1601) { zip.write(chunk) } // 100 MiB + 64 KiB, compressed; avoids a large allocation.
            zip.closeEntry()
        }
        val target = temporaryDirectory.resolve("oversized").toFile()
        val failure = assertFailsWith<IllegalStateException> { exporter.extract(archive.path, target.path) }
        assertContains(failure.message.orEmpty(), "maximum archive file limit")
        assertFalse(target.exists())
        assertFalse(requireNotNull(temporaryFolder.root.list()).any { it.contains(".ares-extract-") })
    }

    private fun archive(name: String, vararg entries: Pair<String, ByteArray>): File =
        temporaryDirectory.resolve("$name.zip").toFile().also { archive ->
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun canonicalProject(name: String): File = temporaryDirectory.resolve(name).toFile().apply {
        File(this, ".ares").mkdirs()
        File(this, ".ares/project.json").writeText("{}")
    }
}
