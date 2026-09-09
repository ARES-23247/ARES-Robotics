package com.areslib.math

import org.junit.jupiter.api.Test
import kotlin.test.*

class InputConditioningAuditTest {
    @Test fun `invalid scalar observations neutralize`() {
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.01, 1.01)) {
            assertEquals(0.0, InputMath.applyDeadband(value, 0.05))
            assertEquals(0.0, InputMath.applyCurve(value, 2.0))
        }
    }
    @Test fun `invalid scalar configuration neutralizes`() {
        for (deadband in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.0, 2.0)) {
            assertEquals(0.0, InputMath.applyDeadband(0.5, deadband))
        }
        for (exponent in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertEquals(0.0, InputMath.applyCurve(0.5, exponent))
        }
    }
    @Test fun `valid narrow active deadband still reaches full travel`() {
        val deadband = Math.nextDown(1.0)
        assertEquals(1.0, InputMath.applyDeadband(1.0, deadband))
        assertEquals(-1.0, InputMath.applyDeadband(-1.0, deadband))
        assertEquals(0.0, InputMath.applyDeadband(deadband, deadband))
    }
    @Test fun `zero curve exponent cannot command full vector just outside deadband`() {
        assertEquals(0.0 to 0.0, InputMath.processJoystickVector(0.05, 0.0, 0.05, 0.0))
        assertEquals(0.0 to 0.0, InputMath.processJoystickVector(Math.nextUp(0.05), 0.0, 0.05, 0.0))
    }
    @Test fun `one invalid stick coordinate neutralizes the whole vector`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.01, 1.01)) {
            assertEquals(0.0 to 0.0, InputMath.processJoystickVector(invalid, 0.5))
            assertEquals(0.0 to 0.0, InputMath.processJoystickVector(0.5, invalid))
        }
    }
    @Test fun `positive fractional curves remain supported and continuous at zero`() {
        assertEquals(0.5, InputMath.applyCurve(0.25, 0.5))
        assertEquals(-0.5, InputMath.applyCurve(-0.25, 0.5))
        assertEquals(0.0, InputMath.applyCurve(0.0, 0.5))
        assertEquals(0.5 to 0.0, InputMath.processJoystickVector(0.25, 0.0, 0.0, 0.5))
    }

    @Test fun `buffer output matches independent radial law and preserves direction`() {
        val random = kotlin.random.Random(4001)
        val output = doubleArrayOf(0.0, 0.0, 123.0)
        repeat(5_000) {
            val x = random.nextDouble(-1.0, 1.0); val y = random.nextDouble(-1.0, 1.0)
            val deadband = random.nextDouble(0.0, 1.0); val exponent = random.nextDouble(0.1, 5.0)
            val radius = kotlin.math.sqrt(x * x + y * y)
            val normalized = ((radius.coerceAtMost(1.0) - deadband) / (1.0 - deadband)).coerceAtLeast(0.0)
            val expectedRadius = Math.pow(normalized, exponent)
            InputMath.processJoystickVectorInto(x, y, output, deadband, exponent)
            assertEquals(expectedRadius, kotlin.math.hypot(output[0], output[1]), 2e-14)
            assertEquals(0.0, x * output[1] - y * output[0], 2e-15)
            assertTrue(x * output[0] + y * output[1] >= 0.0)
            val pair = InputMath.processJoystickVector(x, y, deadband, exponent)
            assertEquals(pair.first, output[0]); assertEquals(pair.second, output[1]); assertEquals(123.0, output[2])
        }
    }

    @Test fun `buffer boundaries and invalid arguments do not leave stale coordinates`() {
        val output = doubleArrayOf(10.0, 20.0)
        InputMath.processJoystickVectorInto(Double.NaN, 1.0, output)
        assertContentEquals(doubleArrayOf(0.0, 0.0), output)
        for (deadband in listOf(-1.0, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            InputMath.processJoystickVectorInto(0.5, 0.5, output, deadband)
            assertContentEquals(doubleArrayOf(0.0, 0.0), output)
        }
        for (exponent in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            InputMath.processJoystickVectorInto(0.5, 0.5, output, exponent = exponent)
            assertContentEquals(doubleArrayOf(0.0, 0.0), output)
        }
        val short = doubleArrayOf(123.0)
        assertFailsWith<IllegalArgumentException> { InputMath.processJoystickVectorInto(1.0, 1.0, short) }
        assertEquals(123.0, short[0])
    }

    @Test fun `scalar conditioning is odd bounded and monotonic through full travel`() {
        for (deadband in listOf(0.0, 0.05, 0.9)) for (exponent in listOf(0.5, 1.0, 2.0, 5.0)) {
            var last = 0.0
            for (step in 0..1000) {
                val x = step / 1000.0
                val actual = InputMath.applyCurve(InputMath.applyDeadband(x, deadband), exponent)
                val opposite = InputMath.applyCurve(InputMath.applyDeadband(-x, deadband), exponent)
                assertTrue(actual >= last && actual <= 1.0); assertEquals(actual, -opposite, 0.0)
                last = actual
            }
            assertEquals(1.0, last)
        }
    }

    @Test fun `subnormal vectors and square corners preserve finite normalized direction`() {
        val output = DoubleArray(2)
        InputMath.processJoystickVectorInto(Double.MIN_VALUE, 0.0, output, 0.0, 0.5)
        assertEquals(kotlin.math.sqrt(Double.MIN_VALUE), output[0]); assertEquals(0.0, output[1])
        for (signX in listOf(-1.0, 1.0)) for (signY in listOf(-1.0, 1.0)) {
            InputMath.processJoystickVectorInto(signX, signY, output)
            assertEquals(signX / kotlin.math.sqrt(2.0), output[0], 1e-15)
            assertEquals(signY / kotlin.math.sqrt(2.0), output[1], 1e-15)
        }
    }
}
