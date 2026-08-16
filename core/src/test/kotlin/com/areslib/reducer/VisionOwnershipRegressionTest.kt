package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Quaternion
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionState
import kotlin.test.Test
import kotlin.test.assertEquals

class VisionOwnershipRegressionTest {
    @Test
    fun `reducer snapshots pooled measurement and nested pose objects`() {
        val measurement = VisionMeasurement(
            timestampMs = 10L,
            targetPose = Pose3d(Translation3d(1.0, 2.0, 3.0), Rotation3d(Quaternion(1.0, 0.1, 0.2, 0.3))),
            tagId = 7,
            ambiguity = 0.1,
            robotPoseTargetSpace = Pose3d(Translation3d(4.0, 5.0, 6.0), Rotation3d())
        )
        val retained = VisionReducer.reduce(
            VisionState(),
            RobotAction.VisionMeasurementsReceived(listOf(measurement), 10L, fuseIntoPoseEstimator = false)
        ).measurements.single()

        measurement.targetPose.translation.x = 99.0
        measurement.targetPose.rotation.q.w = -1.0
        measurement.robotPoseTargetSpace.translation.z = 88.0
        measurement.tagId = 42

        assertEquals(1.0, retained.targetPose.x)
        assertEquals(1.0, retained.targetPose.quaternionW)
        assertEquals(6.0, retained.robotPoseTargetSpace.z)
        assertEquals(7, retained.tagId)
    }
}
