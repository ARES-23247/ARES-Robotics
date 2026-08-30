package com.ares.analytics.service.project

import com.ares.analytics.shared.models.League
import java.io.File

data class ProjectIdeLaunchResult(
    val launched: Boolean,
    val message: String,
)

internal data class ProjectIdeCommand(
    val label: String,
    val executable: File,
    val arguments: List<String>,
)

/** Opens a standalone robot repository in a league-appropriate IDE without requiring Studio. */
class ProjectIdeLauncher(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: File = File(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val processStarter: (List<String>) -> Unit = { command -> ProcessBuilder(command).start() },
) {
    fun open(projectPath: String, league: League): ProjectIdeLaunchResult {
        val root = File(projectPath).absoluteFile
        if (!root.isDirectory) {
            return ProjectIdeLaunchResult(
                false,
                "The selected robot repository no longer exists at ${root.path}. Choose or create a project, then try again.",
            )
        }
        val failures = mutableListOf<String>()
        for (candidate in preferredIdeCommands(root, league, osName, userHome, environment)) {
            if (!candidate.executable.isFile) continue
            val command = listOf(candidate.executable.absolutePath) + candidate.arguments
            runCatching { processStarter(command) }
                .onSuccess {
                    return ProjectIdeLaunchResult(
                        true,
                        "Opened ${root.name} in ${candidate.label}. Studio can now be closed; Gradle, simulation, verification, and deployment remain available in the repository.",
                    )
                }
                .onFailure { failures += "${candidate.label}: ${it.message ?: "could not start"}" }
        }
        val preferred = if (league == League.FTC) "Android Studio" else "WPILib VS Code or IntelliJ IDEA"
        val detail = failures.firstOrNull()?.let { " Last launch error: $it" }.orEmpty()
        return ProjectIdeLaunchResult(
            false,
            "ARES could not find $preferred. Install it, then open this repository directly: ${root.path}.$detail",
        )
    }
}

internal fun preferredIdeCommands(
    projectRoot: File,
    league: League,
    osName: String,
    userHome: File,
    environment: Map<String, String>,
): List<ProjectIdeCommand> {
    val root = projectRoot.absolutePath
    val localAppData = environment["LOCALAPPDATA"]?.let(::File)
    val programFiles = environment["ProgramFiles"]?.let(::File) ?: File("C:/Program Files")
    val androidStudio = listOfNotNull(
        environment["ARES_ANDROID_STUDIO"]?.let(::File),
        File(programFiles, "Android/Android Studio/bin/studio64.exe"),
        localAppData?.let { File(it, "Programs/Android Studio/bin/studio64.exe") },
    ).map { ProjectIdeCommand("Android Studio", it, listOf(root)) }
    val intellij = listOfNotNull(
        environment["ARES_INTELLIJ_IDEA"]?.let(::File),
        File(programFiles, "JetBrains/IntelliJ IDEA/bin/idea64.exe"),
    ).map { ProjectIdeCommand("IntelliJ IDEA", it, listOf(root)) }
    val vscode = listOfNotNull(
        environment["ARES_VSCODE"]?.let(::File),
        localAppData?.let { File(it, "Programs/Microsoft VS Code/Code.exe") },
    ).map { ProjectIdeCommand("Visual Studio Code", it, listOf(root)) }
    val wpilib = listOf(2027, 2026, 2025).map {
        ProjectIdeCommand("WPILib VS Code", File(userHome, "wpilib/$it/vscode/Code.exe"), listOf(root))
    }

    if (osName.contains("Mac", ignoreCase = true)) {
        val open = File("/usr/bin/open")
        return if (league == League.FTC) {
            listOf(ProjectIdeCommand("Android Studio", open, listOf("-a", "Android Studio", root)))
        } else {
            val wpilibMac = listOf(2027, 2026, 2025).map {
                ProjectIdeCommand(
                    "WPILib VS Code",
                    File(userHome, "wpilib/$it/vscode/Visual Studio Code.app/Contents/MacOS/Electron"),
                    listOf(root),
                )
            }
            wpilibMac + listOf(
                ProjectIdeCommand("WPILib VS Code", open, listOf("-a", "Visual Studio Code", root)),
                ProjectIdeCommand("IntelliJ IDEA", open, listOf("-a", "IntelliJ IDEA", root)),
            )
        }
    }
    if (!osName.contains("Windows", ignoreCase = true)) {
        val linuxAndroid = ProjectIdeCommand("Android Studio", File("/opt/android-studio/bin/studio.sh"), listOf(root))
        val linuxCode = ProjectIdeCommand("Visual Studio Code", File("/usr/bin/code"), listOf(root))
        val linuxIdea = ProjectIdeCommand("IntelliJ IDEA", File("/usr/local/bin/idea"), listOf(root))
        return if (league == League.FTC) listOf(linuxAndroid, linuxCode) else listOf(linuxCode, linuxIdea)
    }
    return if (league == League.FTC) androidStudio + intellij + vscode else wpilib + intellij + vscode
}
