package com.areslib.telemetry.web

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Filesystem helpers used by the robot web server's offline log workflow.
 *
 * This low-level API accepts a file name relative to [logDir] and recognizes `.csv` and `.jsonl`
 * suffixes. Callers at a network boundary must canonicalize and contain the resolved path before
 * invoking these helpers; these methods do not perform path-traversal validation themselves.
 */
object LogArchivePackager {
    /** Lists recognized regular entries in deterministic name order, or returns empty if absent. */
    fun listLogFiles(logDir: File): List<String> {
        if (!logDir.exists()) return emptyList()
        val files = logDir.listFiles { _, name -> name.endsWith(".csv") || name.endsWith(".jsonl") } ?: emptyArray()
        return files.sortedBy { it.name }.map { it.name }
    }

    /** Checks existence and suffix only; it does not prove that [fileName] stays beneath [logDir]. */
    fun isValidLogFile(logDir: File, fileName: String): Boolean {
        val file = File(logDir, fileName)
        return file.exists() && (file.name.endsWith(".csv") || file.name.endsWith(".jsonl"))
    }

    /** Copies a trusted relative log file to [out] without closing the caller-owned output stream. */
    fun streamLogFile(logDir: File, fileName: String, out: OutputStream) {
        val file = File(logDir, fileName)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
            }
        }
    }

    /** Returns the trusted relative file's length, or zero under normal [File.length] failure rules. */
    fun getFileLength(logDir: File, fileName: String): Long {
        return File(logDir, fileName).length()
    }

    /**
     * Moves a trusted relative log into `logDir/synced` using [File.renameTo].
     * Returns `false` when directory creation or the platform rename fails; no copy fallback occurs.
     */
    fun markSynced(logDir: File, fileName: String): Boolean {
        val file = File(logDir, fileName)
        val syncedDir = File(logDir, "synced")
        if (!syncedDir.exists()) {
            syncedDir.mkdirs()
        }
        val destFile = File(syncedDir, file.name)
        return file.renameTo(destFile)
    }
}
