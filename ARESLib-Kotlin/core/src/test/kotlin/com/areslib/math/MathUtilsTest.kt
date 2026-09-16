package com.areslib.math

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MathUtilsTest {

    @Test
    fun testWrapAngle() {
        // Zero cases
        assertEquals(0.0, wrapAngle(0.0), 1e-6)
        assertEquals(0.0, wrapAngle(Double.NaN), 1e-6)
        assertEquals(0.0, wrapAngle(Double.POSITIVE_INFINITY), 1e-6)
        assertEquals(0.0, wrapAngle(Double.NEGATIVE_INFINITY), 1e-6)

        // Simple wrapping cases
        assertEquals(-Math.PI, wrapAngle(Math.PI), 1e-6)
        assertEquals(-Math.PI, wrapAngle(-Math.PI), 1e-6)
        assertEquals(0.0, wrapAngle(2.0 * Math.PI), 1e-6)
        assertEquals(Math.PI / 2.0, wrapAngle(Math.PI / 2.0), 1e-6)
        assertEquals(-Math.PI / 2.0, wrapAngle(-Math.PI / 2.0), 1e-6)
        assertEquals(-Math.PI / 2.0, wrapAngle(1.5 * Math.PI), 1e-6)
        assertEquals(Math.PI / 2.0, wrapAngle(-1.5 * Math.PI), 1e-6)
    }

    @Test
    fun `test wrapAngle exactly pi and minus pi edge cases`() {
        val res1 = wrapAngle(Math.PI)
        val res2 = wrapAngle(-Math.PI)
        val correct = when {
            kotlin.math.abs(res1 + Math.PI) < 1e-6 && kotlin.math.abs(res2 + Math.PI) < 1e-6 -> true
            else -> false
        }
        assertEquals(true, correct)
    }

    @Test
    fun `test wrapAngle exactly zero edge cases`() {
        val res = wrapAngle(0.0)
        val correct = when {
            kotlin.math.abs(res) < 1e-6 -> true
            else -> false
        }
        assertEquals(true, correct)
    }

    @Test
    fun `test floating point values close to pi boundary`() {
        val boundary = Math.PI - 1e-15
        val res = wrapAngle(boundary)
        val correct = when {
            kotlin.math.abs(res - boundary) < 1e-10 -> true
            else -> false
        }
        assertEquals(true, correct)
    }

    @Test
    fun `wrapping preserves orientation across ordinary full turns`() {
        for (turns in -100..100) {
            val angle = 0.4 + turns * (2.0 * Math.PI)
            assertEquals(0.4, wrapAngle(angle), 1e-13)
            assertEquals(Math.cos(angle), Math.cos(wrapAngle(angle)), 1e-13)
            assertEquals(Math.sin(angle), Math.sin(wrapAngle(angle)), 1e-13)
        }
    }

    @Test
    fun `wrapping is idempotent at principal interval neighbors`() {
        for (angle in listOf(Math.nextDown(-Math.PI), Math.nextUp(-Math.PI),
            Math.nextDown(Math.PI), Math.nextUp(Math.PI), -0.0, 1e-200)) {
            val once = wrapAngle(angle)
            assertEquals(once.toRawBits(), wrapAngle(once).toRawBits())
            kotlin.test.assertTrue(once >= -Math.PI && once < Math.PI)
        }
    }

    @Test
    fun `test angle and rate conversion extensions`() {
        assertEquals(Math.PI / 2.0, (2.5 * Math.PI).wrapToPi(), 1e-6)
        assertEquals(0.0, (2.0 * Math.PI).wrapTo2Pi(), 1e-6)
        assertEquals(1.5 * Math.PI, (-0.5 * Math.PI).wrapTo2Pi(), 1e-6)
        assertEquals(0.0, Double.NaN.wrapTo2Pi(), 1e-6)

        assertEquals(180.0, Math.PI.toDegrees(), 1e-6)
        assertEquals(Math.PI, 180.0.toRadians(), 1e-6)

        // 1 rot/sec = 2*PI rad/sec = 60 RPM
        assertEquals(60.0, (2.0 * Math.PI).radPerSecToRpm(), 1e-6)
        assertEquals(2.0 * Math.PI, 60.0.rpmToRadPerSec(), 1e-6)
        assertEquals(0.0, Double.NaN.radPerSecToRpm(), 1e-6)
        assertEquals(0.0, Double.NaN.rpmToRadPerSec(), 1e-6)
    }

    @Test
    fun `test productRoundoff precision`() {
        val a = 1.0000001
        val b = 2.0000002
        val prod = a * b
        val roundoff = productRoundoff(a, b, prod)
        kotlin.test.assertTrue(roundoff.isFinite())
    }
}
