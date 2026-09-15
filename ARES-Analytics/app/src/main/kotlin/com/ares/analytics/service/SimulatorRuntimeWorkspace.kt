package com.ares.analytics.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

internal const val SIMULATOR_RUNTIME_ROOT_ENV = "ARES_SIM_RUNTIME_ROOT"

/**
 * Studio owns this unique parent; Gradle owns snapshots inside it. Retain descendant identities
 * before stopping the process tree so cleanup never depends on a killed Gradle finalizer. No log
 * line or project-supplied path grants deletion authority, and file traversal never follows links.
 */
internal class SimulatorRuntimeWorkspace private constructor(val directory: File) {
    private val handles = linkedMapOf<Long, ProcessHandle>()

    fun configureEnvironment(builder: ProcessBuilder) {
        builder.environment()[SIMULATOR_RUNTIME_ROOT_ENV] = directory.absolutePath
        // Older exported runSim tasks do not know ARES_SIM_RUNTIME_ROOT. Inherited JVM options
        // also put their Files.createTempDirectory snapshots beneath this owned parent. Preserve
        // user JVM options; the last temporary-directory property deliberately selects this run.
        val option = "\"-Djava.io.tmpdir=${directory.absolutePath}\""
        builder.environment().merge("JAVA_TOOL_OPTIONS", option) { existing, owned -> "$existing $owned" }
    }

    @Synchronized
    fun retainProcessTree(process: Process) {
        handles[process.pid()] = process.toHandle()
        process.descendants().use { stream -> stream.forEach { handles[it.pid()] = it } }
    }

    @Synchronized
    fun cleanup(): Boolean {
        if (handles.values.any(ProcessHandle::isAlive)) return false
        if (!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return true
    }

    companion object {
        fun create(): SimulatorRuntimeWorkspace = SimulatorRuntimeWorkspace(
            Files.createTempDirectory("ares-studio-sim-").toFile(),
        )
    }
}
