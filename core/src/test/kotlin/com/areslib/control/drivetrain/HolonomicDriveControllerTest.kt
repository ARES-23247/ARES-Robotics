package com.areslib.control.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.PathPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HolonomicDriveControllerTest {

    @Test
    fun `calculate outputs zero speeds when at target`() {
        val xController = PIDController(1.0, 0.0, 0.0)
        val yController = PIDController(1.0, 0.0, 0.0)
        val thetaController = PIDController(1.0, 0.0, 0.0)
        val controller = HolonomicDriveController(xController, yController, thetaController)
        
        val targetPose = Pose2d(1.0, 1.0, Rotation2d.fromDegrees(0.0))
        
        val output = controller.calculate(
            currentPose = Pose2d(1.0, 1.0, Rotation2d.fromDegrees(0.0)),
            targetPose = targetPose,
            targetVelocityMps = 0.0,
            targetHeading = Rotation2d.fromDegrees(0.0),
            dtSeconds = 0.02
        )
        
        assertEquals(0.0, output.vxMetersPerSecond, 0.001)
        assertEquals(0.0, output.vyMetersPerSecond, 0.001)
        assertEquals(0.0, output.omegaRadiansPerSecond, 0.001)
    }
    
    @Test
    fun `calculate outputs positive x when behind target`() {
        val xController = PIDController(1.0, 0.0, 0.0)
        val yController = PIDController(1.0, 0.0, 0.0)
        val thetaController = PIDController(1.0, 0.0, 0.0)
        val controller = HolonomicDriveController(xController, yController, thetaController)
        
        val targetPose = Pose2d(1.0, 0.0, Rotation2d.fromDegrees(0.0))
        
        val output = controller.calculate(
            currentPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0)),
            targetPose = targetPose,
            targetVelocityMps = 0.0,
            targetHeading = Rotation2d.fromDegrees(0.0),
            dtSeconds = 0.02
        )
        
        assertEquals(1.0, output.vxMetersPerSecond, 0.001)
        assertEquals(0.0, output.vyMetersPerSecond, 0.001)
        assertEquals(0.0, output.omegaRadiansPerSecond, 0.001)
    }

    @Test
    fun `calculate outputs negative y when above target`() {
        val xController = PIDController(1.0, 0.0, 0.0)
        val yController = PIDController(1.0, 0.0, 0.0)
        val thetaController = PIDController(1.0, 0.0, 0.0)
        val controller = HolonomicDriveController(xController, yController, thetaController)

        val targetPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0))

        val output = controller.calculate(
            currentPose = Pose2d(0.0, 1.0, Rotation2d.fromDegrees(0.0)),
            targetPose = targetPose,
            targetVelocityMps = 0.0,
            targetHeading = Rotation2d.fromDegrees(0.0),
            dtSeconds = 0.02
        )

        assertEquals(0.0, output.vxMetersPerSecond, 0.001)
        assertEquals(-1.0, output.vyMetersPerSecond, 0.001)
        assertEquals(0.0, output.omegaRadiansPerSecond, 0.001)
    }

    @Test
    fun `test centripetal limiting caps target feedforward in curves`() {
        val xController = PIDController(0.0, 0.0, 0.0) // zero feedback to isolate feedforward
        val yController = PIDController(0.0, 0.0, 0.0)
        val thetaController = PIDController(0.0, 0.0, 0.0)
        val controller = HolonomicDriveController(xController, yController, thetaController)

        val targetPose = Pose2d(5.0, 0.0, Rotation2d.fromDegrees(0.0))
        val currentPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0))

        // No curvature -> should output full feedforward
        val outputNoCurve = controller.calculate(
            currentPose = currentPose,
            targetPose = targetPose,
            targetVelocityMps = 4.0,
            targetHeading = Rotation2d.fromDegrees(0.0),
            dtSeconds = 0.02,
            curvature = 0.0
        )
        assertEquals(4.0, outputNoCurve.vxMetersPerSecond, 0.001)

        // Curve curvature = 2.0, maxCentripetalAccel = 2.0 -> cap is sqrt(2.0/2.0) = 1.0 m/s
        val outputWithCurve = controller.calculate(
            currentPose = currentPose,
            targetPose = targetPose,
            targetVelocityMps = 4.0,
            targetHeading = Rotation2d.fromDegrees(0.0),
            dtSeconds = 0.02,
            curvature = 2.0,
            maxCentripetalAccel = 2.0
        )
        // Since feedback is zero, the output speed is strictly the feedforward cap
        assertEquals(1.0, outputWithCurve.vxMetersPerSecond, 0.001)
    }

    @Test
    fun `invalid timestep returns zero chassis command`() {
        val controller = HolonomicDriveController(
            PIDController(1.0, 0.0, 0.0),
            PIDController(1.0, 0.0, 0.0),
            PIDController(1.0, 0.0, 0.0)
        )

        val output = controller.calculateDirect(
            currentX = 0.0,
            currentY = 0.0,
            currentHeadingRad = 0.0,
            targetX = 1.0,
            targetY = 1.0,
            targetHeadingRad = 0.5,
            targetVelocityMps = 2.0,
            dtSeconds = Double.NaN
        )

        assertEquals(0.0, output.vxMetersPerSecond)
        assertEquals(0.0, output.vyMetersPerSecond)
        assertEquals(0.0, output.omegaRadiansPerSecond)
    }
}
