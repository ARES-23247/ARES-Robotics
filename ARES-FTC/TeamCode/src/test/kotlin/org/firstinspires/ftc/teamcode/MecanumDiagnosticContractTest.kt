package org.firstinspires.ftc.teamcode

import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.firstinspires.ftc.teamcode.opmodes.mecanumDiagnosticMotorDefinitions
import org.junit.Assert.assertEquals
import org.junit.Test

class MecanumDiagnosticContractTest {
    @Test
    fun `diagnostic uses all four generated hardware names directions and stable controls`() {
        val definitions = mecanumDiagnosticMotorDefinitions()

        assertEquals(listOf("A / Cross", "B / Circle", "X / Square", "Y / Triangle"), definitions.map { it.control })
        assertEquals(
            listOf(
                GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FL.HARDWARE_ID,
                GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FR.HARDWARE_ID,
                GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RL.HARDWARE_ID,
                GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RR.HARDWARE_ID,
            ),
            definitions.map { it.hardwareMapName },
        )
        assertEquals(
            listOf(
                GeneratedAresFtcMecanumRuntimeConfig.frontLeftDirection,
                GeneratedAresFtcMecanumRuntimeConfig.frontRightDirection,
                GeneratedAresFtcMecanumRuntimeConfig.rearLeftDirection,
                GeneratedAresFtcMecanumRuntimeConfig.rearRightDirection,
            ),
            definitions.map { it.direction },
        )
        assertEquals(setOf("rl", "rr"), definitions.drop(2).map { it.hardwareMapName }.toSet())
    }
}
