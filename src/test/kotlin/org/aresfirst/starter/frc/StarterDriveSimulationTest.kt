package org.aresfirst.starter.frc

import com.areslib.state.DriveState
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StarterDriveSimulationTest {
    @Test
    fun `field-relative command moves the educational robot deterministically`() {
        val simulation = StarterDriveSimulation(startX = 1.0, startY = 2.0)
        val state = RobotState(
            drive = DriveState(
                xVelocityMetersPerSecond = 2.0,
                yVelocityMetersPerSecond = -1.0,
                angularVelocityRadiansPerSecond = 0.5,
                isFieldCentric = true,
            )
        )

        val update = simulation.step(state, dtSeconds = 0.02, timestampMs = 20L)

        assertEquals(1.04, update.xMeters, 1e-9)
        assertEquals(1.98, update.yMeters, 1e-9)
        assertEquals(0.01, update.headingRadians, 1e-9)
        assertTrue(update.isExternalEstimate)
        assertTrue(update.motionMeasurementsValid)
    }

    @Test
    fun `physical output is fail closed until an adapter is installed`() {
        assertFalse(physicalDriveOutputsPermitted(isReal = true, adapterInstalled = false))
        assertTrue(physicalDriveOutputsPermitted(isReal = true, adapterInstalled = true))
        assertTrue(physicalDriveOutputsPermitted(isReal = false, adapterInstalled = false))
    }
}
