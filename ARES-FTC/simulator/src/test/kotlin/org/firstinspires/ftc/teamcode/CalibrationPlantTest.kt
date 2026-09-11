package org.firstinspires.ftc.teamcode

import com.areslib.hardware.actuator.FlywheelIO
import org.junit.Assert.*
import org.junit.Test

class CalibrationPlantTest {
    private fun plant(): FlywheelIO = Class.forName("org.firstinspires.ftc.teamcode.CalibrationTestOpMode\$SimulatedFlywheelIO")
        .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as FlywheelIO

    @Test fun `voltage response saturates and safe coasts without resetting sensor state`() {
        val io = plant()
        io.setAppliedVoltage(20.0)
        io.refresh()
        assertEquals(403.2, io.velocityRpm, 1e-9)
        io.safe()
        io.refresh()
        assertEquals(403.2 * 0.92, io.velocityRpm, 1e-9)
        assertTrue(io.velocityValid)
    }

    @Test fun `invalid voltage commands neutralize without poisoning subsequent samples`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val io = plant()
            io.setAppliedVoltage(6.0)
            io.refresh()
            val prior = io.velocityRpm
            io.setAppliedVoltage(invalid)
            io.refresh()
            assertEquals(prior * 0.92, io.velocityRpm, 1e-9)
            assertTrue(io.velocityValid)
            io.setAppliedVoltage(6.0)
            io.refresh()
            assertTrue(io.velocityRpm > prior * 0.92)
        }
    }

    @Test fun `velocity mode respects bounded effort and rejects invalid requests`() {
        val limited = plant()
        limited.setVelocityRpm(5040.0, 0.25)
        limited.refresh()
        assertEquals(100.8, limited.velocityRpm, 1e-9)
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val io = plant()
            io.setVelocityRpm(5040.0, scale)
            io.refresh()
            assertEquals(0.0, io.velocityRpm, 0.0)
        }
        for (rpm in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val io = plant()
            io.setVelocityRpm(rpm, 1.0)
            io.refresh()
            assertEquals(0.0, io.velocityRpm, 0.0)
        }
    }
}
