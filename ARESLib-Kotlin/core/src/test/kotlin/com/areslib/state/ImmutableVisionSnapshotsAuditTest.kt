package com.areslib.state

import com.areslib.math.geometry.*
import kotlin.math.PI
import kotlin.test.*
import org.junit.jupiter.api.Test

class ImmutableVisionSnapshotsAuditTest {
    @Test fun singularEulerReconstruction() {
        for (pitch in listOf(-PI / 2, PI / 2)) {
            for (roll in listOf(-2.1, 0.3, 1.2)) for (yaw in listOf(-1.7, 0.8, 2.6)) {
                val pose = Pose3d(rotation = Rotation3d(roll, pitch, yaw))
                val snapshot = Pose3dSnapshot.from(pose)
                val actual = Rotation3d(snapshot.rotationX, snapshot.rotationY, snapshot.rotationZ).q
                sameRotation(pose.rotation.q, actual)
                assertEquals(0.0, snapshot.rotationX)
            }
        }
    }

    @Test fun nearSingularPitchPrecision() {
        for (pitch in listOf(-PI / 2 + 1e-9, PI / 2 - 1e-9)) {
            val snapshot = Pose3dSnapshot.from(Pose3d(rotation = Rotation3d(0.3, pitch, 0.8)))
            assertEquals(pitch, snapshot.rotationY, 1e-14)
        }
    }

    @Test fun planarProjectionParity() {
        for (pitch in listOf(-PI / 2, -0.7, 0.0, 0.9, PI / 2)) {
            val pose = Pose3d(Translation3d(2.0, -3.0, 4.0), Rotation3d(0.3, pitch, 0.8))
            val snapshot = Pose3dSnapshot.from(pose)
            assertEquals(pose.toPose2d(), snapshot.toPose2d())
            assertEquals(pose.rotation.x, snapshot.rotationX)
            assertEquals(pose.rotation.y, snapshot.rotationY)
            assertEquals(pose.rotation.z, snapshot.rotationZ)
        }
    }

    @Test fun ordinaryRotationsAndNegatedQuaternions() {
        for (roll in listOf(-2.0, 0.0, 1.2)) for (pitch in listOf(-1.2, 0.0, 1.2)) {
            for (yaw in listOf(-PI, 0.0, PI)) for (sign in listOf(-1.0, 1.0)) {
                val q = Rotation3d(roll, pitch, yaw).q
                val s = Pose3dSnapshot(quaternionW = sign*q.w, quaternionX = sign*q.x,
                    quaternionY = sign*q.y, quaternionZ = sign*q.z)
                sameRotation(q, Rotation3d(s.rotationX, s.rotationY, s.rotationZ).q)
            }
        }
    }

    @Test fun everyMeasurementFieldAndNestedPoseOwnership() {
        val source = VisionMeasurement(11L, 12L, Pose3d(Translation3d(1.0, 2.0, 3.0)),
            13, 0.14, false, Pose3d(Translation3d(4.0, 5.0, 6.0)), 16, 0.17, 0.18,
            0.19, "camera", 20L, VisionSolverType.MEGATAG2, 0.21, 0.22, 0.23, 0.24,
            Pose3d(Translation3d(7.0, 8.0, 9.0)), true, 0.25, true)
        val snapshot = source.snapshot()
        assertEquals(VisionMeasurementSnapshot(11L, 12L, Pose3dSnapshot(1.0, 2.0, 3.0),
            13, 0.14, false, Pose3dSnapshot(4.0, 5.0, 6.0), 16, 0.17, 0.18, 0.19,
            "camera", 20L, VisionSolverType.MEGATAG2, 0.21, 0.22, 0.23, 0.24,
            Pose3dSnapshot(7.0, 8.0, 9.0), true, 0.25, true), snapshot)
        source.timestampMs = 999L
        source.tagId = 999
        for (pose in listOf(source.targetPose, source.robotPoseTargetSpace, source.recoveryPose)) {
            pose.translation.x = 999.0
            pose.rotation.setEulerAngles(1.0, 1.0, 1.0)
        }
        assertEquals(11L, snapshot.timestampMs)
        assertEquals(13, snapshot.tagId)
        assertEquals(listOf(1.0, 4.0, 7.0), listOf(snapshot.targetPose.x, snapshot.robotPoseTargetSpace.x, snapshot.recoveryPose.x))
        assertEquals(listOf(0.0, 0.0, 0.0), listOf(snapshot.targetPose.rotationZ, snapshot.robotPoseTargetSpace.rotationZ, snapshot.recoveryPose.rotationZ))
    }

    @Test fun matrixRowOrderAndStorageOwnership() {
        val input = DoubleArray(10) { it.toDouble() }
        val snapshot = Matrix3x3Snapshot.from(input)
        input.fill(99.0)
        val exported = snapshot.copyToDoubleArray()
        assertContentEquals(DoubleArray(9) { it.toDouble() }, exported)
        exported.fill(-99.0)
        assertContentEquals(DoubleArray(9) { it.toDouble() }, snapshot.copyToDoubleArray())
        assertFailsWith<IllegalArgumentException> { Matrix3x3Snapshot.from(DoubleArray(8)) }
    }

    private fun sameRotation(expected: Quaternion, actual: Quaternion) {
        val sign = if (expected.w*actual.w + expected.x*actual.x + expected.y*actual.y + expected.z*actual.z < 0) -1 else 1
        assertEquals(expected.w, sign*actual.w, 1e-12)
        assertEquals(expected.x, sign*actual.x, 1e-12)
        assertEquals(expected.y, sign*actual.y, 1e-12)
        assertEquals(expected.z, sign*actual.z, 1e-12)
    }
}
