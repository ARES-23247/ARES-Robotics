package com.areslib.logging

import java.io.File
import java.io.FileFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRetentionAccountingAuditTest {
    private class Log(name: String, val bytes: Long, val modified: Long, val removable: Boolean = true) : File(name) {
        var attempts = 0
        var timestampReads = 0
        var sizeReads = 0
        var removed = false
        override fun isFile() = !removed
        override fun lastModified(): Long { timestampReads++; return modified }
        override fun length(): Long { sizeReads++; return bytes }
        override fun delete(): Boolean { attempts++; removed = removable; return removable }
    }

    private class Directory(val logs: List<Log>, private val available: Long = Long.MAX_VALUE) : File("retention-fixture") {
        override fun isDirectory() = true
        override fun getUsableSpace() = available
        override fun listFiles(filter: FileFilter): Array<File> = logs.filter(filter::accept).toTypedArray()
    }

    private fun policy(maxCount: Int = 2, minimum: Int = 1) = testLoggingPolicy(
        maxFileBytes = 10, maxDirectoryBytes = 1000, maxCompletedFiles = maxCount, minRetainedFiles = minimum
    )

    @Test fun `failed oldest deletion does not stop count based pruning`() {
        val logs = listOf(Log("ares_log_a.csv", 10, 1, false), Log("ares_log_b.csv", 20, 2), Log("ares_log_c.csv", 30, 3))
        assertEquals(LogRetentionResult(1, 20, 2, 40), LogStorageGovernance.enforceRetention(Directory(logs), policy()))
        assertFalse(logs[0].removed)
        assertTrue(logs[1].removed)
        assertEquals(0, logs[2].attempts)
    }

    @Test fun `failed files remain in result count and byte totals`() {
        val logs = (1..4).map { Log("ares_log_$it.csv", 10, it.toLong(), false) }
        assertEquals(LogRetentionResult(0, 0, 4, 40), LogStorageGovernance.enforceRetention(Directory(logs), policy()))
        assertTrue(logs.all { it.attempts == 1 }, "Each eligible file is tried once, even if earlier deletions fail")
    }

    @Test fun `minimum retention uses actual surviving files`() {
        val logs = (1..4).map { Log("ares_log_$it.csv", 10, it.toLong(), it != 1) }
        val result = LogStorageGovernance.enforceRetention(Directory(logs), policy(2, 2).copy(maxDirectoryBytes = 10))
        assertEquals(LogRetentionResult(2, 20, 2, 20), result)
        assertTrue(logs[0].isFile && logs[3].isFile)
    }

    @Test fun `free space estimate grows only after successful deletion`() {
        val logs = listOf(Log("ares_log_a.csv", 100, 1, false), Log("ares_log_b.csv", 40, 2), Log("ares_log_c.csv", 80, 3))
        val result = LogStorageGovernance.enforceRetention(Directory(logs, 20), policy(3, 0).copy(minFreeSpaceBytes = 60))
        assertEquals(LogRetentionResult(1, 40, 2, 180), result)
        assertEquals(0, logs[2].attempts)
    }

    @Test fun `retention snapshots metadata once before sorting and deleting`() {
        val logs = (1..128).map { Log("ares_log_$it.csv.gz", 10, (129 - it).toLong()) }
        assertEquals(126, LogStorageGovernance.enforceRetention(Directory(logs), policy()).deletedFiles)
        assertTrue(logs.all { it.timestampReads == 1 }, "Sorting must use the timestamp snapshot")
        assertTrue(logs.all { it.sizeReads == 1 }, "Accounting must use one size snapshot")
        assertTrue(logs.take(2).none { it.removed })
    }

    @Test fun `equal timestamps have deterministic filename order`() {
        val logs = listOf(Log("ares_log_c.csv", 1, 1), Log("ares_log_b.csv", 1, 1), Log("ares_log_a.csv", 1, 1))
        LogStorageGovernance.enforceRetention(Directory(logs), policy())
        assertTrue(logs[2].removed)
    }

    @Test fun `unowned active abandoned and unsupported files are untouched`() {
        val logs = listOf("action_log_a.jsonl", "ares_log_a.csv.active", "ares_log_b.csv.abandoned", "download.csv", "ares_log_a.txt")
            .map { Log(it, 10, 1) }
        val completed = Log("ares_log_valid.CSV.GZ", 10, 1)
        assertEquals(LogRetentionResult(0, 0, 1, 10), LogStorageGovernance.enforceRetention(Directory(logs + completed), policy()))
        assertTrue(logs.all { it.attempts == 0 && it.sizeReads == 0 })
    }
}
