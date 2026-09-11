package org.aresfirst.marvin

import org.aresfirst.marvin.hardware.FrcMechanismConfigurationStatus
import org.aresfirst.marvin.hardware.FrcMechanismHomingStatus
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.geometry.Pose2d
import com.areslib.state.DriveState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows

class ARESRobotSafetyBoundaryTest {

    private class FakeSwerveIO : SwerveHardwareIO {
        val encoderPositions = doubleArrayOf(0.1, -0.2, 0.3, -0.4)
        var latencyMs = 0.0
        var encoderValid = true
        var encoderReads = 0
        var refreshCalls = 0
        var failReads = false
        var incompleteRead = false

        override fun refresh() { refreshCalls++ }
        override fun read(): DriveState = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun getCurrents(out: DoubleArray) = out.fill(Double.NaN)
        override val currentMeasurementsValid: Boolean = false
        override fun getEncoderPositions(out: DoubleArray) {
            encoderReads++
            check(!failReads) { "Synthetic encoder read failure" }
            if (incompleteRead) out[0] = encoderPositions[0] else encoderPositions.copyInto(out)
        }
        override val encoderPositionsValid: Boolean
            get() = encoderValid
        override val signalLatencyMs: Double
            get() = latencyMs
        override val pitchDegrees: Double = Double.NaN
        override val rollDegrees: Double = Double.NaN
        override val rawGyroYawDegrees: Double = Double.NaN
        override val yawRateDegreesPerSecond: Double = Double.NaN
        override fun getModuleSpeeds(out: DoubleArray) = out.fill(Double.NaN)
        override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean = false
        override fun seedPose(pose: Pose2d) = Unit
        override fun getFaults(out: IntArray) = out.fill(-1)
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) = Unit
    }

    private class ConfigurationStatus(
        var valid: Boolean
    ) : FrcMechanismConfigurationStatus {
        override val configurationValid: Boolean
            get() = valid
    }

    private class HomingStatus(
        override val homed: Boolean
    ) : FrcMechanismHomingStatus {
        override fun homeAtKnownZero(): Boolean = homed
    }

    @Test
    fun `swerve calibration cache requires recent finite plausible four-module sample`() {
        val io = FakeSwerveIO()
        val cache = SwerveOffsetCalibrationSampleCache(maxAgeMs = 100L)
        val output = DoubleArray(4)

        cache.record(io, 1_000L)
        assertTrue(cache.copyFresh(1_100L, output))
        assertArrayEquals(io.encoderPositions, output, 1e-12)
        assertFalse(cache.copyFresh(1_101L, output))

        io.encoderPositions[2] = Double.NaN
        cache.record(io, 1_200L)
        assertFalse(cache.copyFresh(1_200L, output))

        io.encoderPositions[2] = 1.01
        cache.record(io, 1_300L)
        assertFalse(cache.copyFresh(1_300L, output))

        io.encoderPositions[2] = 0.25
        io.latencyMs = 101.0
        cache.record(io, 1_400L)
        assertFalse(cache.copyFresh(1_400L, output))

        io.latencyMs = 0.0
        io.encoderValid = false
        cache.record(io, 1_500L)
        assertFalse(cache.copyFresh(1_500L, output))
    }

    @Test
    fun `calibration freshness includes sensor latency and cache residence`() {
        val io = FakeSwerveIO().apply { latencyMs = 90.25 }
        val cache = SwerveOffsetCalibrationSampleCache()
        val output = DoubleArray(5) { 42.0 }
        cache.record(io, 1_000L)
        assertTrue(cache.copyFresh(1_009L, output))
        assertEquals(42.0, output[4])
        output.fill(42.0)
        assertFalse(cache.copyFresh(1_010L, output))
        assertTrue(output.all { it == 42.0 }, "Rejected copies must leave caller storage unchanged")
    }

    @Test
    fun `calibration cache rejects clock rewind even when subtraction wraps positive`() {
        val cache = SwerveOffsetCalibrationSampleCache()
        cache.record(FakeSwerveIO(), Long.MAX_VALUE - 5L)
        assertFalse(cache.copyFresh(Long.MIN_VALUE + 5L, DoubleArray(4)))
    }

    @Test
    fun `calibration freshness keeps the exact combined boundary and replaces old latency`() {
        val io = FakeSwerveIO().apply { latencyMs = 90.0 }
        val cache = SwerveOffsetCalibrationSampleCache()
        val output = DoubleArray(4)
        cache.record(io, 1_000L)
        assertTrue(cache.copyFresh(1_010L, output))
        assertFalse(cache.copyFresh(1_011L, output))
        io.latencyMs = 0.0
        cache.record(io, 2_000L)
        assertTrue(cache.copyFresh(2_100L, output))
        assertFalse(cache.copyFresh(1_999L, output))
        cache.record(io, -1L)
        assertFalse(cache.copyFresh(Long.MAX_VALUE, output), "Overflowed positive elapsed time must reject")
    }

    @Test
    fun `calibration owns its snapshot and invalid acquisition revokes it`() {
        val io = FakeSwerveIO()
        val cache = SwerveOffsetCalibrationSampleCache()
        val output = DoubleArray(4)
        cache.record(io, 1_000L)
        io.encoderPositions.fill(0.9)
        assertTrue(cache.copyFresh(1_000L, output))
        assertEquals(0.1, output[0])
        output.fill(0.8)
        assertTrue(cache.copyFresh(1_000L, output))
        assertEquals(0.1, output[0])
        for (latency in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1)) {
            io.latencyMs = 0.0
            cache.record(io, 2_000L)
            assertTrue(cache.copyFresh(2_000L, output))
            io.latencyMs = latency
            cache.record(io, 2_000L)
            assertFalse(cache.copyFresh(2_000L, output))
        }
    }

    @Test
    fun `calibration copies use only owned storage and failed reads revoke the sample`() {
        val io = FakeSwerveIO()
        val cache = SwerveOffsetCalibrationSampleCache()
        val output = DoubleArray(4)
        assertFalse(cache.copyFresh(0L, output))
        cache.record(io, 1_000L)
        repeat(5) { assertTrue(cache.copyFresh(1_000L, output)) }
        assertEquals(1, io.encoderReads)
        assertEquals(0, io.refreshCalls)
        assertThrows(IllegalArgumentException::class.java) { cache.copyFresh(1_000L, DoubleArray(3)) }
        io.failReads = true
        cache.record(io, 1_001L)
        assertFalse(cache.copyFresh(1_001L, output))
        io.failReads = false
        cache.record(io, 1_002L)
        assertTrue(cache.copyFresh(1_002L, output))
        io.incompleteRead = true
        cache.record(io, 1_003L)
        assertFalse(cache.copyFresh(1_003L, output))
    }

    @Test
    fun `mechanism configuration health fails closed on any reporting adapter`() {
        assertTrue(mechanismsConfigured(arrayOf(ConfigurationStatus(true))))
        assertFalse(mechanismsConfigured(arrayOf(ConfigurationStatus(true), ConfigurationStatus(false))))
        val resettable = ConfigurationStatus(true)
        assertTrue(mechanismsConfigured(arrayOf(resettable)))
        resettable.valid = false
        assertFalse(mechanismsConfigured(arrayOf(resettable)), "a post-startup reset must invalidate live health")
    }

    @Test
    fun `relative position mechanism health fails closed until every device is homed`() {
        assertTrue(mechanismsHomed(arrayOf(HomingStatus(true))))
        assertFalse(mechanismsHomed(arrayOf(HomingStatus(true), HomingStatus(false))))
        assertFalse(mechanismSafetyHealthy(true, false, null))
        assertFalse(mechanismSafetyHealthy(false, true, null))
        assertFalse(mechanismSafetyHealthy(true, true, IllegalStateException("update failed")))
        assertTrue(mechanismSafetyHealthy(true, true, null))
    }

    @Test
    fun `safe zero recovery requires both operators and disabled state`() {
        assertFalse(mechanismHomingComboPressed(true, true, true, false))
        assertTrue(mechanismHomingComboPressed(true, true, true, true))
        assertTrue(mechanismHomingRequestAllowed(isDisabled = true, isTestEnabled = false))
        assertFalse(mechanismHomingRequestAllowed(isDisabled = false, isTestEnabled = true))
        assertFalse(mechanismHomingRequestAllowed(isDisabled = false, isTestEnabled = false))
    }

    @Test
    fun `unknown or suspicious enabled PDH current remains explicitly invalid`() {
        assertTrue(validatedPdhCurrent(Double.NaN, false).isNaN())
        assertTrue(validatedPdhCurrent(0.0, true).isNaN())
        assertTrue(validatedPdhCurrent(-1.0, true).isNaN())
        assertTrue(validatedPdhCurrent(35.0, true) == 35.0)
        assertTrue(validatedPdhCurrent(0.0, false) == 0.0)
    }

    @Test
    fun `marvin topology retains primary and member CAN identities`() {
        val topology = marvinCanTopology("Flywheel", 9, 9, 10, 11, 12)

        assertEquals("CAN2", topology.canBus)
        assertEquals(9, topology.canId)
        assertEquals("9,10,11,12", topology.metadata["canIds"])
        assertEquals(com.areslib.telemetry.schema.TopologyNodeType.CAN_MOTOR_CONTROLLER, topology.type)
    }

    @Test
    fun `mechanism safety health returns false whenever an update failure occurs`() {
        assertTrue(mechanismSafetyHealthy(configurationValid = true, homingValid = true, fatalUpdateFailure = null))
        assertFalse(mechanismSafetyHealthy(configurationValid = false, homingValid = true, fatalUpdateFailure = null))
        assertFalse(mechanismSafetyHealthy(configurationValid = true, homingValid = false, fatalUpdateFailure = null))
        assertFalse(mechanismSafetyHealthy(configurationValid = true, homingValid = true, fatalUpdateFailure = RuntimeException("CAN timeout")))
    }

    @Test
    fun `validated PDH current handles non-finite and zero boundaries correctly`() {
        assertTrue(validatedPdhCurrent(Double.POSITIVE_INFINITY, false).isNaN())
        assertTrue(validatedPdhCurrent(Double.NEGATIVE_INFINITY, true).isNaN())
        assertTrue(validatedPdhCurrent(0.0, false) == 0.0)
        assertTrue(validatedPdhCurrent(12.5, true) == 12.5)
        assertTrue(validatedPdhCurrent(0.0, true).isNaN())
    }
}
