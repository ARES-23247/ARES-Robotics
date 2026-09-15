package com.areslib.hardware

import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.geometry.Pose2d
import com.areslib.state.DriveState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.hardware.drive.SwerveModuleIO
import com.google.gson.Gson
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import com.areslib.telemetry.ITelemetry

class SwerveIoContractAuditTest {
    @Volatile private var retainedAllocation: DoubleArray? = null
    @Test
    fun `telemetry publishes four owned values and truthful checked validity across instances`() {
        val arrays = mutableMapOf<String, DoubleArray>()
        val flags = mutableMapOf<String, Boolean>()
        val telemetry = object : ITelemetry {
            override fun putDoubleArray(key: String, value: DoubleArray) { arrays[key] = value.copyOf() }
            override fun putBoolean(key: String, value: Boolean) { flags[key] = value }
            override fun putNumber(key: String, value: Double) = Unit
            override fun putString(key: String, value: String) = Unit
            override fun getNumber(key: String, defaultValue: Double) = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
            override fun getString(key: String, defaultValue: String) = defaultValue
        }
        val first = ContractSwerveIO()
        first.logTelemetry(telemetry, "First")
        val second = ContractSwerveIO()
        second.currents[0] = Double.NaN
        second.encoderPositionsValid = false
        second.logTelemetry(telemetry, "Second")
        assertArrayEquals(first.currents, arrays.getValue("First/Currents"))
        assertArrayEquals(first.encoders, arrays.getValue("First/EncoderPositions"))
        assertTrue(flags.getValue("First/CurrentsValid"))
        assertTrue(flags.getValue("First/EncoderPositionsValid"))
        assertFalse(flags.getValue("Second/CurrentsValid"))
        assertFalse(flags.getValue("Second/EncoderPositionsValid"))
        assertTrue(arrays.getValue("Second/Currents").all { it.isNaN() })
        assertTrue(arrays.getValue("Second/EncoderPositions").all { it.isNaN() })
        assertEquals(2, first.reads)
        assertEquals(1, second.reads)
    }

    @Test
    fun `covariance fallback forwards the accepted pose and timestamp exactly once`() {
        val io = ContractSwerveIO()
        val pose = Pose2d(1.0, -2.0, com.areslib.math.geometry.Rotation2d(0.4))
        io.addVisionMeasurement(pose, 123.25, 0.2, 0.3, 0.4)
        assertSame(pose, io.visionPose)
        assertEquals(123.25, io.visionTimestamp)
        assertEquals(1, io.visionCalls)
    }
    @Test
    fun `checked reads reject nonfinite and incomplete cached snapshots`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(4) { 99.0 }
        io.currents[2] = Double.POSITIVE_INFINITY
        assertFalse(io.getCurrentsIfValid(out))
        assertTrue(out.all { it.isNaN() })
        io.count = 3
        assertFalse(io.getEncoderPositionsIfValid(out))
        assertTrue(out.all { it.isNaN() })
    }

    @Test
    fun `both checked channels reject every nonfinite member and missing module`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(6) { 77.0 }
        for (index in 0 until 4) {
            for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                io.currents[index] = bad
                io.encoders[index] = bad
                assertFalse(io.getCurrentsIfValid(out))
                assertFalse(io.getEncoderPositionsIfValid(out))
                assertTrue(out.take(4).all { it.isNaN() })
                io.currents[index] = 1.0
                io.encoders[index] = 0.1
            }
        }
        for (count in 0 until 4) {
            io.count = count
            out.fill(9.0, 0, 4)
            assertFalse(io.getCurrentsIfValid(out))
            out.fill(9.0, 0, 4)
            assertFalse(io.getEncoderPositionsIfValid(out))
        }
        assertEquals(77.0, out[4])
        assertEquals(77.0, out[5])
    }

    @Test
    fun `short buffers reject before mutation and cached getters`() {
        val io = ContractSwerveIO()
        for (valid in listOf(false, true)) {
            io.currentMeasurementsValid = valid
            io.encoderPositionsValid = valid
            for (length in 0 until 4) {
                val out = DoubleArray(length) { 7.0 }
                assertThrows(IllegalArgumentException::class.java) { io.getCurrentsIfValid(out) }
                assertThrows(IllegalArgumentException::class.java) { io.getEncoderPositionsIfValid(out) }
                assertTrue(out.all { it == 7.0 })
            }
        }
        assertEquals(0, io.reads)
    }

    @Test
    fun `unavailable snapshots skip getters and preserve trailing storage`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(6) { 88.0 }
        io.currentMeasurementsValid = false
        io.encoderPositionsValid = false
        assertFalse(io.getCurrentsIfValid(out))
        assertFalse(io.getEncoderPositionsIfValid(out))
        assertEquals(0, io.reads)
        assertTrue(out.take(4).all { it.isNaN() })
        assertEquals(88.0, out[4])
        assertEquals(88.0, out[5])
    }

    @Test
    fun `partial writes followed by exceptions invalidate all entries and preserve original cause`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(5) { 66.0 }
        for (failure in listOf(IllegalStateException("read"), AssertionError("fatal read"))) {
            io.failure = failure
            assertSame(failure, assertThrows(Throwable::class.java) { io.getCurrentsIfValid(out) })
            assertTrue(out.take(4).all { it.isNaN() })
            assertSame(failure, assertThrows(Throwable::class.java) { io.getEncoderPositionsIfValid(out) })
            assertTrue(out.take(4).all { it.isNaN() })
            assertEquals(66.0, out[4])
        }
    }

    @Test
    fun `finite snapshots preserve exact values and require one getter call`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(5) { 55.0 }
        io.currents[0] = -0.0
        io.currents[1] = Double.MIN_VALUE
        io.currents[2] = Double.MAX_VALUE
        assertTrue(io.getCurrentsIfValid(out))
        assertArrayEquals(io.currents, out.copyOf(4))
        assertTrue(io.getEncoderPositionsIfValid(out))
        assertArrayEquals(io.encoders, out.copyOf(4))
        assertEquals(2, io.reads)
        assertEquals(55.0, out[4])
    }

    @Test
    fun `input DTO defaults remain invalid and serialization retains explicit validity and timestamp`() {
        val gson = Gson()
        val legacy = gson.fromJson("{\"drivePositionRads\":12.0}", SwerveModuleInputs::class.java)
        assertFalse(legacy.drivePositionValid)
        assertFalse(legacy.driveVelocityValid)
        assertFalse(legacy.steerAbsoluteValid)
        assertEquals(0L, legacy.timestampMs)
        val inputs = SwerveModuleInputs(1.2, -3.4, 0.6, true, false, true, 123456789L)
        assertEquals(inputs, gson.fromJson(gson.toJson(inputs), SwerveModuleInputs::class.java))
        val sensorOnly = object : SwerveModuleIO {
            override fun updateInputs(inputs: SwerveModuleInputs) { inputs.drivePositionRads = 2.0 }
        }
        sensorOnly.setDesiredPower(0.0, 0.0)
        sensorOnly.updateInputs(legacy)
        assertFalse(legacy.drivePositionValid)
    }

    @Test
    fun `checked cached reads allocate no bytes after warmup`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(4)
        var checksum = 0.0
        val samples = allocationWindows {
            if (io.getCurrentsIfValid(out)) checksum += out[0]
            if (io.getEncoderPositionsIfValid(out)) checksum += out[0]
        }
        println("Checked swerve reads: ${samples.contentToString()} bytes per 20,000 reads")
        // Warm the measured loop and counter too, then require both final windows to be zero.
        // Early windows can include one-time JVM linkage/JIT work on a fresh CI worker.
        assertEquals(0L, samples[8], "allocation windows: ${samples.contentToString()}")
        assertEquals(0L, samples[9], "allocation windows: ${samples.contentToString()}")
        assertTrue(checksum.isFinite() && checksum > 0.0)
        assertEquals(300_000, io.reads)
    }

    @Test
    fun `allocation windows detect per-read array allocation after warmup`() {
        val io = ContractSwerveIO()
        val out = DoubleArray(4)
        val samples = allocationWindows {
            io.getCurrentsIfValid(out)
            retainedAllocation = out.copyOf()
        }
        println("Allocating control: ${samples.contentToString()} bytes per 10,000 reads")
        // The escaped copy must remain visible to the same counter and warm-up procedure.
        assertTrue(samples.all { it >= 10_000L * 4 * Double.SIZE_BYTES }, samples.contentToString())
        assertArrayEquals(io.currents, retainedAllocation)
        assertEquals(150_000, io.reads)
    }

    private fun allocationWindows(tick: () -> Unit): LongArray {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val samples = LongArray(10)
        repeat(50_000) { tick() }
        repeat(samples.size) { window ->
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            samples[window] = bean.getThreadAllocatedBytes(thread) - before
        }
        return samples
    }
}

internal class ContractSwerveIO : SwerveHardwareIO {
    val currents = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
    val encoders = doubleArrayOf(-0.2, 0.1, 0.3, 0.4)
    var count = 4
    var reads = 0
    var failure: Throwable? = null
    var visionPose: Pose2d? = null
    var visionTimestamp = Double.NaN
    var visionCalls = 0
    override var currentMeasurementsValid = true
    override var encoderPositionsValid = true
    override fun getCurrents(out: DoubleArray) { reads++; currents.copyInto(out, endIndex = count); failure?.let { throw it } }
    override fun getEncoderPositions(out: DoubleArray) { reads++; encoders.copyInto(out, endIndex = count); failure?.let { throw it } }
    override fun refresh() = Unit
    override fun read() = DriveState()
    override fun write(driveState: DriveState, powerScale: Double) = Unit
    override val pitchDegrees = 0.0
    override val rollDegrees = 0.0
    override val rawGyroYawDegrees = 0.0
    override val yawRateDegreesPerSecond = 0.0
    override fun getModuleSpeeds(out: DoubleArray) = out.fill(0.0)
    override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) {
        visionPose = pose; visionTimestamp = timestampSeconds; visionCalls++
    }
    override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray) = false
    override fun seedPose(pose: Pose2d) = Unit
    override fun getFaults(out: IntArray) = out.fill(0)
    override val signalLatencyMs = 0.0
}
