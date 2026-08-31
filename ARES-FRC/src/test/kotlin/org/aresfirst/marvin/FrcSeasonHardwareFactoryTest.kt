package org.aresfirst.marvin

import com.ctre.phoenix6.CANBus
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class FrcSeasonHardwareFactoryTest {
    @Test
    fun `simulation factory returns one coherent FRC-specific IO graph`() {
        val hardware = FrcSeasonHardwareFactory.create(
            isReal = false,
            fieldContract = null,
            canBus = CANBus("sim"),
        )
        val simulation = requireNotNull(hardware.simulation)

        assertNull(hardware.swerveIO)
        assertNull(hardware.visionIO)
        assertNull(hardware.powerDistribution)
        assertNotNull(hardware.dashboardDriveInput)
        assertSame(simulation.flywheelIO, hardware.flywheelIO)
        assertSame(simulation.cowlIO, hardware.cowlIO)
        assertSame(simulation.intakeIO, hardware.intakeIO)
        assertSame(simulation.feederIO, hardware.feederIO)
        assertSame(simulation.floorIO, hardware.floorIO)
        assertSame(simulation.climberIO, hardware.climberIO)
    }
}
