package com.areslib.frc

import com.areslib.frc.hardware.FrcMechanismConfigurationStatus
import com.areslib.frc.hardware.FrcMechanismHomingStatus
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.geometry.Pose2d
import com.areslib.state.DriveState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ARESRobotSafetyBoundaryTest {

    private class FakeSwerveIO : SwerveHardwareIO {
        val encoderPositions = doubleArrayOf(0.1, -0.2, 0.3, -0.4)
        var latencyMs = 0.0
        var encoderValid = true

        override fun read(): DriveState = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun getEncoderPositions(out: DoubleArray) {
            encoderPositions.copyInto(out)
        }
        override val encoderPositionsValid: Boolean
            get() = encoderValid
        override val signalLatencyMs: Double
            get() = latencyMs
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
    fun `mechanism configuration health fails closed on any reporting adapter`() {
        assertTrue(mechanismsConfigured(ConfigurationStatus(true)))
        assertFalse(mechanismsConfigured(ConfigurationStatus(true), ConfigurationStatus(false)))
        val resettable = ConfigurationStatus(true)
        assertTrue(mechanismsConfigured(resettable))
        resettable.valid = false
        assertFalse(mechanismsConfigured(resettable), "a post-startup reset must invalidate live health")
    }

    @Test
    fun `relative position mechanism health fails closed until every device is homed`() {
        assertTrue(mechanismsHomed(HomingStatus(true)))
        assertFalse(mechanismsHomed(HomingStatus(true), HomingStatus(false)))
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
