package org.aresfirst.starter.frc

import com.areslib.state.DriveState
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
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
        assertFalse(physicalOutputsPermitted(isReal = true, adapterInstalled = false))
        assertTrue(physicalOutputsPermitted(isReal = true, adapterInstalled = true))
        assertTrue(physicalOutputsPermitted(isReal = false, adapterInstalled = false))
    }

    @Test
    fun `canonical blocking obstacle stops chassis while preserving deterministic slide`() {
        val simulation = StarterDriveSimulation(startX = 1.0, startY = 1.0)
        simulation.configureField(
            RobotFieldConfig(
                id = "collision-field",
                fieldType = FieldType.FRC,
                widthMeters = 5.0,
                heightMeters = 5.0,
                obstacles = listOf(
                    RobotFieldObstacle(
                        id = "wall",
                        name = "Wall",
                        x = 2.0,
                        y = 1.0,
                        width = 0.2,
                        height = 2.0,
                        shape = "rectangle",
                    )
                ),
            )
        )
        val state = RobotState(
            drive = DriveState(
                xVelocityMetersPerSecond = 10.0,
                yVelocityMetersPerSecond = 1.0,
                isFieldCentric = true,
            )
        )

        simulation.step(state, dtSeconds = 0.05, timestampMs = 50L)
        val blocked = simulation.step(state, dtSeconds = 0.05, timestampMs = 100L)

        assertEquals(1.5, blocked.xMeters, 1e-9)
        assertEquals(1.1, blocked.yMeters, 1e-9)
    }

    @Test
    fun `canonical field boundary keeps full bumper footprint on field`() {
        val simulation = StarterDriveSimulation(startX = 4.9, startY = 4.9)
        simulation.configureField(
            RobotFieldConfig(
                id = "bounded-field",
                fieldType = FieldType.FRC,
                widthMeters = 5.0,
                heightMeters = 5.0,
            )
        )
        val state = RobotState(
            drive = DriveState(
                xVelocityMetersPerSecond = 2.0,
                yVelocityMetersPerSecond = 2.0,
                isFieldCentric = true,
            )
        )

        val update = simulation.step(state, dtSeconds = 0.05, timestampMs = 50L)

        assertEquals(4.625, update.xMeters, 1e-9)
        assertEquals(4.675, update.yMeters, 1e-9)
    }

    @Test
    fun `generated mechanisms stay simulated until physical adapters are permitted`() {
        var requestedPhysicalAdapters: Boolean? = null

        installGeneratedSubsystems(
            usePhysicalAdapters = false,
            hardwareRegistry = com.areslib.hardware.HardwareRegistry(),
            register = {},
            createAll = { physical, _ ->
                requestedPhysicalAdapters = physical
                emptyList()
            },
        )

        assertEquals(false, requestedPhysicalAdapters)
    }
}
