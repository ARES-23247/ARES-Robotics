package com.areslib.xrp.hardware

import com.areslib.math.geometry.ChassisSpeeds
import java.math.BigDecimal
import java.math.MathContext
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrpMecanumNumericalAuditTest {
    @Test fun `mixed commands match an independent high precision four wheel power oracle`() {
        val random = Random(4401)
        val precision = MathContext(80)
        repeat(2_000) {
            val width = 0.05 + random.nextDouble() * 0.75
            val length = 0.05 + random.nextDouble() * 0.90
            val vx = random.nextDouble() * 8.0 - 4.0
            val vy = random.nextDouble() * 8.0 - 4.0
            val omega = random.nextDouble() * 20.0 - 10.0
            val limit = 0.01 + random.nextDouble() * 1.99
            val k = (BigDecimal(width) + BigDecimal(length)).divide(BigDecimal(2))
            val rotation = k * BigDecimal(omega)
            val forward = BigDecimal(vx)
            val left = BigDecimal(vy)
            val wheel = listOf(forward - left - rotation, forward + left + rotation,
                forward + left - rotation, forward - left + rotation)
            var denominator = BigDecimal(limit)
            for (speed in wheel) denominator = denominator.max(speed.abs())
            val expected = wheel.map { speed -> speed.divide(denominator, precision).toDouble() }
            val drive = StandardXrpMecanumHardwareIO(trackWidthMeters = width, wheelBaseMeters = length)
            drive.drive(ChassisSpeeds(vx, vy, omega), limit)
            val actual = listOf(drive.frontLeftMotor.effort, drive.frontRightMotor.effort,
                drive.backLeftMotor.effort, drive.backRightMotor.effort)
            for (i in actual.indices) {
                assertTrue(actual[i].isFinite() && actual[i] in -1.0..1.0)
                assertEquals(expected[i], actual[i], 2e-14, "sample=$it wheel=$i")
            }
        }
    }
}
