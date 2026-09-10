package com.areslib.frc.drivetrain

import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import edu.wpi.first.math.geometry.Pose2d
import edu.wpi.first.math.geometry.Rotation2d
import edu.wpi.first.math.kinematics.SwerveModuleState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class SwerveCtreReaderSnapshotTest {
    @BeforeEach fun clock() { RobotClock.useMockTime(1000L) }
    @AfterEach fun restoreClock() { RobotClock.useSystemTime() }

    @Test fun `snapshots own pose speed signals and fault values across vendor mutation`() {
        val source = AuditCtreSource()
        source.stateValue.Pose = Pose2d(2.0, -3.0, Rotation2d(0.5))
        source.stateValue.Speeds.vxMetersPerSecond = 4.0
        source.stateValue.Speeds.vyMetersPerSecond = -5.0
        source.stateValue.Speeds.omegaRadiansPerSecond = 0.6
        for (i in 0 until 4) source.stateValue.ModuleStates[i].speedMetersPerSecond = i + 1.0
        source.values[8] = 8.0; source.values[9] = -9.0
        source.values[10] = 170.0; source.values[11] = -20.0
        source.values[12] = 1.0; source.values[29] = 1.0
        val reader = SwerveCtreDrivetrainReader(source)
        reader.refresh()
        val first = reader.read()
        val out = DoubleArray(5) { 555.0 }
        val faults = IntArray(5) { 555 }
        source.stateValue.Pose = Pose2d(99.0, 99.0, Rotation2d())
        source.stateValue.Speeds.vxMetersPerSecond = 99.0
        source.stateValue.ModuleStates[0].speedMetersPerSecond = 99.0
        source.values.fill(0.0)
        reader.getModuleSpeeds(out)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 555.0), out)
        reader.getFaults(faults)
        assertArrayEquals(intArrayOf(1, 16, 0, 0, 555), faults)
        assertEquals(8.0, reader.pitchDegrees)
        assertEquals(-9.0, reader.rollDegrees)
        assertEquals(170.0, reader.rawGyroYawDegrees)
        assertEquals(-20.0, reader.yawRateDegreesPerSecond)
        assertEquals(2.0, first.odometryX)
        assertEquals(-3.0, first.odometryY)
        assertEquals(0.5, first.odometryHeading)
        assertEquals(4.0, first.xVelocityMetersPerSecond)
        assertEquals(-5.0, first.yVelocityMetersPerSecond)
        assertEquals(0.6, first.angularVelocityRadiansPerSecond)
        assertSame(first, reader.read())
        assertEquals(1, source.stateCalls)
        assertEquals(1, source.timeCaptures)
        assertTrue(source.refreshCalls.all { it == 1 })
        assertTrue(source.valueCalls.all { it == 1 })
        reader.refresh()
        assertEquals(99.0, reader.read().odometryX)
        assertEquals(2.0, first.odometryX)
        assertNotSame(first, reader.read())
    }

    @Test fun `all six fault bits map independently for all four modules`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        val out = IntArray(4)
        for (module in 0 until 4) for (bit in 0 until 6) {
            val index = 12 + bit * 4 + module
            source.values[index] = 1.0
            reader.refresh()
            reader.getFaults(out)
            for (i in 0 until 4) assertEquals(if (i == module) 1 shl bit else 0, out[i])
            source.values[index] = 0.0
        }
    }

    @Test fun `short outputs reject before mutation while long tails survive every getter`() {
        val reader = SwerveCtreDrivetrainReader(AuditCtreSource())
        for (valid in listOf(false, true)) {
            if (valid) reader.refresh()
            for (size in 0 until 4) {
                val out = DoubleArray(size) { 5.0 }
                val faults = IntArray(size) { 5 }
                assertThrows(IllegalArgumentException::class.java) { reader.getCurrents(out) }
                assertThrows(IllegalArgumentException::class.java) { reader.getEncoderPositions(out) }
                assertThrows(IllegalArgumentException::class.java) { reader.getModuleSpeeds(out) }
                assertThrows(IllegalArgumentException::class.java) { reader.getFaults(faults) }
                assertTrue(out.all { it == 5.0 })
                assertTrue(faults.all { it == 5 })
            }
            val out = DoubleArray(5) { 42.0 }
            val faults = IntArray(5) { 42 }
            reader.getCurrents(out); assertEquals(42.0, out[4])
            reader.getEncoderPositions(out); assertEquals(42.0, out[4])
            reader.getModuleSpeeds(out); assertEquals(42.0, out[4])
            reader.getFaults(faults); assertEquals(42, faults[4])
        }
    }

    @Test fun `invalid or old motion rejects pose and wheel speeds while valid signal data remain independent`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        val out = DoubleArray(4)
        for (age in doubleArrayOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.101)) {
            source.stateAge = age
            reader.refresh()
            assertTrue(reader.read().odometryX.isNaN())
            reader.getModuleSpeeds(out)
            assertTrue(out.all { it.isNaN() })
            assertTrue(reader.currentMeasurementsValid)
        }
        source.stateAge = 0.0
        source.stateValue.Speeds.vxMetersPerSecond = Double.NaN
        reader.refresh(); assertTrue(reader.read().odometryX.isNaN())
        source.stateValue.Speeds.vxMetersPerSecond = 0.0
        source.stateValue.ModuleStates[2].speedMetersPerSecond = Double.POSITIVE_INFINITY
        reader.refresh(); assertTrue(reader.read().odometryX.isNaN())
        source.stateValue.ModuleStates = Array(3) { SwerveModuleState() }
        reader.refresh(); assertTrue(reader.read().odometryX.isNaN())
    }

    @Test fun `cached getters allocate nothing and never reacquire vendor state`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        reader.refresh()
        val expected = reader.read()
        reader.refresh()
        assertSame(expected, reader.read()) // unchanged immutable pose/motion is reused
        val values = DoubleArray(4)
        val faults = IntArray(4)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        var checksum = 0.0
        fun tick() {
            reader.getCurrents(values); checksum += values[0]
            reader.getEncoderPositions(values)
            reader.getModuleSpeeds(values)
            reader.getFaults(faults)
            checksum += reader.read().odometryX + reader.pitchDegrees + reader.signalLatencyMs
        }
        repeat(50_000) { tick() }
        val thread = Thread.currentThread().id
        repeat(2) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            assertEquals(0L, bean.getThreadAllocatedBytes(thread) - before)
        }
        assertEquals(70_000.0, checksum)
        assertEquals(2, source.stateCalls)
        assertEquals(2, source.timeCaptures)
        assertTrue(source.valueCalls.all { it == 2 })
    }
}
