package com.areslib.action

import com.areslib.state.Alliance
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionLoggerFailureAuditTest {
    @TempDir lateinit var directory: File

    private class FailingWriter(private val fault: String) : BufferedWriter(StringWriter()) {
        var closeAttempted = false
        override fun write(value: String, offset: Int, length: Int) {
            if (fault == "write") throw IOException("write failed")
            super.write(value, offset, length)
        }
        override fun flush() {
            if (fault == "flush") throw IOException("flush failed")
            super.flush()
        }
        override fun close() {
            closeAttempted = true
            if (fault == "close") throw IllegalStateException("close failed")
            super.close()
        }
    }

    private fun runFault(fault: String, rotate: Boolean = false) {
        val logger = ActionLogger(logDirectory = directory)
        val writer = FailingWriter(fault)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        logger.beforeWriteForTest = {
            logger.beforeWriteForTest = null
            val field = ActionLogger::class.java.getDeclaredField("writer").apply { isAccessible = true }
            (field.get(logger) as BufferedWriter).close()
            field.set(logger, writer)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val stopped = CountDownLatch(1)
        val stopper = Thread({ try { logger.stop() } finally { stopped.countDown() } }, "audit-logger-stop")
        stopper.isDaemon = true
        try {
            logger.logAction(RobotAction.SetAlliance(Alliance.RED, 1L))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            logger.logAction(RobotAction.SetAlliance(Alliance.BLUE, 2L), if (rotate) "Auto" else "Init")
            stopper.start()
            release.countDown()
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "stop must signal completion after $fault failure")
            assertTrue(writer.closeAttempted, "close must be attempted after $fault failure")
            assertEquals(2L, logger.droppedActionCount, "unfinalized records must be accounted for")
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "jsonl" })
            assertTrue(directory.listFiles().orEmpty().any { it.name.endsWith(".jsonl.active") })
        } finally {
            release.countDown()
            // Before-fix close failures strand this latch. Release only this test-owned stopper.
            if (stopper.isAlive) {
                val field = ActionLogger::class.java.getDeclaredField("workerDone").apply { isAccessible = true }
                (field.get(logger) as CountDownLatch).countDown()
            }
            if (stopper.state == Thread.State.NEW) stopper.start()
            stopper.join(5000)
            check(!stopper.isAlive)
            val executorField = ActionLogger::class.java.getDeclaredField("executor").apply { isAccessible = true }
            check((executorField.get(logger) as java.util.concurrent.ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `flush failure still closes and leaves the log incomplete`() = runFault("flush")
    @Test fun `unexpected close failure cannot strand stop or finalize the log`() = runFault("close")
    @Test fun `write failure stops the stream and accounts for queued records`() = runFault("write")
    @Test fun `rotation cannot continue after failure to flush the prior file`() = runFault("flush", rotate = true)

    @Test fun `finalization collision preserves both files and counts uncommitted records`() {
        val logger = ActionLogger(logDirectory = directory)
        val field = ActionLogger::class.java.getDeclaredField("completedLogFile").apply { isAccessible = true }
        val destination = field.get(logger) as File
        try {
            destination.writeText("existing content")
            logger.logAction(RobotAction.SetAlliance(Alliance.RED, 1L))
        } finally { logger.stop() }
        assertEquals("existing content", destination.readText())
        assertEquals(1L, logger.droppedActionCount)
        assertEquals(1, directory.listFiles().orEmpty().count { it.name.endsWith(".active") })
    }

    @Test fun `initialization failure rejects actions and stop remains idempotent`() {
        val invalidDirectory = File(directory, "ordinary-file").also { it.writeText("preserve") }
        val logger = ActionLogger(logDirectory = invalidDirectory)
        logger.logAction(RobotAction.SetAlliance(Alliance.RED, 1L))
        logger.stop()
        logger.stop()
        assertEquals(1L, logger.droppedActionCount)
        assertEquals("preserve", invalidDirectory.readText())
    }

    @Test fun `saturated queue rejects exactly the excess and drains accepted records`() {
        val logger = ActionLogger(logDirectory = directory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        logger.beforeWriteForTest = {
            logger.beforeWriteForTest = null
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            logger.logAction(RobotAction.SetAlliance(Alliance.RED, 0L))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (time in 1L..1002L) logger.logAction(RobotAction.SetAlliance(Alliance.RED, time))
            assertEquals(2L, logger.droppedActionCount)
        } finally {
            release.countDown()
            logger.stop()
        }
        val file = directory.listFiles().orEmpty().single { it.extension == "jsonl" }
        assertEquals((0L..1000L).toList(), ActionReplay.parseActions(file).map { it.timestampMs })
        logger.logAction(RobotAction.SetAlliance(Alliance.RED, 2000L))
        logger.stop()
        assertEquals(3L, logger.droppedActionCount)
    }
}
