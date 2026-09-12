package com.ares.analytics.service.project.persistence

import com.ares.analytics.util.Sha256
import com.areslib.project.schema.ProjectDocumentKind
import com.areslib.routine.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AutonomousCatalogRestoreAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val routines = RoutineProjectRepository()
    private val repository = AutonomousCatalogProjectRepository(routines)
    private fun routine(root: File) = routines.save(root.path, RoutineDocument(documentId = "a", name = "A", steps = listOf(RoutineStep.wait(0.1))))
    private fun catalog(routineId: String = "a", x: Double = 0.2) = AutonomousCatalogDocument(
        projectId = "test", defaultEntryId = "main", entries = listOf(AutonomousCatalogEntry(
            entryId = "main", displayName = "Main", routineId = routineId, startingPose = RoutinePose(x, 0.3, 0.4),
            authoredAlliance = RoutineAlliance.BLUE, mirrorForOppositeAlliance = false,
        )),
    )
    @Test fun `catalog restore retains entry metadata and linear revision history`() {
        val root = temporary.newFolder()
        routine(root)
        val first = repository.save(root.path, catalog())
        val second = repository.save(root.path, catalog(x = 0.6))
        assertFalse(repository.save(root.path, second.document).createdRevision)
        val restored = repository.restore(root.path, first.contentHash)
        assertEquals(first.document.entries, restored.document.entries)
        assertEquals(3, restored.document.revision)
        assertEquals(restored.document, repository.load(root.path).getOrThrow())
        assertEquals(listOf(3, 2, 1), repository.listRevisions(root.path).map { it.revision })
        assertNull(repository.diagnostic(root.path))
    }
    @Test fun `missing routine references reject both save and restore even with an empty known set`() {
        val root = temporary.newFolder()
        val routine = routine(root)
        val saved = repository.save(root.path, catalog())
        Files.delete(routine.currentFile.toPath())
        val before = saved.currentFile.readBytes()
        assertFailsWith<IllegalArgumentException> { repository.save(root.path, catalog()) }
        assertFailsWith<IllegalArgumentException> { repository.restore(root.path, saved.contentHash) }
        assertTrue(repository.load(root.path).isFailure)
        assertNotNull(repository.diagnostic(root.path))
        assertContentEquals(before, saved.currentFile.readBytes())
        assertEquals(1, repository.listRevisions(root.path).size)
    }
    @Test fun `corrupt routine files cannot be bypassed through historical catalog restoration`() {
        val root = temporary.newFolder()
        val routine = routine(root)
        val saved = repository.save(root.path, catalog())
        routine.currentFile.writeText("corrupt")
        val before = saved.currentFile.readBytes()
        assertFailsWith<IllegalArgumentException> { repository.restore(root.path, saved.contentHash) }
        assertFailsWith<IllegalArgumentException> { repository.save(root.path, catalog()) }
        assertContentEquals(before, saved.currentFile.readBytes())
    }
    @Test fun `valid history can restore a catalog whose current references are invalid`() {
        val root = temporary.newFolder()
        routine(root)
        val saved = repository.save(root.path, catalog())
        assertFailsWith<IllegalArgumentException> { repository.save(root.path, catalog("missing")) }
        saved.currentFile.writeText(AutonomousCatalogCodec.encode(catalog("missing")))
        assertTrue(repository.load(root.path).isFailure)
        val restored = repository.restore(root.path, saved.contentHash)
        assertEquals(saved.document.entries, restored.document.entries)
        assertEquals(restored.document, repository.load(root.path).getOrThrow())
    }
    @Test fun `missing and corrupt catalogs have distinct diagnostics and are never silently replaced`() {
        val root = temporary.newFolder()
        assertTrue(repository.load(root.path).isFailure)
        assertNull(repository.diagnostic(root.path))
        routine(root)
        val saved = repository.save(root.path, catalog())
        saved.currentFile.writeText("corrupt")
        assertNotNull(repository.diagnostic(root.path))
        assertFailsWith<IllegalArgumentException> { repository.restore(root.path, saved.contentHash) }
        assertEquals("corrupt", saved.currentFile.readText())
        assertFails { repository.restore(root.path, "0".repeat(64)) }
        assertEquals("corrupt", saved.currentFile.readText())
    }
    @Test fun `malformed historical entries reject restore before current content changes`() {
        val root = temporary.newFolder()
        routine(root)
        val saved = repository.save(root.path, catalog())
        val before = saved.currentFile.readBytes()
        saved.historyFile.writeText("{}")
        assertFailsWith<IllegalArgumentException> { repository.restore(root.path, saved.contentHash) }
        assertContentEquals(before, saved.currentFile.readBytes())
    }

    private data class Value(val revision: Int = 1, val text: String = "first")
    private class Fixture : SingletonProjectDocumentStore<Value>(ProjectDocumentKind.CAPABILITY_CATALOG, "fixture.txt", "fixture", "txt") {
        override fun encode(document: Value) = "${document.revision}|${document.text}"
        override fun decode(json: String): Value = json.split('|').let { Value(it[0].toInt(), it[1]) }
        override fun contentHash(document: Value) = Sha256.hex(encode(document))
        override fun revision(document: Value) = document.revision
        override fun withRevision(document: Value, revision: Int) = document.copy(revision = revision)
        override fun sameContent(previous: Value, draft: Value) = previous.text == draft.text
    }
    @Test fun `restore validates the selected snapshot once and commits that same content`() {
        val root = temporary.newFolder()
        val store = Fixture()
        val first = store.save(root.path, Value())
        store.save(root.path, Value(text = "second"))
        var checks = 0
        val restored = store.restore(root.path, first.contentHash) { selected ->
            checks++
            assertEquals(first.document, selected)
            first.historyFile.writeText("1|replaced after validation")
        }
        assertEquals(1, checks)
        assertEquals("first", restored.document.text)
        assertEquals(3, restored.document.revision)
    }
    @Test fun `a selected-snapshot validation failure leaves current and history unchanged`() {
        val root = temporary.newFolder()
        val store = Fixture()
        val first = store.save(root.path, Value())
        val second = store.save(root.path, Value(text = "second"))
        val before = second.currentFile.readBytes()
        assertFailsWith<IllegalStateException> { store.restore(root.path, first.contentHash) { error("Invalid project reference") } }
        assertContentEquals(before, second.currentFile.readBytes())
        assertEquals(listOf(2, 1), store.listRevisions(root.path).map { it.revision })
    }
}
