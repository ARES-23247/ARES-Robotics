package com.areslib.state

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.estimation.HistoryBuffer
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoreSnapshotImmutabilityTest {
    @Test
    fun `odometry reductions preserve older flattened estimator snapshots`() {
        val initialEstimator = PoseEstimatorSnapshot(
            covariance00 = 1.0,
            covariance01 = 2.0,
            covariance02 = 3.0,
            covariance10 = 4.0,
            covariance11 = 5.0,
            covariance12 = 6.0,
            covariance20 = 7.0,
            covariance21 = 8.0,
            covariance22 = 9.0,
            kalmanGain00 = 0.25,
            kalmanGain01 = 1.25,
            kalmanGain02 = 2.25,
            kalmanGain10 = 3.25,
            kalmanGain11 = 4.25,
            kalmanGain12 = 5.25,
            kalmanGain20 = 6.25,
            kalmanGain21 = 7.25,
            kalmanGain22 = 8.25
        )
        val store = Store(RobotState(drive = DriveState(poseEstimator = initialEstimator)))
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
        val resetEstimator = retainedReset.drive.poseEstimator

        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = 0.25,
                yMeters = -0.10,
                headingRadians = 0.05,
                timestampMs = 21L
            )
        )

        assertEquals(initialEstimator, retainedInitial.drive.poseEstimator)
        assertEquals(resetEstimator, retainedReset.drive.poseEstimator)
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
        val firstEstimator = retainedAfterFirstOdometry.drive.poseEstimator
        val firstCovariance = firstEstimator.copyCovariance()

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
        val secondEstimator = retainedAfterSecondOdometry.drive.poseEstimator
        val secondCovariance = secondEstimator.copyCovariance()

        store.dispatch(
            RobotAction.VisionMeasurementsReceived(
                measurements = listOf(
                    VisionMeasurement(
                        timestampMs = 100L,
                        targetPose = Pose3d(Translation3d(1.5, 0.0, 0.0), Rotation3d()),
                        tagId = 2,
                        ambiguity = 0.01
                    )
                ),
                timestampMs = 170L
            )
        )

        assertEquals(firstEstimator, retainedAfterFirstOdometry.drive.poseEstimator)
        assertArrayEquals(firstCovariance, retainedAfterFirstOdometry.drive.poseEstimator.copyCovariance())
        assertEquals(secondEstimator, retainedAfterSecondOdometry.drive.poseEstimator)
        assertArrayEquals(secondCovariance, retainedAfterSecondOdometry.drive.poseEstimator.copyCovariance())
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
                        targetPose = Pose3d(Translation3d(0.25, 0.0, 0.0), Rotation3d()),
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
    fun `published estimator snapshot exposes no mutable fields arrays or history`() {
        val fields = PoseEstimatorSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

        assertTrue(fields.isNotEmpty())
        for (field in fields) {
            assertTrue(Modifier.isFinal(field.modifiers), "${field.name} must be final")
            assertFalse(field.type.isArray, "${field.name} must not expose an array")
            assertFalse(
                HistoryBuffer::class.java.isAssignableFrom(field.type),
                "${field.name} must not expose estimator history"
            )
        }
    }
}
