package com.areslib.frc.drivetrain

import com.areslib.telemetry.ITelemetry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the persistence, multi-tier fallback loading hierarchy, timestamped flash backups,
 * and live NetworkTables sync of Swerve Module zero offsets on FRC robots.
 *
 * ### 4-Tier Fallback Loading Hierarchy:
 * 1. **Tier 1 (Runtime Zero):** `/home/lvuser/swerve_offsets_runtime.json` (Latest pit calibration)
 * 2. **Tier 2 (Git Deployed Base):** `/home/lvuser/deploy/swerve_offsets.json` (Checked into Git, deployed via `./gradlew deploy`)
 * 3. **Tier 3 (Local Backup):** Most recent `/home/lvuser/backups/swerve_offsets_*.json` file
 * 4. **Tier 4 (Hardcoded Code Fallback):** Code defaults supplied from `TunerConstants.java`
 */
object SwerveOffsetManager {

    private val isRoboRio: Boolean
        get() = File("/home/lvuser").exists()

    val rootDir: File
        get() = if (isRoboRio) File("/home/lvuser") else File(".")

    val deployDir: File
        get() = if (isRoboRio) File("/home/lvuser/deploy") else File("./src/main/deploy")

    val backupsDir: File
        get() = File(rootDir, "backups")

    val runtimeFile: File
        get() = File(rootDir, "swerve_offsets_runtime.json")

    val deployFile: File
        get() = File(deployDir, "swerve_offsets.json")

    /**
     * Resolves and loads the active swerve offsets according to the 4-tier fallback hierarchy.
     * Uses flat functional chains to eliminate nested control flow.
     *
     * @param defaultOffsets Baseline offsets from code constants (`TunerConstants`).
     * @return Resolved [SwerveOffsetData] object.
     */
    fun loadOffsets(defaultOffsets: SwerveOffsetData = SwerveOffsetData()): SwerveOffsetData {
        return readOffsetFile(runtimeFile, "Tier 1 runtime")
            ?: readOffsetFile(deployFile, "Tier 2 deployed")
            ?: readLatestBackupFile()
            ?: run {
                println("ARES SwerveOffsetManager: Falling back to Tier 4 hardcoded defaults.")
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

        // 1. Write runtime file
        runCatching {
            rootDir.takeIf { !it.exists() }?.mkdirs()
            runtimeFile.writeText(json)
            println("ARES SwerveOffsetManager: Successfully saved runtime offsets to ${runtimeFile.absolutePath}")
        }.onFailure { e ->
            System.err.println("ARES SwerveOffsetManager: Failed to write runtime file: ${e.message}")
        }

        // 2. Write timestamped backup file
        runCatching {
            backupsDir.takeIf { !it.exists() }?.mkdirs()
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val backupFile = File(backupsDir, "swerve_offsets_$timestamp.json")
            backupFile.writeText(json)
            println("ARES SwerveOffsetManager: Saved backup to ${backupFile.absolutePath}")

            pruneOldBackups()
        }.onFailure { e ->
            System.err.println("ARES SwerveOffsetManager: Failed to create backup file: ${e.message}")
        }

        // 3. Broadcast to Telemetry / NetworkTables
        telemetry?.let { t ->
            t.putString("ARES/Swerve/OffsetsJSON", json)
            t.putNumber("ARES/Swerve/Offsets/FrontLeft", offsets.frontLeft)
            t.putNumber("ARES/Swerve/Offsets/FrontRight", offsets.frontRight)
            t.putNumber("ARES/Swerve/Offsets/BackLeft", offsets.backLeft)
            t.putNumber("ARES/Swerve/Offsets/BackRight", offsets.backRight)
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
