package com.areslib.math.kinematics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OdometryMathTest {

    @Test
    fun `calculateDeltaPose at zero heading maps directly`() {
        val delta = OdometryMath.calculateDeltaPose(0.0, 1.0, 0.5)
        assertEquals(1.0, delta.x, 0.001)
        assertEquals(0.5, delta.y, 0.001)
    }

    @Test
    fun `calculateDeltaPose at 90 degrees swaps axes`() {
        val delta = OdometryMath.calculateDeltaPose(Math.PI / 2, 1.0, 0.5)
        assertEquals(-0.5, delta.x, 0.001)
        assertEquals(1.0, delta.y, 0.001)
    }
}
