package com.ares.analytics.service

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudArchiveDownloadTest {
    @Test fun `exhausted retries remove a partially written archive`() = runTest {
        lateinit var archive: File
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            withDownloadedCloudArchive("ares-download-test-", { file ->
                archive = file; file.writeText("partial"); attempts++; error("offline")
            }) { error("must not restore") }
        }
        assertEquals(3, attempts)
        assertFalse(archive.exists())
    }
    @Test fun `cancellation during download or backoff cleans without retrying`() = runTest {
        for (backoff in listOf(false, true)) {
            lateinit var archive: File
            var attempts = 0
            val job = launch {
                withDownloadedCloudArchive("ares-download-test-", { file ->
                    archive = file; file.writeText("partial"); attempts++
                    if (backoff) error("offline") else awaitCancellation()
                }) { error("must not restore") }
            }
            runCurrent()
            job.cancelAndJoin()
            assertEquals(1, attempts)
            assertFalse(archive.exists())
        }
    }
    @Test fun `failed restore removes the completed download`() = runTest {
        lateinit var archive: File
        assertFailsWith<IllegalArgumentException> {
            withDownloadedCloudArchive("ares-download-test-", { file -> archive = file; file.writeText("complete") }) {
                throw IllegalArgumentException("checksum mismatch")
            }
        }
        assertFalse(archive.exists())
    }
}
