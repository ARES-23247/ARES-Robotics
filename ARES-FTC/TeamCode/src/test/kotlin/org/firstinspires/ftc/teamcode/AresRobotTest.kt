package org.firstinspires.ftc.teamcode

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Focused contract tests for generated canonical drivebase configuration.
 */
class AresRobotTest {
    @Test
    fun testCanonicalDrivebaseConfiguration() {
        assertEquals("fl", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FL.HARDWARE_ID)
        assertEquals("fr", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FR.HARDWARE_ID)
        assertEquals("rl", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RL.HARDWARE_ID)
        assertEquals("rr", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RR.HARDWARE_ID)
        assertEquals("pinpoint", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_LOCALIZATION_PINPOINT.HARDWARE_ID)
        assertEquals("imu", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_LOCALIZATION_IMU.HARDWARE_ID)
        assertEquals("limelight", org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig.Components.FTC_LOCALIZATION_LIMELIGHT.HARDWARE_ID)
    }
}
