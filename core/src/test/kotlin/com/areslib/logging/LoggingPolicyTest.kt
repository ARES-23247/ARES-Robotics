package com.areslib.logging

import com.areslib.util.RobotClock
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingPolicyTest {

    @Test
    fun `compressed logger produces importable gzip csv`() {
        val directory = kotlin.io.path.createTempDirectory("ares-compressed-log").toFile()
        try {
            val logger = ARESDataLogger(
                mode = "Compressed",
                logDirectory = directory,
                policy = testLoggingPolicy(compress = true)
            )
            repeat(100) { index ->
                logger.logFrame(
                    hashMapOf(
                        "TimestampMs" to index.toLong(),
                        "Drive/Pose_X" to index * 0.01,
                        "RepeatedStatus" to "nominal-nominal-nominal"
                    )
                )
            }
            logger.stop()

            val completed = directory.listFiles { file -> file.name.endsWith(".csv.gz") }.orEmpty().single()
            val text = GZIPInputStream(completed.inputStream()).bufferedReader().use { it.readText() }
            assertTrue(text.startsWith("TimestampMs"))
            assertTrue(text.contains("Drive/Pose_X"))
            assertTrue(text.contains("nominal-nominal-nominal"))
            assertEquals(100L, logger.metricsSnapshot().writtenFrames)
            assertTrue(logger.metricsSnapshot().completedBytes > 0L)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `duration rotation finalizes multiple independently importable files`() {
        val directory = kotlin.io.path.createTempDirectory("ares-log-rotation").toFile()
        RobotClock.useMockTime(10_000L)
        try {
            val logger = ARESDataLogger(
                mode = "Rotation",
                logDirectory = directory,
                policy = testLoggingPolicy(maxFileDurationMs = 1L)
            )
            RobotClock.useMockTime(10_002L)
            logger.logFrame(hashMapOf("TimestampMs" to 10_002L, "Value" to 1.0))
            logger.logFrame(hashMapOf("TimestampMs" to 10_003L, "Value" to 2.0))
            logger.stop()

            val completed = directory.listFiles { file -> file.name.endsWith(".csv") }.orEmpty()
            assertEquals(2, completed.size)
            assertTrue(completed.all { file -> file.readLines().size >= 2 })
            assertEquals(1L, logger.metricsSnapshot().rotations)
        } finally {
            RobotClock.useSystemTime()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retention removes oldest owned logs but never crosses minimum retained count`() {
        val directory = kotlin.io.path.createTempDirectory("ares-log-retention").toFile()
        try {
            val files = (1..4).map { index ->
                File(directory, "ares_log_$index.csv").apply {
                    writeBytes(ByteArray(40) { index.toByte() })
                    setLastModified(index.toLong())
                }
            }
            File(directory, "action_log_keep.jsonl").writeText("keep")
            val policy = testLoggingPolicy(
                maxFileBytes = 50L,
                maxDirectoryBytes = 100L,
                maxCompletedFiles = 3,
                minRetainedFiles = 2
            )

            val result = LogStorageGovernance.enforceRetention(directory, policy)

            assertEquals(2, result.deletedFiles)
            assertEquals(80L, result.deletedBytes)
            assertFalse(files[0].exists())
            assertFalse(files[1].exists())
            assertTrue(files[2].exists())
            assertTrue(files[3].exists())
            assertTrue(File(directory, "action_log_keep.jsonl").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stale active recovery skips locked writer and quarantines abandoned reservation`() {
        val directory = kotlin.io.path.createTempDirectory("ares-log-recovery").toFile()
        RobotClock.useMockTime(50_000L)
        try {
            val policy = testLoggingPolicy(staleActiveAfterMs = 1L)
            val logger = ARESDataLogger("Locked", directory, policy)
            val liveActive = directory.listFiles { file -> file.name.endsWith(".active") }.orEmpty().single()
            liveActive.setLastModified(1L)

            val whileLocked = LogStorageGovernance.quarantineStaleActiveFiles(directory, 50_000L, 1L)
            assertEquals(0, whileLocked.quarantinedFiles)
            assertTrue(liveActive.exists())
            logger.stop()

            val abandoned = File(directory, "ares_log_abandoned.csv.gz.active").apply {
                writeText("incomplete")
                setLastModified(1L)
            }
            val recovered = LogStorageGovernance.quarantineStaleActiveFiles(directory, 50_000L, 1L)
            assertEquals(1, recovered.quarantinedFiles)
            assertFalse(abandoned.exists())
            assertTrue(directory.listFiles { file -> file.name.endsWith(".abandoned") }.orEmpty().isNotEmpty())
        } finally {
            RobotClock.useSystemTime()
            directory.deleteRecursively()
        }
    }
}
