package com.areslib.math.geometry

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI

class Pose2dRelativeToTest {

    @Test
    fun testIdentityRelativeToIdentity() {
        val p1 = Pose2d(0.0, 0.0, Rotation2d(0.0))
        val rel = p1.relativeTo(p1)
        assertEquals(0.0, rel.x, 1e-9)
        assertEquals(0.0, rel.y, 1e-9)
        assertEquals(0.0, rel.heading.radians, 1e-9)
    }

    @Test
    fun testPureTranslationRelativeToIdentity() {
        val origin = Pose2d(0.0, 0.0, Rotation2d(0.0))
        val target = Pose2d(5.0, 3.0, Rotation2d(1.0))
        val rel = target.relativeTo(origin)
        assertEquals(5.0, rel.x, 1e-9)
        assertEquals(3.0, rel.y, 1e-9)
        assertEquals(1.0, rel.heading.radians, 1e-9)
    }

    @Test
    fun testRotatedOriginTransformsDisplacementToBodyFrame() {
        // Origin at (0, 0) facing +Y (PI/2 rad)
        val origin = Pose2d(0.0, 0.0, Rotation2d(PI / 2.0))
        // Target at (0, 5) in world coordinates, facing +Y
        val target = Pose2d(0.0, 5.0, Rotation2d(PI / 2.0))
        
        // In origin's frame, (0, 5) is directly FORWARD (+X = 5, +Y = 0)
        val rel = target.relativeTo(origin)
        assertEquals(5.0, rel.x, 1e-9)
        assertEquals(0.0, rel.y, 1e-9)
        assertEquals(0.0, rel.heading.radians, 1e-9)
    }

    @Test
    fun testTranslationNormalize() {
        val v = Translation2d(3.0, 4.0)
        val unit = v.normalize()
        assertEquals(0.6, unit.x, 1e-9)
        assertEquals(0.8, unit.y, 1e-9)
        assertEquals(1.0, unit.norm, 1e-9)

        // Zero vector normalizes safely to (0, 0) without throwing or returning NaN
        val zero = Translation2d(0.0, 0.0).normalize()
        assertEquals(0.0, zero.x)
        assertEquals(0.0, zero.y)

        // Non-finite vector normalizes safely to (0, 0)
        val nanVec = Translation2d(Double.NaN, 5.0).normalize()
        assertEquals(0.0, nanVec.x)
        assertEquals(0.0, nanVec.y)
    }
}
