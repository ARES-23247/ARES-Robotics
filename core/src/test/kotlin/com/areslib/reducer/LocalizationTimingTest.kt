package com.areslib.reducer

import com.areslib.Store
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
    fun `timestamp zero is a real observation and duplicate zero is rejected`() {
        val store = Store()
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 0L, isReset = true))
        val reset = store.state
        store.dispatch(RobotAction.PoseUpdate(1.0, 0.0, 0.0, timestampMs = 0L))

        assertEquals(reset.drive.odometryX, store.state.drive.odometryX, 0.0)
        assertEquals(0L, store.state.drive.poseEstimator.lastObservationTimestampMs)
    }

    @Test
    fun `duplicate odometry timestamp is rejected`() {
        val store = Store()
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true))
        store.dispatch(RobotAction.PoseUpdate(0.1, 0.0, 0.0, timestampMs = 120L))
        val state = store.state
        store.dispatch(RobotAction.PoseUpdate(0.2, 0.0, 0.0, timestampMs = 120L))
        val duplicate = store.state

        assertEquals(state.drive.odometryX, duplicate.drive.odometryX, 0.0)
        assertEquals(
            state.drive.poseEstimator.estimatedPoseX,
            duplicate.drive.poseEstimator.estimatedPoseX,
            0.0
        )
    }

    @Test
    fun `stationary process noise scales with measured interval`() {
        val shortStore = Store()
        val longStore = Store()
        shortStore.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true))
        longStore.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true))
        shortStore.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 120L))
        longStore.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 200L))
        val shortInterval = shortStore.state.drive
        val longInterval = longStore.state.drive

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
        val store = Store(RobotState(vision = VisionState(filterConfig = config)))
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L, isReset = true))
        store.dispatch(RobotAction.PoseUpdate(1.0, 0.0, 0.0, timestampMs = 200L))
        val delayed = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(0.1, 0.0, 0.0), Rotation3d()),
            ambiguity = 0.01,
            tagCount = 2
        )

        store.dispatch(
            RobotAction.VisionMeasurementsReceived(
                measurements = listOf(delayed),
                timestampMs = 220L,
                customVisionStdDevs = Vector3(0.2, 0.2, 0.5)
            )
        )
        val state = store.state

        assertTrue(state.vision.lastRejectionReason != "prefilter_rejected")
        assertEquals(1, state.vision.measurementCount)
    }
}
