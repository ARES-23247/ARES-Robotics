package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MecanumKinematicsTest {

    private val kinematics = MecanumKinematics(trackWidthMeters = 0.5, wheelBaseMeters = 0.5)

    @Test
    fun `forward ChassisSpeeds gives equal positive wheel speeds`() {
        val speeds = ChassisSpeeds(vxMetersPerSecond = 1.0, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 0.0)
        val wheels = kinematics.toWheelSpeeds(speeds)
        
        assertEquals(1.0, wheels.frontLeftMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.frontRightMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.backLeftMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.backRightMetersPerSecond, 0.001)
    }

    @Test
    fun `strafe left ChassisSpeeds gives alternating wheel speeds`() {
        val speeds = ChassisSpeeds(vxMetersPerSecond = 0.0, vyMetersPerSecond = 1.0, omegaRadiansPerSecond = 0.0)
        val wheels = kinematics.toWheelSpeeds(speeds)
        
        assertEquals(-1.0, wheels.frontLeftMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.frontRightMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.backLeftMetersPerSecond, 0.001)
        assertEquals(-1.0, wheels.backRightMetersPerSecond, 0.001)
    }
    
    @Test
    fun `normalize limits wheel speeds properly`() {
        val wheels = MecanumWheelSpeeds(2.0, 1.0, -2.0, 0.5)
        val normalized = wheels.normalize(1.0)
        
        assertEquals(1.0, normalized.frontLeftMetersPerSecond, 0.001)
        assertEquals(0.5, normalized.frontRightMetersPerSecond, 0.001)
        assertEquals(-1.0, normalized.backLeftMetersPerSecond, 0.001)
        assertEquals(0.25, normalized.backRightMetersPerSecond, 0.001)
    }

    @Test
    fun `normalize handles zero, negative, and NaN maxSpeed gracefully`() {
        val arr = doubleArrayOf(2.0, -1.0, 3.0, 4.0)
        MecanumKinematics.normalize(arr, 0.0)
        assertEquals(0.0, arr[0], 0.001)
        assertEquals(0.0, arr[1], 0.001)
        
        val arr2 = doubleArrayOf(2.0, -1.0, 3.0, 4.0)
        MecanumKinematics.normalize(arr2, -1.5)
        assertEquals(0.0, arr2[2], 0.001)
        
        val arr3 = doubleArrayOf(2.0, -1.0, 3.0, 4.0)
        MecanumKinematics.normalize(arr3, Double.NaN)
        assertEquals(0.0, arr3[3], 0.001)
    }

    @Test
    fun `pure rotation gives opposite side wheel speeds`() {
        val speeds = ChassisSpeeds(vxMetersPerSecond = 0.0, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 2.0)
        val wheels = kinematics.toWheelSpeeds(speeds)

        // For W=0.5, B=0.5: radius term = (0.5 + 0.5) / 2 = 0.5 -> 0.5 * 2.0 = 1.0 m/s
        assertEquals(-1.0, wheels.frontLeftMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.frontRightMetersPerSecond, 0.001)
        assertEquals(-1.0, wheels.backLeftMetersPerSecond, 0.001)
        assertEquals(1.0, wheels.backRightMetersPerSecond, 0.001)
    }

    @Test
    fun `primitive inverse and optimized forward kinematics round trip mixed motion`() {
        val wheels = DoubleArray(4)
        kinematics.toWheelSpeeds(vx = 1.25, vy = -0.4, omega = 0.75, outSpeeds = wheels)

        val chassis = kinematics.toChassisSpeeds(
            fl = wheels[0],
            fr = wheels[1],
            bl = wheels[2],
            br = wheels[3],
        )

        assertEquals(1.25, chassis.vxMetersPerSecond, 1e-12)
        assertEquals(-0.4, chassis.vyMetersPerSecond, 1e-12)
        assertEquals(0.75, chassis.omegaRadiansPerSecond, 1e-12)
    }

    @Test
    fun `wheel speeds within limit are not scaled down by normalize`() {
        val wheels = MecanumWheelSpeeds(0.5, -0.4, 0.3, -0.2)
        val normalized = wheels.normalize(1.0)

        assertEquals(0.5, normalized.frontLeftMetersPerSecond, 0.001)
        assertEquals(-0.4, normalized.frontRightMetersPerSecond, 0.001)
        assertEquals(0.3, normalized.backLeftMetersPerSecond, 0.001)
        assertEquals(-0.2, normalized.backRightMetersPerSecond, 0.001)
    }
}
