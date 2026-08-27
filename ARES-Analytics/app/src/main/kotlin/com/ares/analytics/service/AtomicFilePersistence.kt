package com.ares.analytics.service

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Test seam invoked after a temporary file is durable but before it replaces its destination. */
typealias BeforeAtomicReplace = (temporary: Path, destination: Path) -> Unit

internal val NO_OP_BEFORE_ATOMIC_REPLACE: BeforeAtomicReplace = { _, _ -> }

/**
 * Writes a destination through a checked temporary file in the destination directory.
 *
 * The existing destination is never opened or removed. The completed temporary file is flushed
 * before a single atomic replace, so a producer or replace failure leaves the previous bytes
 * untouched. Atomic-move support is deliberately required; silently falling back to a non-atomic
 * move would violate the persistence contract this helper provides.
 */
internal fun <T> writeFileAtomically(
    destinationFile: File,
    beforeReplace: BeforeAtomicReplace = NO_OP_BEFORE_ATOMIC_REPLACE,
    writeTemporary: (File) -> T,
): T {
    val destination = destinationFile.canonicalFile.toPath()
    val parent = requireNotNull(destination.parent) { "Destination must have a parent directory" }
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
    check(temporary.parent == parent) { "Atomic temporary file escaped the destination directory" }

    try {
        val result = writeTemporary(temporary.toFile())
        check(Files.isRegularFile(temporary)) { "Atomic writer did not produce a regular file" }
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
        beforeReplace(temporary, destination)
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        // Directory fsync is unavailable on some platforms (notably Windows). The file itself
        // has already been force-flushed; retain the strongest supported directory durability.
        runCatching {
            FileChannel.open(parent, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
        return result
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Suspend-friendly counterpart used by paged exports that query between writes. */
internal suspend fun <T> writeFileAtomicallySuspending(
    destinationFile: File,
    beforeReplace: BeforeAtomicReplace = NO_OP_BEFORE_ATOMIC_REPLACE,
    writeTemporary: suspend (File) -> T,
): T {
    val destination = destinationFile.canonicalFile.toPath()
    val parent = requireNotNull(destination.parent) { "Destination must have a parent directory" }
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
    check(temporary.parent == parent) { "Atomic temporary file escaped the destination directory" }

    try {
        val result = writeTemporary(temporary.toFile())
        check(Files.isRegularFile(temporary)) { "Atomic writer did not produce a regular file" }
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
        beforeReplace(temporary, destination)
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        runCatching {
            FileChannel.open(parent, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
        return result
    } finally {
        Files.deleteIfExists(temporary)
    }
}
