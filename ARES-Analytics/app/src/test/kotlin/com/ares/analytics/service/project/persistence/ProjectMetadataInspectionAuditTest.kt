package com.ares.analytics.service.project.persistence

import com.ares.analytics.shared.AppJson
import com.ares.analytics.util.Sha256
import com.areslib.project.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProjectMetadataInspectionAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository = ProjectMetadataRepository()
    private fun document() = AresProjectMetadataDocument(
        projectId = "test-project", identity = AresProjectIdentityDocument("99999", "2026", "robot", "Robot"),
        league = AresLeague.FTC, coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.45, robotWidthMeters = 0.43, fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )
    private fun write(root: File, bytes: ByteArray) = repository.file(root.path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test fun `invalid inspection retains the hash of the diagnosed bytes after replacement`() {
        val root = temporary.newFolder()
        val original = "{\"schemaVersion\":5}".toByteArray()
        val file = write(root, original)
        val inspected = repository.inspect(root.path)
        val replacement = "{\"projectId\":null}".toByteArray()
        file.writeBytes(replacement)
        assertTrue(inspected.result.isFailure)
        assertFalse(inspected.result.exceptionOrNull() is UnsupportedProjectMetadataSchemaException)
        assertEquals(Sha256.hex(original), inspected.rawContentHash)
        assertFailsWith<IllegalArgumentException> {
            repository.repairReviewed(root.path, requireNotNull(inspected.rawContentHash), document())
        }
        assertContentEquals(replacement, file.readBytes())
        assertFalse(File(root, ".ares/recovery").exists())
    }

    @Test fun `valid inspection preserves a decoded snapshot and exact noncanonical byte hash`() {
        val root = temporary.newFolder()
        val encoded = AresProjectMetadataCodec.encode(document())
        val raw = ("\r\n" + encoded.replace("\n", "\r\n") + " \r\n").toByteArray()
        val file = write(root, raw)
        val inspected = repository.inspect(root.path)
        file.writeText("replacement")
        assertEquals(document(), inspected.result.getOrThrow())
        assertEquals(Sha256.hex(raw), inspected.rawContentHash)
        assertNotEquals(AresProjectMetadataCodec.contentHash(document()), inspected.rawContentHash)
    }

    @Test fun `missing empty and directory targets have distinct inspection outcomes`() {
        val root = temporary.newFolder()
        val missing = repository.inspect(root.path)
        assertIs<NoSuchElementException>(missing.result.exceptionOrNull())
        assertNull(missing.rawContentHash)
        val emptyFile = write(root, byteArrayOf())
        val empty = repository.inspect(root.path)
        assertTrue(empty.result.isFailure)
        assertEquals(Sha256.hex(byteArrayOf()), empty.rawContentHash)
        assertTrue(emptyFile.delete())
        assertTrue(emptyFile.mkdir())
        assertFails { repository.inspect(root.path) }
    }

    @Test fun `unsupported integral JSON versions are classified before current shape checks`() {
        for (rawVersion in listOf("3", "3.0", "3e0", "6.00", "6e0")) {
            val root = temporary.newFolder()
            val raw = "{\"schemaVersion\":$rawVersion}".toByteArray()
            val file = write(root, raw)
            val inspected = repository.inspect(root.path)
            val error = assertIs<UnsupportedProjectMetadataSchemaException>(inspected.result.exceptionOrNull())
            assertEquals(rawVersion.toBigDecimal().intValueExact(), error.schemaVersion)
            assertFailsWith<UnsupportedProjectMetadataSchemaException> {
                repository.repairReviewed(root.path, requireNotNull(inspected.rawContentHash), document())
            }
            assertContentEquals(raw, file.readBytes())
            assertFalse(File(root, ".ares/recovery").exists())
        }
    }

    @Test fun `current integral versions agree with the library codec without accepting fractional versions`() {
        val base = AppJson.parseToJsonElement(AresProjectMetadataCodec.encode(document())).jsonObject
        for (version in listOf("5", "5.0", "5e0")) {
            val json = JsonObject(base + ("schemaVersion" to AppJson.parseToJsonElement(version))).toString()
            assertEquals(AresProjectMetadataCodec.decode(json), decodeProjectMetadata(json))
        }
        for (version in listOf("5.5", "5.00000000000000001", "2147483648", "\"5\"", "true")) {
            val json = JsonObject(base + ("schemaVersion" to AppJson.parseToJsonElement(version))).toString()
            assertFailsWith<IllegalArgumentException>(version) { decodeProjectMetadata(json) }
        }
    }
}
