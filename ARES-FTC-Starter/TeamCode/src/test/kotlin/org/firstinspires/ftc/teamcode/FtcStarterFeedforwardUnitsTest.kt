package org.firstinspires.ftc.teamcode

import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresTuningConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class FtcStarterFeedforwardUnitsTest {
    @Test
    fun `starter nominal duty gain matches its declared simulation speed`() {
        val drive = GeneratedAresFtcMecanumRuntimeConfig.initialTuningState().drive
        assertEquals(GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND,
            1.0 / drive.driveFeedforward.kV, 1e-12)
        assertEquals(GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND,
            1.0 / drive.driveFeedforward.kV / ((drive.trackWidthMeters + drive.wheelBaseMeters) / 2.0), 1e-12)
    }

    @Test
    fun `starter describes normalized gains and bounds static effort to full duty`() {
        val parameters = GeneratedAresTuningConfig.metadata().declarations.associateBy { it.key }
        for ((key, unit) in mapOf("Ks" to "normalized", "Kv" to "normalized/(m/s)", "Ka" to "normalized/(m/s^2)")) {
            assertEquals(key, unit, parameters.getValue("drive.feedforward$key").unit)
        }
        assertEquals(1.0, parameters.getValue("drive.feedforwardKs").maximum!!, 0.0)
    }
}
