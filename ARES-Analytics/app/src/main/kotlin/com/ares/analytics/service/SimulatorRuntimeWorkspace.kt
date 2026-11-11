package com.ares.analytics.service

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
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
        val rootPath = directory.toPath()
        for (attempt in 0 until CLEANUP_MAX_ATTEMPTS) {
            if (!Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
            try {
                Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                return true
            } catch (locked: FileSystemException) {
                if (attempt == CLEANUP_MAX_ATTEMPTS - 1 || locked !is AccessDeniedException && locked.javaClass != FileSystemException::class.java) {
                    throw locked
                }
                try {
                    Thread.sleep(CLEANUP_RETRY_DELAY_MS * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw locked
                }
            }
        }
        return !Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    companion object {
        private const val CLEANUP_MAX_ATTEMPTS = 3
        private const val CLEANUP_RETRY_DELAY_MS = 50L

        fun create(): SimulatorRuntimeWorkspace = SimulatorRuntimeWorkspace(
            Files.createTempDirectory("ares-studio-sim-").toFile(),
        )
    }
}
