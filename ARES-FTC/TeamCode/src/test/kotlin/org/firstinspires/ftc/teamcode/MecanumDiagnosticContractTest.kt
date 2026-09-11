package org.firstinspires.ftc.teamcode

import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.firstinspires.ftc.teamcode.opmodes.mecanumDiagnosticMotorDefinitions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*
import com.qualcomm.robotcore.hardware.DcMotorEx
import org.firstinspires.ftc.teamcode.opmodes.ARESMecanumDiagnostic

class MecanumDiagnosticContractTest {
    @org.junit.Before fun fixedClock() = com.areslib.util.RobotClock.useMockTime(0L)
    @org.junit.After fun restoreClock() = com.areslib.util.RobotClock.useSystemTime()
    private fun fixture(): Pair<ARESMecanumDiagnostic, List<DcMotorEx>> {
        val mode = spy(ARESMecanumDiagnostic())
        mode.hardwareMap = mock(com.qualcomm.robotcore.hardware.HardwareMap::class.java)
        mode.telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        mode.gamepad1 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        val motors = mecanumDiagnosticMotorDefinitions().map { definition ->
            mock(DcMotorEx::class.java).also {
                `when`(mode.hardwareMap.get(DcMotorEx::class.java, definition.hardwareMapName)).thenReturn(it)
            }
        }
        doNothing().`when`(mode).waitForStart()
        doReturn(false).`when`(mode).isStopRequested
        doReturn(false).`when`(mode).opModeIsActive()
        return mode to motors
    }

    @Test fun `failed initial neutral write is retried during final cleanup`() {
        val (mode, motors) = fixture()
        doThrow(IllegalStateException("temporary write failure")).doNothing().`when`(motors[0]).power = 0.0
        mode.runOpMode()
        motors.forEach { verify(it, times(2)).power = 0.0 }
        verify(mode, never()).opModeIsActive()
    }

    @Test fun `configuration failure retains motor and cleanup continues after another failure`() {
        val (mode, motors) = fixture()
        doThrow(IllegalStateException("direction failed")).`when`(motors[1]).direction = mecanumDiagnosticMotorDefinitions()[1].direction
        doNothing().doThrow(IllegalStateException("neutral failed")).`when`(motors[0]).power = 0.0
        mode.runOpMode()
        motors.forEach { verify(it, times(2)).power = 0.0 }
        verify(mode, never()).opModeIsActive()
    }

    @Test fun `held button commands only its motor and exit neutralizes all motors`() {
        val (mode, motors) = fixture()
        mode.gamepad1.a = true
        doReturn(true, false).`when`(mode).opModeIsActive()
        doNothing().`when`(mode).sleep(20L)
        mode.runOpMode()
        verify(motors[0]).power = 0.4
        motors.drop(1).forEach { verify(it, never()).power = 0.4 }
        motors.forEach {
            val writes = mockingDetails(it).invocations.filter { call -> call.method.name == "setPower" }
            assertEquals(0.0, writes.last().arguments[0])
        }
        verify(mode.telemetry, times(2)).update()
    }

    @Test fun `missing motor blocks active loop and neutralizes all discovered motors`() {
        val (mode, motors) = fixture()
        `when`(mode.hardwareMap.get(DcMotorEx::class.java, mecanumDiagnosticMotorDefinitions()[2].hardwareMapName))
            .thenThrow(IllegalArgumentException("missing"))
        mode.runOpMode()
        verify(mode, never()).opModeIsActive()
        motors.filterIndexed { index, _ -> index != 2 }.forEach { verify(it, times(2)).power = 0.0 }
        verifyNoInteractions(motors[2])
    }

    @Test fun `active write failure still neutralizes all discovered motors`() {
        val (mode, motors) = fixture()
        val failure = IllegalStateException("active write failed")
        mode.gamepad1.a = true
        doReturn(true).`when`(mode).opModeIsActive()
        doThrow(failure).`when`(motors[0]).power = 0.4
        org.junit.Assert.assertSame(failure, runCatching { mode.runOpMode() }.exceptionOrNull())
        motors.forEach { verify(it, times(2)).power = 0.0 }
    }
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
