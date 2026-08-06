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
     *
     * @param defaultOffsets Baseline offsets from code constants (`TunerConstants`).
     * @return Resolved [SwerveOffsetData] object.
     */
    fun loadOffsets(defaultOffsets: SwerveOffsetData = SwerveOffsetData()): SwerveOffsetData {
        // Tier 1: Runtime Pit Calibration File
        if (runtimeFile.exists() && runtimeFile.length() > 0) {
            try {
                val json = runtimeFile.readText()
                val parsed = SwerveOffsetData.fromJsonString(json)
                println("ARES SwerveOffsetManager: Loaded Tier 1 runtime offsets from ${runtimeFile.absolutePath}")
                return parsed
            } catch (e: Exception) {
                System.err.println("ARES SwerveOffsetManager: Tier 1 runtime read failed: ${e.message}")
            }
        }

        // Tier 2: Deployed Base Config File (Version-controlled in Git)
        if (deployFile.exists() && deployFile.length() > 0) {
            try {
                val json = deployFile.readText()
                val parsed = SwerveOffsetData.fromJsonString(json)
                println("ARES SwerveOffsetManager: Loaded Tier 2 deployed offsets from ${deployFile.absolutePath}")
                return parsed
            } catch (e: Exception) {
                System.err.println("ARES SwerveOffsetManager: Tier 2 deploy read failed: ${e.message}")
            }
        }

        // Tier 3: Most Recent Local Backup File
        if (backupsDir.exists()) {
            val backupFiles = backupsDir.listFiles { _, name -> name.startsWith("swerve_offsets_") && name.endsWith(".json") }
            if (backupFiles != null && backupFiles.isNotEmpty()) {
                val latestBackup = backupFiles.maxByOrNull { it.lastModified() }
                if (latestBackup != null && latestBackup.length() > 0) {
                    try {
                        val json = latestBackup.readText()
                        val parsed = SwerveOffsetData.fromJsonString(json)
                        println("ARES SwerveOffsetManager: Loaded Tier 3 backup offsets from ${latestBackup.absolutePath}")
                        return parsed
                    } catch (e: Exception) {
                        System.err.println("ARES SwerveOffsetManager: Tier 3 backup read failed: ${e.message}")
                    }
                }
            }
        }

        // Tier 4: Code Defaults
        println("ARES SwerveOffsetManager: Falling back to Tier 4 hardcoded defaults.")
        return defaultOffsets
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
        try {
            if (!rootDir.exists()) rootDir.mkdirs()
            runtimeFile.writeText(json)
            println("ARES SwerveOffsetManager: Successfully saved runtime offsets to ${runtimeFile.absolutePath}")
        } catch (e: Exception) {
            System.err.println("ARES SwerveOffsetManager: Failed to write runtime file: ${e.message}")
        }

        // 2. Write timestamped backup file
        try {
            if (!backupsDir.exists()) backupsDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val backupFile = File(backupsDir, "swerve_offsets_$timestamp.json")
            backupFile.writeText(json)
            println("ARES SwerveOffsetManager: Saved backup to ${backupFile.absolutePath}")

            // Clean up old backups if count exceeds 10
            val backupFiles = backupsDir.listFiles { _, name -> name.startsWith("swerve_offsets_") && name.endsWith(".json") }
            if (backupFiles != null && backupFiles.size > 10) {
                val sorted = backupFiles.sortedBy { it.lastModified() }
                for (i in 0 until (sorted.size - 10)) {
                    sorted[i].delete()
                }
            }
        } catch (e: Exception) {
            System.err.println("ARES SwerveOffsetManager: Failed to create backup file: ${e.message}")
        }

        // 3. Broadcast to Telemetry / NetworkTables
        telemetry?.putString("ARES/Swerve/OffsetsJSON", json)
        telemetry?.putNumber("ARES/Swerve/Offsets/FrontLeft", offsets.frontLeft)
        telemetry?.putNumber("ARES/Swerve/Offsets/FrontRight", offsets.frontRight)
        telemetry?.putNumber("ARES/Swerve/Offsets/BackLeft", offsets.backLeft)
        telemetry?.putNumber("ARES/Swerve/Offsets/BackRight", offsets.backRight)
    }
}
