package com.areslib.math

import com.areslib.math.geometry.Matrix3x3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Matrix3x3EdgeCaseTest {

    @Test
    fun testIllConditionedMatrixInverseReturnsZeroMatrix() {
        // Determinant below threshold 1e-15
        val singular = Matrix3x3(
            1.0, 2.0, 3.0,
            2.0, 4.0, 6.0,
            1.0, 1.0, 1.0
        )
        val inv = singular.inverse()
        assertEquals(0.0, inv.m00)
        assertEquals(0.0, inv.m11)
        assertEquals(0.0, inv.m22)
    }

    @Test
    fun testExtremeMagnitudeScaleInverseDoesNotProduceInfinity() {
        // Extreme scale 1e160
        val huge = Matrix3x3(
            1e160, 0.0, 0.0,
            0.0, 1e160, 0.0,
            0.0, 0.0, 1e160
        )
        val inv = huge.inverse()
        assertTrue(inv.m00.isFinite())
        assertTrue(inv.m11.isFinite())
        assertTrue(inv.m22.isFinite())
        assertEquals(1e-160, inv.m00, 1e-170)
        assertEquals(1e-160, inv.m11, 1e-170)
        assertEquals(1e-160, inv.m22, 1e-170)
    }

    @Test
    fun testTinyMagnitudeScaleInverseDoesNotProduceInfinity() {
        // Tiny scale 1e-160
        val tiny = Matrix3x3(
            1e-160, 0.0, 0.0,
            0.0, 1e-160, 0.0,
            0.0, 0.0, 1e-160
        )
        val inv = tiny.inverse()
        assertTrue(inv.m00.isFinite())
        assertTrue(inv.m11.isFinite())
        assertTrue(inv.m22.isFinite())
        assertEquals(1e160, inv.m00, 1e150)
        assertEquals(1e160, inv.m11, 1e150)
        assertEquals(1e160, inv.m22, 1e150)
    }

    @Test
    fun testNonFiniteMatrixElementsReturnZeroMatrix() {
        val nanMatrix = Matrix3x3(
            Double.NaN, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
        val inv = nanMatrix.inverse()
        assertEquals(0.0, inv.m00)
        assertEquals(0.0, inv.m11)
        assertEquals(0.0, inv.m22)
    }

    @Test
    fun testZeroMatrixReturnsZeroMatrix() {
        val zero = Matrix3x3()
        val inv = zero.inverse()
        assertEquals(0.0, inv.m00)
        assertEquals(0.0, inv.m11)
        assertEquals(0.0, inv.m22)
    }
}
