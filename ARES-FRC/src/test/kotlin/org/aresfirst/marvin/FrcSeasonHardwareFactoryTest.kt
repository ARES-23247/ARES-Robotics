package org.aresfirst.marvin

import com.ctre.phoenix6.CANBus
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import com.areslib.state.RobotFieldConfig
import edu.wpi.first.apriltag.AprilTagFieldLayout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class FrcSeasonHardwareFactoryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `simulation factory returns one coherent FRC-specific IO graph`(configured: Boolean) {
        val contract = if (configured) loadFrcFieldContract(java.io.File("src/main/deploy/paths/field.json").readBytes()) else null
        if (configured) assertNotNull(contract)
        val hardware = FrcSeasonHardwareFactory.create(
            isReal = false,
            fieldContract = contract,
            canBus = CANBus("sim"),
        )
        try {
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
        } finally {
            // These are the two factory owners; mechanism IO aliases belong to simulation.
            try { hardware.dashboardDriveInput?.close() } finally { hardware.simulation?.close() }
        }
    }

    @Test
    fun `configured construction failure propagates rather than returning a partial graph`() {
        val invalid = FrcFieldContract(
            RobotFieldConfig(widthMeters = Double.POSITIVE_INFINITY),
            AprilTagFieldLayout(emptyList(), 16.54175, 8.21055),
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FrcSeasonHardwareFactory.create(false, invalid, CANBus("sim"))
        }
        assertEquals("Field width must be finite and positive", failure.message)
    }
}
