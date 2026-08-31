package com.areslib.frc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.state.VisionState
import com.areslib.reducer.rootReducer
import com.areslib.action.RobotAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.frc.TestSwerveHardwareIO
import com.areslib.state.DriveState
import com.areslib.math.geometry.Pose2d
import com.areslib.state.VisionSolverType
import com.areslib.state.RecoveryTuningState
import com.areslib.state.TuningState

/**
 * MockFrcVisionIO declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class MockFrcVisionIO(var mockMeasurements: List<VisionMeasurement> = emptyList()) : VisionIO {
    val isConnected: Boolean = true
    var lastYawDegrees = Double.NaN
    var lastImuMode = Int.MIN_VALUE
    var orientationCalls = 0
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

    override fun setOrientation(
        yawDegrees: Double,
        yawRateDegPerSec: Double,
        pitchDegrees: Double,
        pitchRateDegPerSec: Double,
        rollDegrees: Double,
        rollRateDegPerSec: Double,
        linearVelocityMps: Double
    ) {
        orientationCalls++
        lastYawDegrees = yawDegrees
    }

    override fun setImuMode(mode: Int) {
        lastImuMode = mode
    }
}

/**
 * FrcVisionTrackerTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class FrcVisionTrackerTest {
    private class RecordingSwerveIO : TestSwerveHardwareIO() {
        var visionCalls = 0
        var lastTimestampSeconds = Double.NaN
        var lastStdDevX = Double.NaN
        var lastStdDevY = Double.NaN
        var lastStdDevHeading = Double.NaN
        var seedCalls = 0
        var lastSeedPose = Pose2d()
        var historicalPoseAvailable = false
        val historicalPose = doubleArrayOf(0.0, 0.0, 0.0)
        var sampledTimestampSeconds = Double.NaN

        override fun read(): DriveState = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) {}
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) {
            visionCalls++
            lastTimestampSeconds = timestampSeconds
        }

        override fun addVisionMeasurement(
            pose: Pose2d,
            timestampSeconds: Double,
            stdDevXMeters: Double,
            stdDevYMeters: Double,
            stdDevHeadingRadians: Double
        ) {
            visionCalls++
            lastTimestampSeconds = timestampSeconds
            lastStdDevX = stdDevXMeters
            lastStdDevY = stdDevYMeters
            lastStdDevHeading = stdDevHeadingRadians
        }

        override fun seedPose(pose: Pose2d) {
            seedCalls++
            lastSeedPose = pose
        }

        override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean {
            sampledTimestampSeconds = timestampSeconds
            if (!historicalPoseAvailable) return false
            out[0] = historicalPose[0]
            out[1] = historicalPose[1]
            out[2] = historicalPose[2]
            return true
        }
    }

    @Test
    fun `invalid drive observations are not forwarded to MegaTag2`() {
        val store = Store(
            RobotState(
                drive = DriveState(
                    measuredMotionValid = false,
                    imuMeasurementsValid = true
                )
            ),
            ::rootReducer
        )
        val vision = MockFrcVisionIO()
        val tracker = FrcVisionTracker(
            store = store,
            visionIO = vision,
            swerveIO = RecordingSwerveIO(),
            isSimulation = false,
            estimatorTimeSecondsProvider = { 1.0 },
            fpgaToEstimatorTimeSeconds = { it },
            isDisabledProvider = { false }
        )

        tracker.update(1_000L)

        assertEquals(0, vision.orientationCalls)
        assertEquals("REJECTED_DRIVE_SIGNALS", tracker.lastVisionStatus)
        assertTrue(RobotStatusTracker.visionConnected)
    }

    @Test
    fun `test vision measurement forwarding and store dispatch`() {
        val initialState = RobotState(
            vision = VisionState(
                filterConfig = VisionFilterConfig.frcDefaults()
            )
        )
        val store = Store(initialState, ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(1.0, 1.0, 0.0, timestampMs = 100L, isReset = true))
        val mockMeasurement = VisionMeasurement(
            tagId = 2,
            targetPose = Pose3d(Translation3d(2.0, 3.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.02,
            timestampMs = 150
        )
        val visionIO = MockFrcVisionIO(listOf(mockMeasurement))
        
        // We run in isSimulation = true to bypass WPILib Timer JNI calls under unit test
        val tracker = FrcVisionTracker(store, visionIO, swerveIO = null, isSimulation = true)
        
        tracker.update(150)
        
        // Check if the store received the measurements
        val received = store.state.vision.measurements
        assertTrue(received.isNotEmpty(), "Store should have received visual measurements")
        assertEquals(2, received[0].tagId)
        assertEquals(0.02, received[0].ambiguity, 1e-6)
        assertTrue(RobotStatusTracker.visionConnected, "RobotStatusTracker vision connection state should be true")
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPoseX, 0.0,
            "FRC vision is already owned by the platform estimator and must not be fused twice")
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPoseY, 0.0)
    }

    @Test
    fun `accepted camera frame reaches swerve estimator once with covariance`() {
        val store = Store(
            RobotState(vision = VisionState(filterConfig = VisionFilterConfig.frcDefaults())),
            ::rootReducer
        )
        store.dispatch(RobotAction.PoseUpdate(1.0, 1.0, 0.0, timestampMs = 100L, isReset = true))
        val measurement = VisionMeasurement(
            tagId = 2,
            targetPose = Pose3d(Translation3d(1.2, 1.1, 0.0), Rotation3d()),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d()),
            ambiguity = 0.02,
            timestampMs = 150L,
            sourceId = "limelight-left",
            frameId = 42L,
            solverType = VisionSolverType.MEGATAG2,
            stdDevXMeters = 0.2,
            stdDevYMeters = 0.3,
            stdDevHeadingRadians = 1.0e6
        )
        val visionIO = MockFrcVisionIO(listOf(measurement))
        val swerveIO = RecordingSwerveIO()
        val tracker = FrcVisionTracker(
            store,
            visionIO,
            swerveIO,
            isSimulation = false,
            estimatorTimeSecondsProvider = { 10.0 }
        )

        tracker.update(200L)

        assertEquals(1, swerveIO.visionCalls)
        assertEquals(9.95, swerveIO.lastTimestampSeconds, 1e-9)
        assertEquals(0.2, swerveIO.lastStdDevX, 0.0)
        assertEquals(0.3, swerveIO.lastStdDevY, 0.0)
        assertEquals(1.0e6, swerveIO.lastStdDevHeading, 0.0)
        assertEquals("ACCEPTED", tracker.lastVisionStatus)

        tracker.update(220L)

        assertEquals(1, swerveIO.visionCalls, "A cached camera frame must not be fused twice")
        assertEquals("STALE_FRAME", tracker.lastVisionStatus)

        tracker.fusionEnabled = false
        visionIO.mockMeasurements = listOf(measurement.copy(timestampMs = 180L, frameId = 43L))
        tracker.update(240L)

        assertEquals(1, swerveIO.visionCalls, "Odometry calibration must observe but not fuse camera frames")
        assertEquals("FUSION_DISABLED", tracker.lastVisionStatus)
    }

    @Test
    fun `atomic capture timestamp and historical pose drive residual gating`() {
        val store = Store(
            RobotState(vision = VisionState(filterConfig = VisionFilterConfig.frcDefaults())),
            ::rootReducer
        )
        store.dispatch(RobotAction.PoseUpdate(1.0, 1.0, 0.0, timestampMs = 100L, isReset = true))
        val measurement = VisionMeasurement(
            timestampMs = 150L,
            captureTimestampMicros = 2_000_000L,
            tagId = 2,
            targetPose = Pose3d(Translation3d(4.0, 2.0, 0.0), Rotation3d()),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d()),
            sourceId = "limelight-back",
            frameId = 99L,
            solverType = VisionSolverType.MEGATAG2
        )
        val io = MockFrcVisionIO(listOf(measurement))
        val swerve = RecordingSwerveIO().apply {
            historicalPoseAvailable = true
            historicalPose[0] = 4.0
            historicalPose[1] = 2.0
        }
        val tracker = FrcVisionTracker(
            store, io, swerve, isSimulation = false,
            estimatorTimeSecondsProvider = { 999.0 },
            fpgaToEstimatorTimeSeconds = { it + 100.0 }
        )

        tracker.update(200L)

        assertEquals(1, swerve.visionCalls)
        assertEquals(102.0, swerve.sampledTimestampSeconds, 0.0)
        assertEquals(102.0, swerve.lastTimestampSeconds, 0.0)
    }

    @Test
    fun `disabled robot reseeds only after consistent independent MegaTag1 poses`() {
        val store = Store(
            RobotState(
                vision = VisionState(filterConfig = VisionFilterConfig.frcDefaults()),
                tuning = TuningState(
                    recovery = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0)
                )
            ),
            ::rootReducer
        )
        store.dispatch(RobotAction.PoseUpdate(1.0, 1.0, 0.0, timestampMs = 100L, isReset = true))
        val first = VisionMeasurement(
            timestampMs = 150L,
            tagId = 2,
            targetPose = Pose3d(Translation3d(1.1, 1.0, 0.0), Rotation3d()),
            recoveryPose = Pose3d(Translation3d(3.0, 3.0, 0.0), Rotation3d(0.0, 0.0, 0.8)),
            hasRecoveryPose = true,
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d()),
            ambiguity = 0.02,
            recoveryAmbiguity = 0.02,
            recoveryAmbiguityAvailable = true,
            tagCount = 2,
            sourceId = "limelight-left",
            frameId = 41L,
            solverType = VisionSolverType.MEGATAG2,
            stdDevXMeters = 0.2,
            stdDevYMeters = 0.2,
            stdDevHeadingRadians = 1.0e6
        )
        val visionIO = MockFrcVisionIO(listOf(first))
        val swerveIO = RecordingSwerveIO()
        val tracker = FrcVisionTracker(
            store,
            visionIO,
            swerveIO,
            isSimulation = false,
            estimatorTimeSecondsProvider = { 10.0 },
            isDisabledProvider = { true }
        )

        tracker.update(200L)
        assertEquals(0, swerveIO.seedCalls)

        visionIO.mockMeasurements = listOf(
            first.copy(
                timestampMs = 670L,
                frameId = 42L,
                recoveryPose = Pose3d(
                    Translation3d(3.04, 2.98, 0.0),
                    Rotation3d(0.0, 0.0, 0.82)
                )
            )
        )
        tracker.update(720L)

        assertEquals(1, swerveIO.seedCalls)
        assertEquals(3.02, swerveIO.lastSeedPose.x, 1e-9)
        assertEquals(2.99, swerveIO.lastSeedPose.y, 1e-9)
        assertEquals(0.81, swerveIO.lastSeedPose.heading.radians, 1e-3)
        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
    }

    @Test
    fun `MegaTag2 receives estimator heading and lifecycle IMU mode`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.7, timestampMs = 100L, isReset = true))
        val visionIO = MockFrcVisionIO()
        var disabled = true
        val tracker = FrcVisionTracker(
            store,
            visionIO,
            swerveIO = RecordingSwerveIO(),
            isSimulation = true,
            isDisabledProvider = { disabled }
        )

        tracker.update(100L)
        assertEquals(Math.toDegrees(0.7), visionIO.lastYawDegrees, 1e-9)
        assertEquals(1, visionIO.lastImuMode)

        disabled = false
        tracker.update(120L)
        assertEquals(4, visionIO.lastImuMode)
    }
}
