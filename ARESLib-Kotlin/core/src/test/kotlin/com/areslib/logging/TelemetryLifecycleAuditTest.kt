package com.areslib.logging

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TelemetryLifecycleAuditTest {
    @TempDir lateinit var directory: File
    private val originalMode = RobotStatusTracker.activeOpMode
    @AfterEach fun restoreGlobals() {
        RobotStatusTracker.activeOpMode = originalMode
        RobotClock.useSystemTime()
    }
    private fun telemetry(backend: ITelemetry? = null): DataLoggingTelemetry {
        RobotStatusTracker.activeOpMode = "Init"
        return DataLoggingTelemetry(backend, LoggingPolicy.forProfile(LoggingProfile.SIMULATION)
            .copy(compress=false, minFreeSpaceBytes=0), directory)
    }

    @Test fun `first frame at zero is emitted and rewind does not freeze logging`() {
        RobotClock.useMockTime(0)
        val telemetry = telemetry()
        try {
            telemetry.update()
            assertEquals(1L, telemetry.loggingMetrics().acceptedFrames)
            RobotClock.useMockTime(100)
            telemetry.update()
            RobotClock.useMockTime(50)
            telemetry.update()
            assertEquals(3L, telemetry.loggingMetrics().acceptedFrames)
        } finally { telemetry.close() }
    }

    @Test fun `overflowed throttle elapsed time emits a new frame`() {
        RobotClock.useMockTime(-1000)
        val telemetry = telemetry()
        try {
            telemetry.minLogIntervalMs = 0
            telemetry.update()
            val before = telemetry.loggingMetrics().acceptedFrames
            assertEquals(1L,before)
            RobotClock.useMockTime(Long.MAX_VALUE)
            telemetry.update()
            assertEquals(before+1, telemetry.loggingMetrics().acceptedFrames)
        } finally { telemetry.close() }
    }

    @Test fun `closed telemetry rejects writes and mode changes without reopening a file`() {
        val telemetry = telemetry()
        telemetry.close()
        val files = directory.listFiles().orEmpty().map { it.name }.toSet()
        try {
            assertFailsWith<IllegalStateException> { telemetry.putNumber("closed",1.0) }
            assertFailsWith<IllegalStateException> { telemetry.putBoolean("closed",true) }
            assertFailsWith<IllegalStateException> { telemetry.putString("closed","text") }
            assertFailsWith<IllegalStateException> { telemetry.putDoubleArray("closed",doubleArrayOf(1.0)) }
            RobotStatusTracker.activeOpMode = "TeleOp"
            assertFailsWith<IllegalStateException> { telemetry.update() }
            assertEquals(files, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally { telemetry.close() }
    }

    @Test fun `repeated close does not close the live backend twice`() {
        var closes = 0
        val backend = object : ITelemetry {
            override fun putNumber(key: String,value: Double) = Unit
            override fun putBoolean(key: String,value: Boolean) = Unit
            override fun putString(key: String,value: String) = Unit
            override fun putDoubleArray(key: String,value: DoubleArray) = Unit
            override fun getNumber(key: String,defaultValue: Double) = defaultValue
            override fun getBoolean(key: String,defaultValue: Boolean) = defaultValue
            override fun getString(key: String,defaultValue: String) = defaultValue
            override fun close() { closes++ }
        }
        val telemetry = telemetry(backend)
        telemetry.close()
        telemetry.close()
        assertEquals(1, closes)
    }

    @Test fun `mode change returns while the old CSV writer is blocked and preserves mode attribution`() {
        RobotClock.useMockTime(1000)
        val telemetry = telemetry()
        telemetry.minLogIntervalMs = 0
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = DataLoggingTelemetry::class.java.getDeclaredField("logger").apply { isAccessible = true }
        val logger = field.get(telemetry) as ARESDataLogger
        logger.beforeWriteForTest = { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
        val failure = AtomicReference<Throwable?>()
        val transition = Thread({
            try {
                RobotStatusTracker.activeOpMode = "TeleOp"
                telemetry.putNumber("Value",2.0)
                telemetry.update()
            } catch(error: Throwable) { failure.set(error) }
        },"audit-owned-telemetry-transition")
        try {
            telemetry.putNumber("Value",1.0)
            telemetry.update()
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            transition.start()
            transition.join(1000)
            assertFalse(transition.isAlive,"Mode transitions must not wait for disk drainage")
        } finally {
            release.countDown()
            transition.join(3000)
            telemetry.close()
            check(!transition.isAlive)
        }
        failure.get()?.let { throw it }
        val files = directory.listFiles().orEmpty().filter { it.extension=="csv" }
        for ((mode,value) in listOf("Init" to 1.0,"TeleOp" to 2.0)) {
            val lines = files.single { it.name.contains("_$mode") }.readLines()
            val column = lines.first().split(',').indexOf("Value")
            assertEquals(listOf(value),lines.drop(1).map { it.split(',')[column].toDouble() })
        }
    }

    @Test fun `CSV serialization preserves finite doubles across magnitude and rounding boundaries`() {
        val logger = ARESDataLogger("Values",directory,LoggingPolicy.forProfile(LoggingProfile.FORENSIC)
            .copy(compress=false,minFreeSpaceBytes=0))
        val values = listOf(1.99999, -1.99999, -0.0, 1e30, -1e30, Double.MAX_VALUE, Double.MIN_VALUE)
        try {
            values.forEachIndexed { i, value -> logger.logFrame(hashMapOf("TimestampMs" to i,"Value" to value)) }
        } finally { logger.stop() }
        val lines = directory.listFiles().orEmpty().single { it.extension=="csv" }.readLines()
        val valueColumn = lines.first().split(',').indexOf("Value")
        val actual = lines.drop(1).map { it.split(',')[valueColumn].toDouble() }
        assertEquals(values,actual)
    }

    @Test fun `mode boundary flushes throttled fields under their original mode`() {
        RobotClock.useMockTime(1000)
        val telemetry = telemetry()
        telemetry.minLogIntervalMs = 1000
        try {
            telemetry.update()
            RobotClock.useMockTime(1010)
            telemetry.putNumber("OldModeOnly",7.0)
            telemetry.update()
            RobotStatusTracker.activeOpMode = "TeleOp"
            telemetry.putNumber("NewModeOnly",8.0)
            telemetry.update()
        } finally { telemetry.close() }
        val files=directory.listFiles().orEmpty().filter { it.extension=="csv" }
        val old=files.single { it.name.contains("_Init") }.readText()
        val next=files.single { it.name.contains("_TeleOp") }.readText()
        assertTrue(old.contains("OldModeOnly"))
        assertFalse(old.contains("NewModeOnly"))
        assertTrue(next.contains("NewModeOnly"))
        assertFalse(next.contains("OldModeOnly"))
    }

    @Test fun `close flushes the last throttled values once`() {
        RobotClock.useMockTime(1000)
        val telemetry = telemetry()
        telemetry.minLogIntervalMs = 1000
        try {
            telemetry.putNumber("Value",1.0)
            telemetry.update()
            RobotClock.useMockTime(1010)
            telemetry.putNumber("Value",2.0)
            telemetry.update()
            assertEquals(1L,telemetry.loggingMetrics().acceptedFrames)
        } finally { telemetry.close() }
        telemetry.close()
        val lines=directory.listFiles().orEmpty().single { it.extension=="csv" }.readLines()
        val column=lines.first().split(',').indexOf("Value")
        assertEquals(listOf(1.0,2.0),lines.drop(1).map { it.split(',')[column].toDouble() })
    }
}
