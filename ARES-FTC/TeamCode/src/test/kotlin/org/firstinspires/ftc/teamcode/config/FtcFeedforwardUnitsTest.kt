package org.firstinspires.ftc.teamcode.config

import com.areslib.ftc.drivetrain.MecanumDriveFeedforward
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresTuningConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class FtcFeedforwardUnitsTest {
    @Test
    fun `authored gain units describe the controller nominal duty convention`() {
        val parameters = GeneratedAresTuningConfig.metadata().declarations.associateBy { it.key }
        for ((key, unit) in mapOf("Ks" to "normalized", "Kv" to "normalized/(m/s)", "Ka" to "normalized/(m/s^2)")) {
            assertEquals(key, unit, parameters.getValue("drive.feedforward$key").unit)
        }
        assertEquals(1.0, parameters.getValue("drive.feedforwardKs").maximum!!, 0.0)
    }

    @Test
    fun `generated season gains command equal nominal voltage at different battery voltages`() {
        val gains = GeneratedAresFtcMecanumRuntimeConfig.initialTuningState().drive.driveFeedforward
        // Steady 0.5 m/s: 0.638 * 0.5 + 0.05 = 0.369 duty at 12 V = 4.428 V.
        // Use the second sample to exclude acceleration feedforward from this steady-state check.
        for (battery in listOf(12.0, 9.0)) {
            val controller = MecanumDriveFeedforward(gains.kS).apply { kV = gains.kV; kA = gains.kA }
            val output = DoubleArray(4)
            repeat(2) {
                controller.calculateMotorPowers(DoubleArray(4) { 0.5 }, 2.0, battery, 1.0,
                    false, 2000.0, 0.0, 0.0, 0.0, 0.0, output)
            }
            output.forEach { assertEquals(4.428, it * battery, 1e-12) }
        }
    }

    @Test
    fun `season geometry speed limits agree with normalized velocity gain`() {
        val drive = GeneratedAresFtcMecanumRuntimeConfig.initialTuningState().drive
        val speed = 1.0 / drive.driveFeedforward.kV
        assertEquals(GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND, speed, 1e-12)
        assertEquals(GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND,
            speed / ((drive.trackWidthMeters + drive.wheelBaseMeters) / 2.0), 1e-12)
    }
}
