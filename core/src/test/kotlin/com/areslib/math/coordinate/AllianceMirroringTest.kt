package com.areslib.math.coordinate

import com.areslib.state.Alliance
import com.areslib.pathing.Path
import com.areslib.math.geometry.*
import com.areslib.pathing.PathPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AllianceMirroringTest {

    private val epsilon = 1e-6
    private val fieldLength = 3.6576 // 12 feet (FTC)
    private val fieldWidth = 3.6576

    @Test
    fun testBlueAllianceNoOp() {
        val originalPose = Pose2d(1.0, 1.5, Rotation2d.fromDegrees(45.0))
        val mirroredPose = AllianceMirroring.mirror(originalPose, Alliance.BLUE, FieldSymmetry.ROTATIONAL)

        assertEquals(originalPose.x, mirroredPose.x, epsilon)
        assertEquals(originalPose.y, mirroredPose.y, epsilon)
        assertEquals(originalPose.heading.radians, mirroredPose.heading.radians, epsilon)

        val originalTranslation = Translation2d(1.0, 1.5)
        val mirroredTranslation = AllianceMirroring.mirror(originalTranslation, Alliance.BLUE, FieldSymmetry.ROTATIONAL)
        assertEquals(originalTranslation.x, mirroredTranslation.x, epsilon)
        assertEquals(originalTranslation.y, mirroredTranslation.y, epsilon)
    }

    @Test
    fun testRotationalMirroring() {
        // Rotational symmetry: 180° rotation about center of the field
        val originalPose = Pose2d(1.0, 1.5, Rotation2d.fromDegrees(45.0))
        val mirroredPose = AllianceMirroring.mirror(originalPose, Alliance.RED, FieldSymmetry.ROTATIONAL, fieldLength, fieldWidth)

        // Expected: x = -1.0, y = -1.5, heading = 225°
        assertEquals(-1.0, mirroredPose.x, epsilon)
        assertEquals(-1.5, mirroredPose.y, epsilon)
        assertEquals(Rotation2d.fromDegrees(225.0).radians, mirroredPose.heading.radians, epsilon)
    }

    @Test
    fun testReflectionalMirroring() {
        // Reflectional symmetry: mirror reflection across x midline (y remains same, x flipped, yaw mirrored)
        val originalPose = Pose2d(1.0, 1.5, Rotation2d.fromDegrees(45.0))
        val mirroredPose = AllianceMirroring.mirror(originalPose, Alliance.RED, FieldSymmetry.MIRRORED, fieldLength, fieldWidth)

        // Expected: x = 1.0, y = -1.5, heading = -45° (315°)
        assertEquals(1.0, mirroredPose.x, epsilon)
        assertEquals(-1.5, mirroredPose.y, epsilon)
        assertEquals(Rotation2d.fromDegrees(-45.0).radians, mirroredPose.heading.radians, epsilon)
    }

    @Test
    fun testPathMirroring() {
        val points = listOf(
            PathPoint(Pose2d(1.0, 1.5, Rotation2d.fromDegrees(45.0)), 1.0, 0.0, 0.5),
            PathPoint(Pose2d(2.0, 2.5, Rotation2d.fromDegrees(90.0)), 2.0, 1.5, -0.3)
        )
        val path = Path(points)

        // Test Rotational mirroring
        val rotatedPath = AllianceMirroring.mirror(path, Alliance.RED, FieldSymmetry.ROTATIONAL, fieldLength, fieldWidth)
        assertEquals(2, rotatedPath.points.size)
        // Curvatures should be UNCHANGED under rotation
        assertEquals(0.5, rotatedPath.points[0].curvature, epsilon)
        assertEquals(-0.3, rotatedPath.points[1].curvature, epsilon)
        assertEquals(-1.0, rotatedPath.points[0].pose.x, epsilon)
        assertEquals(-1.5, rotatedPath.points[0].pose.y, epsilon)

        // Test Reflectional mirroring
        val reflectedPath = AllianceMirroring.mirror(path, Alliance.RED, FieldSymmetry.MIRRORED, fieldLength, fieldWidth)
        assertEquals(2, reflectedPath.points.size)
        // Curvatures should be INVERTED under reflection
        assertEquals(-0.5, reflectedPath.points[0].curvature, epsilon)
        assertEquals(0.3, reflectedPath.points[1].curvature, epsilon)
        assertEquals(1.0, reflectedPath.points[0].pose.x, epsilon)
        assertEquals(-1.5, reflectedPath.points[0].pose.y, epsilon)
    }
}
