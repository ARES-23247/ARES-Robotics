package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SplinePolynomialAuditTest {
    @Test fun `collapsed endpoint handles retain the limiting curve tangent`() {
        val start = Translation2d(0.0, 0.0)
        val end = Translation2d(0.0, 2.0)
        for (handles in listOf(listOf(start, end), listOf(start, start), listOf(end, end))) {
            for (t in listOf(0.0, 1.0)) {
                assertEquals(Math.PI/2, BezierSpline.evaluateHeading(start, handles[0], handles[1], end, t).radians, 1e-12)
            }
        }
        val handle = Translation2d(2.0, -1.0)
        assertEquals(atan2(-1.0, 2.0), BezierSpline.evaluateHeading(start, start, handle, end, 0.0).radians, 1e-12)
        assertEquals(atan2(3.0, -2.0), BezierSpline.evaluateHeading(start, handle, end, end, 1.0).radians, 1e-12)
    }

    @Test fun `parsed paths without handles use their actual endpoint tangent for default heading`() {
        val parsed = PathPlannerJsonParser.parse("""{"waypoints":[{"anchor":{"x":0,"y":0}},{"anchor":{"x":0,"y":2}}]}""", 2.0, 1.0)
        val path = SplineMotionProfiler.buildProfiledPath(parsed)
        assertEquals(Math.PI/2, path.points.first().tangentRadians, 1e-12)
        assertEquals(Math.PI/2, path.points.last().tangentRadians, 1e-12)
        path.points.forEach { assertEquals(Math.PI/2, it.pose.heading.radians, 1e-12) }
    }

    @Test fun `Bezier derivative agrees with central differences on asymmetric curves`() {
        val random = Random(95)
        repeat(40) {
            val p = List(4) { Translation2d(random.nextDouble(-4.0, 4.0), random.nextDouble(-4.0, 4.0)) }
            for (t in listOf(0.0, 0.17, 0.53, 0.89, 1.0)) {
                val h = 1e-5
                val before = BezierSpline.evaluate(p[0], p[1], p[2], p[3], t-h)
                val after = BezierSpline.evaluate(p[0], p[1], p[2], p[3], t+h)
                val derivative = BezierSpline.evaluateDerivative(p[0], p[1], p[2], p[3], t)
                assertEquals((after.x-before.x)/(2*h), derivative.x, 1e-7)
                assertEquals((after.y-before.y)/(2*h), derivative.y, 1e-7)
                assertEquals(atan2(derivative.y, derivative.x),
                    BezierSpline.evaluateHeading(p[0], p[1], p[2], p[3], t).radians, 1e-12)
            }
        }
    }

    @Test fun `Bezier endpoints and reversed control polygons retain position and tangent meaning`() {
        val p = listOf(Translation2d(-2.0, 3.0), Translation2d(4.0, -1.0),
            Translation2d(-3.0, -5.0), Translation2d(7.0, 2.0))
        vector(p.first(), BezierSpline.evaluate(p[0], p[1], p[2], p[3], 0.0))
        vector(p.last(), BezierSpline.evaluate(p[0], p[1], p[2], p[3], 1.0))
        vector(Translation2d(18.0, -12.0), BezierSpline.evaluateDerivative(p[0], p[1], p[2], p[3], 0.0))
        vector(Translation2d(30.0, 21.0), BezierSpline.evaluateDerivative(p[0], p[1], p[2], p[3], 1.0))
        for (t in listOf(0.1, 0.4, 0.8)) {
            vector(BezierSpline.evaluate(p[0], p[1], p[2], p[3], t),
                BezierSpline.evaluate(p[3], p[2], p[1], p[0], 1-t))
            val reversed = BezierSpline.evaluateDerivative(p[3], p[2], p[1], p[0], 1-t)
            vector(Translation2d(-reversed.x, -reversed.y),
                BezierSpline.evaluateDerivative(p[0], p[1], p[2], p[3], t))
        }
        val same = Translation2d(2.0, 3.0)
        vector(Translation2d(0.0, 0.0), BezierSpline.evaluateDerivative(same, same, same, same, 0.5))
        assertEquals(0.0, BezierSpline.evaluateHeading(same, same, same, same, 0.5).radians)
    }

    @Test fun `natural controls match an independent dense piecewise polynomial solve`() {
        val random = Random(950)
        for (size in 2..7) repeat(5) {
            val anchors = List(size) { Translation2d(random.nextDouble(-3.0, 3.0), random.nextDouble(-3.0, 3.0)) }
            val controls = naturalCubicWaypointControls(anchors)
            val x = densePolynomialCoefficients(anchors.map { it.x })
            val y = densePolynomialCoefficients(anchors.map { it.y })
            for (segment in 0 until size-1) {
                val index = 4*segment
                vector(Translation2d(x[index]+x[index+1]/3, y[index]+y[index+1]/3), controls[segment].nextControl)
                vector(Translation2d(anchors[segment+1].x-(x[index+1]+2*x[index+2]+3*x[index+3])/3,
                    anchors[segment+1].y-(y[index+1]+2*y[index+2]+3*y[index+3])/3), controls[segment+1].prevControl)
            }
            anchors.indices.forEach { vector(anchors[it], controls[it].anchor) }
        }
    }

    @Test fun `natural controls cover short and coincident anchors`() {
        assertFailsWith<IllegalArgumentException> { naturalCubicWaypointControls(emptyList()) }
        assertFailsWith<IllegalArgumentException> { naturalCubicWaypointControls(listOf(Translation2d())) }
        val anchor = Translation2d(3.0, -4.0)
        for (size in listOf(2, 3, 8)) {
            naturalCubicWaypointControls(List(size) { anchor }).forEach {
                vector(anchor, it.anchor)
                vector(anchor, it.prevControl)
                vector(anchor, it.nextControl)
            }
        }
    }

    private fun vector(expected: Translation2d, actual: Translation2d) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    // Unknowns are a,b,c,d per segment, not the production first-derivative system.
    private fun densePolynomialCoefficients(values: List<Double>): DoubleArray {
        val segments = values.size-1
        val n = 4*segments
        val matrix = Array(n) { DoubleArray(n+1) }
        var row = 0
        for (s in 0 until segments) {
            matrix[row][4*s] = 1.0; matrix[row++][n] = values[s]
            for (k in 0..3) matrix[row][4*s+k] = 1.0
            matrix[row++][n] = values[s+1]
        }
        for (s in 0 until segments-1) {
            matrix[row][4*s+1] = 1.0; matrix[row][4*s+2] = 2.0
            matrix[row][4*s+3] = 3.0; matrix[row++][4*(s+1)+1] = -1.0
            matrix[row][4*s+2] = 2.0; matrix[row][4*s+3] = 6.0
            matrix[row++][4*(s+1)+2] = -2.0
        }
        matrix[row++][2] = 2.0
        matrix[row][n-2] = 2.0; matrix[row][n-1] = 6.0
        for (column in 0 until n) {
            val pivot = (column until n).maxBy { abs(matrix[it][column]) }
            val swap = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = swap
            val divisor = matrix[column][column]
            check(abs(divisor) > 1e-12)
            for (k in column..n) matrix[column][k] /= divisor
            for (other in 0 until n) if (other != column) {
                val factor = matrix[other][column]
                for (k in column..n) matrix[other][k] -= factor*matrix[column][k]
            }
        }
        return DoubleArray(n) { matrix[it][n] }
    }
}
