package com.areslib.logging

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Operational logging modes with deliberately different fidelity and storage budgets. */
internal enum class LoggingProfile {
    /** Match use: retain every 20 ms control sample while compressing and bounding storage. */
    COMPETITION,

    /** Desktop simulation: 20 Hz durable samples are sufficient for routine development. */
    SIMULATION,

    /** Explicit incident capture: retain every submitted frame at the cost of higher disk usage. */
    FORENSIC;

    companion object {
        /** Parses a configured profile name without silently accepting misspellings. */
        @JvmStatic
        fun parse(value: String): LoggingProfile = entries.firstOrNull {
            it.name.equals(value.trim(), ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Unknown ARES logging profile '$value'; expected ${entries.joinToString { it.name }}"
        )
    }
}

/**
 * Immutable resource policy for one telemetry logger.
 *
 * File and directory limits apply only to completed `ares_log_*` telemetry files owned by
 * [ARESDataLogger]. Action logs and synced/downloaded artifacts have separate ownership.
 */
internal data class LoggingPolicy(
    val profile: LoggingProfile,
    val minFrameIntervalMs: Long,
    val compress: Boolean,
    val maxFileBytes: Long,
    val maxFileDurationMs: Long,
    val maxDirectoryBytes: Long,
    val minFreeSpaceBytes: Long,
    val maxCompletedFiles: Int,
    val minRetainedFiles: Int,
    val staleActiveAfterMs: Long
) {
    init {
        require(minFrameIntervalMs >= 0L) { "Minimum log interval cannot be negative" }
        require(maxFileBytes > 0L) { "Maximum log file size must be positive" }
        require(maxFileDurationMs > 0L) { "Maximum log duration must be positive" }
        require(maxDirectoryBytes >= maxFileBytes) {
            "Directory budget must be at least one maximum-size log file"
        }
        require(minFreeSpaceBytes >= 0L) { "Minimum free-space reserve cannot be negative" }
        require(maxCompletedFiles > 0) { "Maximum completed-file count must be positive" }
        require(minRetainedFiles in 0..maxCompletedFiles) {
            "Minimum retained-file count must fit within the completed-file limit"
        }
        require(staleActiveAfterMs > 0L) { "Stale active-file age must be positive" }
    }

    companion object {
        private const val MIB = 1024L * 1024L

        /** Returns conservative cross-platform defaults for [profile]. */
        @JvmStatic
        fun forProfile(profile: LoggingProfile): LoggingPolicy = when (profile) {
            LoggingProfile.COMPETITION -> LoggingPolicy(
                profile = profile,
                minFrameIntervalMs = 20L,
                compress = true,
                maxFileBytes = 64L * MIB,
                maxFileDurationMs = 30L * 60L * 1_000L,
                maxDirectoryBytes = 1_024L * MIB,
                minFreeSpaceBytes = 256L * MIB,
                maxCompletedFiles = 200,
                minRetainedFiles = 20,
                staleActiveAfterMs = 12L * 60L * 60L * 1_000L
            )

            LoggingProfile.SIMULATION -> LoggingPolicy(
                profile = profile,
                minFrameIntervalMs = 50L,
                compress = true,
                maxFileBytes = 64L * MIB,
                maxFileDurationMs = 15L * 60L * 1_000L,
                maxDirectoryBytes = 2_048L * MIB,
                minFreeSpaceBytes = 1_024L * MIB,
                maxCompletedFiles = 200,
                minRetainedFiles = 10,
                staleActiveAfterMs = 12L * 60L * 60L * 1_000L
            )

            LoggingProfile.FORENSIC -> LoggingPolicy(
                profile = profile,
                minFrameIntervalMs = 0L,
                compress = true,
                maxFileBytes = 128L * MIB,
                maxFileDurationMs = 15L * 60L * 1_000L,
                maxDirectoryBytes = 4_096L * MIB,
                minFreeSpaceBytes = 1_024L * MIB,
                maxCompletedFiles = 300,
                minRetainedFiles = 10,
                staleActiveAfterMs = 12L * 60L * 60L * 1_000L
            )
        }
    }
}

/** Result of one bounded retention pass. */
internal data class LogRetentionResult(
    val deletedFiles: Int = 0,
    val deletedBytes: Long = 0L,
    val remainingFiles: Int = 0,
    val remainingBytes: Long = 0L
)

/** Result of quarantining abandoned writer reservations. */
internal data class LogRecoveryResult(
    val quarantinedFiles: Int = 0,
    val quarantinedBytes: Long = 0L
)

/** Filesystem governance shared by logger startup, rotation, and tests. */
internal object LogStorageGovernance {
    private const val ACTIVE_SUFFIX = ".active"
    private const val ABANDONED_SUFFIX = ".abandoned"

    /**
     * Moves old, unlocked `ares_log_*.active` files out of the importable namespace.
     *
     * Every current logger holds an operating-system file lock for its active reservation. A lock
     * that cannot be acquired is therefore treated as live, even when the file timestamp is old.
     */
    @JvmStatic
    fun quarantineStaleActiveFiles(
        directory: File,
        nowMs: Long,
        staleAfterMs: Long
    ): LogRecoveryResult {
        require(staleAfterMs > 0L) { "Stale active-file age must be positive" }
        if (!directory.isDirectory) return LogRecoveryResult()

        var quarantinedFiles = 0
        var quarantinedBytes = 0L
        val candidates = directory.listFiles { file ->
            file.isFile && file.name.startsWith("ares_log_") &&
                file.name.endsWith(ACTIVE_SUFFIX, ignoreCase = true) &&
                nowMs - file.lastModified() >= staleAfterMs
        }.orEmpty()

        for (candidate in candidates) {
            val reclaimable = try {
                FileChannel.open(candidate.toPath(), StandardOpenOption.WRITE).use { channel ->
                    val lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock == null) false else {
                        lock.release()
                        true
                    }
                }
            } catch (_: Exception) {
                false
            }
            if (!reclaimable) continue

            val bytes = candidate.length()
            val quarantine = uniqueQuarantineTarget(candidate, nowMs)
            try {
                Files.move(candidate.toPath(), quarantine.toPath(), StandardCopyOption.ATOMIC_MOVE)
                quarantinedFiles++
                quarantinedBytes += bytes
            } catch (_: Exception) {
                try {
                    Files.move(candidate.toPath(), quarantine.toPath())
                    quarantinedFiles++
                    quarantinedBytes += bytes
                } catch (_: Exception) {
                    // A concurrent writer or filesystem policy won the race; leave the file intact.
                }
            }
        }
        return LogRecoveryResult(quarantinedFiles, quarantinedBytes)
    }

    /** Deletes oldest completed ARES telemetry logs until every configured budget is satisfied. */
    @JvmStatic
    fun enforceRetention(directory: File, policy: LoggingPolicy): LogRetentionResult {
        if (!directory.isDirectory) return LogRetentionResult()
        val files = completedTelemetryFiles(directory).sortedBy(File::lastModified).toMutableList()
        var totalBytes = files.sumOf(File::length)
        var deletedFiles = 0
        var deletedBytes = 0L
        var estimatedUsableSpace = directory.usableSpace

        fun overBudget(): Boolean = files.size > policy.maxCompletedFiles ||
            totalBytes > policy.maxDirectoryBytes ||
            (policy.minFreeSpaceBytes > 0L && estimatedUsableSpace < policy.minFreeSpaceBytes)

        while (files.size > policy.minRetainedFiles && overBudget()) {
            val oldest = files.removeAt(0)
            val bytes = oldest.length()
            if (oldest.delete()) {
                deletedFiles++
                deletedBytes += bytes
                totalBytes -= bytes
                estimatedUsableSpace += bytes
            }
        }
        return LogRetentionResult(deletedFiles, deletedBytes, files.size, totalBytes)
    }

    /** Completed files owned by [ARESDataLogger], excluding active and abandoned reservations. */
    @JvmStatic
    fun completedTelemetryFiles(directory: File): List<File> = directory.listFiles { file ->
        file.isFile && file.name.startsWith("ares_log_") &&
            (file.name.endsWith(".csv", ignoreCase = true) ||
                file.name.endsWith(".csv.gz", ignoreCase = true))
    }.orEmpty().toList()

    private fun uniqueQuarantineTarget(active: File, nowMs: Long): File {
        val base = active.name.removeSuffix(ACTIVE_SUFFIX)
        var target = File(active.parentFile, "$base.$nowMs$ABANDONED_SUFFIX")
        var suffix = 1
        while (target.exists()) {
            target = File(active.parentFile, "$base.$nowMs-$suffix$ABANDONED_SUFFIX")
            suffix++
        }
        return target
    }
}
