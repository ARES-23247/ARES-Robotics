package com.areslib.control.assist

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class ShotSetupTest {

    @Test
    fun `shot config rejects empty lookup tables`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShotConfig(
                shooterOffsetX = 0.0,
                shooterOffsetY = 0.0,
                tofKeys = doubleArrayOf(),
                tofValues = doubleArrayOf(),
                shotKeys = doubleArrayOf(),
                shotRpm = doubleArrayOf(),
                shotCowl = doubleArrayOf()
            )
        }
    }

    @Test
    fun `shot config rejects unsorted and nonfinite lookup data`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShotConfig(
                shooterOffsetX = 0.0,
                shooterOffsetY = 0.0,
                tofKeys = doubleArrayOf(2.0, 1.0),
                tofValues = doubleArrayOf(0.2, 0.1),
                shotKeys = doubleArrayOf(1.0),
                shotRpm = doubleArrayOf(Double.NaN),
                shotCowl = doubleArrayOf(1.0)
            )
        }
    }

    @Test
    fun `shot config rejects nonfinite lookup values`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShotConfig(
                shooterOffsetX = 0.0,
                shooterOffsetY = 0.0,
                tofKeys = doubleArrayOf(1.0),
                tofValues = doubleArrayOf(Double.POSITIVE_INFINITY),
                shotKeys = doubleArrayOf(1.0),
                shotRpm = doubleArrayOf(3000.0),
                shotCowl = doubleArrayOf(1.0)
            )
        }
    }

    /**
     * Test config matching the original Marvin 19 hardcoded values.
     * This ensures backward compatibility after parameterization.
     */
    private val testConfig = ShotConfig(
        shooterOffsetX = -0.044704,
        shooterOffsetY = -0.055626,
        tofKeys = doubleArrayOf(1.24, 2.0, 3.0, 4.0, 5.6),
        tofValues = doubleArrayOf(0.128, 0.212, 0.345, 0.481, 0.795),
        shotKeys = doubleArrayOf(
            1.24, 2.0, 2.2, 2.5, 3.0, 3.2, 3.4, 3.63, 3.80, 4.0, 4.2, 4.4, 4.6, 4.8, 5.0, 5.2, 5.4, 5.6
        ),
        shotRpm = doubleArrayOf(
            3350.0, 3400.0, 3450.0, 3500.0, 3550.0, 3600.0, 3650.0, 3700.0, 3750.0, 3800.0, 3850.0, 3900.0, 3950.0, 4000.0, 4050.0, 4100.0, 4150.0, 4200.0
        ),
        shotCowl = doubleArrayOf(
            0.50, 0.70, 0.80, 0.95, 1.10, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.45, 1.50, 1.55, 1.60, 1.65, 1.70, 1.75
        ),
        delayCompensationSeconds = 0.05,
        shooterFacesRearward = true
    )

    private val shotSetup = ShotSetup(testConfig)

    @Test
    fun `cowl lookup contract is mechanism rotations`() {
        assertEquals(0.50, shotSetup.interpolateCowlRotations(1.24), 1e-12)
        assertEquals(1.75, shotSetup.interpolateCowlRotations(5.6), 1e-12)

        val result = ShotResult()
        result.targetCowlAngleRotations = 1.25
        @Suppress("DEPRECATION")
        assertEquals(1.25, result.targetCowlAngleDegrees, 1e-12)
    }

    @Test
    fun testStaticShot() {
        val robotPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0))
        val speeds = ChassisSpeeds(0.0, 0.0, 0.0)
        val target = Translation2d(4.0, 0.0)
        
        val result = ShotResult()
        shotSetup.calculate(robotPose, speeds, target, result)
        
        // Since the shooter is offset by -0.044704 along X, -0.055626 along Y:
        // Compensated pos: X = 0.0, Y = 0.0, heading = 0.0
        // Shooter pos: X = -0.044704, Y = -0.055626
        // Target is at X = 4.0, Y = 0.0
        // Aim vector from shooter to target: dx = 4.044704, dy = 0.055626
        // Distance should be hypot(4.044704, 0.055626) = 4.0450865
        val expectedDistance = hypot(4.044704, 0.055626)
        assertEquals(expectedDistance, result.aimDistanceMeters, 1e-6)
        
        // Aim angle is atan2(0.055626, 4.044704)
        val expectedAimAngle = atan2(0.055626, 4.044704)
        assertEquals(expectedAimAngle, result.aimAngleRad, 1e-6)
        
        // Robot target heading should be expectedAimAngle + PI
        var expectedRobotHeading = expectedAimAngle + PI
        while (expectedRobotHeading > PI) expectedRobotHeading -= 2.0 * PI
        while (expectedRobotHeading < -PI) expectedRobotHeading += 2.0 * PI
        assertEquals(expectedRobotHeading, result.robotTargetHeadingRad, 1e-6)
        
        // Feedforward should be zero since speeds are zero
        assertEquals(0.0, result.angularVelocityFeedforwardRadPerSec, 1e-6)

        // Interpolation checks
        val expectedRpm = shotSetup.interpolateRpm(expectedDistance)
        assertEquals(expectedRpm, result.targetFlywheelRpm, 1e-6)
    }

    @Test
    fun testTranslatingShotCompensation() {
        val robotPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0))
        // Robot translating at 2.0 m/s in +Y (field-centric)
        val speeds = ChassisSpeeds(0.0, 2.0, 0.0)
        val target = Translation2d(4.0, 0.0)
        
        val result = ShotResult()
        shotSetup.calculate(robotPose, speeds, target, result)
        
        // Assert exact, physically correct SOTM lead positions and TOF values
        assertEquals(-0.987, result.virtualTargetY, 0.01)
        assertEquals(-0.252, result.aimAngleRad, 0.01)
        assertEquals(2.889, result.robotTargetHeadingRad, 0.01)
        
        // Since robot is translating +Y, the shooter's velocity is +Y (2.0 m/s).
        // During the time of flight, the projectile gets carried along +Y.
        // Therefore, we must aim in the -Y direction (aimAngleRad < 0) to compensate.
        // Virtual target Y should be negative to aim behind the target.
        assertTrue(result.virtualTargetY < 0.0, "Virtual target Y should be negative for +Y robot velocity")
        assertTrue(result.aimAngleRad < 0.0, "Aim angle should be negative to lead the target")
    }

    @Test
    fun testRotatingShotCompensation() {
        val robotPose = Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0))
        // Robot rotating at 1.0 rad/s
        val speeds = ChassisSpeeds(0.0, 0.0, 1.0)
        val target = Translation2d(4.0, 0.0)
        
        val result = ShotResult()
        shotSetup.calculate(robotPose, speeds, target, result)
        
        // Shooter is at (-0.25, 0). Under 1.0 rad/s counter-clockwise rotation,
        // it acquires a tangential velocity of omega * r = 1.0 * (-0.25) = -0.25 m/s along Y.
        // During the TOF, the projectile gets carried along -Y.
        // Therefore, we must aim in the +Y direction (aimAngleRad > 0) to compensate.
        assertTrue(result.virtualTargetY > 0.0, "Virtual target Y should be positive for negative shooter velocity")
        assertTrue(result.aimAngleRad > 0.0, "Aim angle should be positive to compensate")
    }
}
