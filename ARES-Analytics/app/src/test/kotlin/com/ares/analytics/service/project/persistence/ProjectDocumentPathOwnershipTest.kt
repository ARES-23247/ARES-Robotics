package com.ares.analytics.service.project.persistence

import com.ares.analytics.shared.models.League
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.viewmodel.field.FieldDocumentMapper
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureStatePreset
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProjectDocumentPathOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun routine(id: String = "a") = RoutineDocument(documentId = id, name = id, steps = listOf(RoutineStep.wait(0.1)))
    private fun coordinator() = SuperstructureDocument(
        superstructureId = "main", displayName = "Main", initialStateId = "idle", faultStateId = "fault",
        states = listOf(SuperstructureStatePreset("idle"), SuperstructureStatePreset("fault")),
    )
    private data class Stored(val current: File, val history: File, val load: () -> Unit, val save: () -> Unit, val revisions: () -> Unit)
    private fun eachStore(block: (File, Stored) -> Unit) {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val stored = if (singleton) {
                val repository = CapabilityCatalogProjectRepository()
                val document = CapabilityCatalogDocument(projectId = "test", actions = listOf(ActionDescriptor("intake.run", "Run", "Run intake")))
                val saved = repository.save(root.path, document)
                Stored(saved.currentFile, saved.historyFile, { repository.load(root.path).getOrThrow() },
                    { repository.save(root.path, document) }, { repository.listRevisions(root.path) })
            } else {
                val repository = RoutineProjectRepository()
                val saved = repository.save(root.path, routine())
                Stored(saved.currentFile, saved.historyFile, { repository.load(root.path, "a") },
                    { repository.save(root.path, routine()) }, { repository.listRevisions(root.path, "a") })
            }
            block(root, stored)
        }
    }
    private fun linkedCopy(file: File, block: (File) -> Unit) {
        val outside = temporary.newFile()
        file.copyTo(outside, overwrite = true)
        Files.delete(file.toPath())
        withLink(file, outside, false) { block(outside) }
    }
    private fun withLink(link: File, target: File, directory: Boolean, block: () -> Unit) {
        Files.createDirectories(link.parentFile.toPath())
        if (directory && System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.path, target.path).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Junction creation timed out")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            } finally { if (process.isAlive) process.destroyForcibly() }
        } else Files.createSymbolicLink(link.toPath(), target.toPath())
        try { block() } finally { Files.deleteIfExists(link.toPath()) }
    }

    @Test fun `resolver rejects outside directory links even for missing descendant files`() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        withLink(File(root, ".ares"), outside, true) {
            assertFailsWith<IllegalArgumentException> { resolveProjectPath(root.path, ".ares/missing/child.json") }
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `current document reads reject outside file links in both stores`() = eachStore { _, stored ->
        linkedCopy(stored.current) { outside ->
            val before = outside.readBytes()
            assertFailsWith<IllegalArgumentException> { stored.load() }
            assertContentEquals(before, outside.readBytes())
        }
    }

    @Test fun `current document saves reject outside file links in both stores`() = eachStore { _, stored ->
        linkedCopy(stored.current) { outside ->
            val before = outside.readBytes()
            assertFailsWith<IllegalArgumentException> { stored.save() }
            assertTrue(Files.isSymbolicLink(stored.current.toPath()))
            assertContentEquals(before, outside.readBytes())
        }
    }

    @Test fun `routine writes cannot publish through an outside directory junction`() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        withLink(File(root, ".ares/routines"), outside, true) {
            assertFailsWith<IllegalArgumentException> { RoutineProjectRepository().save(root.path, routine()) }
            assertTrue(outside.listFiles().orEmpty().isEmpty())
            assertFalse(File(root, ".ares/history").exists())
        }
    }

    @Test fun `routine listing diagnoses outside leaf links without hiding valid documents`() {
        val root = temporary.newFolder()
        val repository = RoutineProjectRepository()
        val first = repository.save(root.path, routine())
        repository.save(root.path, routine("b"))
        linkedCopy(first.currentFile) {
            val listing = repository.list(root.path)
            assertEquals(listOf("b"), listing.documents.map { it.documentId })
            assertEquals(listOf("a.aresroutine"), listing.diagnostics.map { it.file.name })
        }
    }

    @Test fun `history scans reject outside leaf links in both stores`() = eachStore { _, stored ->
        linkedCopy(stored.history) {
            assertFailsWith<IllegalArgumentException> { stored.revisions() }
        }
    }

    @Test fun `checkpoint validation cannot accept outside linked history in either store`() = eachStore { _, stored ->
        linkedCopy(stored.history) { outside ->
            val before = stored.current.readBytes()
            val historical = outside.readBytes()
            assertFailsWith<IllegalArgumentException> { stored.save() }
            assertContentEquals(before, stored.current.readBytes())
            assertContentEquals(historical, outside.readBytes())
        }
    }

    @Test fun `removal refuses an outside recovery directory without consuming current content`() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val repository = RoutineProjectRepository()
        val saved = repository.save(root.path, routine())
        withLink(File(root, ".ares/recovery/routines"), outside, true) {
            assertFailsWith<IllegalArgumentException> { repository.remove(root.path, "a", saved.contentHash) }
            assertTrue(saved.currentFile.isFile)
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `restoration refuses an outside recovery file without writing current content`() {
        val root = temporary.newFolder()
        val repository = RoutineProjectRepository()
        val saved = repository.save(root.path, routine())
        val removed = repository.remove(root.path, "a", saved.contentHash)
        val relative = removed.recoveryFile.relativeTo(root).invariantSeparatorsPath
        linkedCopy(removed.recoveryFile) { outside ->
            assertFailsWith<IllegalArgumentException> { repository.restoreRemoved(root.path, "a", saved.contentHash, relative) }
            assertFalse(saved.currentFile.exists())
            assertTrue(outside.isFile)
        }
    }

    @Test fun `project directory aliases retain routine history and recovery`() {
        val root = temporary.newFolder()
        val alias = File(temporary.root, "project-alias")
        withLink(alias, root, true) {
            val repository = RoutineProjectRepository()
            val saved = repository.save(alias.path, routine())
            assertEquals(saved.document, repository.load(root.path, "a"))
            val removed = repository.remove(alias.path, "a", saved.contentHash)
            val relative = removed.recoveryFile.relativeTo(alias.canonicalFile).invariantSeparatorsPath
            assertFalse(relative.startsWith(".."))
            assertEquals(saved.document, repository.restoreRemoved(alias.path, "a", saved.contentHash, relative))
            assertEquals(1, repository.listRevisions(alias.path, "a").size)
        }
    }

    @Test fun `field reads and writes reject outside current file links for every league`() {
        for (league in League.entries) for (saving in listOf(false, true)) {
            val root = temporary.newFolder()
            val document = FieldDocumentMapper.newDocument(league)
            FieldDocumentStore.save(root.path, league, document)
            linkedCopy(ProjectLayout.fieldDefinitionFile(root.path, league)) { outside ->
                val before = outside.readBytes()
                assertFailsWith<IllegalArgumentException> {
                    if (saving) FieldDocumentStore.save(root.path, league, document) else FieldDocumentStore.load(root.path, league)
                }
                assertContentEquals(before, outside.readBytes())
            }
        }
    }

    @Test fun `field checkpoints reject outside leaf links`() {
        val root = temporary.newFolder()
        val document = FieldDocumentMapper.newDocument(League.FTC)
        FieldDocumentStore.save(root.path, League.FTC, document)
        val history = File(root, ".ares/history/fields").listFiles().orEmpty().single()
        linkedCopy(history) {
            assertFailsWith<IllegalArgumentException> { FieldDocumentStore.save(root.path, League.FTC, document) }
        }
    }

    @Test fun `superstructure load save and listing reject outside leaf links`() {
        for (operation in listOf("load", "save", "list")) {
            val root = temporary.newFolder()
            val repository = SuperstructureProjectRepository()
            val saved = repository.save(root.path, coordinator(), null, emptyList(), emptySet())
            linkedCopy(saved.currentFile) {
                if (operation == "list") {
                    val listing = repository.list(root.path)
                    assertTrue(listing.documents.isEmpty())
                    assertEquals(1, listing.diagnostics.size)
                } else assertFailsWith<IllegalArgumentException> {
                    if (operation == "load") repository.load(root.path, "main")
                    else repository.save(root.path, coordinator(), saved.contentHash, emptyList(), emptySet())
                }
            }
        }
    }

    @Test fun `superstructure history rejects corrupt existing checkpoints and repairs missing parent history`() {
        for (changed in listOf(false, true)) {
            val root = temporary.newFolder()
            val repository = SuperstructureProjectRepository()
            val saved = repository.save(root.path, coordinator(), null, emptyList(), emptySet())
            val before = saved.currentFile.readBytes()
            saved.historyFile.writeText("corrupt checkpoint")
            assertFailsWith<IllegalArgumentException> {
                repository.save(root.path, saved.document.copy(displayName = if (changed) "Changed" else "Main"), saved.contentHash, emptyList(), emptySet())
            }
            assertContentEquals(before, saved.currentFile.readBytes())
            Files.delete(saved.historyFile.toPath())
            repository.save(root.path, saved.document.copy(displayName = "Changed"), saved.contentHash, emptyList(), emptySet())
            assertEquals(saved.document, com.areslib.superstructure.SuperstructureDocumentCodec.decode(saved.historyFile.readText()))
        }
    }

    @Test fun `superstructure history cannot accept a checkpoint linked outside the project`() {
        val root = temporary.newFolder()
        val repository = SuperstructureProjectRepository()
        val saved = repository.save(root.path, coordinator(), null, emptyList(), emptySet())
        linkedCopy(saved.historyFile) {
            assertFailsWith<IllegalArgumentException> {
                repository.save(root.path, coordinator(), saved.contentHash, emptyList(), emptySet())
            }
            assertEquals(saved.document, repository.load(root.path, "main"))
        }
    }

    @Test fun `project snapshots diagnose outside field drivetrain and tuning files and directories`() {
        val relatives = listOf("src/main/assets/paths/field.json", ".ares/drivetrains/outside.aresdrivetrain",
            ".ares/tuning-components/outside.arestuningcomponent", ".ares/tuning/outside.arestuning")
        for (relative in relatives) for (directory in listOf(false, true)) {
            val root = temporary.newFolder()
            ProjectMetadataRepository().save(root.path, com.areslib.project.AresProjectMetadataDocument(
                projectId = "test-project",
                identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
                league = com.areslib.project.AresLeague.FTC,
                coordinateConvention = com.areslib.project.AresCoordinateConvention.CENTER_ORIGIN_CCW,
                robotLengthMeters = 0.45, robotWidthMeters = 0.43,
                fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
                runtimeOptions = com.areslib.project.AresRuntimeOptionsDocument(ftc = com.areslib.project.AresFtcRuntimeOptionsDocument()),
            ))
            val file = File(root, relative).apply {
                parentFile.mkdirs()
                writeText(if (name == "field.json") com.areslib.state.RobotFieldDocument.encode(FieldDocumentMapper.newDocument(League.FTC)) else "{}")
            }
            fun assertRejected() {
                val snapshot = AresProjectDocuments().load(root.path)
                assertTrue(snapshot.diagnostics.any {
                    (it.file == file || it.file == file.parentFile) && it.message.contains("escapes the selected repository")
                }, "Unowned ${file.name} must be rejected before codec decoding")
                assertEquals(null, snapshot.query.field)
            }
            if (directory) {
                val outside = temporary.newFolder()
                file.copyTo(File(outside, file.name))
                Files.delete(file.toPath())
                Files.delete(file.parentFile.toPath())
                withLink(file.parentFile, outside, true, ::assertRejected)
            } else linkedCopy(file) { assertRejected() }
        }
    }

    @Test fun `document locks serialize different aliases of a missing file`() {
        val root = temporary.newFolder()
        val alias = File(temporary.root, "lock-alias")
        withLink(alias, root, true) {
            val workers = Executors.newSingleThreadExecutor()
            val attempting = CountDownLatch(1)
            val acquired = CountDownLatch(1)
            try {
                lateinit var result: java.util.concurrent.Future<*>
                ProjectDocumentWriteLocks.withLock(File(root, "missing.json")) {
                    result = workers.submit {
                        attempting.countDown()
                        ProjectDocumentWriteLocks.withLock(File(alias, "missing.json")) { acquired.countDown() }
                    }
                    assertTrue(attempting.await(5, TimeUnit.SECONDS))
                    assertFalse(acquired.await(200, TimeUnit.MILLISECONDS), "Alias acquired the same document while its first lock was held")
                }
                result.get(5, TimeUnit.SECONDS)
                assertEquals(0L, acquired.count)
            } finally {
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
