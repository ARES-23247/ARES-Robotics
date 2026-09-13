package com.areslib.logging

import java.io.File
import java.io.FileFilter
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRecoveryBoundaryAuditTest {
    private fun withDirectory(block: (File) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory("ares-recovery-boundary").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun only(directory: File, candidate: File) = object : File(directory.path) {
        override fun listFiles(filter: FileFilter): Array<File> = if (filter.accept(candidate)) arrayOf(candidate) else emptyArray()
    }

    @Test fun `quarantine never replaces a target created after name selection`() = withDirectory { directory ->
        val active = File(directory, "ares_log_race.csv.active").apply { writeText("abandoned"); setLastModified(1) }
        val target = File(directory, "ares_log_race.csv.10000.abandoned")
        val candidate = object : File(active.path) {
            var pathReads = 0
            override fun toPath(): Path {
                if (++pathReads == 2) target.writeText("other recovery evidence")
                return super.toPath()
            }
        }
        val result = LogStorageGovernance.quarantineStaleActiveFiles(only(directory, candidate), 10_000, 1)
        assertEquals("other recovery evidence", target.readText())
        assertEquals(0, result.quarantinedFiles)
        assertEquals("abandoned", active.readText())
    }

    @Test fun `timestamp comparison handles both signed subtraction boundaries`() = withDirectory { directory ->
        val active = File(directory, "ares_log_future.csv.active").apply { writeText("future") }
        val candidate = object : File(active.path) { override fun lastModified() = Long.MAX_VALUE }
        assertEquals(LogRecoveryResult(), LogStorageGovernance.quarantineStaleActiveFiles(only(directory, candidate), Long.MIN_VALUE, 1))
        assertTrue(active.exists())
        val ancient = object : File(active.path) { override fun lastModified() = Long.MIN_VALUE }
        assertEquals(LogRecoveryResult(1, 6), LogStorageGovernance.quarantineStaleActiveFiles(only(directory, ancient), Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun `uppercase active suffix is removed from quarantine name`() = withDirectory { directory ->
        val active = File(directory, "ares_log_case.csv.ACTIVE").apply { writeText("partial"); setLastModified(1) }
        assertEquals(LogRecoveryResult(1, 7), LogStorageGovernance.quarantineStaleActiveFiles(directory, 10_000, 1))
        assertTrue(File(directory, "ares_log_case.csv.10000.abandoned").exists())
        assertFalse(active.exists())
    }

    @Test fun `stale threshold is inclusive and existing quarantine evidence survives`() = withDirectory { directory ->
        val active = File(directory, "ares_log_age.csv.active").apply { writeText("partial"); setLastModified(10_000) }
        val existing = File(directory, "ares_log_age.csv.11000.abandoned").apply { writeText("existing") }
        assertEquals(LogRecoveryResult(), LogStorageGovernance.quarantineStaleActiveFiles(directory, 10_999, 1_000))
        assertTrue(active.exists())
        assertEquals(LogRecoveryResult(1, 7), LogStorageGovernance.quarantineStaleActiveFiles(directory, 11_000, 1_000))
        assertEquals("existing", existing.readText())
        assertEquals("partial", File(directory, "ares_log_age.csv.11000-1.abandoned").readText())
    }

    @Test fun `live writer lock prevents quarantine regardless of timestamp`() = withDirectory { directory ->
        val active = File(directory, "ares_log_locked.csv.active").apply { writeText("live"); setLastModified(1) }
        FileChannel.open(active.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertEquals(LogRecoveryResult(), LogStorageGovernance.quarantineStaleActiveFiles(directory, 10_000, 1))
                assertTrue(active.exists())
            }
        }
    }
}
