package com.areslib.state

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
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
        val resetHistoryX = retainedReset.drive.poseEstimator.history[0].x

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
        assertEquals(resetHistoryX, retainedReset.drive.poseEstimator.history[0].x)
        assertNotSame(retainedReset.drive.poseEstimator.covarianceArray, latest.drive.poseEstimator.covarianceArray)
        assertNotSame(retainedReset.drive.poseEstimator.lastKalmanGain, latest.drive.poseEstimator.lastKalmanGain)
        assertNotSame(retainedReset.drive.covarianceMatrix, latest.drive.covarianceMatrix)
        assertNotSame(retainedReset.drive.lastKalmanGain, latest.drive.lastKalmanGain)
    }
}
