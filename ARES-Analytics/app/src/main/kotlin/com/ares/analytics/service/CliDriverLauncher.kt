package com.ares.analytics.service

import java.io.File
import java.nio.file.Files

object CliDriverLauncher {
    fun launch(projectPath: String, target: String) {
        val normalizedTarget = target.trim().ifBlank { "127.0.0.1" }
        require(normalizedTarget.matches(Regex("[A-Za-z0-9._:-]{1,253}"))) { "Enter a valid IP address or host name." }
        val root = File(projectPath).canonicalFile
        require(root.isDirectory) { "Select a robot project before launching the CLI driver." }
        val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
        require(File(root, if (windows) "gradlew.bat" else "gradlew").isFile) {
            "The selected project does not contain a Gradle wrapper."
        }
        configureAndroidSdk(root)
        val initScript = Files.createTempFile("ares-cli-driver-", ".gradle").toFile().apply {
            writeText(gradleInitScript(normalizedTarget))
            deleteOnExit()
        }
        when {
            windows -> launchWindows(root, initScript)
            System.getProperty("os.name").contains("mac", ignoreCase = true) -> launchMac(root, initScript)
            else -> launchLinux(root, initScript)
        }
    }

    private fun launchWindows(root: File, initScript: File) {
        val command = "cd /d ${windowsQuote(root.path)} && gradlew.bat -I ${windowsQuote(initScript.path)} " +
            ":simulator:runAresCliDriver --console=plain"
        ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", command).start()
    }

    private fun launchMac(root: File, initScript: File) {
        val script = Files.createTempFile("ares-cli-driver-", ".command").toFile().apply {
            writeText(unixScript(root, initScript))
            setExecutable(true, true)
            deleteOnExit()
        }
        ProcessBuilder("/usr/bin/open", "-a", "Terminal", script.path).start()
    }

    private fun launchLinux(root: File, initScript: File) {
        val shellCommand = unixScript(root, initScript) + "\nexec \"${'$'}SHELL\"\n"
        val candidates = listOf(
            listOf("x-terminal-emulator", "-e", "bash", "-lc", shellCommand),
            listOf("gnome-terminal", "--", "bash", "-lc", shellCommand),
            listOf("konsole", "-e", "bash", "-lc", shellCommand),
            listOf("xterm", "-e", "bash", "-lc", shellCommand),
        )
        val command = candidates.firstOrNull { executableOnPath(it.first()) }
            ?: error("No supported terminal was found. Install x-terminal-emulator, GNOME Terminal, Konsole, or xterm.")
        ProcessBuilder(command).start()
    }

    internal fun unixScript(root: File, initScript: File): String = buildString {
        appendLine("#!/bin/bash")
        ManagedToolchainPaths.resolveJavaHome()?.let {
            appendLine("export JAVA_HOME=${shellQuote(it.invariantSeparatorsPath)}")
        }
        appendLine("cd ${shellQuote(root.invariantSeparatorsPath)} || exit 1")
        appendLine(
            "exec ./gradlew -I ${shellQuote(initScript.invariantSeparatorsPath)} " +
                ":simulator:runAresCliDriver --console=plain",
        )
    }

    internal fun gradleInitScript(target: String): String = """
        gradle.afterProject { project ->
            if (project.path == ':simulator') {
                project.tasks.register('runAresCliDriver', JavaExec) {
                    group = 'application'
                    mainClass.set('com.areslib.sim.infra.FakeControllerClient')
                    classpath = project.sourceSets.main.runtimeClasspath
                    standardInput = System.in
                    args(${groovyQuote(target)})
                }
            }
        }
    """.trimIndent() + "\n"

    private fun configureAndroidSdk(root: File) {
        val localProperties = File(root, "local.properties")
        if (localProperties.exists()) return
        val sdk = ManagedToolchainPaths.resolveAndroidSdk()?.canonicalFile ?: return
        writeFileAtomically(localProperties) { temporary ->
            temporary.writeText("sdk.dir=${sdk.path.replace('\\', '/')}\n")
        }
    }

    private fun executableOnPath(name: String): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar).filter(String::isNotBlank).any { File(it, name).canExecute() }

    internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    private fun groovyQuote(value: String): String = "'${value.replace("\\", "\\\\").replace("'", "\\'")}'"
    private fun windowsQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
