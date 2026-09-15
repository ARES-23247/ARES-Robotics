package com.areslib.ftc.hardware

import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

class FloodgateIntegrationAuditTest {
    @AfterEach
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `invalid declared parameters reject instead of selecting another model`() {
        val analog = Analog()
        val invalidModels = listOf<() -> FtcFloodgateCurrentSensor>(
            { FtcFloodgateCurrentSensor(analog, maxCurrentAmps = Double.NaN) },
            { FtcFloodgateCurrentSensor(analog, maxCurrentAmps = 0.0) },
            { FtcFloodgateCurrentSensor(analog, filterAlpha = -0.1) },
            { FtcFloodgateCurrentSensor(analog, filterAlpha = 1.1) },
            { FtcFloodgateCurrentSensor(analog, filterAlpha = Double.NaN) },
            { FtcFloodgateCurrentSensor(analog, fuseRatingAmps = -1.0) },
            { FtcFloodgateCurrentSensor(analog, fuseCalibrationMultiple = 1.0) },
            { FtcFloodgateCurrentSensor(analog, fuseCalibrationTripSeconds = 0.0) },
            { FtcFloodgateCurrentSensor(analog, fuseCoolingTimeConstantSeconds = Double.POSITIVE_INFINITY) },
        )
        for (construct in invalidModels) assertFailsWith<IllegalArgumentException> { construct() }
        assertEquals(0, analog.reads)
    }

    @Test
    fun `nonrepresentable current and thermal constants reject at construction`() {
        val analog = Analog()
        assertFailsWith<IllegalArgumentException> { FtcFloodgateCurrentSensor(analog, fuseRatingAmps = 1e200) }
        assertFailsWith<IllegalArgumentException> { FtcFloodgateCurrentSensor(analog, fuseRatingAmps = 1e-200) }
        assertFailsWith<IllegalArgumentException> { FtcFloodgateCurrentSensor(analog, maxCurrentAmps = 1e200) }
        assertFailsWith<IllegalArgumentException> {
            FtcFloodgateCurrentSensor(analog, fuseCalibrationTripSeconds = Double.MAX_VALUE)
        }
    }

    @Test
    fun `invalid overload threshold cannot silently suppress a warning`() {
        val sensor = FtcFloodgateCurrentSensor(Analog(3.3))
        sensor.update()
        for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFailsWith<IllegalArgumentException> { sensor.isOverloadWarning(threshold) }
        }
    }

    @Test
    fun `first valid current seeds the filter without fictitious zero history`() {
        RobotClock.useMockTime(0)
        val analog = Analog(0.825)
        val sensor = FtcFloodgateCurrentSensor(analog)
        sensor.update()
        assertEquals(20.0, sensor.current, 1e-10)
        assertEquals(20.0, sensor.instantaneousCurrent, 1e-10)
        assertEquals(0.0, sensor.totalAmpHours)
    }

    @Test
    fun `first recovered observation does not integrate an unobserved interval`() {
        RobotClock.useMockTime(0)
        val analog = Analog(Double.NaN)
        val sensor = FtcFloodgateCurrentSensor(analog, filterAlpha = 1.0)
        sensor.update()
        analog.voltageValue = 0.825
        RobotClock.useMockTime(1000)
        sensor.update()
        assertEquals(0.0, sensor.totalAmpHours)
        RobotClock.useMockTime(2000)
        sensor.update()
        assertEquals(20.0 / 3600.0, sensor.totalAmpHours, 1e-12)
    }

    @Test
    fun `cooling time constant is independent of update subdivision`() {
        fun cooledPercent(steps: Int): Double {
            RobotClock.useMockTime(0)
            val analog = Analog(1.65)
            val sensor = FtcFloodgateCurrentSensor(analog, filterAlpha = 1.0)
            sensor.update()
            RobotClock.useMockTime(2000)
            sensor.update()
            assertEquals(100.0, sensor.fuseThermalLoadPercent, 1e-10)
            analog.voltageValue = 0.0
            repeat(steps) { index ->
                RobotClock.useMockTime(2000L + (index + 1) * 15_000L / steps)
                sensor.update()
            }
            return sensor.fuseThermalLoadPercent
        }
        val oneStep = cooledPercent(1)
        val fifteenSteps = cooledPercent(15)
        assertEquals(36.787944117144235, oneStep, 1e-10) // 100/e after one 15-second time constant
        assertEquals(oneStep, fifteenSteps, 1e-10)
    }

    @Test
    fun `ordered signed timestamp overflow retains the measured elapsed duration`() {
        RobotClock.useMockTime(Long.MIN_VALUE + 10)
        val sensor = FtcFloodgateCurrentSensor(Analog(0.04125), filterAlpha = 1.0) // 1A
        sensor.update()
        RobotClock.useMockTime(Long.MAX_VALUE - 10)
        sensor.update()
        // Exact integer elapsed milliseconds / 3,600,000, rounded to a representable Double.
        assertEquals(5_124_095_576_030.431, sensor.totalAmpHours, 0.005)
    }

    @Test
    fun `extreme heating then cooling never poisons the thermal percentage`() {
        RobotClock.useMockTime(0)
        val analog = Analog(3.3)
        val sensor = FtcFloodgateCurrentSensor(analog, maxCurrentAmps = 1e154, filterAlpha = 1.0)
        sensor.update()
        RobotClock.useMockTime(2000)
        sensor.update()
        assertEquals(100.0, sensor.fuseThermalLoadPercent)
        analog.voltageValue = 0.0
        RobotClock.useMockTime(17_000)
        sensor.update()
        assertEquals(100.0, sensor.fuseThermalLoadPercent)
        RobotClock.useMockTime(1_000_000_000_000L)
        sensor.update()
        assertEquals(0.0, sensor.fuseThermalLoadPercent)
    }

    @Test
    fun `unknown interval cannot silently cool accumulated thermal history`() {
        RobotClock.useMockTime(0)
        val analog = Analog(1.65)
        val sensor = FtcFloodgateCurrentSensor(analog, filterAlpha = 1.0)
        sensor.update()
        RobotClock.useMockTime(2000)
        sensor.update()
        analog.voltageValue = Double.NaN
        RobotClock.useMockTime(3000)
        sensor.update()
        assertFalse(sensor.isReadingValid)
        analog.voltageValue = 0.0
        RobotClock.useMockTime(17_000)
        sensor.update()
        assertEquals(100.0, sensor.fuseThermalLoadPercent)
    }

    @Test
    fun `charge units and cached getters do not reread analog hardware`() {
        RobotClock.useMockTime(0)
        val analog = Analog(0.04125) // 1A
        val sensor = FtcFloodgateCurrentSensor(analog)
        sensor.update()
        RobotClock.useMockTime(3_600_000)
        sensor.update()
        assertEquals(1.0, sensor.totalAmpHours, 1e-12)
        assertEquals(12.0, sensor.estimatedEnergyWattHours, 1e-12)
        repeat(20) {
            assertEquals(1.0, sensor.current)
            assertEquals(1.0, sensor.instantaneousCurrent)
            sensor.fuseThermalLoadPercent
            sensor.isOverloadWarning()
        }
        assertEquals(2, analog.reads)
    }

    @Test
    fun `invalid ADC values and exceptions preserve filter history without exposing stale current`() {
        RobotClock.useMockTime(0)
        val analog = Analog(0.825)
        val sensor = FtcFloodgateCurrentSensor(analog, filterAlpha = 0.5)
        sensor.update()
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 3.30001)) {
            analog.voltageValue = invalid
            sensor.update()
            assertFalse(sensor.isReadingValid)
            assertEquals(0.0, sensor.current)
            assertEquals(0.0, sensor.instantaneousCurrent)
        }
        analog.failRead = true
        sensor.update()
        assertFalse(sensor.isReadingValid)
        analog.failRead = false
        analog.voltageValue = 1.65
        sensor.update()
        assertEquals(30.0, sensor.current, 1e-10)
        assertEquals(40.0, sensor.instantaneousCurrent, 1e-10)
    }

    @Test
    fun `rewind reanchors integration and tracker reset preserves the valid current sample`() {
        RobotClock.useMockTime(0)
        val analog = Analog(0.825)
        val sensor = FtcFloodgateCurrentSensor(analog, filterAlpha = 1.0)
        sensor.update()
        RobotClock.useMockTime(2000)
        sensor.update()
        RobotClock.useMockTime(1000)
        sensor.update()
        assertEquals(40.0 / 3600.0, sensor.totalAmpHours, 1e-12)
        RobotClock.useMockTime(2000)
        sensor.update()
        assertEquals(60.0 / 3600.0, sensor.totalAmpHours, 1e-12)
        sensor.resetTracker()
        assertEquals(0.0, sensor.totalAmpHours)
        assertEquals(0.0, sensor.fuseThermalLoadPercent)
        assertTrue(sensor.isReadingValid)
        assertEquals(20.0, sensor.current)
        analog.voltageValue = 1.65
        RobotClock.useMockTime(3000)
        sensor.update()
        assertEquals(40.0 / 3600.0, sensor.totalAmpHours, 1e-12)
    }

    @Test
    fun `endpoint filter weights retain seeded semantics`() {
        for (alpha in listOf(0.0, 1.0)) {
            val analog = Analog(0.825)
            val sensor = FtcFloodgateCurrentSensor(analog, filterAlpha = alpha)
            sensor.update()
            analog.voltageValue = 1.65
            sensor.update()
            assertEquals(if (alpha == 0.0) 20.0 else 40.0, sensor.current, 1e-10)
        }
    }

    @Test
    fun `steady sensor sampling and cached conversion allocate nothing`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean, "JVM allocation instrumentation is required")
        val allocation = bean as ThreadMXBean
        assumeTrue(allocation.isThreadAllocatedMemorySupported, "Thread allocation counters are required")
        allocation.isThreadAllocatedMemoryEnabled = true
        RobotClock.useSystemTime()
        val liveStart = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(liveStart - 2000)
        val analog = Analog(1.65)
        val sensor = FtcFloodgateCurrentSensor(analog)
        sensor.update()
        RobotClock.useMockTime(liveStart)
        sensor.update() // Establish thermal history for live-time cooling below the rating.
        analog.voltageValue = 0.5
        RobotClock.useSystemTime() // Clock mode mutations are outside the measured sensor loop.
        repeat(50_000) { sensor.update(); sensor.instantaneousCurrent; sensor.isOverloadWarning() }
        val threadId = Thread.currentThread().id
        var consecutiveZero = 0
        var windows = 0
        while (windows < 10 && consecutiveZero < 2) {
            val before = allocation.getThreadAllocatedBytes(threadId)
            repeat(10_000) { sensor.update(); sensor.instantaneousCurrent; sensor.isOverloadWarning() }
            val bytes = allocation.getThreadAllocatedBytes(threadId) - before
            consecutiveZero = if (bytes == 0L) consecutiveZero + 1 else 0
            windows++
        }
        assertEquals(2, consecutiveZero)
        println("Floodgate sensor: two consecutive zero-allocation 10,000-update windows; windows=$windows")
    }

    private class Analog(var voltageValue: Double = 0.0) : AnalogVoltageInput {
        var reads = 0
        var failRead = false
        override val voltage: Double get() { reads++; if (failRead) error("ADC unavailable"); return voltageValue }
    }
}
