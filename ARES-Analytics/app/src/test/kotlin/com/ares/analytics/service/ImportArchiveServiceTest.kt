package com.ares.analytics.service

import com.ares.analytics.shared.AppJsonPretty
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportArchiveServiceTest {

    @Test
    fun `loads imported quarantined and unreadable reports`() {
        val project = createTempDirectory("import-archive-load").toFile()
        val imported = File(project, "logs/imported").apply { mkdirs() }
        val quarantine = File(project, "logs/quarantine").apply { mkdirs() }
        writeEvidence(imported, "good.csv", ImportStatus.SUCCESS)
        writeEvidence(quarantine, "bad.jsonl", ImportStatus.REJECTED)
        File(quarantine, "broken.csv${AutoImportService.IMPORT_REPORT_SUFFIX}").writeText("not-json")

        val snapshot = ImportArchiveService().load(project.absolutePath)

        assertEquals(1, snapshot.imported.size)
        assertEquals("good.csv", snapshot.imported.single().report?.sourceName)
        assertEquals(2, snapshot.quarantined.size)
        assertEquals(1, snapshot.unreadableCount)
        assertTrue(snapshot.quarantined.any { it.readError != null })
        project.deleteRecursively()
    }

    @Test
    fun `retry atomically requeues a copy and preserves quarantine evidence`() {
        val project = createTempDirectory("import-archive-retry").toFile()
        val quarantine = File(project, "logs/quarantine").apply { mkdirs() }
        val originalBytes = "timestamp,value\n1000,1.0".toByteArray()
        val entry = writeEvidence(
            quarantine,
            "abc123_bad.csv",
            ImportStatus.REJECTED,
            sourceName = "../../repaired.csv",
            contents = originalBytes
        )
        val service = ImportArchiveService()
        val loaded = service.load(project.absolutePath).quarantined.single()

        val requeued = service.retry(project.absolutePath, loaded)

        assertEquals(File(project, "logs").canonicalFile, requeued.parentFile.canonicalFile)
        assertTrue(requeued.name.endsWith("_repaired.csv"))
        assertContentEquals(originalBytes, requeued.readBytes())
        assertTrue(File(entry.logPath).isFile, "quarantined log evidence was removed")
        assertTrue(File(entry.reportPath).isFile, "quarantine report evidence was removed")
        assertTrue(File(project, "logs").listFiles().orEmpty().none { it.name.endsWith(".partial") })
        project.deleteRecursively()
    }

    @Test
    fun `retry requeues Driver Station log and events companion together`() {
        val project = createTempDirectory("import-archive-ds-retry").toFile()
        val quarantine = File(project, "logs/quarantine").apply { mkdirs() }
        val dslogBytes = byteArrayOf(4, 1, 2, 3)
        val eventsBytes = byteArrayOf(4, 5, 6, 7)
        val entry = writeEvidence(
            quarantine,
            "abc123_match.dslog",
            ImportStatus.REJECTED,
            sourceName = "match.dslog",
            contents = dslogBytes,
        )
        val quarantinedEvents = File(quarantine, "abc123_match.dsevents").apply {
            writeBytes(eventsBytes)
        }

        val loaded = ImportArchiveService().load(project.absolutePath).quarantined.single()
        val requeued = ImportArchiveService().retry(project.absolutePath, loaded)
        val requeuedEvents = File(
            requeued.parentFile,
            requeued.name.substringBeforeLast('.') + ".dsevents",
        )

        assertContentEquals(dslogBytes, requeued.readBytes())
        assertContentEquals(eventsBytes, requeuedEvents.readBytes())
        assertTrue(File(entry.logPath).isFile, "quarantined dslog evidence was removed")
        assertTrue(quarantinedEvents.isFile, "quarantined dsevents evidence was removed")
        assertTrue(File(project, "logs").listFiles().orEmpty().none { it.name.endsWith(".partial") })
        project.deleteRecursively()
    }

    @Test
    fun `retry rejects paths outside quarantine`() {
        val project = createTempDirectory("import-archive-escape").toFile()
        val outside = File(project, "outside.csv").apply { writeText("timestamp,value\n1,1") }
        val entry = ImportArchiveEntry(
            reportPath = outside.absolutePath + AutoImportService.IMPORT_REPORT_SUFFIX,
            logPath = outside.absolutePath,
            location = ImportArchiveLocation.QUARANTINE,
            report = ImportReport(
                sourceName = "outside.csv",
                sourceSha256 = "0".repeat(64),
                sourceSizeBytes = outside.length(),
                decoder = "csv",
                status = ImportStatus.REJECTED
            )
        )

        assertFailsWith<IllegalArgumentException> {
            ImportArchiveService().retry(project.absolutePath, entry)
        }
        project.deleteRecursively()
    }

    private fun writeEvidence(
        directory: File,
        logName: String,
        status: ImportStatus,
        sourceName: String = logName,
        contents: ByteArray = "data".toByteArray()
    ): ImportArchiveEntry {
        val log = File(directory, logName).apply { writeBytes(contents) }
        val report = ImportReport(
            sourceName = sourceName,
            sourceSha256 = "a".repeat(64),
            sourceSizeBytes = log.length(),
            decoder = log.extension,
            status = status,
            acceptedRecords = if (status == ImportStatus.SUCCESS) 1 else 0,
            error = if (status == ImportStatus.REJECTED) "Malformed input" else null
        )
        val reportFile = File(directory, log.name + AutoImportService.IMPORT_REPORT_SUFFIX).apply {
            writeText(AppJsonPretty.encodeToString(report))
        }
        return ImportArchiveEntry(
            reportPath = reportFile.absolutePath,
            logPath = log.absolutePath,
            location = if (status == ImportStatus.REJECTED) {
                ImportArchiveLocation.QUARANTINE
            } else {
                ImportArchiveLocation.IMPORTED
            },
            report = report,
            lastModifiedMs = reportFile.lastModified()
        )
    }
}
