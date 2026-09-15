package com.areslib.kinematics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WheelMathBoundaryAuditTest {
    @Test fun `short output buffers reject before mutation and valid tails remain untouched`() {
        val dd = DifferentialDriveKinematics(0.5)
        val mecanum = MecanumKinematics(0.5, 0.5)
        val short = doubleArrayOf(17.0)
        assertFailsWith<IllegalArgumentException> { dd.toWheelSpeeds(1.0, 1.0, short) }
        assertFailsWith<IllegalArgumentException> { DifferentialDriveKinematics.normalize(short, 1.0) }
        assertEquals(17.0, short[0])
        val small = doubleArrayOf(17.0, 18.0, 19.0)
        assertFailsWith<IllegalArgumentException> { mecanum.toWheelSpeeds(1.0, 1.0, 1.0, small) }
        assertFailsWith<IllegalArgumentException> { MecanumKinematics.normalize(small, 1.0) }
        assertEquals(listOf(17.0, 18.0, 19.0), small.toList())
        val output = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 99.0)
        mecanum.toWheelSpeeds(1.0, 0.5, 1.0, output)
        MecanumKinematics.normalize(output, 1.0)
        assertEquals(99.0, output[4])
        dd.toWheelSpeeds(1.0, 1.0, output)
        DifferentialDriveKinematics.normalize(output, 1.0)
        assertEquals(99.0, output[4])
    }

    @Test fun `normalization retains unscaled value identity and allows an unlimited positive bound`() {
        val dd = DifferentialWheelSpeeds(1.0, -0.5)
        val mecanum = MecanumWheelSpeeds(1.0, -0.5, 0.25, 0.0)
        assertSame(dd, dd.normalize(1.0))
        assertSame(mecanum, mecanum.normalize(Double.POSITIVE_INFINITY))
    }

    @Test fun `odd subnormal track width is not rounded before multiplying angular speed`() {
        val width = 3.0 * Double.MIN_VALUE
        val result = DoubleArray(2)
        DifferentialDriveKinematics(width).toWheelSpeeds(0.0, Double.MAX_VALUE, result)
        assertEquals((Double.MAX_VALUE * 0.5) * width, result[1])
    }

    @Test fun `mecanum rotation divides tiny separation before averaging underflows`() {
        val result = MecanumKinematics(Double.MIN_VALUE, Double.MIN_VALUE)
            .toChassisSpeeds(-Double.MIN_VALUE, 0.0, 0.0, 0.0)
        assertEquals(0.25, result.omegaRadiansPerSecond)
    }

    @Test fun `differential average preserves large equal speeds`() {
        val result = DifferentialDriveKinematics(1.0).toChassisSpeeds(Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(Double.MAX_VALUE, result.vxMetersPerSecond)
        assertEquals(0.0, result.omegaRadiansPerSecond)
    }

    @Test fun `differential rotation divides before an overflowing separation is lost`() {
        val result = DifferentialDriveKinematics(Double.MAX_VALUE).toChassisSpeeds(-Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(2.0, result.omegaRadiansPerSecond)
    }

    @Test fun `subnormal differential width preserves stationary and rotational results`() {
        val kinematics = DifferentialDriveKinematics(Double.MIN_VALUE)
        assertEquals(0.0, kinematics.toChassisSpeeds(0.0, 0.0).omegaRadiansPerSecond)
        val wheels = DoubleArray(2)
        kinematics.toWheelSpeeds(0.0, Double.MAX_VALUE, wheels)
        val expected = (Double.MAX_VALUE * 0.5) * Double.MIN_VALUE
        assertEquals(-expected, wheels[0])
        assertEquals(expected, wheels[1])
    }

    @Test fun `mecanum average preserves large equal speeds`() {
        val result = MecanumKinematics(1.0, 1.0).toChassisSpeeds(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(Double.MAX_VALUE, result.vxMetersPerSecond)
        assertEquals(0.0, result.vyMetersPerSecond)
        assertEquals(0.0, result.omegaRadiansPerSecond)
    }

    @Test fun `mecanum moment arm and rotation retain subnormal geometry`() {
        val kinematics = MecanumKinematics(Double.MIN_VALUE, Double.MIN_VALUE)
        assertEquals(Double.MIN_VALUE, kinematics.k)
        val result = kinematics.toChassisSpeeds(-Double.MIN_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_VALUE)
        assertEquals(1.0, result.omegaRadiansPerSecond)
    }

    @Test fun `differential normalization preserves a representable tiny limit`() {
        val wheels = DifferentialWheelSpeeds(1e200, -5e199).normalize(1e-200)
        assertEquals(1e-200, wheels.leftMetersPerSecond)
        assertEquals(-5e-201, wheels.rightMetersPerSecond)
        val buffer = doubleArrayOf(1e200, -5e199)
        DifferentialDriveKinematics.normalize(buffer, 1e-200)
        assertEquals(wheels.leftMetersPerSecond, buffer[0])
        assertEquals(wheels.rightMetersPerSecond, buffer[1])
    }

    @Test fun `mecanum normalization preserves a representable tiny limit`() {
        val wheels = MecanumWheelSpeeds(1e200, -5e199, 0.0, 1e199).normalize(1e-200)
        assertEquals(1e-200, wheels.frontLeftMetersPerSecond)
        assertEquals(-5e-201, wheels.frontRightMetersPerSecond)
        val buffer = doubleArrayOf(1e200, -5e199, 0.0, 1e199)
        MecanumKinematics.normalize(buffer, 1e-200)
        assertEquals(wheels.frontLeftMetersPerSecond, buffer[0])
        assertEquals(wheels.frontRightMetersPerSecond, buffer[1])
    }

    @Test fun `swerve normalization preserves a representable tiny limit`() {
        val states = arrayOf(SwerveModuleState(1e200), SwerveModuleState(-5e199))
        SwerveKinematics(emptyList()).desaturateWheelSpeeds(states, 1e-200)
        assertEquals(1e-200, states[0].speedMetersPerSecond)
        assertEquals(-5e-201, states[1].speedMetersPerSecond)
        assertTrue(states.all { it.angle.radians == 0.0 })
    }
}
