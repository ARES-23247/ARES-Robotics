package com.areslib.drivetrain

import com.areslib.telemetry.ITelemetry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Manages the persistence, multi-tier fallback loading hierarchy, timestamped flash backups,
 * and live NetworkTables sync of Swerve Module zero offsets on FRC and FTC robots.
 *
 * ### 4-Tier Fallback Loading Hierarchy:
 * 1. **Tier 1 (Runtime Zero):** `swerve_offsets_runtime.json` in root directory (Latest pit calibration)
 * 2. **Tier 2 (Git Deployed Base):** `deploy/swerve_offsets.json` or `assets/swerve_offsets.json`
 * 3. **Tier 3 (Local Backup):** Most recent `backups/swerve_offsets_*.json` file
 * 4. **Tier 4 (Hardcoded Code Fallback):** Code defaults supplied from [SwerveConstants]
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

    val deployDir: File
        get() {
            val override = configuredRootDir
            return when {
                override != null -> File(override, "deploy")
                isRoboRio -> File("/home/lvuser/deploy")
                isFtcControlHub -> File("/sdcard/FIRST/deploy")
                else -> File("./src/main/deploy")
            }
        }

    val backupsDir: File
        get() = File(rootDir, "backups")

    val runtimeFile: File
        get() = File(rootDir, "swerve_offsets_runtime.json")

    val deployFile: File
        get() = File(deployDir, "swerve_offsets.json")

    /**
     * Resolves and loads the active swerve offsets according to the fallback hierarchy:
     * 1. Tier 1 (Runtime Zero): Local runtime overlay
     * 2. Tier 2 (Typed Tuning Profile): Canonical schema-declared values from .ares/tuning/
     * 3. Tier 3 (Legacy Deployed JSON): deploy/swerve_offsets.json
     * 4. Tier 4 (Local Backup): Most recent backups/swerve_offsets_*.json
     * 5. Tier 5 (Hardcoded Defaults)
     *
     * @param defaultOffsets Baseline offsets from code constants ([SwerveOffsetData]).
     * @param typedTuningOffsets Optional schema-declared profile offsets from .ares/tuning/.
     * @return Resolved [SwerveOffsetData] object.
     */
    fun loadOffsets(
        defaultOffsets: SwerveOffsetData = SwerveOffsetData(),
        typedTuningOffsets: SwerveOffsetData? = null
    ): SwerveOffsetData {
        return readOffsetFile(runtimeFile, "Tier 1 runtime")
            ?: typedTuningOffsets
            ?: readOffsetFile(deployFile, "Tier 2 deployed")
            ?: readLatestBackupFile()
            ?: run {
                println("ARES SwerveOffsetManager: Falling back to Tier 5 hardcoded defaults.")
                defaultOffsets
            }
    }

    /**
     * Reads and parses a specified offset JSON file safely without nested conditional branches.
     */
    private fun readOffsetFile(file: File, tag: String): SwerveOffsetData? {
        val validFile = file.takeIf { it.exists() && it.length() > 0 } ?: return null
        return runCatching {
            val json = validFile.readText()
            val parsed = SwerveOffsetData.fromJsonString(json)
            println("ARES SwerveOffsetManager: Loaded $tag offsets from ${validFile.absolutePath}")
            parsed
        }.getOrElse { e ->
            System.err.println("ARES SwerveOffsetManager: $tag read failed: ${e.message}")
            null
        }
    }

    /**
     * Locates and loads the most recent timestamped backup file in the backups directory.
     */
    private fun readLatestBackupFile(): SwerveOffsetData? {
        val backupDir = backupsDir.takeIf { it.exists() } ?: return null
        val latestFile = backupDir.listFiles { _, name -> name.startsWith("swerve_offsets_") && name.endsWith(".json") }
            ?.filter { it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
            ?: return null

        return readOffsetFile(latestFile, "Tier 3 backup")
    }

    /**
     * Saves the calibrated offsets to local runtime flash, creates a timestamped backup,
     * and streams telemetry to NetworkTables.
     *
     * @param offsets The newly calibrated [SwerveOffsetData].
     * @param telemetry Telemetry interface for broadcasting NetworkTables JSON updates.
     */
    fun saveRuntimeOffsets(offsets: SwerveOffsetData, telemetry: ITelemetry? = null) {
        val json = offsets.toJsonString()

        // 1. Atomically replace the authoritative runtime file. Persistence failure is surfaced;
        // callers must not report a calibration as successful when it was not durably installed.
        atomicWrite(runtimeFile, json)
        println("ARES SwerveOffsetManager: Successfully saved runtime offsets to ${runtimeFile.absolutePath}")

        // 2. Atomically create/replace the timestamped backup.
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(Date(com.areslib.util.RobotClock.currentTimeMillis()))
        val backupFile = File(backupsDir, "swerve_offsets_$timestamp.json")
        atomicWrite(backupFile, json)
        println("ARES SwerveOffsetManager: Saved backup to ${backupFile.absolutePath}")
        pruneOldBackups()

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
    private fun pruneOldBackups() {
        val backupFiles = backupsDir.listFiles { _, name -> name.startsWith("swerve_offsets_") && name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: return

        val excess = backupFiles.size - 10
        if (excess > 0) {
            for (i in 0 until excess) {
                backupFiles[i].delete()
            }
        }
    }
}
