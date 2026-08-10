package com.areslib.telemetry.web

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Filesystem helpers used by the robot web server's offline log workflow.
 *
 * This low-level API accepts only a direct child file name beneath [logDir], recognizes `.csv` and
 * `.jsonl` suffixes case-insensitively, and performs canonical containment checks itself. The
 * defense belongs here because these helpers are also callable outside the HTTP route layer.
 */
object LogArchivePackager {
    /** Lists recognized regular entries in deterministic name order, or returns empty if absent. */
    fun listLogFiles(logDir: File): List<String> {
        if (!logDir.exists()) return emptyList()
        val files = logDir.listFiles { file -> file.isFile && hasLogSuffix(file.name) } ?: emptyArray()
        return files.sortedBy { it.name }.map { it.name }
    }

    /** Returns whether [fileName] identifies an existing direct child log beneath [logDir]. */
    fun isValidLogFile(logDir: File, fileName: String): Boolean {
        return resolveLogFile(logDir, fileName) != null
    }

    /** Copies a validated log file to [out] without closing the caller-owned output stream. */
    fun streamLogFile(logDir: File, fileName: String, out: OutputStream) {
        val file = requireNotNull(resolveLogFile(logDir, fileName)) {
            "Invalid or missing log file: $fileName"
        }
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
            }
        }
    }

    /** Returns a validated log file's length, or zero when the name is invalid or missing. */
    fun getFileLength(logDir: File, fileName: String): Long {
        return resolveLogFile(logDir, fileName)?.length() ?: 0L
    }

    /**
     * Moves a validated log into `logDir/synced` using [File.renameTo].
     * Returns `false` when directory creation or the platform rename fails; no copy fallback occurs.
     */
    fun markSynced(logDir: File, fileName: String): Boolean {
        val file = resolveLogFile(logDir, fileName) ?: return false
        val root = logDir.canonicalFile
        val syncedDir = File(root, "synced").canonicalFile
        if (syncedDir.parentFile != root || (!syncedDir.isDirectory && !syncedDir.mkdirs())) return false
        val destFile = File(syncedDir, file.name).canonicalFile
        if (destFile.parentFile != syncedDir || destFile.exists()) return false
        return file.renameTo(destFile)
    }

    private fun resolveLogFile(logDir: File, fileName: String): File? {
        if (fileName.isBlank() || fileName != File(fileName).name || !hasLogSuffix(fileName)) return null
        val root = logDir.canonicalFile
        val candidate = File(root, fileName).canonicalFile
        return candidate.takeIf { it.parentFile == root && it.isFile }
    }

    private fun hasLogSuffix(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".csv") || lowerName.endsWith(".jsonl")
    }
}
