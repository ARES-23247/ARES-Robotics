package com.ares.analytics.service

import com.ares.analytics.shared.AppJsonPretty
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class AutoImportArchiveOps(
    private val logParserService: LogParserService,
) {
    private val importedFingerprintCaches = ConcurrentHashMap<String, MutableSet<String>>()

    fun contentFingerprint(stableFile: File): String {
        return Sha256.fileHex(stableFile)
    }

    fun contentFingerprint(stableFiles: List<File>): String {
        require(stableFiles.isNotEmpty()) { "At least one stable file is required" }
        if (stableFiles.size == 1) return contentFingerprint(stableFiles.single())

        return Sha256.compositeHex {
            val buffer = ByteArray(CONTENT_HASH_BUFFER_BYTES)
            for (stableFile in stableFiles) {
                update(stableFile.length().toString().toByteArray(Charsets.US_ASCII))
                update(0.toByte())
                stableFile.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        update(buffer, 0, count)
                    }
                }
            }
        }
    }

    fun matchingDsEvents(dslog: File): File? {
        if (!dslog.name.endsWith(".dslog", ignoreCase = true)) return null
        val baseName = dslog.name.substringBeforeLast('.')
        return dslog.parentFile?.listFiles()?.firstOrNull { candidate ->
            candidate.isFile &&
                candidate.name.endsWith(".dsevents", ignoreCase = true) &&
                candidate.name.substringBeforeLast('.').equals(baseName, ignoreCase = true)
        } ?: File(dslog.parentFile, "$baseName.dsevents")
    }

    fun archivedDsEvents(dslog: File): File =
        File(dslog.parentFile, dslog.name.substringBeforeLast('.') + ".dsevents")

    fun safeArchiveFile(directory: File, fingerprint: String, sourceName: String): File {
        val basename = sourceName.substringAfterLast('/').substringAfterLast('\\').trim()
        require(basename.isNotEmpty() && basename != "." && basename != "..") {
            "Invalid log filename"
        }
        val sanitized = buildString(basename.length) {
            basename.forEach { character ->
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == '.' || character == '_' || character == '-' || character == ' ' -> character
                        else -> '_'
                    }
                )
            }
        }.trim().take(MAX_ARCHIVE_BASENAME_LENGTH)
        require(sanitized.isNotEmpty() && isSupportedLog(sanitized)) { "Unsupported log filename" }

        val root = directory.toPath().toAbsolutePath().normalize()
        val target = root.resolve("${fingerprint.take(12)}_$sanitized").normalize()
        require(target.parent == root && target.startsWith(root)) { "Log archive path escaped its root" }
        return target.toFile()
    }

    private fun importedFingerprints(manifest: File): MutableSet<String> {
        return importedFingerprintCaches.computeIfAbsent(manifest.absolutePath) {
            ConcurrentHashMap.newKeySet<String>().apply {
                if (manifest.exists()) {
                    manifest.useLines { lines ->
                        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                    }
                }
            }
        }
    }

    fun isFingerprintImported(manifest: File, fingerprint: String): Boolean {
        return fingerprint in importedFingerprints(manifest)
    }

    fun markFingerprintImported(manifest: File, fingerprint: String) {
        val fingerprints = importedFingerprints(manifest)
        if (!fingerprints.add(fingerprint)) return
        manifest.parentFile?.mkdirs()
        try {
            FileOutputStream(manifest, true).use { output ->
                output.write((fingerprint + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
        } catch (e: Exception) {
            fingerprints.remove(fingerprint)
            throw e
        }
    }

    fun quarantineFailedImport(
        config: WorkspaceConfig,
        archivedFile: File,
        fingerprint: String,
        failure: Throwable,
        sourceName: String = archivedFile.name,
    ): File {
        val quarantineDir = File(config.projectPath, "logs/quarantine")
        quarantineDir.mkdirs()
        val quarantinedFile = File(quarantineDir, archivedFile.name)
        Files.move(archivedFile.toPath(), quarantinedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val archivedEvents = matchingDsEvents(archivedFile)
        if (archivedEvents?.isFile == true) {
            Files.move(
                archivedEvents.toPath(),
                archivedDsEvents(quarantinedFile).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val report = logParserService.buildRejectedImportReport(quarantinedFile, failure)
            .copy(sourceName = sourceName)
        writeImportReport(quarantinedFile, report)
        markFingerprintImported(File(quarantineDir, QUARANTINE_MANIFEST_NAME), fingerprint)
        return quarantinedFile
    }

    fun writeImportReport(logFile: File, report: ImportReport): File {
        val reportFile = File(logFile.parentFile, logFile.name + IMPORT_REPORT_SUFFIX)
        val temporaryFile = File(reportFile.parentFile, ".${reportFile.name}.tmp")
        temporaryFile.writeText(AppJsonPretty.encodeToString(report))
        try {
            Files.move(
                temporaryFile.toPath(),
                reportFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryFile.toPath(), reportFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return reportFile
    }

    fun quarantineManifest(config: WorkspaceConfig): File =
        File(config.projectPath, "logs/quarantine/$QUARANTINE_MANIFEST_NAME")

    fun isSupportedLog(name: String): Boolean {
        val lower = name.lowercase()
        return SUPPORTED_EXTENSIONS.any(lower::endsWith)
    }

    /** Only ImportArchiveService's direct-child retry files may bypass quarantine dedup once. */
    fun isExplicitRetrySource(config: WorkspaceConfig, file: File): Boolean {
        val logsRoot = File(config.projectPath, "logs").toPath().toAbsolutePath().normalize()
        val candidate = file.toPath().toAbsolutePath().normalize()
        return candidate.parent == logsRoot && file.name.startsWith("retry_") && isSupportedLog(file.name)
    }

    fun copyStableLocalFile(source: File, destination: File, expected: AutoImportService.SourceSnapshot) {
        destination.parentFile?.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val afterCopy = AutoImportService.SourceSnapshot(source.length(), source.lastModified())
        if (afterCopy != expected || destination.length() != expected.size) {
            destination.delete()
            throw java.io.IOException("Log changed while it was being copied")
        }
    }

    companion object {
        const val IMPORT_MANIFEST_NAME = ".auto-import-index"
        const val QUARANTINE_MANIFEST_NAME = ".auto-import-quarantine-index"
        const val IMPORT_REPORT_SUFFIX = ".import-report.json"
        const val MAX_ARCHIVE_BASENAME_LENGTH = 160
        private const val CONTENT_HASH_BUFFER_BYTES = 64 * 1024
        val SUPPORTED_EXTENSIONS = setOf(
            ".wpilog", ".wpilogxz", ".jsonl", ".csv.gz", ".csv", ".parquet", ".hoot",
            ".dslog", ".rlog", ".revlog", ".log",
        )
    }
}
