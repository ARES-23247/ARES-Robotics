package com.areslib.frc.drivetrain

import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class SwerveCtreReaderFreshnessTest {
    @BeforeEach fun clock() { RobotClock.useMockTime(1000L) }
    @AfterEach fun restoreClock() { RobotClock.useSystemTime() }

    @Test fun `configuration failure prevents acquisition and initial outputs are unavailable`() {
        val failed = AuditCtreSource().apply { configured = false }
        assertThrows(IllegalStateException::class.java) { SwerveCtreDrivetrainReader(failed) }
        assertEquals(1, failed.configureCalls)
        assertEquals(0, failed.stateCalls)
        val reader = SwerveCtreDrivetrainReader(AuditCtreSource())
        assertFalse(reader.currentMeasurementsValid)
        assertFalse(reader.encoderPositionsValid)
        assertTrue(reader.read().odometryX.isNaN())
        assertTrue(reader.pitchDegrees.isNaN())
        val faults = IntArray(4)
        reader.getFaults(faults)
        assertArrayEquals(IntArray(4) { 0x40 }, faults)
    }

    @Test fun `every status timestamp and nonfinite channel rejects the signal group`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        for (index in 0 until 36) {
            source.statuses[index] = false
            reader.refresh()
            assertFalse(reader.currentMeasurementsValid, "status=$index")
            source.statuses[index] = true
            source.timestamps[index] = false
            reader.refresh()
            assertFalse(reader.encoderPositionsValid, "timestamp=$index")
            source.timestamps[index] = true
            val old = source.values[index]
            for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                source.values[index] = bad
                reader.refresh()
                assertFalse(reader.currentMeasurementsValid, "finite=$index")
            }
            source.values[index] = old
        }
        source.values[12] = 2.0
        reader.refresh()
        assertFalse(reader.currentMeasurementsValid)
    }

    @Test fun `negative nonfinite and old ages never renew a successful status`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        for (index in 0 until 36) {
            for (age in doubleArrayOf(-0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 1.0)) {
                source.ages[index] = age
                reader.refresh()
                assertFalse(reader.currentMeasurementsValid, "age=$age index=$index")
                assertEquals(Double.POSITIVE_INFINITY, reader.signalLatencyMs)
            }
            source.ages[index] = 0.0
        }
    }

    @Test fun `vendor age plus local elapsed expires feedback even without another refresh`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        source.ages[4] = 0.080
        reader.refresh()
        assertEquals(80.0, reader.signalLatencyMs)
        RobotClock.useMockTime(1020)
        assertTrue(reader.encoderPositionsValid)
        assertEquals(100.0, reader.signalLatencyMs)
        RobotClock.useMockTime(1021)
        assertFalse(reader.encoderPositionsValid)
        assertTrue(reader.rollDegrees.isNaN())
        assertTrue(reader.rawGyroYawDegrees.isNaN())
        assertTrue(reader.yawRateDegreesPerSecond.isNaN())
        RobotClock.useMockTime(1101)
        assertTrue(reader.read().odometryHeading.isNaN())
    }

    @Test fun `diagnostics have their own slower age bound and expire the group at that bound`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        source.ages[35] = 0.730
        reader.refresh()
        RobotClock.useMockTime(1020)
        assertTrue(reader.currentMeasurementsValid)
        RobotClock.useMockTime(1021)
        assertFalse(reader.currentMeasurementsValid)
    }

    @Test fun `slow acquisitions cannot refresh their age on return`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        source.onRefresh = { if (it == 35) RobotClock.useMockTime(1101) }
        reader.refresh()
        assertFalse(reader.currentMeasurementsValid)
        assertTrue(reader.read().odometryX.isNaN())
    }

    @Test fun `clock rewind signed overflow and wrap reject cached authority`() {
        for ((start, end) in listOf(1000L to 999L, Long.MIN_VALUE to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE)) {
            RobotClock.useMockTime(start)
            val reader = SwerveCtreDrivetrainReader(AuditCtreSource())
            reader.refresh()
            assertTrue(reader.currentMeasurementsValid)
            RobotClock.useMockTime(end)
            assertFalse(reader.currentMeasurementsValid)
            assertTrue(reader.read().odometryX.isNaN())
        }
    }

    @Test fun `late state failure revokes all channels until a complete later refresh`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        reader.refresh()
        val failure = AssertionError("state")
        source.stateFailure = failure
        assertSame(failure, assertThrows(AssertionError::class.java) { reader.refresh() })
        assertFalse(reader.currentMeasurementsValid)
        assertTrue(reader.read().odometryX.isNaN())
        source.stateFailure = null
        reader.refresh()
        assertTrue(reader.currentMeasurementsValid)
        assertEquals(0.0, reader.read().odometryX)
    }
}
