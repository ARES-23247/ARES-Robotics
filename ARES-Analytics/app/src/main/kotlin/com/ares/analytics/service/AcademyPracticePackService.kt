package com.ares.analytics.service

import java.io.File

data class AcademyPracticePackResult(
    val directory: File,
    val files: List<File>,
    val reusedExistingFiles: Boolean,
)

/**
 * Installs two small, deterministic CSV teaching runs into `.ares/academy/practice-runs`.
 * Existing bytes are never replaced. These datasets are synthetic and must not be presented as
 * robot, simulator, or field evidence.
 */
class AcademyPracticePackService(
    private val files: Map<String, String> = BUNDLED_PRACTICE_FILES,
) {
    fun install(projectRoot: File): AcademyPracticePackResult {
        val root = projectRoot.canonicalFile
        require(root.isDirectory) { "Choose an existing ARES robot project first." }
        require(File(root, ".ares").isDirectory) { "The selected folder does not contain an .ares project directory." }
        val destination = File(root, ".ares/academy/practice-runs").canonicalFile
        check(destination.toPath().startsWith(root.toPath())) { "Practice pack destination escaped the selected project." }
        destination.mkdirs()
        check(destination.isDirectory) { "ARES could not create the practice-run folder." }

        var reused = true
        val installed = files.toSortedMap().map { (name, content) ->
            require(name.matches(Regex("[a-z0-9-]+\\.csv|README\\.md"))) { "Invalid practice pack filename" }
            val target = File(destination, name).canonicalFile
            check(target.parentFile == destination) { "Practice file escaped its destination." }
            when {
                target.isFile && target.readText() == content -> Unit
                target.exists() -> error("Practice file ${target.name} already exists with different contents; nothing was overwritten.")
                else -> {
                    reused = false
                    writeFileAtomically(target) { temporary -> temporary.writeText(content) }
                }
            }
            target
        }
        return AcademyPracticePackResult(destination, installed, reused)
    }

    companion object {
        internal val BUNDLED_PRACTICE_FILES = linkedMapOf(
            "baseline-arm-run.csv" to """TimestampMs,Arm/PositionRad,Arm/VelocityRadPerSec,Arm/CurrentAmps,Robot/Enabled,Robot/Mode,Match/Event
0,0.00,0.00,0.8,false,DISABLED,
20,0.03,1.50,2.1,true,AUTO,
40,0.09,3.00,3.2,true,AUTO,
60,0.18,4.50,4.0,true,AUTO,
80,0.28,5.00,4.2,true,AUTO,Arm cycle begins
100,0.37,4.50,3.8,true,AUTO,
120,0.44,3.50,3.1,true,AUTO,
140,0.49,2.50,2.5,true,AUTO,
160,0.52,1.50,1.9,true,AUTO,
180,0.54,1.00,1.5,true,AUTO,
200,0.55,0.50,1.2,true,AUTO,
""",
            "stalled-arm-run.csv" to """TimestampMs,Arm/PositionRad,Arm/VelocityRadPerSec,Arm/CurrentAmps,Robot/Enabled,Robot/Mode,Match/Event
0,0.00,0.00,0.8,false,DISABLED,
20,0.03,1.50,2.1,true,AUTO,
40,0.07,2.00,3.5,true,AUTO,
60,0.08,0.50,7.0,true,AUTO,
80,0.08,0.00,10.5,true,AUTO,Arm cycle begins
100,0.08,0.00,12.0,true,AUTO,
120,0.08,0.00,12.2,true,AUTO,
140,0.08,0.00,12.1,true,AUTO,
160,0.08,0.00,0.9,false,AUTO,
180,0.08,0.00,0.8,false,AUTO,
200,0.08,0.00,0.8,false,AUTO,
""",
            "README.md" to """# ARES Academy synthetic practice runs

These CSV files are deliberately small teaching datasets. They are not physical-robot logs and
not simulator output. Import both files, align them by run start, autonomous start, or the shared
"Arm cycle begins" match event, then compare position, velocity, current, and enabled state. Write
a bounded claim and identify what evidence would still be required on a real robot.
""",
        )
    }
}
