package com.areslib.math.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI

class Geometry3dTest {
    @Test
    fun testQuaternionNormalization() {
        val q = Quaternion(2.0, 0.0, 0.0, 0.0).normalize()
        assertEquals(1.0, q.w, 1e-6)
        assertEquals(0.0, q.x, 1e-6)
    }

    @Test
    fun testRotation3dEulerAngles() {
        // Roll 90 degrees
        val r = Rotation3d(PI / 2, 0.0, 0.0)
        assertEquals(PI / 2, r.x, 1e-6)
        assertEquals(0.0, r.y, 1e-6)
        assertEquals(0.0, r.z, 1e-6)

        // Pitch 90 degrees
        val p = Rotation3d(0.0, PI / 2, 0.0)
        assertEquals(0.0, p.x, 1e-6)
        assertEquals(PI / 2, p.y, 1e-6)
        assertEquals(0.0, p.z, 1e-6)

        // Yaw 90 degrees
        val y = Rotation3d(0.0, 0.0, PI / 2)
        assertEquals(0.0, y.x, 1e-6)
        assertEquals(0.0, y.y, 1e-6)
        assertEquals(PI / 2, y.z, 1e-6)
    }

    @Test
    fun testTransformBy() {
        val initialPose = Pose3d(Translation3d(1.0, 2.0, 0.0), Rotation3d(0.0, 0.0, PI / 2))
        val transform = Transform3d(Translation3d(1.0, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0))
        
        val transformed = initialPose.transformBy(transform)
        
        // Rotating the local translation (1, 0, 0) by PI/2 yaw results in field translation (0, 1, 0)
        // Adding that to (1, 2, 0) -> (1, 3, 0)
        assertEquals(1.0, transformed.translation.x, 1e-6)
        assertEquals(3.0, transformed.translation.y, 1e-6)
        assertEquals(0.0, transformed.translation.z, 1e-6)
        assertEquals(PI / 2, transformed.rotation.z, 1e-6)
    }

    @Test
    fun testRelativeTo() {
        val pose1 = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, PI / 2))
        val pose2 = Pose3d(Translation3d(2.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0))
        
        val transform = pose2.relativeTo(pose1)
        
        // Pose1 is at (1, 1) facing +Y (PI/2).
        // Pose2 is at (2, 1) facing +X (0.0).
        // From Pose1's perspective (+Y is forward, +X is right), Pose2 is 1 unit to the right (x=0, y=-1 in local frame? No, local x is world Y, local y is world -X).
        // Wait, standard robotics coordinates: local +X is forward, local +Y is left.
        // Pose1 facing PI/2 (world +Y). So Pose1's local +X is world +Y. Pose1's local +Y is world -X.
        // Pose2 is at world (2, 1). Pose1 is at (1, 1). Delta is world (+1, 0).
        // In Pose1's local frame, world (+1, 0) is local -Y. So local (-0, -1).
        assertEquals(0.0, transform.translation.x, 1e-6)
        assertEquals(-1.0, transform.translation.y, 1e-6)
        assertEquals(0.0, transform.translation.z, 1e-6)
        assertEquals(-PI / 2, transform.rotation.z, 1e-6)
    }
}
