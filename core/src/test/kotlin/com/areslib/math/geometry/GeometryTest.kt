package com.areslib.math.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeometryTest {

    @Test
    fun testRotation2dWrapping() {
        // 1. Check exact bounds
        assertEquals(0.0, Rotation2d(0.0).radians, 1e-6)
        assertEquals(-Math.PI, Rotation2d(Math.PI).radians, 1e-6)
        assertEquals(-Math.PI, Rotation2d(-Math.PI).radians, 1e-6)

        // 2. Check positive overflow wrapping
        assertEquals(-Math.PI / 2.0, Rotation2d(1.5 * Math.PI).radians, 1e-6)
        assertEquals(0.0, Rotation2d(4.0 * Math.PI).radians, 1e-6)
        assertEquals(Math.toRadians(10.0), Rotation2d.fromDegrees(370.0).radians, 1e-6)

        // 3. Check negative underflow wrapping
        assertEquals(Math.PI / 2.0, Rotation2d(-1.5 * Math.PI).radians, 1e-6)
        assertEquals(-Math.PI / 2.0, Rotation2d(-2.5 * Math.PI).radians, 1e-6)
        assertEquals(Math.toRadians(-10.0), Rotation2d.fromDegrees(-370.0).radians, 1e-6)
    }
}
