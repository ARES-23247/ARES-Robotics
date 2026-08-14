package com.areslib.state

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StoreSnapshotImmutabilityTest {
    @Test
    fun `odometry reductions never mutate or retain estimator arrays from older snapshots`() {
        val initialGain = DoubleArray(9) { index -> index + 0.25 }
        val initialCovariance = DoubleArray(9) { index -> index + 1.0 }
        val initialState = RobotState(
            drive = DriveState(
                poseEstimator = PoseEstimatorState(
                    covarianceArray = initialCovariance.copyOf(),
                    lastKalmanGain = initialGain.copyOf()
                ),
                covarianceMatrix = initialCovariance.copyOf(),
                lastKalmanGain = initialGain.copyOf()
            )
        )
        val store = Store(initialState)
        val retainedInitial = store.state

        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = 0.0,
                yMeters = 0.0,
                headingRadians = 0.0,
                timestampMs = 1L,
                isReset = true
            )
        )
        val retainedReset = store.state
        val resetEstimatorCovariance = retainedReset.drive.poseEstimator.covarianceArray.copyOf()
        val resetEstimatorGain = retainedReset.drive.poseEstimator.lastKalmanGain.copyOf()
        val resetDriveCovariance = retainedReset.drive.covarianceMatrix.copyOf()
        val resetDriveGain = retainedReset.drive.lastKalmanGain.copyOf()
        assertTrue(retainedReset.drive.poseEstimator.history.isEmpty())

        assertNotSame(retainedInitial.drive.poseEstimator.covarianceArray, retainedReset.drive.poseEstimator.covarianceArray)
        assertNotSame(retainedInitial.drive.poseEstimator.lastKalmanGain, retainedReset.drive.poseEstimator.lastKalmanGain)

        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = 0.25,
                yMeters = -0.10,
                headingRadians = 0.05,
                timestampMs = 21L
            )
        )
        val latest = store.state

        assertArrayEquals(initialCovariance, retainedInitial.drive.poseEstimator.covarianceArray)
        assertArrayEquals(initialGain, retainedInitial.drive.poseEstimator.lastKalmanGain)
        assertArrayEquals(resetEstimatorCovariance, retainedReset.drive.poseEstimator.covarianceArray)
        assertArrayEquals(resetEstimatorGain, retainedReset.drive.poseEstimator.lastKalmanGain)
        assertArrayEquals(resetDriveCovariance, retainedReset.drive.covarianceMatrix)
        assertArrayEquals(resetDriveGain, retainedReset.drive.lastKalmanGain)
        assertNotSame(retainedReset.drive.poseEstimator.covarianceArray, latest.drive.poseEstimator.covarianceArray)
        assertNotSame(retainedReset.drive.poseEstimator.lastKalmanGain, latest.drive.poseEstimator.lastKalmanGain)
        assertNotSame(retainedReset.drive.covarianceMatrix, latest.drive.covarianceMatrix)
        assertNotSame(retainedReset.drive.lastKalmanGain, latest.drive.lastKalmanGain)
    }

    @Test
    fun `odometry and delayed vision preserve every retained estimator snapshot`() {
        val store = Store()
        store.dispatch(
            RobotAction.DriveHardwareUpdate(
                xVelocity = 1.0,
                yVelocity = 0.0,
                angularVelocity = 0.0,
                deltaX = 1.0,
                deltaY = 0.0,
                deltaHeading = 0.0,
                timestampMs = 100L
            )
        )
        val retainedAfterFirstOdometry = store.state
        val firstPoseX = retainedAfterFirstOdometry.drive.poseEstimator.estimatedPoseX
        val firstTimestamp = retainedAfterFirstOdometry.drive.poseEstimator.lastObservationTimestampMs
        val firstCovariance = retainedAfterFirstOdometry.drive.poseEstimator.covarianceArray.copyOf()

        store.dispatch(
            RobotAction.DriveHardwareUpdate(
                xVelocity = 1.0,
                yVelocity = 0.0,
                angularVelocity = 0.0,
                deltaX = 1.0,
                deltaY = 0.0,
                deltaHeading = 0.0,
                timestampMs = 150L
            )
        )
        val retainedAfterSecondOdometry = store.state
        val secondPoseX = retainedAfterSecondOdometry.drive.poseEstimator.estimatedPoseX
        val secondTimestamp = retainedAfterSecondOdometry.drive.poseEstimator.lastObservationTimestampMs
        val secondCovariance = retainedAfterSecondOdometry.drive.poseEstimator.covarianceArray.copyOf()

        assertTrue(retainedAfterFirstOdometry.drive.poseEstimator.history.isEmpty())
        assertTrue(retainedAfterSecondOdometry.drive.poseEstimator.history.isEmpty())

        store.dispatch(
            RobotAction.VisionMeasurementsReceived(
                measurements = listOf(
                    VisionMeasurement(
                        timestampMs = 100L,
                        targetPose = Pose3d(
                            Translation3d(1.5, 0.0, 0.0),
                            Rotation3d()
                        ),
                        tagId = 2,
                        ambiguity = 0.01
                    )
                ),
                timestampMs = 170L
            )
        )
        val latest = store.state

        assertEquals(firstPoseX, retainedAfterFirstOdometry.drive.poseEstimator.estimatedPoseX)
        assertEquals(firstTimestamp, retainedAfterFirstOdometry.drive.poseEstimator.lastObservationTimestampMs)
        assertArrayEquals(firstCovariance, retainedAfterFirstOdometry.drive.poseEstimator.covarianceArray)
        assertEquals(secondPoseX, retainedAfterSecondOdometry.drive.poseEstimator.estimatedPoseX)
        assertEquals(secondTimestamp, retainedAfterSecondOdometry.drive.poseEstimator.lastObservationTimestampMs)
        assertArrayEquals(secondCovariance, retainedAfterSecondOdometry.drive.poseEstimator.covarianceArray)
        assertTrue(latest.drive.poseEstimator.history.isEmpty())
    }

    @Test
    fun `stores own independent delayed vision histories`() {
        val firstStore = Store()
        val secondStore = Store()
        firstStore.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 100L, isReset = true))
        secondStore.dispatch(RobotAction.PoseUpdate(5.0, 0.0, 0.0, 100L, isReset = true))
        firstStore.dispatch(RobotAction.PoseUpdate(1.0, 0.0, 0.0, 200L))
        secondStore.dispatch(RobotAction.PoseUpdate(6.0, 0.0, 0.0, 200L))
        val retainedSecond = secondStore.state

        firstStore.dispatch(
            RobotAction.VisionMeasurementsReceived(
                measurements = listOf(
                    VisionMeasurement(
                        timestampMs = 100L,
                        targetPose = Pose3d(
                            Translation3d(0.25, 0.0, 0.0),
                            Rotation3d()
                        ),
                        tagId = 2,
                        ambiguity = 0.01
                    )
                ),
                timestampMs = 220L
            )
        )

        assertEquals(retainedSecond, secondStore.state)
        assertEquals(6.0, secondStore.state.drive.poseEstimator.estimatedPoseX, 1e-9)
        assertEquals(1, firstStore.state.vision.measurementCount)
        assertEquals(0, secondStore.state.vision.measurementCount)
    }

    @Test
    fun `published estimator history cannot be mutated`() {
        val history = Store().state.drive.poseEstimator.history
        assertTrue(history.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            history.addEntry(1L, Pose2d(), Matrix3x3(), 1.0)
        }
    }
}
