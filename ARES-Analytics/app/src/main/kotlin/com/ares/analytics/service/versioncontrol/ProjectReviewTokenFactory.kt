package com.ares.analytics.service.versioncontrol

import com.ares.analytics.util.Sha256
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
        var totalBytes = 0L
        return Sha256.compositeHex {
            changes.forEach { change ->
                updateChange(change)
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
                            update(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    fun restoreToken(localCommit: String, remoteCommit: String, changes: List<ProjectChange>): String =
        Sha256.compositeHex {
            update(localCommit.toByteArray(StandardCharsets.US_ASCII))
            update(remoteCommit.toByteArray(StandardCharsets.US_ASCII))
            changes.forEach { change -> updateChange(change) }
        }

    private fun MessageDigest.updateChange(change: ProjectChange) {
        update(change.kind.name.toByteArray(StandardCharsets.US_ASCII))
        update(0)
        update(change.path.toByteArray(StandardCharsets.UTF_8))
        update(0)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
