package com.areslib.math

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InputMathTest {

    @Test
    fun `deadband correctly strips noise`() {
        assertEquals(0.0, InputMath.applyDeadband(0.04, 0.05), 0.001)
        assertEquals(0.0, InputMath.applyDeadband(-0.04, 0.05), 0.001)
    }

    @Test
    fun `deadband rescales values outside deadband`() {
        assertEquals(0.5, InputMath.applyDeadband(0.55, 0.1), 0.001)
        assertEquals(-0.5, InputMath.applyDeadband(-0.55, 0.1), 0.001)
        assertEquals(1.0, InputMath.applyDeadband(1.0, 0.1), 0.001)
        assertEquals(-1.0, InputMath.applyDeadband(-1.0, 0.1), 0.001)
    }

    @Test
    fun `curve applies exponent while preserving sign`() {
        assertEquals(0.25, InputMath.applyCurve(0.5, 2.0), 0.001)
        assertEquals(-0.25, InputMath.applyCurve(-0.5, 2.0), 0.001)
    }
}
