package com.areslib.kinematics

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WheelMathOracleAuditTest {
    private val precision = MathContext(80)
    private fun number(random: Random): Double = Math.scalb(random.nextDouble(1.0, 2.0), random.nextInt(-1074, 1024))
    private fun check(expected: Double, actual: Double) {
        if (expected.isInfinite()) assertEquals(expected, actual)
        else assertEquals(expected, actual, 6 * Math.ulp(expected))
    }

    @Test fun `coupled normalization agrees with exact ratios across binary scales`() {
        val random = Random(4301)
        val swerve = SwerveKinematics(emptyList())
        repeat(3_000) {
            val input = DoubleArray(4) { number(random) * if (random.nextBoolean()) 1.0 else -1.0 }
            val limit = number(random)
            val max = input.maxOf(::abs)
            val expected = DoubleArray(4) { i -> if (max <= limit) input[i] else
                BigDecimal(input[i]).multiply(BigDecimal(limit)).divide(BigDecimal(max), precision).toDouble() }
            val mutable = input.copyOf()
            MecanumKinematics.normalize(mutable, limit)
            val value = MecanumWheelSpeeds(input[0], input[1], input[2], input[3]).normalize(limit)
            val objectSpeeds = doubleArrayOf(value.frontLeftMetersPerSecond, value.frontRightMetersPerSecond, value.backLeftMetersPerSecond, value.backRightMetersPerSecond)
            val modules = Array(4) { i -> SwerveModuleState(input[i]) }
            swerve.desaturateWheelSpeeds(modules, limit)
            for (i in input.indices) {
                check(expected[i], mutable[i])
                check(expected[i], objectSpeeds[i])
                check(expected[i], modules[i].speedMetersPerSecond)
                assertTrue(abs(mutable[i]) <= limit)
            }
            val pairMax = maxOf(abs(input[0]), abs(input[1]))
            val pair = DifferentialWheelSpeeds(input[0], input[1]).normalize(limit)
            val pairBuffer = doubleArrayOf(input[0], input[1])
            DifferentialDriveKinematics.normalize(pairBuffer, limit)
            for (i in 0..1) {
                val oracle = if (pairMax <= limit) input[i] else BigDecimal(input[i]).multiply(BigDecimal(limit))
                    .divide(BigDecimal(pairMax), precision).toDouble()
                check(oracle, pairBuffer[i])
                check(oracle, if (i == 0) pair.leftMetersPerSecond else pair.rightMetersPerSecond)
            }
        }
    }

    @Test fun `forward kinematics agrees with exact means and differences across binary scales`() {
        val random = Random(4302)
        repeat(3_000) {
            val input = DoubleArray(4) { number(random) * if (random.nextBoolean()) 1.0 else -1.0 }
            val exact = input.map(::BigDecimal)
            val width = number(random)
            val dd = DifferentialDriveKinematics(width).toChassisSpeeds(input[0], input[1])
            check(exact[0].add(exact[1]).divide(BigDecimal(2)).toDouble(), dd.vxMetersPerSecond)
            check(exact[1].subtract(exact[0]).divide(BigDecimal(width), precision).toDouble(), dd.omegaRadiansPerSecond)
            val mecanum = MecanumKinematics(width, width).toChassisSpeeds(input[0], input[1], input[2], input[3])
            check(exact.reduce(BigDecimal::add).divide(BigDecimal(4)).toDouble(), mecanum.vxMetersPerSecond)
            check(exact[0].negate().add(exact[1]).add(exact[2]).subtract(exact[3]).divide(BigDecimal(4)).toDouble(), mecanum.vyMetersPerSecond)
            val moment = exact[0].negate().add(exact[1]).subtract(exact[2]).add(exact[3])
                .divide(BigDecimal(4)).divide(BigDecimal(width), precision).toDouble()
            check(moment, mecanum.omegaRadiansPerSecond)
        }
    }
}
