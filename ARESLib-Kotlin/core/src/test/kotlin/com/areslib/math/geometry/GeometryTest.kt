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

    @Test
    fun testRotation2dCosAndSin() {
        val r0 = Rotation2d(0.0)
        assertEquals(1.0, r0.cos, 1e-6)
        assertEquals(0.0, r0.sin, 1e-6)

        val r90 = Rotation2d.fromDegrees(90.0)
        assertEquals(0.0, r90.cos, 1e-6)
        assertEquals(1.0, r90.sin, 1e-6)
    }

    @Test
    fun testTranslation2dNorm() {
        val t = Translation2d(3.0, 4.0)
        assertEquals(5.0, t.norm, 1e-6)
    }

    @Test
    fun testTranslation2dOperators() {
        val a = Translation2d(1.0, 2.0)
        val b = Translation2d(3.0, 4.0)
        
        val sum = a + b
        assertEquals(4.0, sum.x, 1e-6)
        assertEquals(6.0, sum.y, 1e-6)

        val diff = b - a
        assertEquals(2.0, diff.x, 1e-6)
        assertEquals(2.0, diff.y, 1e-6)

        val scaled = a * 2.5
        assertEquals(2.5, scaled.x, 1e-6)
        assertEquals(5.0, scaled.y, 1e-6)

        val divided = b / 2.0
        assertEquals(1.5, divided.x, 1e-6)
        assertEquals(2.0, divided.y, 1e-6)

        val neg = -a
        assertEquals(-1.0, neg.x, 1e-6)
        assertEquals(-2.0, neg.y, 1e-6)

        assertEquals(11.0, a.dot(b), 1e-6) // 1*3 + 2*4 = 11
        assertEquals(5.0, Translation2d(0.0, 0.0).distanceTo(Translation2d(3.0, 4.0)), 1e-6)
        assertEquals(25.0, Translation2d(0.0, 0.0).distanceSquared(Translation2d(3.0, 4.0)), 1e-6)

        val rotated = Translation2d(1.0, 0.0).rotateBy(Rotation2d.fromDegrees(90.0))
        assertEquals(0.0, rotated.x, 1e-6)
        assertEquals(1.0, rotated.y, 1e-6)

        assertEquals(90.0, Translation2d(0.0, 1.0).angle().degrees, 1e-6)
    }

    @Test
    fun testRotation2dOperators() {
        val r30 = Rotation2d.fromDegrees(30.0)
        val r60 = Rotation2d.fromDegrees(60.0)

        val sum = r30 + r60
        assertEquals(90.0, sum.degrees, 1e-6)

        val diff = r60 - r30
        assertEquals(30.0, diff.degrees, 1e-6)

        val scaled = r30 * 3.0
        assertEquals(90.0, scaled.degrees, 1e-6)

        val neg = -r30
        assertEquals(-30.0, neg.degrees, 1e-6)
    }

    @Test
    fun testPose2dTranslationExtractionAndFormatting() {
        val pose = Pose2d(1.5, -2.5, Rotation2d.fromDegrees(45.0))
        assertEquals(1.5, pose.translation.x, 1e-6)
        assertEquals(-2.5, pose.translation.y, 1e-6)
        assertEquals("(1.50, -2.50) 45.0°", pose.toFormattedString())

        // Transform by 1m forward and 45 deg turn
        val transformed = pose.transformBy(Translation2d(1.0, 0.0), Rotation2d.fromDegrees(45.0))
        assertEquals(90.0, transformed.heading.degrees, 1e-6)
        assertEquals(5.0, Pose2d(0.0, 0.0).distanceTo(Pose2d(3.0, 4.0)), 1e-6)
    }
}
