package com.ares.analytics.service.versioncontrol

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ProjectVersionControlLimits {
    const val MAX_REVIEWED_FILE_BYTES = 20L * 1024L * 1024L
    const val MAX_REVIEWED_CHANGE_BYTES = 100L * 1024L * 1024L
    const val MAX_RESTORED_PROJECT_BYTES = 500L * 1024L * 1024L
}

/** Creates domain-separated confirmation tokens for reviewed project changes and restores. */
internal class ProjectReviewTokenFactory {
    fun workingTreeToken(root: File, changes: List<ProjectChange>): String {
        val canonicalRoot = root.canonicalFile
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        changes.forEach { change ->
            digest.updateChange(change)
            val file = File(canonicalRoot, change.path).canonicalFile
            require(file.toPath().startsWith(canonicalRoot.toPath())) { "A changed path escaped the project." }
            if (file.isFile) {
                require(file.length() <= ProjectVersionControlLimits.MAX_REVIEWED_FILE_BYTES) {
                    "${change.path} is too large for reviewed project backup."
                }
                totalBytes = Math.addExact(totalBytes, file.length())
                require(totalBytes <= ProjectVersionControlLimits.MAX_REVIEWED_CHANGE_BYTES) {
                    "The pending change set is too large for one reviewed backup."
                }
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return digest.finishHex()
    }

    fun restoreToken(localCommit: String, remoteCommit: String, changes: List<ProjectChange>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(localCommit.toByteArray(StandardCharsets.US_ASCII))
        digest.update(remoteCommit.toByteArray(StandardCharsets.US_ASCII))
        changes.forEach { change -> digest.updateChange(change) }
        return digest.finishHex()
    }

    private fun MessageDigest.updateChange(change: ProjectChange) {
        update(change.kind.name.toByteArray(StandardCharsets.US_ASCII))
        update(0)
        update(change.path.toByteArray(StandardCharsets.UTF_8))
        update(0)
    }

    private fun MessageDigest.finishHex(): String = digest().joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
