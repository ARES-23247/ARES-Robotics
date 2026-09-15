package com.ares.analytics.service.hardware

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.*

class HardwareEvidenceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath()
    private val kind = HardwareEvidenceKind.CONFIGURATION

    @Test
    fun `normal publication exposes complete UTF8 bytes and removes its temporary link`() {
        val encoded = "{\"reviewer\":\"Rénée\"}\n"
        val store = HardwareEvidenceStore(root)
        val target = store.append(kind, 10L, encoded) { prepared, destination ->
            assertEquals(encoded, Files.readString(prepared))
            assertFalse(Files.exists(destination))
            assertTrue(store.files(kind).isEmpty())
        }
        assertEquals(encoded, Files.readString(target))
        assertEquals(listOf(target.toFile()), store.files(kind))
        assertEquals(1, target.parent.toFile().listFiles().orEmpty().size)
    }

    @Test
    fun `unsupported and failed hard links fall back to complete exclusive writes`() {
        for (unsupported in listOf(false, true)) {
            val project = temporary.newFolder().toPath()
            val store = HardwareEvidenceStore(project) { _, _ ->
                if (unsupported) throw UnsupportedOperationException("No hard links")
                else throw IOException("Provider does not support hard links")
            }
            val encoded = "{\"reviewer\":\"Rénée\"}\n"
            val target = store.append(kind, 10L, encoded)
            assertEquals(encoded, Files.readString(target))
            assertEquals(1, target.parent.toFile().listFiles().orEmpty().size)
        }
    }

    @Test
    fun `fallback cannot overwrite a destination that appeared after preparation`() {
        val store = HardwareEvidenceStore(root) { _, _ -> throw UnsupportedOperationException("No hard links") }
        var record: java.nio.file.Path? = null
        assertFailsWith<FileAlreadyExistsException> {
            store.append(kind, 10L, "new bytes") { _, target ->
                record = target
                Files.writeString(target, "other owner", StandardOpenOption.CREATE_NEW)
            }
        }
        assertEquals("other owner", Files.readString(requireNotNull(record)))
        assertEquals(1, record!!.parent.toFile().listFiles().orEmpty().size)
    }

    @Test
    fun `failure before publication creates no record and removes prepared bytes`() {
        val store = HardwareEvidenceStore(root)
        assertFailsWith<IOException> {
            store.append(kind, 10L, "fixture") { _, _ -> throw IOException("Before publication") }
        }
        assertTrue(store.files(kind).isEmpty())
        assertTrue(root.resolve(".ares/evidence/hardware/configuration").toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `repeated identical append preserves the existing record`() {
        val store = HardwareEvidenceStore(root)
        val target = store.append(kind, 10L, "fixture")
        assertFailsWith<FileAlreadyExistsException> { store.append(kind, 10L, "fixture") }
        assertEquals("fixture", Files.readString(target))
        assertEquals(1, target.parent.toFile().listFiles().orEmpty().size)
    }
}
