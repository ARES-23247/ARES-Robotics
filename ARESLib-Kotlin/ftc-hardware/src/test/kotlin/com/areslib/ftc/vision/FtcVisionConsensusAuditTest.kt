package com.areslib.ftc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.vision.*
import com.areslib.math.geometry.*
import com.areslib.state.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FtcVisionConsensusAuditTest {
    private class Camera : VisionIO {
        var frame = VisionMeasurement()
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = true
            inputs.measurements = listOf(frame)
        }
    }
    private class Harness(threshold: Double = 2.0, initialized: Boolean = true,
        config: VisionFilterConfig = VisionFilterConfig(maxRotationDeviationRad = 0.1)) {
        val store = Store(RobotState(drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true),
            vision = VisionState(filterConfig = config),
            tuning = TuningState(recovery = RecoveryTuningState(stolenRobotRejectionThreshold = threshold))))
        val camera = Camera()
        val seeds = ArrayList<Pose2d>()
        var failReseed = false
        val tracker = FtcVisionTracker(store, camera, null, onOdometryReseed = {
            if (failReseed) throw IllegalStateException("reseed failed")
            seeds.add(it)
        }).apply {
            hasInitializedPoseWithVision = initialized
        }
        fun poll(time: Long, x: Double = 0.8, normal: Boolean = false, heading: Double = 1.0) {
            camera.frame = VisionMeasurement(timestampMs = time, frameId = time, tagId = 1, tagCount = 2,
                ambiguity = 0.01, solverType = VisionSolverType.MEGATAG2,
                targetPose = Pose3d(Translation3d(if (normal) x else Double.NaN, 0.0, 0.0), Rotation3d(0.0, 0.0, heading)),
                hasRecoveryPose = !normal,
                recoveryPose = Pose3d(Translation3d(x, 0.0, 0.0), Rotation3d(0.0, 0.0, heading)),
                recoveryAmbiguity = 0.01, recoveryAmbiguityAvailable = true, averageTagDistanceMeters = 2.0)
            tracker.update(time)
        }
    }

    @Test fun `invalid sample thresholds cannot authorize a recovery snap`() {
        for (threshold in doubleArrayOf(Double.NaN, 0.0, -1.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val h = Harness(threshold)
            h.poll(100L)
            h.poll(200L)
            assertTrue(h.seeds.isEmpty(), "threshold=$threshold")
        }
    }

    @Test fun `fractional sample requirements do not truncate to an earlier snap`() {
        val h = Harness(2.5)
        h.poll(100L)
        h.poll(200L)
        assertTrue(h.seeds.isEmpty())
        h.poll(300L)
        assertEquals(1, h.seeds.size)
        assertEquals(0.8, h.seeds.single().x, 1e-12)
    }

    @Test fun `finite large repeated poses produce a finite recovery mean`() {
        val h = Harness(config = VisionFilterConfig(maxDistanceMeters = Double.MAX_VALUE,
            minFieldX = -Double.MAX_VALUE, maxFieldX = Double.MAX_VALUE,
            robotLengthMeters = 0.0, robotWidthMeters = 0.0, maxRotationDeviationRad = 0.1))
        h.poll(100L, 1e308)
        h.poll(200L, 1e308)
        assertEquals(1, h.seeds.size)
        assertEquals(1e308, h.seeds.single().x, 0.0)
        assertEquals(1e308, h.store.state.drive.poseEstimator.estimatedPoseX, 0.0)
    }

    @Test fun `a recovery before initial alignment completes initialization only once`() {
        val h = Harness(initialized = false)
        h.poll(100L)
        h.poll(200L)
        assertEquals(1, h.seeds.size)
        h.poll(300L, normal = true)
        assertEquals(1, h.seeds.size)
        assertTrue(h.tracker.hasInitializedPoseWithVision)
    }

    @Test fun `initial alignment clears recovery evidence from the earlier pose epoch`() {
        val h = Harness(initialized = false)
        h.poll(100L)
        assertTrue(h.seeds.isEmpty())
        h.poll(200L, x = 0.2, normal = true, heading = 0.0)
        assertEquals(1, h.seeds.size)
        h.poll(300L)
        assertEquals(1, h.seeds.size)
        h.poll(400L)
        assertEquals(2, h.seeds.size)
    }

    @Test fun `invalid threshold interrupts evidence before a later valid configuration`() {
        val h = Harness(2.5)
        h.poll(100L)
        h.poll(200L)
        h.store.dispatch(RobotAction.UpdateTuningState(TuningState(recovery = RecoveryTuningState(
            stolenRobotRejectionThreshold = Double.NaN)), 250L))
        h.poll(300L)
        h.store.dispatch(RobotAction.UpdateTuningState(TuningState(recovery = RecoveryTuningState(
            stolenRobotRejectionThreshold = 2.5)), 350L))
        h.poll(400L)
        h.poll(500L)
        assertTrue(h.seeds.isEmpty())
        h.poll(600L)
        assertEquals(1, h.seeds.size)
    }

    @Test fun `failed recovery does not complete initialization or retain consensus`() {
        val h = Harness(initialized = false)
        h.failReseed = true
        h.poll(100L)
        assertThrows(IllegalStateException::class.java) { h.poll(200L) }
        assertFalse(h.tracker.hasInitializedPoseWithVision)
        assertTrue(h.seeds.isEmpty())
        h.failReseed = false
        h.poll(300L)
        assertTrue(h.seeds.isEmpty())
        h.poll(400L)
        assertEquals(1, h.seeds.size)
        assertTrue(h.tracker.hasInitializedPoseWithVision)
    }
}
