package com.areslib.drivetrain

import com.areslib.telemetry.ITelemetry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Persists a reviewed runtime calibration overlay for canonical typed swerve offsets.
 *
 * Normal startup has exactly two sources: the required canonical offsets supplied by the
 * generated robot configuration, and a valid runtime calibration overlay when one exists.
 * Timestamped backups are retained for explicit operator recovery only; they never silently
 * replace canonical configuration during startup.
 */
object SwerveOffsetManager {

    /** Optional storage root override used by simulators and isolated tests. */
    const val STORAGE_ROOT_PROPERTY = "ares.swerve.offset.storageRoot"

    private val configuredRootDir: File?
        get() = System.getProperty(STORAGE_ROOT_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private val isRoboRio: Boolean
        get() = File("/home/lvuser").exists()

    private val isFtcControlHub: Boolean
        get() = File("/sdcard/FIRST").exists()

    val rootDir: File
        get() {
            val override = configuredRootDir
            return when {
                override != null -> override
                isRoboRio -> File("/home/lvuser")
                isFtcControlHub -> File("/sdcard/FIRST")
                else -> File(".")
            }
        }

    val backupsDir: File
        get() = File(rootDir, "backups")

    val runtimeFile: File
        get() = File(rootDir, "swerve_offsets_runtime.json")

    /**
     * Loads a valid runtime calibration overlay or returns the required canonical offsets.
     * A corrupt overlay is reported and ignored; legacy deploy JSON and backups are not startup
     * configuration sources.
     */
    fun loadOffsets(canonicalOffsets: SwerveOffsetData): SwerveOffsetData =
        readOffsetFile(runtimeFile, "runtime calibration") ?: canonicalOffsets

    /**
     * Reads and parses a specified offset JSON file safely without nested conditional branches.
     */
    private fun readOffsetFile(file: File, tag: String): SwerveOffsetData? {
        return try {
            if (!file.isFile || file.length() == 0L) return null
            // Bound acquisition, not merely parsing after an arbitrarily large readText().
            val json = file.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer, 0,
                        minOf(buffer.size, SwerveOffsetData.MAX_JSON_CHARS - text.length + 1))
                    if (count < 0) break
                    require(text.length + count <= SwerveOffsetData.MAX_JSON_CHARS) {
                        "Swerve offset JSON exceeds ${SwerveOffsetData.MAX_JSON_CHARS} characters"
                    }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val parsed = SwerveOffsetData.fromJsonString(json)
            println("ARES SwerveOffsetManager: Loaded $tag offsets from ${file.absolutePath}")
            parsed
        } catch (e: Exception) {
            System.err.println("ARES SwerveOffsetManager: $tag read failed: ${e.message}")
            null
        }
    }

    /**
     * Returns the newest valid recovery backup by filesystem modification time, skipping
     * invalid files. Equal modification times use filename order as a deterministic tie-break.
     * This is explicit recovery only and never installs the returned offsets.
     */
    fun loadLatestBackup(): SwerveOffsetData? {
        val backupDir = backupsDir.takeIf { it.exists() } ?: return null
        val candidates = backupFiles(backupDir)
            .sortedWith(compareByDescending<Backup> { it.modifiedMs }.thenByDescending { it.file.name })
        for (candidate in candidates) {
            val offsets = readOffsetFile(candidate.file, "recovery backup")
            if (offsets != null) return offsets
        }
        return null
    }

    /**
     * Saves the calibrated offsets to local runtime flash, creates a timestamped backup,
     * and streams telemetry to NetworkTables.
     * Each save has a distinct backup name, even at a fixed replay timestamp. The storage
     * root is captured once per call. Runtime replacement commits before backup/telemetry;
     * a later failure propagates but does not roll back the installed runtime file.
     *
     * @param offsets The newly calibrated [SwerveOffsetData].
     * @param telemetry Telemetry interface for broadcasting NetworkTables JSON updates.
     */
    fun saveRuntimeOffsets(offsets: SwerveOffsetData, telemetry: ITelemetry? = null) {
        val json = offsets.toJsonString()
        val storageRoot = rootDir
        val runtime = File(storageRoot, "swerve_offsets_runtime.json")
        val backupDirectory = File(storageRoot, "backups")

        // 1. Atomically replace the authoritative runtime file. Persistence failure is surfaced;
        // callers must not report a calibration as successful when it was not durably installed.
        atomicWrite(runtime, json)
        println("ARES SwerveOffsetManager: Successfully saved runtime offsets to ${runtime.absolutePath}")

        // 2. Atomically publish a distinct timestamped backup; clock resolution is not identity.
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(Date(com.areslib.util.RobotClock.currentTimeMillis()))
        val backupFile = File(backupDirectory, "swerve_offsets_${timestamp}_${UUID.randomUUID()}.json")
        atomicWrite(backupFile, json)
        println("ARES SwerveOffsetManager: Saved backup to ${backupFile.absolutePath}")
        pruneOldBackups(backupDirectory)

        // 3. Broadcast to Telemetry / NetworkTables
        telemetry?.let { t ->
            t.putString("ARES/Swerve/OffsetsJSON", json)
            t.putNumber("ARES/Swerve/Offsets/FrontLeft", offsets.frontLeft)
            t.putNumber("ARES/Swerve/Offsets/FrontRight", offsets.frontRight)
            t.putNumber("ARES/Swerve/Offsets/BackLeft", offsets.backLeft)
            t.putNumber("ARES/Swerve/Offsets/BackRight", offsets.backRight)
        }
    }

    private fun atomicWrite(target: File, contents: String) {
        val directory = target.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Offset target has no parent directory: $target")
        require(directory.exists() || directory.mkdirs()) { "Unable to create offset directory $directory" }
        val temp = Files.createTempFile(directory.toPath(), ".${target.name}.", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val bytes = StandardCharsets.UTF_8.encode(contents)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Keeps only the 10 most recent backup files to prevent storage congestion.
     */
    private data class Backup(val file: File, val modifiedMs: Long)

    private fun backupFiles(directory: File): List<Backup> = directory.listFiles { file ->
        file.isFile && file.name.startsWith("swerve_offsets_") && file.name.endsWith(".json")
    }?.map { Backup(it, it.lastModified()) }.orEmpty()

    private fun pruneOldBackups(directory: File) {
        val backupFiles = backupFiles(directory)
            .sortedWith(compareBy<Backup> { it.modifiedMs }.thenBy { it.file.name })

        val excess = backupFiles.size - 10
        if (excess > 0) {
            for (i in 0 until excess) {
                Files.deleteIfExists(backupFiles[i].file.toPath())
            }
        }
    }
}
