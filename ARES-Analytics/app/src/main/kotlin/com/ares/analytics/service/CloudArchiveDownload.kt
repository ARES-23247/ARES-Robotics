package com.ares.analytics.service

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Owns the archive from allocation through retries, validation and atomic database import. */
internal suspend fun <T> withDownloadedCloudArchive(
    prefix: String,
    download: suspend (File) -> Unit,
    restore: suspend (File) -> T,
): T {
    val archive = File.createTempFile(prefix, ".ares-session.zip")
    try {
        var attempt = 0
        while (true) {
            try {
                download(archive)
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (++attempt >= 3) throw error
                delay(attempt * 1000L)
            }
        }
        return restore(archive)
    } finally {
        archive.delete()
    }
}
