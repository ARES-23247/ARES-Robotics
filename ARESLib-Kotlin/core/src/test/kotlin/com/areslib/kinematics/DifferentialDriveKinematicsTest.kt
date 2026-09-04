package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DifferentialDriveKinematicsTest {

    private val trackWidth = 0.155 // XRP standard 155mm track width
    private val kinematics = DifferentialDriveKinematics(trackWidthMeters = trackWidth)

    @Test
    fun `forward ChassisSpeeds gives equal positive wheel speeds`() {
        val speeds = ChassisSpeeds(vxMetersPerSecond = 0.5, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 0.0)
        val wheels = kinematics.toWheelSpeeds(speeds)

        assertEquals(0.5, wheels.leftMetersPerSecond, 1e-9)
        assertEquals(0.5, wheels.rightMetersPerSecond, 1e-9)
    }

    @Test
    fun `pure rotation CCW gives left negative and right positive wheel speeds`() {
        // Turning left (CCW, omega > 0): left wheel goes backward, right wheel goes forward
        val speeds = ChassisSpeeds(vxMetersPerSecond = 0.0, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 2.0)
        val wheels = kinematics.toWheelSpeeds(speeds)

        val expectedSpeed = 2.0 * (trackWidth / 2.0) // 2.0 * 0.0775 = 0.155 m/s
        assertEquals(-expectedSpeed, wheels.leftMetersPerSecond, 1e-9)
        assertEquals(expectedSpeed, wheels.rightMetersPerSecond, 1e-9)
    }

    @Test
    fun `pure rotation CW gives left positive and right negative wheel speeds`() {
        // Turning right (CW, omega < 0): left wheel goes forward, right wheel goes backward
        val speeds = ChassisSpeeds(vxMetersPerSecond = 0.0, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = -2.0)
        val wheels = kinematics.toWheelSpeeds(speeds)

        val expectedSpeed = 2.0 * (trackWidth / 2.0)
        assertEquals(expectedSpeed, wheels.leftMetersPerSecond, 1e-9)
        assertEquals(-expectedSpeed, wheels.rightMetersPerSecond, 1e-9)
    }

    @Test
    fun `forward kinematics recovers chassis speeds from wheel speeds`() {
        val wheels = DifferentialWheelSpeeds(leftMetersPerSecond = 0.2, rightMetersPerSecond = 0.6)
        val chassis = kinematics.toChassisSpeeds(wheels)

        assertEquals(0.4, chassis.vxMetersPerSecond, 1e-9)
        assertEquals(0.0, chassis.vyMetersPerSecond, 1e-9)
        val expectedOmega = (0.6 - 0.2) / trackWidth
        assertEquals(expectedOmega, chassis.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `primitive zero-GC overloads match object overloads`() {
        val speeds = ChassisSpeeds(vxMetersPerSecond = 0.35, vyMetersPerSecond = 0.0, omegaRadiansPerSecond = 1.2)
        val objectResult = kinematics.toWheelSpeeds(speeds)

        val buffer1 = DoubleArray(2)
        kinematics.toWheelSpeeds(speeds, buffer1)
        assertEquals(objectResult.leftMetersPerSecond, buffer1[0], 1e-9)
        assertEquals(objectResult.rightMetersPerSecond, buffer1[1], 1e-9)

        val buffer2 = DoubleArray(2)
        kinematics.toWheelSpeeds(vx = 0.35, omega = 1.2, outSpeeds = buffer2)
        assertEquals(objectResult.leftMetersPerSecond, buffer2[0], 1e-9)
        assertEquals(objectResult.rightMetersPerSecond, buffer2[1], 1e-9)
    }

    @Test
    fun `round trip inverse and forward kinematics preserves mixed motion`() {
        val originalVx = 0.42
        val originalOmega = -1.8
        val buffer = DoubleArray(2)
        kinematics.toWheelSpeeds(vx = originalVx, omega = originalOmega, outSpeeds = buffer)

        val recovered = kinematics.toChassisSpeeds(leftMetersPerSecond = buffer[0], rightMetersPerSecond = buffer[1])
        assertEquals(originalVx, recovered.vxMetersPerSecond, 1e-9)
        assertEquals(0.0, recovered.vyMetersPerSecond, 1e-9)
        assertEquals(originalOmega, recovered.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `normalize limits wheel speeds while preserving turn curvature`() {
        val wheels = DifferentialWheelSpeeds(leftMetersPerSecond = 2.0, rightMetersPerSecond = 1.0)
        val normalized = wheels.normalize(maxSpeedMetersPerSecond = 1.0)

        assertEquals(1.0, normalized.leftMetersPerSecond, 1e-9)
        assertEquals(0.5, normalized.rightMetersPerSecond, 1e-9)
    }

    @Test
    fun `primitive normalize handles zero, negative, and NaN bounds gracefully`() {
        val arr = doubleArrayOf(1.5, -0.5)
        DifferentialDriveKinematics.normalize(arr, 0.0)
        assertEquals(0.0, arr[0], 1e-9)
        assertEquals(0.0, arr[1], 1e-9)

        val arr2 = doubleArrayOf(1.5, -0.5)
        DifferentialDriveKinematics.normalize(arr2, -2.0)
        assertEquals(0.0, arr2[0], 1e-9)

        val arr3 = doubleArrayOf(1.5, -0.5)
        DifferentialDriveKinematics.normalize(arr3, Double.NaN)
        assertEquals(0.0, arr3[0], 1e-9)
    }

    @Test
    fun `constructor rejects non-positive track width`() {
        assertThrows<IllegalArgumentException> {
            DifferentialDriveKinematics(trackWidthMeters = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            DifferentialDriveKinematics(trackWidthMeters = -0.15)
        }
    }
}
