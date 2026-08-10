package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SwerveKinematicsTest {

    @Test
    fun `test forward chassis speeds maps to identical module speeds`() {
        val kinematics = SwerveKinematics(
            Translation2d(0.5, 0.5),   // FL
            Translation2d(0.5, -0.5),  // FR
            Translation2d(-0.5, 0.5),  // BL
            Translation2d(-0.5, -0.5)  // BR
        )
        
        val speeds = ChassisSpeeds(vxMetersPerSecond = 2.0, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 0.0)
        
        val moduleStates = kinematics.toSwerveModuleStates(speeds)
        
        assertEquals(4, moduleStates.size)
        
        for (state in moduleStates) {
            assertEquals(2.0, state.speedMetersPerSecond, 0.001)
            assertEquals(0.0, state.angle.radians, 0.001)
        }
    }

    @Test
    fun `test swerve kinematics rate limiting caps acceleration and steering steps`() {
        val FL = Translation2d(0.5, 0.5)
        val FR = Translation2d(0.5, -0.5)
        val BL = Translation2d(-0.5, 0.5)
        val BR = Translation2d(-0.5, -0.5)

        val kinematics = SwerveKinematics(
            moduleTranslations = listOf(FL, FR, BL, BR),
            maxSteerVelRadPerSec = Math.PI,          // max angle change of 0.1 * PI = 0.314 rad at dt = 0.1s
            maxSteerAccelRadPerSec2 = Math.PI * 2.0, // max velocity change of 0.2 * PI at dt = 0.1s
            maxDriveAccelMps2 = 4.0                  // max speed change of 0.4 m/s at dt = 0.1s
        )

        // Initialize state at stopped
        kinematics.toSwerveModuleStates(ChassisSpeeds(0.0, 0.0, 0.0), dtSeconds = 0.1)

        // Command sudden high forward speed and angle (via vy = 5.0, vx = 5.0)
        val command = ChassisSpeeds(vxMetersPerSecond = 5.0, vyMetersPerSecond = 5.0, omegaRadiansPerSecond = 0.0)
        val limitedStates1 = kinematics.toSwerveModuleStates(command, dtSeconds = 0.1)

        // Verify drive speed is limited: initial was 0, target is hypot(5,5) = 7.07. 
        // Max change = 4.0 m/s2 * 0.1s = 0.4 m/s.
        // So speed should be limited to ~0.4 m/s.
        for (state in limitedStates1) {
            assertEquals(0.4, state.speedMetersPerSecond, 0.001)
            // Target angle is atan2(5, 5) = PI/4 = 0.785 rad.
            // Max angle change based on steer velocity = PI * 0.1 = 0.314 rad.
            // Since max steer accel is PI * 2, max speed is PI * 2 * 0.1 = PI/5.
            // The steering velocity will ramp from 0.0 up to a limited velocity.
            // In any case, final angle must be strictly less than 0.785 rad.
            kotlin.test.assertTrue(state.angle.radians < 0.785)
            kotlin.test.assertTrue(state.angle.radians > 0.0)
        }
    }
}
