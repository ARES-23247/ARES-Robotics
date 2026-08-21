package com.areslib.logging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertEquals

class ARESDataLoggerTest {

    @Test
    fun `two loggers created in the same millisecond retain distinct completed files`() {
        val directory = kotlin.io.path.createTempDirectory("ares-data-logger-collision").toFile()
        com.areslib.util.RobotClock.useMockTime(1_234_567L)
        try {
            val first = ARESDataLogger("Teleop/Unsafe Name", directory, testLoggingPolicy())
            val second = ARESDataLogger("Teleop/Unsafe Name", directory, testLoggingPolicy())
            first.logFrame(hashMapOf("TimestampMs" to 1L, "Logger" to "first"))
            second.logFrame(hashMapOf("TimestampMs" to 2L, "Logger" to "second"))

            first.stop()
            second.stop()

            val completed = directory.listFiles { file -> file.extension == "csv" }.orEmpty()
            assertEquals(2, completed.size)
            assertEquals(2, completed.map(File::getName).distinct().size)
            assertTrue(completed.all { it.name.contains("Teleop_Unsafe_Name") })
            val contents = completed.map(File::readText)
            assertTrue(contents.any { it.contains("first") })
            assertTrue(contents.any { it.contains("second") })
            assertTrue(directory.listFiles { file -> file.name.endsWith(".active") }.orEmpty().isEmpty())
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `log becomes importable only after clean shutdown`() {
        val mode = "ActiveMarker_${System.nanoTime()}"
        val logger = ARESDataLogger(mode, policy = testLoggingPolicy())
        val logsDir = File("./logs/")

        assertEquals(
            1,
            logsDir.listFiles { _, name -> name.endsWith("_${mode}.csv.active") }?.size ?: 0
        )
        assertEquals(
            0,
            logsDir.listFiles { _, name -> name.endsWith("_${mode}.csv") }?.size ?: 0
        )

        logger.logFrame(hashMapOf("TimestampMs" to 1L, "Value" to 1.0))
        logger.stop()

        assertEquals(
            0,
            logsDir.listFiles { _, name -> name.endsWith("_${mode}.csv.active") }?.size ?: 0
        )
        val completed = logsDir.listFiles { _, name -> name.endsWith("_${mode}.csv") }.orEmpty()
        assertEquals(1, completed.size)
        completed.single().delete()
    }

    @Test
    fun csvEscapesValuesAndPreservesLateFieldsInStableSchema() {
        val mode = "CsvSafety_${System.nanoTime()}"
        val logger = ARESDataLogger(mode, policy = testLoggingPolicy())

        logger.logFrame(hashMapOf(
            "TimestampMs" to 1L,
            "Status" to "ready, \"quoted\""
        ))
        logger.logFrame(hashMapOf(
            "TimestampMs" to 2L,
            "Status" to "second",
            "Late,Key" to "late, \"value\""
        ))
        logger.stop()

        val logFile = File("./logs/").listFiles { _, name ->
            name.endsWith("_${mode}.csv")
        }!!.single()
        val lines = logFile.readLines()
        assertEquals(3, lines.size)

        val header = parseCsvLine(lines[0])
        val firstRow = parseCsvLine(lines[1])
        val secondRow = parseCsvLine(lines[2])
        assertEquals(header.size, firstRow.size)
        assertEquals(header.size, secondRow.size)
        assertEquals("ready, \"quoted\"", firstRow[header.indexOf("Status")])

        val extras = secondRow[header.indexOf(ARESDataLogger.EXTRA_FIELDS_COLUMN)]
        assertTrue(extras.contains("\"Late,Key\""))
        assertTrue(extras.contains("late, \\\"value\\\""))
        logFile.delete()
    }

    @Test
    fun stopDrainsAcceptedFramesAndRejectionsAreCounted() {
        val mode = "Drain_${System.nanoTime()}"
        val logger = ARESDataLogger(mode, policy = testLoggingPolicy())
        for (i in 0 until 200) {
            logger.logFrame(hashMapOf("TimestampMs" to i.toLong(), "Value" to i))
        }

        val droppedWhileRunning = logger.droppedFrameCount
        logger.stop()
        val rejected = logger.obtainMap().apply { this["Value"] = -1 }
        logger.logFrame(rejected)
        assertEquals(droppedWhileRunning + 1L, logger.droppedFrameCount)

        val logFile = File("./logs/").listFiles { _, name ->
            name.endsWith("_${mode}.csv")
        }!!.single()
        assertEquals(201 - droppedWhileRunning.toInt(), logFile.readLines().size)
        logFile.delete()
    }

    @Test
    fun stopRacingProducerAccountsForEveryFrame() {
        val mode = "StopRace_${System.nanoTime()}"
        val logger = ARESDataLogger(mode, policy = testLoggingPolicy())
        val attempted = 500
        val attemptsMade = java.util.concurrent.atomic.AtomicInteger(0)
        val producer = Thread {
            for (i in 0 until attempted) {
                logger.logFrame(hashMapOf("TimestampMs" to i.toLong(), "Value" to i))
                attemptsMade.incrementAndGet()
            }
        }
        producer.start()
        while (attemptsMade.get() < 10) Thread.yield()

        logger.stop()
        producer.join()

        val logFile = File("./logs/").listFiles { _, name ->
            name.endsWith("_${mode}.csv")
        }!!.single()
        val lineCount = logFile.readLines().size
        val loggedFrames = if (lineCount == 0) 0 else lineCount - 1
        assertEquals(attempted.toLong(), loggedFrames.toLong() + logger.droppedFrameCount)
        logFile.delete()
    }

    @Test
    fun testAsyncCSVLogging() {
        val telemetry = DataLoggingTelemetry(loggingPolicy = testLoggingPolicy())
        
        // Log 3 mock frames
        for (i in 1..3) {
            telemetry.putNumber("Test/Value", i.toDouble())
            telemetry.putBoolean("Test/State", i % 2 == 0)
            telemetry.update()
            Thread.sleep(50) // Simulate loop time
        }

        // Close to flush all frames and wait for file IO completion
        telemetry.close()

        // Verify logs directory and contents
        val logsDir = File("./logs/")
        assertTrue(logsDir.exists(), "Logs directory should be created")

        val logFiles = logsDir.listFiles { _, name -> name.startsWith("ares_log_") && name.endsWith(".csv") }
        assertTrue(logFiles != null && logFiles.isNotEmpty(), "At least one log file should be generated")

        // Read the latest log file
        val latestLog = logFiles.maxByOrNull { it.lastModified() }!!
        val lines = latestLog.readLines()

        assertTrue(lines.size >= 4, "Log should contain header + at least 3 data rows")
        
        // Confirm headers contain our fields
        val header = lines[0]
        assertTrue(header.contains("TimestampMs"), "Header must include timestamp")
        assertTrue(header.contains("Test/Value"), "Header must include Test/Value")
        assertTrue(header.contains("Test/State"), "Header must include Test/State")

        // Cleanup the test log file so we don't litter
        latestLog.delete()
    }

    @Test
    fun testMapPoolingAndZeroAllocations() {
        val logger = ARESDataLogger(policy = testLoggingPolicy())
        
        // 1. Exhaust the pre-populated pool of 16 maps to test behavior when empty
        val exhaustedMaps = mutableListOf<HashMap<String, Any>>()
        for (i in 0 until 16) {
            exhaustedMaps.add(logger.obtainMap())
        }

        // 2. Obtain a map when the pool is empty - this allocates a new map
        val map1 = logger.obtainMap()
        assertTrue(map1.isEmpty(), "Obtained map should be clean and empty")
        
        // 3. Put some dummy data and recycle it
        map1["Key1"] = 1.0
        logger.recycleMap(map1)
        assertTrue(map1.isEmpty(), "Recycled map must be cleared upon recycling")

        // 4. Obtain a map again - should return the exact same instance because it is the only one in the pool now
        val map2 = logger.obtainMap()
        assertSame(map1, map2, "The pool must return the recycled map instance to achieve zero allocations")

        // 5. Recycle everything to cleanup and ensure proper behavior
        exhaustedMaps.forEach { logger.recycleMap(it) }
        logger.recycleMap(map2)
        
        logger.stop()
    }

    @Test
    fun testDataLoggingThrottle() {
        com.areslib.util.RobotClock.useMockTime(1000L)
        try {
            val telemetry = DataLoggingTelemetry(loggingPolicy = testLoggingPolicy())
            telemetry.minLogIntervalMs = 50L // 50ms interval

            // Log 5 times in rapid succession (dt = 0ms)
            for (i in 1..5) {
                telemetry.putNumber("Test/Throttle", i.toDouble())
                telemetry.update()
            }

            // Move clock forward by 60ms to exceed the interval and log once more
            com.areslib.util.RobotClock.useMockTime(1060L)
            telemetry.putNumber("Test/Throttle", 100.0)
            telemetry.update()

            // Close to flush
            telemetry.close()

            val logsDir = File("./logs/")
            assertTrue(logsDir.exists(), "Logs directory should exist")

            val logFiles = logsDir.listFiles { _, name -> name.startsWith("ares_log_") && name.endsWith(".csv") }
            assertTrue(logFiles != null && logFiles.isNotEmpty())

            val latestLog = logFiles.maxByOrNull { it.lastModified() }!!
            val lines = latestLog.readLines()

            // We expect:
            // Line 0: Header
            // Line 1: First frame (written at t = 1000ms)
            // Line 2: Second frame (written after mock time elapsed by 60ms)
            // Total lines should be exactly 3
            assertEquals(3, lines.size, "Logging throttle should restrict output to exactly 2 frames")

            // Cleanup
            latestLog.delete()
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    value.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    fields.add(value.toString())
                    value.setLength(0)
                }
                else -> value.append(c)
            }
            i++
        }
        fields.add(value.toString())
        return fields
    }
}

internal fun testLoggingPolicy(
    compress: Boolean = false,
    maxFileBytes: Long = 64L * 1024L * 1024L,
    maxFileDurationMs: Long = 60L * 60L * 1_000L,
    maxDirectoryBytes: Long = Long.MAX_VALUE / 4L,
    maxCompletedFiles: Int = Int.MAX_VALUE,
    minRetainedFiles: Int = 0,
    staleActiveAfterMs: Long = 24L * 60L * 60L * 1_000L
): LoggingPolicy = LoggingPolicy(
    profile = LoggingProfile.FORENSIC,
    minFrameIntervalMs = 0L,
    compress = compress,
    maxFileBytes = maxFileBytes,
    maxFileDurationMs = maxFileDurationMs,
    maxDirectoryBytes = maxDirectoryBytes,
    minFreeSpaceBytes = 0L,
    maxCompletedFiles = maxCompletedFiles,
    minRetainedFiles = minRetainedFiles,
    staleActiveAfterMs = staleActiveAfterMs
)
