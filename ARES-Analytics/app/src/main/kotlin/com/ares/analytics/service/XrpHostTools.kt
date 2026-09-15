package com.ares.analytics.service

import java.util.concurrent.TimeUnit

/** Checks the interpreter used by the exported wrapper; connected-board checks remain deploy-owned. */
internal fun probeXrpHostTools(): RobotToolchainComponent {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val commands = if (windows) listOf(listOf("py", "-3"), listOf("python")) else listOf(listOf("python3"))
    for (command in commands) {
        val process = try {
            ProcessBuilder(command + listOf("-c", "import sys,json,pathlib,subprocess,unittest; print(sys.version.split()[0]); sys.exit(0 if sys.version_info.major == 3 else 1)"))
                .redirectErrorStream(true).start()
        } catch (_: java.io.IOException) { continue }
        try {
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                val version = process.inputStream.bufferedReader().use { it.readText().trim() }
                return RobotToolchainComponent("Python 3 host tools", ToolchainReadiness.READY,
                    "Python $version is available for project generation, tests, and simulation. Board firmware and connectivity are checked separately during deploy preflight.",
                    command.joinToString(" "))
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
        // Windows ares.bat selects py whenever it exists, even if its Python installation is broken.
        if (windows && command.first() == "py") break
    }
    return RobotToolchainComponent("Python 3 host tools", ToolchainReadiness.MANUAL_SETUP_REQUIRED,
        "Install Python 3 and make it available to the XRP project wrapper. Board firmware and connectivity have not been checked.")
}
