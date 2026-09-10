package com.areslib.frc.drivetrain

import com.areslib.util.RobotClock
import com.ctre.phoenix6.Utils
import com.ctre.phoenix6.StatusCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class PhoenixSwerveReaderSourceTest {
    private val names = listOf("fl", "fr", "rl", "rr")
    private val channelOrder = names.map { "$it/current" } + names.map { "$it/encoder" } +
        listOf("pitch", "roll", "yaw", "yaw-rate") +
        listOf("drive-hardware", "drive-brownout", "drive-temperature", "steer-hardware", "steer-brownout", "steer-temperature")
            .flatMap { fault -> names.map { "$it/$fault" } }

    @Test fun `all channels use their own clones values timestamps and status`() {
        mockStatic(Utils::class.java).use { clock ->
            clock.`when`<Double> { Utils.getCurrentTimeSeconds() }.thenReturn(100.0)
            val fixture = PhoenixReaderFixture()
            for ((index, name) in channelOrder.withIndex()) {
                `when`(fixture.clones.getValue(name).valueAsDouble).thenReturn(index + 0.25)
                `when`(fixture.timestamps.getValue(name).time).thenReturn(100.0 - index * 0.001)
            }
            val source = PhoenixSwerveReaderSource(fixture.drivetrain)
            source.captureTime()
            for ((index, name) in channelOrder.withIndex()) {
                assertEquals(index + 0.25, source.value(index), name)
                assertEquals(index * 0.001, source.latencySeconds(index), 1e-10, name)
                assertTrue(source.statusOk(index), name)
                assertTrue(source.timestampValid(index), name)
                `when`(fixture.clones.getValue(name).status).thenReturn(StatusCode.InvalidParamValue)
                `when`(fixture.timestamps.getValue(name).isValid).thenReturn(false)
                assertFalse(source.statusOk(index), name)
                assertFalse(source.timestampValid(index), name)
                source.refresh(index)
                verify(fixture.clones.getValue(name), times(1)).refresh()
                verify(fixture.originals.getValue(name), times(1)).clone()
                verify(fixture.originals.getValue(name), never()).valueAsDouble
                verify(fixture.timestamps.getValue(name), never()).latency
            }
            clock.verify({ Utils.getCurrentTimeSeconds() }, times(1))
        }
    }

    @Test fun `configuration sets only consumed rates and preserves odometry owned frequencies`() {
        val fixture = PhoenixReaderFixture()
        val source = PhoenixSwerveReaderSource(fixture.drivetrain)
        assertTrue(source.configure())
        for ((name, signal) in fixture.clones) {
            val rate = when {
                name.endsWith("/current") || name == "pitch" || name == "roll" -> 20.0
                name.endsWith("/encoder") -> 50.0
                name == "yaw" || name == "yaw-rate" -> null
                else -> 4.0
            }
            if (rate == null) verify(signal, never()).setUpdateFrequency(anyDouble(), anyDouble())
            else verify(signal, times(1)).setUpdateFrequency(rate, 0.0)
        }
        fixture.originals.values.forEach { verify(it, never()).setUpdateFrequency(anyDouble(), anyDouble()) }
        fixture.steerMotors.forEach { verify(it, never()).supplyCurrent }
        verify(fixture.drivetrain, never()).stateCopy
    }

    @Test fun `configuration rejection is not masked by later successes and cannot acquire state`() {
        val fixture = PhoenixReaderFixture()
        `when`(fixture.clones.getValue("fl/current").setUpdateFrequency(anyDouble(), anyDouble()))
            .thenReturn(StatusCode.InvalidParamValue)
        val source = PhoenixSwerveReaderSource(fixture.drivetrain)
        assertFalse(source.configure())
        verify(fixture.clones.getValue("rr/steer-temperature"), times(1)).setUpdateFrequency(4.0, 0.0)
        assertThrows(IllegalStateException::class.java) { SwerveCtreDrivetrainReader(source) }
        verify(fixture.drivetrain, never()).stateCopy
        fixture.clones.values.forEach { verify(it, never()).refresh() }
        verify(fixture.drivetrain, never()).close()
    }

    @Test fun `configuration exceptions propagate without closing borrowed devices`() {
        val fixture = PhoenixReaderFixture()
        val failure = AssertionError("configuration")
        `when`(fixture.clones.getValue("fr/encoder").setUpdateFrequency(anyDouble(), anyDouble())).thenThrow(failure)
        assertSame(failure, assertThrows(AssertionError::class.java) { SwerveCtreDrivetrainReader(fixture.drivetrain) })
        verify(fixture.drivetrain, never()).stateCopy
        verify(fixture.drivetrain, never()).close()
        fixture.driveMotors.forEach { verify(it, never()).close() }
    }

    @Test fun `frame ages share one captured vendor time and use the owning state API`() {
        mockStatic(Utils::class.java).use { clock ->
            clock.`when`<Double> { Utils.getCurrentTimeSeconds() }.thenReturn(100.0)
            val fixture = PhoenixReaderFixture()
            val source = PhoenixSwerveReaderSource(fixture.drivetrain)
            assertTrue(source.latencySeconds(0).isNaN())
            val state = source.state()
            assertSame(fixture.state, state)
            source.captureTime()
            assertEquals(0.01, source.stateAgeSeconds(state), 1e-10)
            clock.`when`<Double> { Utils.getCurrentTimeSeconds() }.thenReturn(200.0)
            repeat(20) {
                assertEquals(0.02, source.latencySeconds(0), 1e-10)
                assertEquals(0.01, source.stateAgeSeconds(state), 1e-10)
            }
            source.captureTime()
            assertEquals(100.02, source.latencySeconds(0), 1e-10)
            clock.verify({ Utils.getCurrentTimeSeconds() }, times(2))
            verify(fixture.drivetrain, times(1)).stateCopy
            verify(fixture.drivetrain, never()).state
        }
    }

    @Test fun `vendor refresh and state failures revoke the public readers previous snapshot`() {
        RobotClock.useMockTime(1000)
        try {
            mockStatic(Utils::class.java).use { clock ->
                clock.`when`<Double> { Utils.getCurrentTimeSeconds() }.thenReturn(100.0)
                val fixture = PhoenixReaderFixture()
                val reader = SwerveCtreDrivetrainReader(fixture.drivetrain)
                reader.refresh()
                assertTrue(reader.currentMeasurementsValid)
                val refreshFailure = IllegalStateException("refresh")
                doThrow(refreshFailure).`when`(fixture.clones.getValue("rr/steer-temperature")).refresh()
                assertSame(refreshFailure, assertThrows(IllegalStateException::class.java) { reader.refresh() })
                assertFalse(reader.currentMeasurementsValid)
                assertTrue(reader.read().odometryX.isNaN())
                doReturn(fixture.clones.getValue("rr/steer-temperature"))
                    .`when`(fixture.clones.getValue("rr/steer-temperature")).refresh()
                reader.refresh()
                assertTrue(reader.currentMeasurementsValid)
                val stateFailure = AssertionError("state")
                `when`(fixture.drivetrain.stateCopy).thenThrow(stateFailure)
                assertSame(stateFailure, assertThrows(AssertionError::class.java) { reader.refresh() })
                assertFalse(reader.encoderPositionsValid)
                assertTrue(reader.read().odometryX.isNaN())
            }
        } finally { RobotClock.useSystemTime() }
    }

    @Test fun `public constructor reads owned vendor clones and one owning state snapshot`() {
        RobotClock.useMockTime(1000)
        try {
            mockStatic(Utils::class.java).use { clock ->
                clock.`when`<Double> { Utils.getCurrentTimeSeconds() }.thenReturn(100.0)
                val fixture = PhoenixReaderFixture()
                val reader = SwerveCtreDrivetrainReader(fixture.drivetrain)
                reader.refresh()
                assertTrue(reader.currentMeasurementsValid)
                assertTrue(reader.encoderPositionsValid)
                val out = DoubleArray(4)
                reader.getCurrents(out)
                assertArrayEquals(DoubleArray(4) { 1.0 }, out)
                reader.getEncoderPositions(out)
                assertArrayEquals(DoubleArray(4) { 0.1 }, out)
                assertEquals(20.0, reader.signalLatencyMs, 1e-8)
                repeat(10) { reader.read(); reader.getModuleSpeeds(out); reader.getCurrents(out) }
                verify(fixture.drivetrain, times(1)).stateCopy
                verify(fixture.drivetrain, never()).state
                clock.verify({ Utils.getCurrentTimeSeconds() }, times(1))
                fixture.originals.values.forEach { verify(it, times(1)).clone(); verify(it, never()).refresh() }
                fixture.clones.values.forEach { verify(it, times(1)).refresh() }
                fixture.driveMotors.forEach { verify(it, never()).close() }
                fixture.steerMotors.forEach { verify(it, never()).supplyCurrent; verify(it, never()).close() }
                fixture.encoders.forEach { verify(it, never()).close() }
                verify(fixture.drivetrain, never()).close()
            }
        } finally { RobotClock.useSystemTime() }
    }
}
