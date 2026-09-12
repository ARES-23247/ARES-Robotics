package com.ares.analytics.service.hardware

import com.ares.analytics.service.BeforeAtomicReplace
import com.ares.analytics.service.NO_OP_BEFORE_ATOMIC_REPLACE
import com.ares.analytics.service.publishPreparedFileExclusively
import com.ares.analytics.service.resolveExistingPath
import com.ares.analytics.service.writeAndForceFile
import com.ares.analytics.util.Sha256
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class HardwareEvidenceKind(val directoryName: String) { CONFIGURATION("configuration"), PHYSICAL("physical") }

/** Project-owned immutable records. Replacement-oriented persistence is deliberately not used. */
internal class HardwareEvidenceStore(
    projectRoot: Path,
    private val publishLink: (Path, Path) -> Unit = { target, temporary -> Files.createLink(target, temporary); Unit },
) {
    private val root = projectRoot.toRealPath()

    fun files(kind: HardwareEvidenceKind): List<File> = directory(kind).toFile()
        .listFiles { file -> file.extension == "json" && file.isFile }
        .orEmpty()
        .map { owned(it.toPath()).toFile() }

    fun append(
        kind: HardwareEvidenceKind,
        recordedAtEpochMillis: Long,
        encoded: String,
        beforePublish: BeforeAtomicReplace = NO_OP_BEFORE_ATOMIC_REPLACE,
    ): Path {
        val directory = directory(kind)
        Files.createDirectories(directory)
        require(owned(directory) == directory) { "Hardware evidence directory changed during preparation." }
        val target = directory.resolve("$recordedAtEpochMillis-${Sha256.hex(encoded).take(12)}.json")
        owned(target)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
        val temporary = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
        try {
            val bytes = encoded.toByteArray(Charsets.UTF_8)
            writeAndForceFile(temporary, bytes, createNew = false)
            beforePublish(temporary, target)
            require(owned(directory) == directory && owned(target) == target) {
                "Hardware evidence directory changed before publication."
            }
            publishPreparedFileExclusively(temporary, target, { bytes }, publishLink)
            return target
        } finally {
            // Do not follow a directory redirected outside the project during cleanup.
            if (runCatching { owned(temporary) == temporary }.getOrDefault(false)) Files.deleteIfExists(temporary)
        }
    }

    private fun directory(kind: HardwareEvidenceKind): Path = owned(root.resolve(".ares/evidence/hardware/${kind.directoryName}"))

    /** Resolve existing links even when the final directory or record does not exist yet. */
    private fun owned(path: Path): Path {
        val resolved = resolveExistingPath(path)
        require(resolved.startsWith(root)) { "Hardware evidence must stay inside the project; a linked path points outside it." }
        return resolved
    }
}
