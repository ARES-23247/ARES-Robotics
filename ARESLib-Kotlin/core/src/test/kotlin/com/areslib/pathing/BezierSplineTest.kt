package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BezierSplineTest {

    @Test
    fun testPositionEvaluation() {
        val p0 = Translation2d(0.0, 0.0)
        val p1 = Translation2d(1.0, 0.0)
        val p2 = Translation2d(1.0, 1.0)
        val p3 = Translation2d(2.0, 1.0)

        // t = 0 should be p0
        val pos0 = BezierSpline.evaluate(p0, p1, p2, p3, 0.0)
        assertEquals(p0.x, pos0.x, 0.001)
        assertEquals(p0.y, pos0.y, 0.001)

        // t = 1 should be p3
        val pos1 = BezierSpline.evaluate(p0, p1, p2, p3, 1.0)
        assertEquals(p3.x, pos1.x, 0.001)
        assertEquals(p3.y, pos1.y, 0.001)

        // t = 0.5 should be midpoint logic for this symmetric control polygon
        val posHalf = BezierSpline.evaluate(p0, p1, p2, p3, 0.5)
        assertEquals(1.0, posHalf.x, 0.001)
        assertEquals(0.5, posHalf.y, 0.001)
    }

    @Test
    fun testDerivativeContinuity() {
        val p0 = Translation2d(0.0, 0.0)
        val p1 = Translation2d(1.0, 0.0)
        val p2 = Translation2d(1.0, 1.0)
        val p3 = Translation2d(2.0, 1.0)

        val d1 = BezierSpline.evaluateDerivative(p0, p1, p2, p3, 0.499)
        val d2 = BezierSpline.evaluateDerivative(p0, p1, p2, p3, 0.500)
        val d3 = BezierSpline.evaluateDerivative(p0, p1, p2, p3, 0.501)

        // Derivative should be continuous, small step -> small change
        assertTrue(abs(d2.x - d1.x) < 0.1)
        assertTrue(abs(d2.y - d1.y) < 0.1)
        assertTrue(abs(d3.x - d2.x) < 0.1)
        assertTrue(abs(d3.y - d2.y) < 0.1)
    }

    @Test
    fun testStraightLineDegenerateCase() {
        val p0 = Translation2d(0.0, 0.0)
        val p1 = Translation2d(1.0, 1.0)
        val p2 = Translation2d(2.0, 2.0)
        val p3 = Translation2d(3.0, 3.0)

        val posHalf = BezierSpline.evaluate(p0, p1, p2, p3, 0.5)
        assertEquals(1.5, posHalf.x, 0.001)
        assertEquals(1.5, posHalf.y, 0.001)
        
        val heading = BezierSpline.evaluateHeading(p0, p1, p2, p3, 0.5)
        assertEquals(Math.PI / 4, heading.radians, 0.001) // 45 degrees
    }
}
