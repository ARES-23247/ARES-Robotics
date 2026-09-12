package com.areslib.hardware

import com.areslib.hardware.actuator.*
import com.areslib.hardware.sensor.*
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreIoDefaultBoundaryAuditTest {
    @Test
    fun `normal intake shutdown and sampled current checks have bounded allocation`() {
        var writes = 0
        var reads = 0
        var voltageSum = 0.0
        var validSamples = 0
        val io = object : IntakeIO {
            override val pivotCurrentAmps: Double get() { reads++; return 3.0 }
            override val rollerCurrentAmps: Double get() { reads++; return 4.0 }
            override val rollerCurrentValid = true
            override fun setPivotAngle(degrees: Double, maxEffortScale: Double) = Unit
            override fun setRollerVelocityRps(rps: Double) = Unit
            override fun setPivotVoltage(volts: Double) { writes++; voltageSum += volts }
            override fun setRollerVoltage(volts: Double) { writes++; voltageSum += volts }
        }
        repeat(100_000) { io.safe(); if (io.isCurrentReadingValid(io.currentAmps)) validSamples++ }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { io.safe(); if (io.isCurrentReadingValid(io.currentAmps)) validSamples++ }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(220_000, writes)
        assertEquals(220_000, reads)
        assertEquals(110_000, validSamples)
        assertEquals(0.0, voltageSum, 0.0)
        println("Intake shutdown/current: $allocated bytes / 10,000 calls (desktop JVM)")
        assertTrue(allocated <= 4096L, "Normal intake path allocated $allocated bytes")
    }

    @Test
    fun `intake attempts roller stop after pivot exception`() {
        val io = IntakeProbe()
        val failure = IllegalStateException("pivot")
        io.pivotFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { io.safe() })
        assertEquals(listOf("pivot:0.0", "roller:0.0"), io.writes)
    }

    @Test
    fun `intake preserves serious primary failure and distinct secondary diagnostics`() {
        val io = IntakeProbe()
        val primary = AssertionError("pivot")
        val secondary = LinkageError("roller")
        io.pivotFailure = primary
        io.rollerFailure = secondary
        repeat(3) { assertSame(primary, assertThrows(AssertionError::class.java) { io.safe() }) }
        assertEquals(6, io.writes.size)
        assertEquals(listOf(secondary), primary.suppressed.toList())
        io.rollerFailure = primary
        assertSame(primary, assertThrows(AssertionError::class.java) { io.safe() })
        assertEquals(listOf(secondary), primary.suppressed.toList())
    }

    @Test
    fun `roller failure is propagated after successful pivot neutralization`() {
        val io = IntakeProbe()
        val failure = IllegalArgumentException("roller")
        io.rollerFailure = failure
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { io.safe() })
        assertEquals(listOf("pivot:0.0", "roller:0.0"), io.writes)
    }

    @Test
    fun `current validation consumes the sampled aggregate without rereading component currents`() {
        val io = IntakeProbe()
        val reading = io.currentAmps
        assertEquals(7.0, reading, 0.0)
        io.failOnCurrentRead = true
        assertTrue(io.isCurrentReadingValid(reading))
        assertEquals(1, io.pivotReads)
        assertEquals(1, io.rollerReads)
        io.currentFresh = false
        assertFalse(io.isCurrentReadingValid(reading), "Freshness must still gate the sampled value")
    }

    @Test
    fun `invalid component currents cannot masquerade as a valid clipped aggregate`() {
        for (bad in doubleArrayOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (pivot in listOf(true, false)) {
                val io = IntakeProbe()
                if (pivot) io.pivotReading = bad else io.rollerReading = bad
                val reading = io.currentAmps
                assertTrue(reading.isNaN(), "Invalid constituent must be encoded in the sampled aggregate")
                assertFalse(io.isCurrentReadingValid(reading))
                assertEquals(1, io.pivotReads)
                assertEquals(1, io.rollerReads)
            }
        }
        val io = IntakeProbe()
        io.pivotReading = Double.MAX_VALUE
        io.rollerReading = Double.MAX_VALUE
        assertFalse(io.isCurrentReadingValid(io.currentAmps))
        for (bad in doubleArrayOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(io.isCurrentReadingValid(bad))
        }
    }

    @Test
    fun `unknown season feedback stays invalid and safety writes neutral voltage`() {
        val voltages = mutableListOf<Double>()
        val flywheel = object : FlywheelIO {
            override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) = Unit
            override fun setAppliedVoltage(volts: Double) { voltages.add(volts) }
        }
        val cowl = object : CowlIO {
            override fun setTargetAngle(rotations: Double, maxEffortScale: Double) = Unit
            override fun setAppliedVoltage(volts: Double) { voltages.add(volts) }
        }
        val feeder = object : FeederIO {
            override fun setAppliedVoltage(volts: Double) { voltages.add(volts) }
        }
        val climber = object : ClimberIO {
            override fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) = Unit
            override fun setAppliedVoltage(volts: Double) { voltages.add(volts) }
        }
        val floor = object : FloorIO {
            override fun setAppliedVoltage(volts: Double) { voltages.add(volts) }
        }
        val devices: List<SubsystemIO> = listOf(flywheel, cowl, feeder, climber, floor)
        for (device in devices) {
            device.safe()
            assertFalse((device as CurrentSourceIO).currentReadingValid)
        }
        assertEquals(List(5) { 0.0 }, voltages)
        assertFalse(flywheel.velocityValid)
        assertFalse(cowl.angleValid)
        assertFalse(feeder.pieceDetectionValid)
        assertFalse(climber.positionValid)
    }

    @Test
    fun `color copy uses one source snapshot and preserves caller ownership and tail`() {
        val values = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
        var reads = 0
        val io = object : ColorSensorIO {
            override val red = 1; override val green = 2; override val blue = 3; override val alpha = 4
            override val normalizedRgb: DoubleArray get() { reads++; return values.copyOf() }
        }
        val destination = DoubleArray(6) { 9.0 }
        io.copyNormalizedRgbInto(destination)
        assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3, 0.4, 9.0, 9.0), destination)
        destination[0] = 7.0
        assertEquals(0.1, values[0], 0.0)
        assertEquals(1, reads)
        assertThrows(IllegalArgumentException::class.java) { io.copyNormalizedRgbInto(DoubleArray(3)) }
        assertEquals(1, reads, "Invalid destination must fail before fetching a frame")
    }

    @Test
    fun `multizone copy reports copied count across empty short and oversized destinations`() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        var reads = 0
        val io = object : MultizoneDistanceSensorIO {
            override val rows = 2; override val columns = 2
            override val distancesMeters: DoubleArray get() { reads++; return values.copyOf() }
        }
        for (size in intArrayOf(0, 2, 4, 6)) {
            val destination = DoubleArray(size) { 9.0 }
            val count = io.copyDistancesMetersInto(destination)
            assertEquals(minOf(size, 4), count)
            for (i in 0 until count) assertEquals(values[i], destination[i], 0.0)
            for (i in count until size) assertEquals(9.0, destination[i], 0.0)
            if (size > 0) destination[0] = 8.0
        }
        assertEquals(4, reads)
        assertEquals(1.0, values[0], 0.0)
    }

    @Test
    fun `nonfinite Prism inputs select explicit off instead of an arbitrary artboard`() {
        val io = PrismProbe()
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            io.setPosition(bad); assertEquals(1055, io.currentPulseWidthUs)
            io.setSineWaveHue(bad); assertEquals(1055, io.currentPulseWidthUs)
            io.setSparkleHue(bad); assertEquals(1055, io.currentPulseWidthUs)
            io.setPulseHue(bad); assertEquals(1055, io.currentPulseWidthUs)
            io.setSolidColorHue(bad); assertEquals(1055, io.currentPulseWidthUs)
        }
    }

    @Test
    fun `finite Prism mappings clamp and truncate within their documented bands`() {
        val io = PrismProbe()
        val methods: List<(Double) -> Unit> = listOf(io::setPosition, io::setSineWaveHue, io::setSparkleHue, io::setPulseHue, io::setSolidColorHue)
        val starts = intArrayOf(500, 700, 620, 1950, 1100)
        val spans = intArrayOf(2000, 249, 79, 249, 799)
        for (i in methods.indices) {
            methods[i](-2.0); assertEquals(starts[i], io.currentPulseWidthUs)
            methods[i](2.0); assertEquals(starts[i]+spans[i], io.currentPulseWidthUs)
            for (n in 0..1024) {
                methods[i](n/1024.0)
                assertEquals(starts[i]+(spans[i].toLong()*n/1024).toInt(), io.currentPulseWidthUs)
            }
        }
        for (preset in PrismPwmPreset.entries) {
            io.setPreset(preset)
            assertEquals(preset.pulseWidthUs, io.currentPulseWidthUs)
            assertTrue(preset.normalizedPosition in 0.0..1.0)
        }
    }

    private class IntakeProbe : IntakeIO {
        val writes = mutableListOf<String>()
        var pivotFailure: Throwable? = null
        var rollerFailure: Throwable? = null
        var pivotReading = 3.0
        var rollerReading = 4.0
        var pivotReads = 0
        var rollerReads = 0
        var currentFresh = true
        var failOnCurrentRead = false
        override val pivotCurrentAmps: Double get() { check(!failOnCurrentRead); pivotReads++; return pivotReading }
        override val rollerCurrentAmps: Double get() { check(!failOnCurrentRead); rollerReads++; return rollerReading }
        override val rollerCurrentValid get() = currentFresh
        override fun setPivotAngle(degrees: Double, maxEffortScale: Double) = Unit
        override fun setRollerVelocityRps(rps: Double) = Unit
        override fun setPivotVoltage(volts: Double) { writes.add("pivot:$volts"); pivotFailure?.let { throw it } }
        override fun setRollerVoltage(volts: Double) { writes.add("roller:$volts"); rollerFailure?.let { throw it } }
    }

    private class PrismProbe : PrismDriverIO {
        override var currentPulseWidthUs = 1055
        override var maxBrightnessPercent = 75
        override fun setPulseWidthUs(pulseWidthUs: Int) { currentPulseWidthUs = pulseWidthUs }
        override fun setSolidColorRgb(r: Int, g: Int, b: Int) = Unit
    }
}
