package com.areslib.math

import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.geometry.Vector3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class Matrix3x3Test {

    @Test
    fun testIdentityMultiplication() {
        val identity = Matrix3x3.IDENTITY
        val mat = Matrix3x3(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 9.0
        )
        
        val result = mat * identity
        assertEquals(mat.m00, result.m00, 0.001)
        assertEquals(mat.m11, result.m11, 0.001)
        assertEquals(mat.m22, result.m22, 0.001)
        assertEquals(mat.m12, result.m12, 0.001)
    }

    @Test
    fun testInversionOfKnownMatrix() {
        val mat = Matrix3x3(
            4.0, 7.0, 2.0,
            2.0, 6.0, 1.0,
            3.0, 1.0, 4.0
        )
        val inv = mat.inverse()
        
        val identityResult = mat * inv
        
        // Should be roughly identity
        assertEquals(1.0, identityResult.m00, 0.001)
        assertEquals(0.0, identityResult.m01, 0.001)
        assertEquals(1.0, identityResult.m11, 0.001)
        assertEquals(1.0, identityResult.m22, 0.001)
    }

    @Test
    fun testDeterminantCalculation() {
        // Singular matrix det is 0
        val mat = Matrix3x3(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 9.0
        )
        
        // 1(5*9 - 6*8) - 2(4*9 - 6*7) + 3(4*8 - 5*7) = 1(-3) - 2(-6) + 3(-3) = -3 + 12 - 9 = 0
        // The inverse method handles det internally and returns all 0s if singular
        val inv = mat.inverse()
        assertEquals(0.0, inv.m00, 0.001)
        assertEquals(0.0, inv.m11, 0.001)
        assertEquals(0.0, inv.m22, 0.001)
    }

    @Test
    fun testTranspose() {
        val mat = Matrix3x3(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 9.0
        )
        
        val t = mat.transpose()
        assertEquals(1.0, t.m00, 0.001)
        assertEquals(4.0, t.m01, 0.001)
        assertEquals(7.0, t.m02, 0.001)
        assertEquals(2.0, t.m10, 0.001)
        assertEquals(6.0, t.m21, 0.001)
    }

    @Test
    fun testMultiplicationAssociativity() {
        val a = Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val b = Matrix3x3(9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0)
        val c = Matrix3x3(2.0, 0.0, 1.0, 0.0, 2.0, 0.0, 1.0, 0.0, 2.0)
        
        val ab_c = (a * b) * c
        val a_bc = a * (b * c)
        
        assertEquals(ab_c.m00, a_bc.m00, 0.001)
        assertEquals(ab_c.m11, a_bc.m11, 0.001)
        assertEquals(ab_c.m22, a_bc.m22, 0.001)
    }

    @Test
    fun testInPlaceOperations() {
        val target = Matrix3x3()
        val source = Matrix3x3(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 9.0
        )

        target.setTo(source)
        assertEquals(1.0, target.m00, 1e-6)
        assertEquals(9.0, target.m22, 1e-6)

        target.addInPlace(source)
        assertEquals(2.0, target.m00, 1e-6)
        assertEquals(18.0, target.m22, 1e-6)

        target.multiplyInPlace(0.5)
        assertEquals(1.0, target.m00, 1e-6)
        assertEquals(9.0, target.m22, 1e-6)
    }
}
