package com.areslib.hardware.sensor

import com.areslib.telemetry.ITelemetry
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImuTelemetryPublisherTest {
    @Test
    fun `retained publisher copies once and reuses buffer and stable topic identities`() {
        val imu = ProbeImu()
        val publisher = ImuTelemetryPublisher(imu)
        val sink = RecordingTelemetry()
        publisher.publish(sink, "A")
        val firstBuffer = imu.lastBuffer
        val firstKeys = sink.values.map { it.first }
        assertFrame(sink.values, "A", 1.0)
        imu.base = 10.0
        sink.values.clear()
        publisher.publish(sink, String(charArrayOf('A')))
        assertSame(firstBuffer, imu.lastBuffer)
        assertEquals(2, imu.copies)
        assertFrame(sink.values, "A", 10.0)
        firstKeys.indices.forEach { assertSame(firstKeys[it], sink.values[it].first) }
        for (prefix in listOf("B", "A")) {
            sink.values.clear()
            publisher.publish(sink, prefix)
            assertFrame(sink.values, prefix, 10.0)
        }
        assertEquals(4, imu.copies)
    }

    @Test
    fun `failed copy publishes nothing and partial retry cannot leak previous values`() {
        var attempt = 0
        val failure = IllegalStateException("copy failed")
        val imu = object : ImuIO {
            override fun resetHeading() = Unit
            override fun updateInputs(inputs: ImuInputs) {
                assertEquals(ImuInputs(), inputs, "Every copy starts with fresh-buffer defaults")
                attempt++
                inputs.headingRadians = attempt.toDouble()
                if (attempt == 1) {
                    inputs.rollRadians = 99.0
                    inputs.timestampMs = 123L
                    throw failure
                }
            }
        }
        val publisher = ImuTelemetryPublisher(imu)
        val sink = RecordingTelemetry()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { publisher.publish(sink, "IMU") })
        assertTrue(sink.values.isEmpty())
        publisher.publish(sink, "IMU")
        assertEquals(listOf(2.0, 0.0, 0.0, 0.0, 0.0, 0.0), sink.values.map { it.second })
    }

    @Test
    fun `nested telemetry callback preserves each frames values and topic prefix`() {
        val imu = ProbeImu()
        val publisher = ImuTelemetryPublisher(imu)
        val sink = RecordingTelemetry()
        var nested = false
        sink.onNumber = {
            if (!nested) {
                nested = true
                imu.base = 100.0
                publisher.publish(sink, "Nested")
            }
        }
        publisher.publish(sink, "Outer")
        assertFrame(sink.values.filter { it.first.startsWith("Outer/") }, "Outer", 1.0)
        assertFrame(sink.values.filter { it.first.startsWith("Nested/") }, "Nested", 100.0)
        assertEquals(2, imu.copies)
    }

    @Test
    fun `independent publishers have independent storage and convenience default keeps same schema`() {
        val first = ProbeImu()
        val second = ProbeImu().apply { base = 20.0 }
        val sink = RecordingTelemetry()
        ImuTelemetryPublisher(first).publish(sink, "First")
        ImuTelemetryPublisher(second).publish(sink, "Second")
        assertNotSame(first.lastBuffer, second.lastBuffer)
        assertFrame(sink.values.take(6), "First", 1.0)
        assertFrame(sink.values.drop(6), "Second", 20.0)
        sink.values.clear()
        val previous = first.lastBuffer
        first.logTelemetry(sink, "Default")
        assertNotSame(previous, first.lastBuffer)
        assertFrame(sink.values, "Default", 1.0)
    }

    @Test
    fun `sink failure leaves publisher reusable with a different prefix`() {
        val imu = ProbeImu()
        val publisher = ImuTelemetryPublisher(imu)
        val sink = RecordingTelemetry()
        val failure = AssertionError("sink")
        sink.onNumber = { throw failure }
        assertSame(failure, assertThrows(AssertionError::class.java) { publisher.publish(sink, "Before") })
        sink.onNumber = null
        sink.values.clear()
        imu.base = 40.0
        publisher.publish(sink, "After")
        assertFrame(sink.values, "After", 40.0)
    }

    @Test
    fun `warm retained publisher has bounded desktop JVM allocation`() {
        val imu = ProbeImu()
        val publisher = ImuTelemetryPublisher(imu)
        var puts = 0
        var sum = 0.0
        val sink = object : BaseTelemetry() {
            override fun putNumber(key: String, value: Double) { puts++; sum += value }
        }
        repeat(100_000) { publisher.publish(sink, "IMU") }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { publisher.publish(sink, "IMU") }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(110_000, imu.copies)
        assertEquals(660_000, puts)
        assertEquals(2_310_000.0, sum, 0.0)
        println("Core IMU telemetry: $allocated bytes / 10,000 calls (desktop JVM)")
        assertTrue(allocated <= 4096L, "Warm publisher allocated $allocated bytes")
    }

    private fun assertFrame(values: List<Pair<String, Double>>, prefix: String, base: Double) {
        val suffixes = listOf("HeadingRad", "PitchRad", "RollRad", "YawVelocityRadPerSec", "PitchVelocityRadPerSec", "RollVelocityRadPerSec")
        assertEquals(suffixes.map { "$prefix/$it" }, values.map { it.first })
        assertEquals(List(6) { base + it }, values.map { it.second })
    }

    private class ProbeImu : ImuIO {
        var base = 1.0
        var copies = 0
        // Test-only identity observation; production providers must not retain caller storage.
        var lastBuffer: ImuInputs? = null
        override fun updateInputs(inputs: ImuInputs) {
            copies++
            lastBuffer = inputs
            inputs.headingRadians = base
            inputs.pitchRadians = base + 1.0
            inputs.rollRadians = base + 2.0
            inputs.yawVelocityRadPerSec = base + 3.0
            inputs.pitchVelocityRadPerSec = base + 4.0
            inputs.rollVelocityRadPerSec = base + 5.0
            inputs.timestampMs = 1000L
        }
        override fun resetHeading() = Unit
    }

    private class RecordingTelemetry : BaseTelemetry() {
        val values = mutableListOf<Pair<String, Double>>()
        var onNumber: (() -> Unit)? = null
        override fun putNumber(key: String, value: Double) { values.add(key to value); onNumber?.invoke() }
    }

    private abstract class BaseTelemetry : ITelemetry {
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
}
