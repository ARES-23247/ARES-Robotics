package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionState
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Vector3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalizationTimingTest {
    @Test
    fun `duplicate odometry timestamp is rejected`() {
        var state = DriveReducer.reduce(
            DriveState(),
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true)
        )
        state = DriveReducer.reduce(
            state,
            RobotAction.PoseUpdate(0.1, 0.0, 0.0, timestampMs = 120L)
        )
        val duplicate = DriveReducer.reduce(
            state,
            RobotAction.PoseUpdate(0.2, 0.0, 0.0, timestampMs = 120L)
        )

        assertEquals(state.odometryX, duplicate.odometryX, 0.0)
        assertEquals(state.poseEstimator.estimatedPoseX, duplicate.poseEstimator.estimatedPoseX, 0.0)
    }

    @Test
    fun `stationary process noise scales with measured interval`() {
        val initialShort = DriveReducer.reduce(
            DriveState(),
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true)
        )
        val initialLong = DriveReducer.reduce(
            DriveState(),
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true)
        )

        val shortInterval = DriveReducer.reduce(
            initialShort,
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 120L)
        )
        val longInterval = DriveReducer.reduce(
            initialLong,
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 200L)
        )

        assertTrue(longInterval.poseEstimator.covarianceArray[0] > shortInterval.poseEstimator.covarianceArray[0])
    }

    @Test
    fun `delayed vision prefilter uses capture-time pose`() {
        val config = VisionFilterConfig(
            maxDistanceMeters = 0.5,
            maxRotationDeviationRad = Math.PI,
            minFieldX = -10.0,
            maxFieldX = 10.0,
            minFieldY = -10.0,
            maxFieldY = 10.0,
            robotLengthMeters = 0.0,
            robotWidthMeters = 0.0
        )
        var state = rootReducer(
            RobotState(vision = VisionState(filterConfig = config)),
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true)
        )
        state = rootReducer(
            state,
            RobotAction.PoseUpdate(1.0, 0.0, 0.0, timestampMs = 200L)
        )
        val delayed = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(0.1, 0.0, 0.0), Rotation3d()),
            ambiguity = 0.01,
            tagCount = 2
        )

        state = rootReducer(
            state,
            RobotAction.VisionMeasurementsReceived(
                measurements = listOf(delayed),
                timestampMs = 220L,
                customVisionStdDevs = Vector3(0.2, 0.2, 0.5)
            )
        )

        assertTrue(state.vision.lastRejectionReason != "prefilter_rejected")
        assertEquals(1, state.vision.measurementCount)
    }
}
