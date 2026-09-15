package com.ares.analytics.service

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Publishes prepared immutable bytes without replacing an existing name. The caller owns cleanup. */
internal fun publishPreparedFileExclusively(
    prepared: Path,
    destination: Path,
    fallbackBytes: () -> ByteArray = { Files.readAllBytes(prepared) },
    publishLink: (Path, Path) -> Unit = { target, source -> Files.createLink(target, source); Unit },
) {
    val linkFailure = try {
        publishLink(destination, prepared)
        null
    } catch (existing: FileAlreadyExistsException) {
        throw existing
    } catch (unsupported: UnsupportedOperationException) {
        unsupported
    } catch (failure: IOException) {
        failure
    }
    if (linkFailure != null) {
        // CREATE_NEW arbitrates the name atomically on providers without hard links. Visibility
        // is not atomic: an interrupted fallback may leave a partial file, never a replaced one.
        try {
            writeAndForceFile(destination, fallbackBytes(), createNew = true)
        } catch (failure: Exception) {
            failure.addSuppressed(linkFailure)
            throw failure
        }
    }
    forceDirectoryIfSupported(destination.parent)
}

internal fun writeAndForceFile(path: Path, bytes: ByteArray, createNew: Boolean) {
    val creation = if (createNew) StandardOpenOption.CREATE_NEW else StandardOpenOption.TRUNCATE_EXISTING
    FileChannel.open(path, StandardOpenOption.WRITE, creation).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}

internal fun forceDirectoryIfSupported(directory: Path) {
    runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
}
