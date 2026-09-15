package com.areslib.frc.drivetrain

import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class FrcSwerveBridgeSnapshotTest {
    @Test fun `all getters consume one cached snapshot and invalidate on close`() = withPhoenixBridge { fixture, io ->
        `when`(fixture.clones.getValue("pitch").valueAsDouble).thenReturn(2.0)
        `when`(fixture.clones.getValue("roll").valueAsDouble).thenReturn(-3.0)
        `when`(fixture.clones.getValue("yaw").valueAsDouble).thenReturn(40.0)
        `when`(fixture.clones.getValue("yaw-rate").valueAsDouble).thenReturn(-5.0)
        fixture.state.ModuleStates[2].speedMetersPerSecond = -2.0
        io.refresh()
        clearInvocations(fixture.drivetrain, *fixture.clones.values.toTypedArray())
        val out = DoubleArray(5) { 9.0 }; val faults = IntArray(5) { 9 }
        repeat(2) {
            io.getCurrents(out); assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 9.0), out)
            io.getEncoderPositions(out); assertArrayEquals(doubleArrayOf(0.1, 0.1, 0.1, 0.1, 9.0), out)
            io.getModuleSpeeds(out); assertArrayEquals(doubleArrayOf(0.0, 0.0, -2.0, 0.0, 9.0), out)
            io.getFaults(faults); assertArrayEquals(intArrayOf(0, 0, 0, 0, 9), faults)
            assertTrue(io.currentMeasurementsValid && io.encoderPositionsValid)
            assertEquals(20.0, io.signalLatencyMs, 1e-8)
            assertEquals(2.0, io.pitchDegrees); assertEquals(-3.0, io.rollDegrees)
            assertEquals(40.0, io.rawGyroYawDegrees); assertEquals(-5.0, io.yawRateDegreesPerSecond)
            assertSame(io.read(), io.read())
        }
        verifyNoInteractions(fixture.drivetrain, *fixture.clones.values.toTypedArray())
        io.close()
        clearInvocations(fixture.drivetrain, *fixture.clones.values.toTypedArray())
        io.getCurrents(out); assertTrue((0 until 4).all { out[it].isNaN() })
        io.getEncoderPositions(out); assertTrue((0 until 4).all { out[it].isNaN() })
        io.getModuleSpeeds(out); assertTrue((0 until 4).all { out[it].isNaN() })
        assertEquals(9.0, out[4])
        io.getFaults(faults); assertArrayEquals(intArrayOf(0x40, 0x40, 0x40, 0x40, 9), faults)
        assertFalse(io.currentMeasurementsValid || io.encoderPositionsValid)
        assertEquals(Double.POSITIVE_INFINITY, io.signalLatencyMs)
        assertTrue(io.pitchDegrees.isNaN() && io.rollDegrees.isNaN() && io.rawGyroYawDegrees.isNaN() && io.yawRateDegreesPerSecond.isNaN())
        assertTrue(io.read().odometryX.isNaN())
        verifyNoInteractions(fixture.drivetrain, *fixture.clones.values.toTypedArray())
    }

    @Test fun `short cached buffers reject before changing caller storage even after close`() = withPhoenixBridge { _, io ->
        repeat(2) {
            val out = doubleArrayOf(1.0, 2.0, 3.0); val faults = intArrayOf(1, 2, 3)
            assertThrows(IllegalArgumentException::class.java) { io.getCurrents(out) }
            assertThrows(IllegalArgumentException::class.java) { io.getEncoderPositions(out) }
            assertThrows(IllegalArgumentException::class.java) { io.getModuleSpeeds(out) }
            assertThrows(IllegalArgumentException::class.java) { io.getFaults(faults) }
            assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), out); assertArrayEquals(intArrayOf(1, 2, 3), faults)
            io.close()
        }
    }

    @Test fun `cached getter loop reuses storage without native calls or steady state allocations`() = withPhoenixBridge { fixture, io ->
        io.refresh()
        clearInvocations(fixture.drivetrain, *fixture.clones.values.toTypedArray())
        val out = DoubleArray(4); val faults = IntArray(4)
        var checksum = 0.0
        fun tick() {
            io.getCurrents(out); checksum += out[0]
            io.getEncoderPositions(out); checksum += out[1]
            io.getModuleSpeeds(out); checksum += out[2]
            io.getFaults(faults); checksum += faults[3]
            checksum += io.read().odometryX + io.pitchDegrees + io.rollDegrees + io.rawGyroYawDegrees + io.yawRateDegreesPerSecond
            checksum += io.signalLatencyMs
            check(io.currentMeasurementsValid && io.encoderPositionsValid)
        }
        assertSteadyStateAllocationWindows(measureAllocationWindows { tick() })
        assertTrue(checksum.isFinite() && checksum > 0.0)
        verifyNoInteractions(fixture.drivetrain, *fixture.clones.values.toTypedArray())
        RobotClock.useMockTime(1101)
        assertFalse(io.currentMeasurementsValid)
    }

    @Test fun `refresh failure with successful brake retains original cause`() = withPhoenixBridge { fixture, io ->
        val failure = AssertionError("refresh")
        doThrow(failure).`when`(fixture.clones.getValue("fl/current")).refresh()
        assertSame(failure, assertThrows(AssertionError::class.java) { io.refresh() })
        assertTrue(failure.suppressed.isEmpty())
        verify(fixture.drivetrain).setControl(any(com.ctre.phoenix6.swerve.SwerveRequest::class.java))
    }
}
