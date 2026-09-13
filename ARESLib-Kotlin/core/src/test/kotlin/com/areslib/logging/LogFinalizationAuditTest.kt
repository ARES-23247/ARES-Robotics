package com.areslib.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFinalizationAuditTest {
    @TempDir lateinit var directory: File
    private val policy = LoggingPolicy.forProfile(LoggingProfile.FORENSIC)
        .copy(compress = false, minFreeSpaceBytes = 0)

    @Test fun `retention failure cannot strand shutdown after worker exits`() {
        val failListing = AtomicBoolean(false)
        val failureObserved = CountDownLatch(1)
        val faultDirectory = object : File(directory.absolutePath) {
            override fun listFiles(filter: FileFilter): Array<File>? {
                if (failListing.get()) {
                    failureObserved.countDown()
                    throw SecurityException("Injected retention access failure")
                }
                return super.listFiles(filter)
            }
        }
        val property = "ares.logging.retention.enabled"
        val previous = System.getProperty(property)
        System.setProperty(property, "true")
        val logger = try { ARESDataLogger("Finalize", faultDirectory, policy) }
        finally { if (previous == null) System.clearProperty(property) else System.setProperty(property, previous) }
        val stopper = Thread({ logger.stop() }, "audit-owned-logger-stop").apply { isDaemon = true }
        val executor = field(logger, "executor") as ThreadPoolExecutor
        try {
            failListing.set(true)
            stopper.start()
            assertTrue(failureObserved.await(3, TimeUnit.SECONDS), "The final retention path must be reached")
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "The owned worker must exit")
            stopper.join(1000)
            assertFalse(stopper.isAlive, "stop must finish even when final retention fails")
        } finally {
            // Release the broken baseline's waiter after observing the defect; never leak a test thread.
            (field(logger, "workerDone") as CountDownLatch).countDown()
            stopper.join(3000)
            check(!stopper.isAlive)
        }
    }

    @Test fun `flush failure still closes the writer and retains the active reservation`() =
        verifyFailedWriter(flushFails = true)

    @Test fun `close failure never advertises a completed log`() =
        verifyFailedWriter(flushFails = false)

    private fun verifyFailedWriter(flushFails: Boolean) {
        val logger = ARESDataLogger("Finalize", directory, policy)
        val sink = checkNotNull(field(logger, "sink"))
        val writerField = sink.javaClass.getDeclaredField("writer").apply { isAccessible = true }
        val original = writerField.get(sink) as BufferedWriter
        val closeAttempted = AtomicBoolean(false)
        writerField.set(sink, object : BufferedWriter(original) {
            override fun flush() {
                if (flushFails) throw IOException("Injected flush failure")
                original.flush()
            }
            override fun close() {
                closeAttempted.set(true)
                original.close()
                if (!flushFails) throw IOException("Injected close failure")
            }
        })
        try {
            logger.stop()
            assertTrue(closeAttempted.get(), "close must be attempted even when flush fails")
            val names = directory.listFiles().orEmpty().map { it.name }
            assertEquals(1, names.size)
            assertTrue(names.single().endsWith(".active"), "Failed finalization must stay unavailable to importers")
            assertEquals(0L, logger.metricsSnapshot().completedBytes)
        } finally { original.close(); logger.stop() }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
