package com.areslib.ftc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.action.RobotAction
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Vector3
import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.state.DriveState
import com.areslib.state.RecoveryTuningState
import com.areslib.state.TuningState
import com.areslib.state.VisionState
import com.areslib.state.Alliance
import com.areslib.reducer.rootReducer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * MockVisionIO declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class MockVisionIO(var mockMeasurements: List<VisionMeasurement> = emptyList()) : VisionIO {
    val isConnected: Boolean = true
    /**
     * updateInputs declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun updateInputs(inputs: VisionIOInputs) {
        inputs.isConnected = isConnected
        inputs.measurements = mockMeasurements
    }
}

/**
 * FtcVisionTrackerTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class FtcVisionTrackerTest {
    @Test
    fun `tracker covariance is passed to the actual EKF update`() {
        fun fusedX(stdDev: Double): Double {
            val store = Store(RobotState(), ::rootReducer)
            store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 0L, isReset = true))
            val tracker = FtcVisionTracker(
                store,
                MockVisionIO(listOf(measurement(0.2, 0.0, 0.0, 100L))),
                pinpointIO = null,
                stdDevs = Vector3(stdDev, stdDev, stdDev)
            )
            tracker.hasInitializedPoseWithVision = true
            tracker.update(100L)
            return store.state.drive.poseEstimator.estimatedPoseX
        }

        val tightlyTrusted = fusedX(0.05)
        val looselyTrusted = fusedX(1.0)

        assertTrue(tightlyTrusted > looselyTrusted * 5.0)
    }

    @Test
    fun `vision snap rebases every odometry source through callback`() {
        val store = Store(RobotState(), ::rootReducer)
        val measurement = VisionMeasurement(
            tagId = 2,
            targetPose = Pose3d(Translation3d(0.75, -0.25, 0.0), Rotation3d(0.0, 0.0, 0.3)),
            ambiguity = 0.01,
            timestampMs = 100L
        )
        var reseededPose: com.areslib.math.geometry.Pose2d? = null
        val tracker = FtcVisionTracker(
            store,
            MockVisionIO(listOf(measurement)),
            pinpointIO = null,
            onOdometryReseed = { reseededPose = it }
        )

        tracker.update(100L)

        val pose = assertNotNull(reseededPose)
        assertEquals(0.75, pose.x, 1e-9)
        assertEquals(-0.25, pose.y, 1e-9)
        assertEquals(0.3, pose.heading.radians, 1e-9)
    }

    @Test
    fun `test initial alignment snap`() {
        val store = Store(RobotState(), ::rootReducer)
        val mockMeasurement = VisionMeasurement(
            tagId = 3,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.5)), // 0.5 yaw rad
            ambiguity = 0.01,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(mockMeasurement))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)

        tracker.update(100)

        // EKF pose estimator should have snapped to tag pose
        val estPose = store.state.drive.poseEstimator.estimatedPose
        assertEquals(1.0, estPose.x, 1e-6)
        assertEquals(1.0, estPose.y, 1e-6)
        assertEquals(0.5, estPose.heading.radians, 1e-6)
        assertEquals("INIT_ALIGN_SNAP", tracker.lastVisionStatus)
    }

    @Test
    fun `limelight field pose remains alliance independent`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.SetAlliance(Alliance.RED, timestampMs = 1L))
        val tracker = FtcVisionTracker(
            store,
            MockVisionIO(listOf(measurement(0.5, -0.25, 0.2, 100L))),
            pinpointIO = null
        )

        tracker.update(100L)

        val pose = assertNotNull(tracker.lastLimelightPose)
        assertEquals(0.5, pose.x, 1e-9)
        assertEquals(-0.25, pose.y, 1e-9)
        assertEquals(0.2, pose.heading.radians, 1e-9)
        assertEquals(0.5, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
        assertEquals(-0.25, store.state.drive.poseEstimator.estimatedPose.y, 1e-9)
    }

    @Test
    fun `test tag ambiguity rejection`() {
        val store = Store(RobotState(), ::rootReducer)
        // High ambiguity tag (> maxAmbiguity, which defaults to 0.15)
        val mockMeasurement = VisionMeasurement(
            tagId = 3,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.5)),
            ambiguity = 0.5,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(mockMeasurement))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)

        tracker.update(100)

        // It should be rejected and EKF pose remains default (0, 0, 0)
        val estPose = store.state.drive.poseEstimator.estimatedPose
        assertEquals(0.0, estPose.x, 1e-6)
        assertTrue(tracker.lastVisionStatus.startsWith("REJ_AMBIG"))
    }
    @Test
    fun `test kidnapped robot recovery snap`() {
        val store = Store(RobotState(), ::rootReducer)
        // Set the state so that the robot is stationary
        store.dispatch(RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L))
        
        // High confidence target but far away (disjointed from 0,0,0)
        val mockMeasurement = VisionMeasurement(
            tagId = 3,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.5)),
            ambiguity = 0.01,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(mockMeasurement))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        
        // Initial snap
        tracker.update(100)
        assertEquals("INIT_ALIGN_SNAP", tracker.lastVisionStatus)
        
        // Move the physical robot to 0,0 (in EKF) to simulate driving away
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 200L, isReset = true))
        
        // Now feed 44 consecutive high-confidence but highly disjointed readings (Mahalanobis rejection)
        for (i in 1..44) {
            val m = mockMeasurement.copy(timestampMs = 200L + i * 100L)
            visionIO.mockMeasurements = listOf(m)
            tracker.update(200L + i * 100L)
            assertTrue(tracker.lastVisionStatus.startsWith("REJ_"))
            // Pose should NOT snap yet
            assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
        }
        
        // Feed the 45th reading
        val m45 = mockMeasurement.copy(timestampMs = 4700L)
        visionIO.mockMeasurements = listOf(m45)
        tracker.update(4700L)

        // It SHOULD snap
        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
    }

    @Test
    fun `test kidnapped robot recovery averages stationary poses over time`() {
        val recoveryFilter = VisionFilterConfig.ftcDefaults().copy(maxDistanceMeters = 0.1)
        val store = Store(
            RobotState(vision = VisionState(filterConfig = recoveryFilter)),
            ::rootReducer
        )
        val visionIO = MockVisionIO()
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        tracker.hasInitializedPoseWithVision = true

        // Supply 45 readings with varying X inside the physical field (0.6 to 1.2, avg = 0.9)
        for (i in 1..45) {
            val xVal = 0.6 + (i - 1) * (0.6 / 44.0)
            val m = VisionMeasurement(
                tagId = 1,
                targetPose = Pose3d(Translation3d(xVal, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
                ambiguity = 0.01,
                timestampMs = 200L + i * 100L
            )
            visionIO.mockMeasurements = listOf(m)
            tracker.update(200L + i * 100L)
        }

        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(0.9, store.state.drive.poseEstimator.estimatedPose.x, 1e-3)
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPose.y, 1e-3)
    }

    @Test
    fun `init alignment rejects center-inside poses whose rotated footprint crosses field bounds`() {
        val config = footprintConfig()
        val cases = listOf(
            Triple(0.75, 0.0, 0.0),
            Triple(0.0, 0.75, Math.PI / 2.0),
            Triple(0.67, 0.67, Math.PI / 4.0)
        )

        for ((x, y, heading) in cases) {
            val store = Store(RobotState(vision = VisionState(filterConfig = config)), ::rootReducer)
            val measurement = measurement(x, y, heading, 100L)
            val tracker = FtcVisionTracker(store, MockVisionIO(listOf(measurement)), pinpointIO = null)

            tracker.update(100L)

            assertEquals("REJ_BOUNDS", tracker.lastVisionStatus)
            assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
            assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.y, 1e-9)
            assertTrue(!tracker.hasInitializedPoseWithVision)
        }
    }

    @Test
    fun `init alignment accepts a rotated footprint fully inside boundary tolerance`() {
        val config = footprintConfig()
        val store = Store(RobotState(vision = VisionState(filterConfig = config)), ::rootReducer)
        val measurement = measurement(0.666, 0.666, Math.PI / 4.0, 100L)
        val tracker = FtcVisionTracker(store, MockVisionIO(listOf(measurement)), pinpointIO = null)

        tracker.update(100L)

        assertEquals("INIT_ALIGN_SNAP", tracker.lastVisionStatus)
        assertEquals(0.666, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
        assertEquals(0.666, store.state.drive.poseEstimator.estimatedPose.y, 1e-9)
    }

    @Test
    fun `init alignment applies distance yaw shock and finite gates`() {
        val cases = listOf(
            Triple(
                RobotState(vision = VisionState(filterConfig = footprintConfig().copy(maxDistanceMeters = 0.1))),
                measurement(0.6, 0.0, 0.0, 100L),
                "REJ_DIST"
            ),
            Triple(
                RobotState(vision = VisionState(filterConfig = footprintConfig().copy(maxRotationDeviationRad = 0.1))),
                measurement(0.0, 0.0, 0.5, 100L),
                "REJ_YAW"
            ),
            Triple(
                RobotState(
                    drive = DriveState(xAccelerationG = 3.0),
                    vision = VisionState(filterConfig = footprintConfig())
                ),
                measurement(0.0, 0.0, 0.0, 100L),
                "REJ_SHOCK"
            ),
            Triple(
                RobotState(
                    drive = DriveState(measuredAngularVelocityRadiansPerSecond = 3.0),
                    vision = VisionState(filterConfig = footprintConfig())
                ),
                measurement(0.0, 0.0, 0.0, 100L),
                "REJ_RATE"
            ),
            Triple(
                RobotState(vision = VisionState(filterConfig = footprintConfig())),
                measurement(Double.NaN, 0.0, 0.0, 100L),
                "REJ_INVALID"
            )
        )

        for ((initialState, measurement, expectedStatus) in cases) {
            val store = Store(initialState, ::rootReducer)
            val tracker = FtcVisionTracker(store, MockVisionIO(listOf(measurement)), pinpointIO = null)

            tracker.update(100L)

            assertEquals(expectedStatus, tracker.lastVisionStatus)
            assertTrue(!tracker.hasInitializedPoseWithVision)
            assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
        }
    }

    @Test
    fun `duplicate frames do not advance kidnapped robot recovery`() {
        val config = footprintConfig().copy(maxRotationDeviationRad = 0.1)
        val tuning = TuningState(
            recovery = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0)
        )
        val store = Store(
            RobotState(vision = VisionState(filterConfig = config), tuning = tuning),
            ::rootReducer
        )
        val rejected = measurement(0.6, 0.0, 0.5, 100L)
        val visionIO = MockVisionIO(listOf(rejected))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        tracker.hasInitializedPoseWithVision = true

        tracker.update(100L)
        tracker.update(120L)

        assertEquals("STALE_FRAME", tracker.lastVisionStatus)
        assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)

        visionIO.mockMeasurements = listOf(rejected.copy(timestampMs = 101L))
        tracker.update(140L)

        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(0.6, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
    }

    @Test
    fun `independent MegaTag1 yaw recovers a stationary rotated robot`() {
        val config = footprintConfig().copy(maxRotationDeviationRad = 0.1)
        val tuning = TuningState(
            recovery = RecoveryTuningState(stolenRobotRejectionThreshold = 3.0)
        )
        val store = Store(
            RobotState(vision = VisionState(filterConfig = config), tuning = tuning),
            ::rootReducer
        )
        val visionIO = MockVisionIO()
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        tracker.hasInitializedPoseWithVision = true

        repeat(3) { index ->
            val timestamp = 100L + index * 100L
            visionIO.mockMeasurements = listOf(
                VisionMeasurement(
                    timestampMs = timestamp,
                    targetPose = Pose3d(Translation3d(0.0, 0.0, 0.0), Rotation3d()),
                    recoveryPose = Pose3d(Translation3d(0.4, 0.2, 0.0), Rotation3d(0.0, 0.0, 1.0)),
                    hasRecoveryPose = true,
                    solverType = com.areslib.state.VisionSolverType.MEGATAG2,
                    tagId = 1,
                    ambiguity = 0.01
                )
            )
            tracker.update(timestamp)
        }

        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(0.4, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
        assertEquals(0.2, store.state.drive.poseEstimator.estimatedPose.y, 1e-9)
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-9)
    }

    @Test
    fun `old first frame cannot initialize pose`() {
        val store = Store(RobotState(), ::rootReducer)
        val tracker = FtcVisionTracker(
            store,
            MockVisionIO(listOf(measurement(0.5, 0.0, 0.0, 100L))),
            pinpointIO = null
        )

        tracker.update(1000L)

        assertEquals("STALE_FRAME", tracker.lastVisionStatus)
        assertTrue(!tracker.hasInitializedPoseWithVision)
        assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
    }

    @Test
    fun `initial snap uses measured motion rather than drive command`() {
        val movingStore = Store(
            RobotState(
                drive = DriveState(
                    xVelocityMetersPerSecond = 0.0,
                    measuredFieldXVelocityMetersPerSecond = 1.0
                )
            ),
            ::rootReducer
        )
        var movingReseeded = false
        val movingTracker = FtcVisionTracker(
            movingStore,
            MockVisionIO(listOf(measurement(0.5, 0.0, 0.0, 100L))),
            pinpointIO = null,
            onOdometryReseed = { movingReseeded = true }
        )
        movingTracker.update(100L)
        assertTrue(!movingTracker.hasInitializedPoseWithVision)
        assertTrue(!movingReseeded)

        val stoppedStore = Store(
            RobotState(
                drive = DriveState(
                    xVelocityMetersPerSecond = 3.0,
                    measuredFieldXVelocityMetersPerSecond = 0.0
                )
            ),
            ::rootReducer
        )
        var stoppedReseeded = false
        val stoppedTracker = FtcVisionTracker(
            stoppedStore,
            MockVisionIO(listOf(measurement(0.5, 0.0, 0.0, 100L))),
            pinpointIO = null,
            onOdometryReseed = { stoppedReseeded = true }
        )
        stoppedTracker.update(100L)
        assertTrue(stoppedTracker.hasInitializedPoseWithVision)
        assertTrue(stoppedReseeded)
        assertEquals("INIT_ALIGN_SNAP", stoppedTracker.lastVisionStatus)
    }

    @Test
    fun `MegaTag2 yaw remains ignored by the centralized vision reducer`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.7, timestampMs = 0L, isReset = true))
        val mt2 = measurement(0.1, 0.0, -2.0, 100L).copy(
            solverType = com.areslib.state.VisionSolverType.MEGATAG2,
            stdDevXMeters = 0.05,
            stdDevYMeters = 0.05,
            stdDevHeadingRadians = 0.001
        )
        val tracker = FtcVisionTracker(store, MockVisionIO(listOf(mt2)), pinpointIO = null)
        tracker.hasInitializedPoseWithVision = true

        tracker.update(100L)

        assertEquals(0.7, store.state.drive.poseEstimator.estimatedPoseHeading, 1e-6)
    }

    @Test
    fun `reseed ignores footprint-outside frames before counting plausible rejections`() {
        val config = footprintConfig().copy(maxRotationDeviationRad = 0.1)
        val tuning = TuningState(
            recovery = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0)
        )
        val store = Store(
            RobotState(vision = VisionState(filterConfig = config), tuning = tuning),
            ::rootReducer
        )
        val visionIO = MockVisionIO(listOf(measurement(0.75, 0.0, 0.0, 100L)))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        tracker.hasInitializedPoseWithVision = true

        tracker.update(100L)
        assertEquals("REJ_BOUNDS", tracker.lastVisionStatus)

        val plausibleRejected = measurement(0.6, 0.0, 0.5, 200L)
        visionIO.mockMeasurements = listOf(plausibleRejected)
        tracker.update(200L)
        assertTrue(tracker.lastVisionStatus.startsWith("REJ_"))
        assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)

        visionIO.mockMeasurements = listOf(plausibleRejected.copy(timestampMs = 300L))
        tracker.update(300L)
        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(0.6, store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
        assertEquals(0.5, store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-9)
    }

    private fun footprintConfig() = VisionFilterConfig(
        maxDistanceMeters = 10.0,
        maxRotationDeviationRad = Math.PI,
        minFieldX = -1.0,
        maxFieldX = 1.0,
        minFieldY = -1.0,
        maxFieldY = 1.0,
        robotLengthMeters = 0.6,
        robotWidthMeters = 0.4,
        fieldBoundsToleranceMeters = 0.02
    )

    private fun measurement(x: Double, y: Double, heading: Double, timestampMs: Long) = VisionMeasurement(
        tagId = 1,
        targetPose = Pose3d(Translation3d(x, y, 0.0), Rotation3d(0.0, 0.0, heading)),
        ambiguity = 0.01,
        timestampMs = timestampMs
    )
}
