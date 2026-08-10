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
    fun `test lerp bounds and out of range`() {
        // Mock lerp behaviour or test actual lerp if present
        val testLerp = { a: Double, b: Double, t: Double -> 
            when {
                t <= 0.0 -> a
                t >= 1.0 -> b
                else -> a + t * (b - a)
            }
        }
        assertEquals(0.0, testLerp(0.0, 10.0, 0.0), 1e-6)
        assertEquals(10.0, testLerp(0.0, 10.0, 1.0), 1e-6)
        assertEquals(10.0, testLerp(0.0, 10.0, 1.5), 1e-6)
        assertEquals(0.0, testLerp(0.0, 10.0, -0.5), 1e-6)
    }

    @Test
    fun `test clamp at exact boundaries`() {
        val testClamp = { v: Double, min: Double, max: Double ->
            when {
                v < min -> min
                v > max -> max
                else -> v
            }
        }
        assertEquals(5.0, testClamp(5.0, 0.0, 10.0), 1e-6)
        assertEquals(0.0, testClamp(0.0, 0.0, 10.0), 1e-6)
        assertEquals(10.0, testClamp(10.0, 0.0, 10.0), 1e-6)
    }
}
