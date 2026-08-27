package org.firstinspires.ftc.teamcode.config

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorSimple
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresTuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedDrivebaseRuntimeConfigTest {
    @Test
    fun `generated profile initializes hardware and Redux from one baseline`() {
        val tuning = GeneratedAresFtcMecanumRuntimeConfig.initialTuningState()

        assertEquals(GeneratedAresDrivebaseConfig.TRACK_WIDTH_METERS, tuning.drive.trackWidthMeters, 0.0)
        assertEquals(GeneratedAresDrivebaseConfig.WHEEL_BASE_METERS, tuning.drive.wheelBaseMeters, 0.0)
        assertEquals(GeneratedAresTuningConfig.Parameters.DRIVE_TICKSPERMETER, tuning.drive.ftc.ticksPerMeter, 0.0)
        assertEquals(GeneratedAresTuningConfig.Parameters.DRIVE_FEEDFORWARDKV, tuning.drive.driveFeedforward.kV, 0.0)
        assertEquals(DcMotorSimple.Direction.FORWARD, GeneratedAresFtcMecanumRuntimeConfig.frontLeftDirection)
        assertEquals(DcMotorSimple.Direction.REVERSE, GeneratedAresFtcMecanumRuntimeConfig.frontRightDirection)
        assertEquals(DcMotor.ZeroPowerBehavior.BRAKE, GeneratedAresFtcMecanumRuntimeConfig.driveZeroPowerBehavior)
        assertFalse(GeneratedAresTuningConfig.Parameters.DRIVE_CLOSEDLOOPVELOCITY)
        assertTrue(GeneratedAresTuningConfig.Parameters.LOCALIZATION_PINPOINTCCWPOSITIVE)
    }

    @Test
    fun `generated physical limits and safety contract stay explicit`() {
        assertTrue(GeneratedAresDrivebaseConfig.CURRENT_VALIDITY_REQUIRED)
        assertEquals("BRAKE", GeneratedAresDrivebaseConfig.ENABLED_NEUTRAL_MODE)
        assertEquals("FORCE_NEUTRAL_BRAKE", GeneratedAresDrivebaseConfig.DISABLED_POLICY)
        assertEquals(1.56739811912226, GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND, 0.0)
    }

    @Test
    fun `runtime updates reject constructor only and unknown parameters`() {
        assertTrue(GeneratedAresFtcMecanumRuntimeConfig.supportsRuntimeParameter("ftc.drive.heading.kp"))
        assertFalse(GeneratedAresFtcMecanumRuntimeConfig.supportsRuntimeParameter("ftc.drive.closed-loop-velocity"))
        assertFalse(GeneratedAresFtcMecanumRuntimeConfig.supportsRuntimeParameter("future.unmapped.parameter"))
    }
}
