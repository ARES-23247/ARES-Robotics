package com.areslib.ftc

import com.areslib.ftc.drivetrain.SwerveModuleIOFtc
import com.areslib.hardware.drive.SwerveModuleInputs
import com.qualcomm.robotcore.hardware.AnalogInput
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwerveModuleIOFtcTest {
    @Test
    fun `nonfinite commands fail closed and absolute encoder exposes validity`() {
        val drive = MockDcMotorEx()
        val steer = MockDcMotorEx()
        val analog = MutableAnalogInput(Double.NaN)
        val io = SwerveModuleIOFtc(drive, steer, analog)
        val inputs = SwerveModuleInputs()

        try {
            io.setDesiredPower(Double.NaN, Double.POSITIVE_INFINITY)
            assertEquals(0.0, drive.currentPower, 1e-9)
            assertEquals(0.0, steer.currentPower, 1e-9)

            waitForSample(io, inputs) { !it.steerAbsoluteValid }
            assertFalse(inputs.steerAbsoluteValid)
            assertTrue(inputs.steerAbsolutePositionRads.isFinite())

            analog.value = 1.65
            waitForSample(io, inputs) { it.steerAbsoluteValid }
            assertTrue(inputs.steerAbsoluteValid)
            assertEquals(Math.PI, inputs.steerAbsolutePositionRads, 1e-6)
        } finally {
            io.close()
        }
    }

    private fun waitForSample(
        io: SwerveModuleIOFtc,
        inputs: SwerveModuleInputs,
        predicate: (SwerveModuleInputs) -> Boolean
    ) {
        repeat(200) {
            io.updateInputs(inputs)
            if (predicate(inputs)) return
            Thread.sleep(1L)
        }
        error("Timed out waiting for swerve analog sample")
    }

    private class MutableAnalogInput(@Volatile var value: Double) : AnalogInput() {
        override val voltage: Double
            get() = value
    }
}
