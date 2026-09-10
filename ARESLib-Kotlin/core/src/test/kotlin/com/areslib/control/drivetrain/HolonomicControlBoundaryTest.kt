package com.areslib.control.drivetrain

import com.areslib.control.feedback.LinearADRC
import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HolonomicControlBoundaryTest {
    private fun pid() = PIDController(0.0, 0.0, 0.0)
    private fun controller(x: PIDController = pid(), adrc: LinearADRC? = null, max: Double = 4.0) =
        HolonomicDriveController(x, pid(), pid(), maxOutputMps = max, xAdrc = adrc)

    private fun command(controller: HolonomicDriveController, x: Double = 0.0,
        target: Double = 1.0, dt: Double = 0.02, velocity: Double = 1.0) =
        controller.calculateDirect(x, 0.0, 0.0, target, 0.0, 0.0, velocity, dt, 0.0)

    @Test fun `object overload rejects invalid raw current and target heading`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(ChassisSpeeds(), controller().calculate(Pose2d(0.0, 0.0, Rotation2d(bad)),
                Pose2d(), 1.0, Rotation2d(), 0.02, 0.0))
            assertEquals(ChassisSpeeds(), controller().calculate(Pose2d(), Pose2d(),
                1.0, Rotation2d(bad), 0.02, 0.0))
        }
    }

    @Test fun `invalid PID configuration cannot be mistaken for valid zero feedback`() {
        val faults: List<(PIDController) -> Unit> = listOf(
            { it.p = Double.NaN }, { it.i = Double.POSITIVE_INFINITY },
            { it.d = Double.NaN }, { it.deadzone = -1.0 },
            { it.setOutputLimits(2.0, 1.0) }, { it.setIntegratorRange(1.0, -1.0) },
            { it.enableContinuousInput(0.0, 0.0) })
        for (fault in faults) {
            val pid = pid().also(fault)
            assertEquals(ChassisSpeeds(), command(controller(pid)))
        }
    }

    @Test fun `PID arithmetic failure neutralizes feedforward and other axes`() {
        assertEquals(ChassisSpeeds(), command(controller(PIDController(Double.MAX_VALUE, 0.0, 0.0)), target = 2.0))
    }

    @Test fun `invalid frame clears integral history before recovery`() {
        val drive = controller(PIDController(0.0, 1.0, 0.0))
        assertEquals(1.0, command(drive, dt = 1.0, velocity = 0.0).vxMetersPerSecond)
        assertEquals(ChassisSpeeds(), command(drive, dt = Double.NaN))
        assertEquals(0.0, command(drive, target = 0.0, velocity = 0.0).vxMetersPerSecond)
    }

    @Test fun `overflowing position difference rejects whole command`() {
        assertEquals(ChassisSpeeds(), command(controller(), x = -Double.MAX_VALUE, target = Double.MAX_VALUE))
    }

    @Test fun `curve speed limit does not underflow its squared ratio`() {
        val acceleration = 1e-300
        val curvature = 1e100
        val result = controller().calculateDirect(0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
            1.0, 0.02, 0.0, curvature, acceleration)
        val expected = sqrt(acceleration) / sqrt(curvature)
        assertTrue(result.vxMetersPerSecond > 0.0)
        assertEquals(expected, result.vxMetersPerSecond, expected * 1e-14)
    }

    @Test fun `large finite heading agrees with the object overload`() {
        val h = 1e100
        val direct = controller().calculateDirect(0.0, 0.0, h, 0.0, 0.0, h, 1.0, 0.02, 0.0)
        val objects = controller().calculate(Pose2d(0.0, 0.0, Rotation2d(h)), Pose2d(), 1.0, Rotation2d(h), 0.02, 0.0)
        assertEquals(objects, direct)
    }

    @Test fun `large finite translation clamp preserves its direction`() {
        val result = controller(PIDController(Double.MAX_VALUE, 0.0, 0.0)).calculateDirect(
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.MAX_VALUE, 0.02, 0.0)
        // Unrepresentable feedback-plus-feedforward is a failed frame, never NaN output.
        assertEquals(ChassisSpeeds(), result)
        val diagonal = HolonomicDriveController(PIDController(1e308, 0.0, 0.0),
            PIDController(1e308, 0.0, 0.0), pid()).calculateDirect(
            0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.02)
        assertEquals(4.0 / sqrt(2.0), diagonal.vxMetersPerSecond, 1e-14)
        assertEquals(diagonal.vxMetersPerSecond, diagonal.vyMetersPerSecond)
    }

    @Test fun `invalid ADRC configuration cannot pass feedforward`() {
        for (adrc in listOf(LinearADRC(Double.NaN, 1.0, 1.0), LinearADRC(0.0, 1.0, 1.0),
            LinearADRC(1.0, 1.0, Double.MAX_VALUE),
            LinearADRC(1.0, 1.0, 1.0).apply { setOutputLimits(2.0, 1.0) },
            LinearADRC(1.0, 1.0, 1.0).apply { enableContinuousInput(0.0, 0.0) })) {
            assertEquals(ChassisSpeeds(), command(controller(adrc = adrc)))
        }
    }

    @Test fun `valid ADRC override ignores unused invalid PID`() {
        val result = command(controller(PIDController(Double.NaN, 0.0, 0.0), LinearADRC(1.0, 0.0, 0.0)))
        assertEquals(1.0, result.vxMetersPerSecond)
    }
}
