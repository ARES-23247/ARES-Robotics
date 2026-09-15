package com.ares.analytics.service.project.persistence

import com.ares.analytics.service.forceDirectoryIfSupported
import com.ares.analytics.service.publishPreparedFileExclusively
import com.ares.analytics.service.resolveExistingPath
import com.ares.analytics.service.writeAndForceFile
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun requireProjectRoot(projectPath: String): File {
    require(projectPath.isNotBlank()) { "Choose a project directory" }
    val root = File(projectPath).canonicalFile
    require(root.isDirectory) { "Project directory does not exist: ${root.path}" }
    return root
}

/** Resolves through existing symlinks and rejects any target that escapes the chosen repository. */
internal fun resolveProjectPath(projectPath: String, relativePath: String): File =
    ProjectPathOwnership(projectPath).resolve(relativePath)

/** One operation's root identity; every target, including listed leaf files, is checked separately. */
internal class ProjectPathOwnership(projectPath: String) {
    val root: File = requireProjectRoot(projectPath)
    private val realRoot = root.toPath().toRealPath()

    // Preserve the logical path returned to callers, which use it for project-relative change lists.
    fun resolve(relativePath: String): File = check(File(root, relativePath).canonicalFile)

    fun check(file: File): File {
        require(file.canonicalFile.toPath().startsWith(root.toPath()) &&
            resolveExistingPath(file.toPath()).startsWith(realRoot)) {
            "Project document path escapes the selected repository"
        }
        return file
    }
}

/** Serializes revision allocation and current-file replacement for each canonical document. */
internal object ProjectDocumentWriteLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<java.nio.file.Path, Any>()

    fun <T> withLock(file: File, block: () -> T): T {
        val lock = locks.computeIfAbsent(resolveExistingPath(file.toPath())) { Any() }
        return synchronized(lock) { block() }
    }
}

internal object AtomicProjectFileWriter {
    fun write(file: File, content: String, replaceExisting: Boolean) {
        write(file, content.toByteArray(Charsets.UTF_8), replaceExisting)
    }

    /** Writes recovery evidence byte-for-byte so even malformed text remains recoverable. */
    fun write(file: File, content: ByteArray, replaceExisting: Boolean) {
        file.parentFile.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".${file.name}.", ".tmp")
        try {
            writeAndForceFile(temporary, content, createNew = false)
            if (replaceExisting) {
                try {
                    Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                forceDirectoryIfSupported(file.parentFile.toPath())
            } else {
                publishPreparedFileExclusively(temporary, file.toPath(), { content })
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
