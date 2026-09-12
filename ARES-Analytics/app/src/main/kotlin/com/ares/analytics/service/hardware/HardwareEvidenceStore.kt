package com.ares.analytics.service.hardware

import com.ares.analytics.service.BeforeAtomicReplace
import com.ares.analytics.service.NO_OP_BEFORE_ATOMIC_REPLACE
import com.ares.analytics.util.Sha256
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
            writeAndForce(temporary, bytes, createNew = false)
            beforePublish(temporary, target)
            require(owned(directory) == directory && owned(target) == target) {
                "Hardware evidence directory changed before publication."
            }
            val linkFailure = try {
                // Creating a hard link publishes complete bytes without ever replacing a name.
                publishLink(target, temporary)
                null
            } catch (existing: FileAlreadyExistsException) {
                throw existing
            } catch (unsupported: UnsupportedOperationException) {
                unsupported
            } catch (failure: IOException) {
                failure
            }
            if (linkFailure != null) {
                // Some project filesystems cannot create hard links. CREATE_NEW still arbitrates
                // writers atomically. Readers may briefly see incomplete JSON and must reject it;
                // a failed write is retained, never replaced or deleted as somebody else's record.
                try {
                    writeAndForce(target, bytes, createNew = true)
                } catch (failure: Exception) {
                    failure.addSuppressed(linkFailure)
                    throw failure
                }
            }
            runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
            return target
        } finally {
            // Do not follow a directory redirected outside the project during cleanup.
            if (runCatching { owned(temporary) == temporary }.getOrDefault(false)) Files.deleteIfExists(temporary)
        }
    }

    private fun writeAndForce(path: Path, bytes: ByteArray, createNew: Boolean) {
        val creation = if (createNew) StandardOpenOption.CREATE_NEW else StandardOpenOption.TRUNCATE_EXISTING
        FileChannel.open(path, StandardOpenOption.WRITE, creation).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun directory(kind: HardwareEvidenceKind): Path = owned(root.resolve(".ares/evidence/hardware/${kind.directoryName}"))

    /** Resolve existing links even when the final directory or record does not exist yet. */
    private fun owned(path: Path): Path {
        var existing = path.toAbsolutePath().normalize()
        val missing = ArrayDeque<Path>()
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.fileName)
            existing = existing.parent ?: throw IOException("Hardware evidence has no existing path root.")
        }
        var resolved = existing.toRealPath()
        for (part in missing) resolved = resolved.resolve(part)
        require(resolved.startsWith(root)) { "Hardware evidence must stay inside the project; a linked path points outside it." }
        return resolved
    }
}
