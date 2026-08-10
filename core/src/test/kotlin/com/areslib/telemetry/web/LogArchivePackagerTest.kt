package com.areslib.telemetry.web

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LogArchivePackagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `rejects traversal absolute paths directories and unsupported files`() {
        val logDir = Files.createDirectory(temporaryDirectory.resolve("logs")).toFile()
        Files.writeString(temporaryDirectory.resolve("outside.csv"), "secret")
        Files.createDirectory(logDir.toPath().resolve("fake.csv"))
        Files.writeString(logDir.toPath().resolve("notes.txt"), "no")

        val invalidNames = listOf(
            "../outside.csv",
            "sub/log.csv",
            temporaryDirectory.resolve("outside.csv").toString(),
            "fake.csv",
            "notes.txt"
        )
        for (name in invalidNames) {
            assertFalse(LogArchivePackager.isValidLogFile(logDir, name), name)
            assertEquals(0L, LogArchivePackager.getFileLength(logDir, name), name)
        }
    }

    @Test
    fun `streams and archives a validated direct child`() {
        val logDir = Files.createDirectory(temporaryDirectory.resolve("logs")).toFile()
        val bytes = byteArrayOf(1, 2, 3, 4)
        Files.write(logDir.toPath().resolve("match.JSONL"), bytes)
        val output = ByteArrayOutputStream()

        assertEquals(listOf("match.JSONL"), LogArchivePackager.listLogFiles(logDir))
        LogArchivePackager.streamLogFile(logDir, "match.JSONL", output)
        assertContentEquals(bytes, output.toByteArray())
        assertTrue(LogArchivePackager.markSynced(logDir, "match.JSONL"))
        assertTrue(logDir.resolve("synced/match.JSONL").isFile)
        assertFalse(LogArchivePackager.markSynced(logDir, "match.JSONL"))
    }
}
