package com.ares.analytics.service.project.persistence

import com.ares.analytics.util.Sha256
import com.areslib.project.schema.ProjectDocumentKind
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.*

class ProjectDocumentStoreAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Document(val id: String = "a", val revision: Int = 1, val parent: String? = null, val value: String = "first")
    private fun encodeDoc(document: Document) = "${document.id}|${document.revision}|${document.parent ?: "~"}|${document.value}"
    private fun decodeDoc(text: String): Document {
        val fields = text.split('|')
        require(fields.size == 4) { "Invalid fixture document" }
        val revision = fields[1].toInt()
        require(revision > 0)
        return Document(fields[0], revision, fields[2].takeUnless { it == "~" }, fields[3].removePrefix("legacy:"))
    }

    private interface Fixture {
        var afterHash: (Document) -> Unit
        fun save(projectPath: String, draft: Document): SavedProjectRevision<Document>
        fun restore(projectPath: String, rawDocumentId: String, requestedHash: String): SavedProjectRevision<Document>
        fun revisions(projectPath: String, id: String): List<ProjectRevisionSummary>
    }
    private inner class VersionedFixture : VersionedProjectDocumentStore<Document>(ProjectDocumentKind.SUBSYSTEM, "fixtures", "fixtures", "fixture"), Fixture {
        override var afterHash: (Document) -> Unit = {}
        override fun encode(document: Document) = encodeDoc(document)
        override fun decode(json: String) = decodeDoc(json)
        override fun contentHash(document: Document) = Sha256.hex(encodeDoc(document)).also { afterHash(document) }
        override fun documentId(document: Document) = document.id
        override fun revision(document: Document) = document.revision
        override fun displayName(document: Document) = document.value
        override fun withRevision(document: Document, revision: Int, parentHash: String?) = document.copy(revision = revision, parent = parentHash)
        override fun sameContent(previous: Document, draft: Document) = previous == draft.copy(revision = previous.revision, parent = previous.parent)
        override fun revisions(projectPath: String, id: String) = listRevisions(projectPath, id)
    }
    private inner class SingletonFixture : SingletonProjectDocumentStore<Document>(ProjectDocumentKind.SUBSYSTEM, "singleton.fixture", "singletons", "fixture"), Fixture {
        override var afterHash: (Document) -> Unit = {}
        override fun encode(document: Document) = encodeDoc(document)
        override fun decode(json: String) = decodeDoc(json)
        override fun contentHash(document: Document) = Sha256.hex(encodeDoc(document)).also { afterHash(document) }
        override fun revision(document: Document) = document.revision
        override fun withRevision(document: Document, revision: Int) = document.copy(revision = revision)
        override fun sameContent(previous: Document, draft: Document) = previous == draft.copy(revision = previous.revision)
        override fun revisions(projectPath: String, id: String) = listRevisions(projectPath)
        override fun restore(projectPath: String, rawDocumentId: String, requestedHash: String) = restore(projectPath, requestedHash)
    }
    private fun fixture(singleton: Boolean): Fixture = if (singleton) SingletonFixture() else VersionedFixture()

    @Test
    fun `immutable file publication never replaces an existing target`() {
        val target = temporary.newFile()
        target.writeText("Existing recovery bytes")
        assertFailsWith<IOException> { AtomicProjectFileWriter.write(target, "replacement", replaceExisting = false) }
        assertEquals("Existing recovery bytes", target.readText())
    }

    @Test
    fun `mutable file publication still replaces and preserves raw bytes`() {
        val target = temporary.newFile()
        target.writeText("old")
        val bytes = byteArrayOf(0, -1, 10, 13, 42)
        AtomicProjectFileWriter.write(target, bytes, replaceExisting = true)
        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun `corrupt existing checkpoints block both unchanged and changed saves`() {
        for (singleton in listOf(false, true)) for (changed in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            val before = first.currentFile.readText()
            first.historyFile.writeText("unreadable checkpoint")
            assertFailsWith<IllegalArgumentException> {
                store.save(root.path, first.document.copy(value = if (changed) "changed" else first.document.value))
            }
            assertEquals(before, first.currentFile.readText())
            assertEquals("unreadable checkpoint", first.historyFile.readText())
            assertEquals(1, first.historyFile.parentFile.listFiles().orEmpty().size)
        }
    }

    @Test
    fun `missing current checkpoint is restored before a changed save replaces current content`() {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            assertTrue(first.historyFile.delete())
            store.save(root.path, first.document.copy(value = "changed"))
            assertEquals(listOf(2, 1), store.revisions(root.path, "a").map { it.revision })
            assertEquals(first.document, decodeDoc(first.historyFile.readText()))
        }
    }

    @Test
    fun `foreign document in history cannot redirect restoration to another current document`() {
        val root = temporary.newFolder()
        val store = VersionedFixture()
        val a = store.save(root.path, Document())
        val b = store.save(root.path, Document(id = "b", value = "old B"))
        val currentB = store.save(root.path, b.document.copy(value = "current B"))
        b.historyFile.copyTo(a.historyFile.parentFile.resolve(b.historyFile.name))
        val beforeA = a.currentFile.readText()
        val beforeB = currentB.currentFile.readText()
        val result = runCatching { store.restore(root.path, "a", b.contentHash) }
        assertEquals(beforeA, a.currentFile.readText())
        assertEquals(beforeB, currentB.currentFile.readText(), "Restoring A must not overwrite B")
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `history changed after selection cannot substitute different content in either store`() {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            val current = store.save(root.path, first.document.copy(value = "current"))
            val before = current.currentFile.readText()
            store.afterHash = { document ->
                if (document == first.document) {
                    store.afterHash = {}
                    first.historyFile.writeText(encodeDoc(first.document.copy(value = "substituted after selection")))
                }
            }
            val restored = store.restore(root.path, "a", first.contentHash)
            assertEquals(first.document.value, restored.document.value)
            assertEquals(first.document.value, decodeDoc(current.currentFile.readText()).value)
            assertNotEquals(before, current.currentFile.readText())
        }
    }

    @Test
    fun `history filenames must agree with the decoded revision and content hash`() {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            first.historyFile.copyTo(first.historyFile.parentFile.resolve("0009-${first.contentHash.take(12)}.fixture"))
            assertFailsWith<IllegalArgumentException> { store.revisions(root.path, "a") }
        }
    }

    @Test
    fun `ordinary saves no-ops and restores preserve the expected history`() {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            val second = store.save(root.path, first.document.copy(value = "second"))
            val unchanged = store.save(root.path, second.document)
            assertFalse(unchanged.createdRevision)
            val restored = store.restore(root.path, "a", first.contentHash)
            assertEquals("first", restored.document.value)
            assertEquals(3, restored.document.revision)
            if (!singleton) assertEquals(second.contentHash, restored.document.parent)
            assertEquals(listOf(3, 2, 1), store.revisions(root.path, "a").map { it.revision })
        }
    }

    @Test
    fun `recovery move cannot replace an existing destination or consume its source`() {
        val source = temporary.newFile().apply { writeText("recoverable source") }
        val destination = temporary.newFile().apply { writeText("another owner") }
        assertFailsWith<IOException> { moveWithoutReplacement(source, destination) }
        assertEquals("recoverable source", source.readText())
        assertEquals("another owner", destination.readText())
    }

    @Test
    fun `recovery move publishes intact bytes before removing the source name`() {
        val source = temporary.newFile().apply { writeBytes(byteArrayOf(0, -1, 42)) }
        val destination = temporary.root.resolve("recovered.fixture")
        moveWithoutReplacement(source, destination)
        assertFalse(source.exists())
        assertContentEquals(byteArrayOf(0, -1, 42), destination.readBytes())
    }

    @Test
    fun `history rejects a filename whose hash matches neither normalized nor original bytes`() {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            first.historyFile.copyTo(first.historyFile.parentFile.resolve("0001-000000000000.fixture"))
            assertFailsWith<IllegalArgumentException> { store.revisions(root.path, "a") }
        }
    }

    @Test
    fun `normalized legacy bytes retain valid history without duplicate logical revisions`() {
        for (singleton in listOf(false, true)) {
            val root = temporary.newFolder()
            val store = fixture(singleton)
            val first = store.save(root.path, Document())
            val raw = encodeDoc(first.document.copy(value = "legacy:first"))
            val legacy = first.historyFile.parentFile.resolve("0001-${Sha256.hex(raw).take(12)}.fixture")
            legacy.writeText(raw)
            assertTrue(first.historyFile.delete())
            assertEquals(first.contentHash, store.revisions(root.path, "a").single().contentHash)
            val restored = store.restore(root.path, "a", first.contentHash)
            assertEquals("first", restored.document.value)
            assertEquals(1, store.revisions(root.path, "a").size)
            assertEquals(raw, legacy.readText())
        }
    }
}
