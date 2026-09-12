package com.areslib.ftc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.*
import com.areslib.state.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FtcVisionSelectionAuditTest {
    private class Camera : VisionIO {
        var frames = emptyList<VisionMeasurement>()
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = true
            inputs.measurements = frames
        }
    }
    private class Harness(initialized: Boolean = false, config: VisionFilterConfig = VisionFilterConfig(),
        estimatedX: Double = 0.0) {
        val store = Store(RobotState(
            drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true,
                poseEstimator = PoseEstimatorSnapshot(estimatedPoseX = estimatedX,
                    covariance00 = 1e-4, covariance11 = 1e-4, covariance22 = 1e-4)),
            vision = VisionState(filterConfig = config),
            tuning = TuningState(recovery = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0))))
        val camera = Camera()
        val seeds = ArrayList<Pose2d>()
        val rawBatches = ArrayList<List<String>>()
        val tracker = FtcVisionTracker(store, camera, null, onOdometryReseed = { seeds.add(it) }).apply {
            hasInitializedPoseWithVision = initialized
        }
        init {
            if (initialized) store.dispatch(RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 50L))
            store.actionListener = { action ->
                if (action is RobotAction.VisionMeasurementsReceived) rawBatches.add(action.measurements.map { it.sourceId })
            }
        }
        fun poll(frames: List<VisionMeasurement>, time: Long = 100L) {
            camera.frames = frames.map { it.copy(timestampMs = time, frameId = time) }
            tracker.update(time)
        }
    }
    private fun frame(source: String, x: Double, ambiguity: Double = 0.01) = VisionMeasurement(
        sourceId = source, timestampMs = 100L, frameId = 1L, tagId = 1, tagCount = 2,
        targetPose = Pose3d(Translation3d(x, 0.0, 0.0), Rotation3d()),
        robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d()),
        ambiguity = ambiguity, stdDevXMeters = 0.01, stdDevYMeters = 0.01, stdDevHeadingRadians = 0.01)

    @Test fun `a later accepted camera cannot hide the selected camera rejection`() {
        val h = Harness(initialized = true)
        val frames = listOf(frame("selected", 0.3), frame("other", 0.0, 0.1))
        h.poll(frames)
        assertEquals("REJ_MAHALANOBIS", h.tracker.lastVisionStatus)
        h.poll(frames, 200L)
        assertEquals(1, h.seeds.size)
        assertEquals(0.3, h.seeds.single().x, 1e-12)
        assertEquals(listOf(listOf("selected", "other"), listOf("selected", "other")), h.rawBatches)
    }

    @Test fun `a later rejected camera cannot trigger recovery of an accepted selected camera`() {
        val h = Harness(initialized = true)
        val frames = listOf(frame("selected", 0.0), frame("other", 0.3, 0.1))
        h.poll(frames)
        assertEquals("ACCEPTED", h.tracker.lastVisionStatus)
        h.poll(frames, 200L)
        assertTrue(h.seeds.isEmpty())
        assertEquals(2, h.store.state.vision.measurementCount)
        assertEquals(2, h.store.state.vision.rejectionCount)
    }

    @Test fun `nonfinite first pose cannot hide a physically valid initialization candidate`() {
        val h = Harness()
        h.poll(listOf(frame("bad", Double.NaN, 0.0), frame("good", 0.2)))
        assertEquals(1, h.seeds.size)
        assertEquals(0.2, h.seeds.single().x, 0.0)
    }

    @Test fun `invalid available ambiguity cannot outrank a valid initialization candidate`() {
        for (bad in doubleArrayOf(-0.1, Double.NaN)) {
            val h = Harness()
            h.poll(listOf(frame("bad", 0.1, bad), frame("good", 0.2)))
            assertEquals(1, h.seeds.size, "ambiguity=$bad")
            assertEquals(0.2, h.seeds.single().x, 0.0)
        }
    }

    @Test fun `known valid ambiguity outranks unavailable ambiguity regardless of its sentinel`() {
        for (unknown in doubleArrayOf(Double.NaN, 0.0, -1.0)) {
            val h = Harness()
            h.poll(listOf(frame("unknown", 0.1, unknown).copy(ambiguityAvailable = false), frame("known", 0.2)))
            assertEquals(0.2, h.seeds.single().x, 0.0)
        }
    }

    @Test fun `unavailable ambiguity candidates use distance without consulting sentinel values`() {
        val h = Harness()
        h.poll(listOf(frame("far", 0.3, Double.NaN).copy(ambiguityAvailable = false),
            frame("near", 0.1, 5.0).copy(ambiguityAvailable = false)))
        assertEquals(0.1, h.seeds.single().x, 0.0)
    }

    @Test fun `physically rejected candidates cannot be published as the last valid pose`() {
        for (bad in listOf(frame("bad", Double.NaN), frame("bad", 0.1, -1.0),
            frame("bad", 0.1).copy(tagCount = 0))) {
            val h = Harness(initialized = true)
            h.poll(listOf(bad))
            assertNull(h.tracker.lastLimelightPose)
            assertTrue(h.seeds.isEmpty())
            assertEquals(listOf(listOf("bad")), h.rawBatches)
        }
    }

    @Test fun `equal ambiguity distance ordering survives squared distance overflow`() {
        val h = Harness(config = VisionFilterConfig(maxDistanceMeters = 1e202, minFieldX = -1e202,
            maxFieldX = 1e202, robotLengthMeters = 0.0, robotWidthMeters = 0.0))
        h.poll(listOf(frame("far", 8e200), frame("near", 4e200)))
        assertEquals(4e200, h.seeds.single().x, 0.0)
    }

    @Test fun `equal ambiguity distance ordering survives squared distance underflow`() {
        val h = Harness()
        h.poll(listOf(frame("far", 8e-200), frame("near", 4e-200)))
        assertEquals(4e-200, h.seeds.single().x, 0.0)
    }

    @Test fun `distances above the finite double range remain ordered`() {
        val h = Harness(estimatedX = -1.6e308, config = VisionFilterConfig(maxDistanceMeters = Double.MAX_VALUE,
            minFieldX = -Double.MAX_VALUE, maxFieldX = Double.MAX_VALUE,
            robotLengthMeters = 0.0, robotWidthMeters = 0.0, fieldBoundsToleranceMeters = 0.0))
        h.poll(listOf(frame("far", 1.2e308), frame("near", 0.7e308)))
        assertEquals(0.7e308, h.tracker.lastLimelightPose!!.x, 0.0)
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `independent-only candidates rank their own valid solve ambiguity`() {
        val h = Harness(initialized = true, config = VisionFilterConfig(maxRotationDeviationRad = 0.1))
        val far = frame("far", Double.NaN, 0.0).copy(hasRecoveryPose = true,
            recoveryPose = Pose3d(Translation3d(1.0, 0.0, 0.0), Rotation3d(0.0, 0.0, 1.0)),
            recoveryAmbiguityAvailable = true, recoveryAmbiguity = 0.1)
        val near = frame("near", Double.NaN, 0.9).copy(hasRecoveryPose = true,
            recoveryPose = Pose3d(Translation3d(0.8, 0.0, 0.0), Rotation3d(0.0, 0.0, 1.0)),
            recoveryAmbiguityAvailable = true, recoveryAmbiguity = 0.01)
        h.poll(listOf(far, near))
        h.poll(listOf(far, near), 200L)
        assertEquals(0.8, h.seeds.single().x, 1e-12)
        assertNull(h.tracker.lastLimelightPose)
    }

    @Test fun `a valid normal candidate remains preferred over an independent-only fallback`() {
        val h = Harness()
        val fallback = frame("fallback", Double.NaN, 0.0).copy(hasRecoveryPose = true,
            recoveryPose = Pose3d(Translation3d(0.8, 0.0, 0.0), Rotation3d()),
            recoveryAmbiguityAvailable = true, recoveryAmbiguity = 0.0)
        h.poll(listOf(fallback, frame("normal", 0.2)))
        assertEquals(0.2, h.seeds.single().x, 0.0)
        assertEquals(0.2, h.tracker.lastLimelightPose!!.x, 0.0)
    }

    @Test fun `a subscriber later batch cannot overwrite the selected camera result`() {
        val h = Harness(initialized = true)
        var nested = false
        h.store.subscribe {
            if (!nested) {
                nested = true
                h.store.dispatch(RobotAction.VisionMeasurementsReceived(emptyList(), 110L))
            }
        }
        h.poll(listOf(frame("selected", 0.0)))
        assertEquals(-1, h.store.state.vision.diagnosticMeasurementIndex)
        assertEquals("ACCEPTED", h.tracker.lastVisionStatus)
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `exact candidate ties preserve input order and still fuse the whole batch`() {
        val h = Harness(initialized = true)
        h.poll(listOf(frame("first", 0.0), frame("second", 0.0)))
        assertEquals(0, h.store.state.vision.diagnosticMeasurementIndex)
        assertEquals(2, h.store.state.vision.measurementCount)
        assertEquals(listOf(listOf("first", "second")), h.rawBatches)
    }
}
