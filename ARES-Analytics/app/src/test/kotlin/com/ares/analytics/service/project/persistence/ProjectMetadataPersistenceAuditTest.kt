package com.ares.analytics.service.project.persistence

import com.ares.analytics.shared.AppJson
import com.ares.analytics.util.Sha256
import com.areslib.project.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProjectMetadataPersistenceAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository = ProjectMetadataRepository()
    private fun document(length: Double = 0.45) = AresProjectMetadataDocument(
        projectId = "test-project", identity = AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
        league = AresLeague.FTC, coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = length, robotWidthMeters = 0.43, fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )
    private fun invalidCurrent(root: File, bytes: ByteArray = byteArrayOf(-1, 0, 13, 10)): File = repository.file(root.path).apply {
        parentFile.mkdirs(); writeBytes(bytes)
    }

    @Test fun `unreviewed creation cannot replace existing valid or corrupt metadata`() {
        for (corrupt in listOf(false, true)) {
            val root = temporary.newFolder()
            val file = if (corrupt) invalidCurrent(root) else {
                repository.save(root.path, document()); repository.file(root.path)
            }
            val before = file.readBytes()
            assertFailsWith<IllegalArgumentException> { repository.save(root.path, document(0.51)) }
            assertContentEquals(before, file.readBytes())
            assertFalse(File(root, ".ares/history").exists())
        }
    }

    @Test fun `two concurrent initializations have one winner`() {
        val root = temporary.newFolder()
        val workers = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val results = listOf(0.45, 0.51).map { length -> workers.submit<Result<String>> {
                barrier.await(5, TimeUnit.SECONDS)
                runCatching { repository.save(root.path, document(length)) }
            } }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(results.single { it.isSuccess }.getOrThrow(), AresProjectMetadataCodec.contentHash(repository.load(root.path).getOrThrow()))
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `shape validation rejects malformed numbers nested values and geometry without null errors`() {
        val base = AppJson.parseToJsonElement(AresProjectMetadataCodec.encode(document())).jsonObject
        val variants = listOf(
            "schemaVersion" to "5.5", "schemaVersion" to "\"5\"", "schemaVersion" to "2147483648",
            "projectId" to "12", "identity" to "[]", "identity" to "{}",
            "identity" to "{\"teamId\":null,\"seasonId\":\"2026\",\"robotId\":\"test\",\"displayName\":\"Robot\"}",
            "league" to "true", "coordinateConvention" to "\"BLUE_CORNER_ORIGIN_CCW\"",
            "robotLengthMeters" to "0", "robotLengthMeters" to "-1", "robotLengthMeters" to "5",
            "robotLengthMeters" to "1e400", "robotLengthMeters" to "\"0.45\"", "robotWidthMeters" to "null",
            "runtimeOptions" to "[]", "runtimeOptions" to "{\"ftc\":{\"hubCommandTransport\":\"STANDARD_SDK\",\"limelightProxyEnabled\":\"true\"}}",
        )
        for ((key, value) in variants) {
            val json = JsonObject(base.toMutableMap().apply { put(key, AppJson.parseToJsonElement(value)) }).toString()
            val error = assertFailsWith<IllegalArgumentException>("$key=$value") { decodeProjectMetadata(json) }
            assertFalse(error.message.orEmpty().contains("non-null is null"))
        }
        for (json in listOf("null", "[]", "true", "{}", "{")) assertFailsWith<IllegalArgumentException> { decodeProjectMetadata(json) }
    }

    @Test fun `reviewed saves bind canonical hashes preserve history and leave noops unchanged`() {
        val root = temporary.newFolder()
        val first = repository.saveReviewed(root.path, null, document().copy(schemaVersion = 1))
        assertEquals(5, first.document.schemaVersion)
        assertEquals(Sha256.hex(repository.file(root.path).readText()), first.contentHash)
        val second = repository.saveReviewed(root.path, first.contentHash, document(0.51))
        assertEquals(first.document, AresProjectMetadataCodec.decode(requireNotNull(second.historyFile).readText()))
        val before = repository.file(root.path).readBytes()
        val unchanged = repository.saveReviewed(root.path, second.contentHash, second.document)
        assertNull(unchanged.historyFile)
        assertFalse(unchanged.created)
        assertContentEquals(before, repository.file(root.path).readBytes())
        assertFailsWith<IllegalArgumentException> { repository.saveReviewed(root.path, first.contentHash, document(0.52)) }
        assertContentEquals(before, repository.file(root.path).readBytes())
        File(root, ".ares/history/project/${second.contentHash}.json").writeText("collision")
        assertFailsWith<IllegalStateException> { repository.saveReviewed(root.path, second.contentHash, document(0.52)) }
        assertContentEquals(before, repository.file(root.path).readBytes())
    }

    @Test fun `repair preserves exact invalid bytes and rejects valid current content`() {
        val root = temporary.newFolder()
        val file = invalidCurrent(root)
        val raw = file.readBytes()
        val hash = repository.rawContentHash(root.path)
        assertEquals(Sha256.hex(raw), hash)
        val repaired = repository.repairReviewed(root.path, hash, document())
        assertTrue(repaired.repaired)
        assertContentEquals(raw, requireNotNull(repaired.historyFile).readBytes())
        assertEquals(repaired.document, repository.load(root.path).getOrThrow())
        val valid = file.readBytes()
        assertFailsWith<IllegalStateException> { repository.repairReviewed(root.path, Sha256.hex(valid), document(0.52)) }
        assertContentEquals(valid, file.readBytes())
    }

    @Test fun `repair rejects changed missing or colliding recovery evidence before replacement`() {
        for (failure in listOf("changed", "missing", "collision")) {
            val root = temporary.newFolder()
            val file = invalidCurrent(root)
            val hash = repository.rawContentHash(root.path)
            when (failure) {
                "changed" -> file.writeText("changed")
                "missing" -> Files.delete(file.toPath())
                else -> File(root, ".ares/recovery/project/$hash.raw").apply { parentFile.mkdirs(); writeText("different") }
            }
            val before = file.takeIf { it.exists() }?.readBytes()
            assertFails { repository.repairReviewed(root.path, hash, document()) }
            if (before == null) assertFalse(file.exists()) else assertContentEquals(before, file.readBytes())
        }
    }

    @Test fun `metadata reads and reviewed writes reject an outside file link`() {
        val root = temporary.newFolder()
        val hash = repository.save(root.path, document())
        val file = repository.file(root.path)
        val outside = temporary.newFile()
        file.copyTo(outside, overwrite = true)
        Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        try {
            val before = outside.readBytes()
            assertFailsWith<IllegalArgumentException> { repository.load(root.path) }
            assertFailsWith<IllegalArgumentException> { repository.rawContentHash(root.path) }
            assertFailsWith<IllegalArgumentException> { repository.saveReviewed(root.path, hash, document(0.51)) }
            assertContentEquals(before, outside.readBytes())
        } finally { Files.deleteIfExists(file.toPath()) }
    }

    @Test fun `invalid drafts never create or replace project bytes`() {
        for (length in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val root = temporary.newFolder()
            assertFailsWith<IllegalArgumentException> { repository.save(root.path, document(length)) }
            assertFalse(File(root, ".ares").exists())
            val hash = repository.save(root.path, document())
            val file = repository.file(root.path)
            val before = file.readBytes()
            assertFailsWith<IllegalArgumentException> { repository.saveReviewed(root.path, hash, document(length)) }
            assertContentEquals(before, file.readBytes())
        }
    }
}
