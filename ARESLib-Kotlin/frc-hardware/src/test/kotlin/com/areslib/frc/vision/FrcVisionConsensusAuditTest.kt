package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.frc.TestSwerveHardwareIO
import com.areslib.hardware.vision.*
import com.areslib.math.geometry.*
import com.areslib.state.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FrcVisionConsensusAuditTest {
    private class Camera : VisionIO {
        var frame = VisionMeasurement()
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = true
            inputs.measurements = listOf(frame)
        }
    }
    private class Swerve : TestSwerveHardwareIO() {
        val seeds = ArrayList<Pose2d>()
        var failReseed = false
        override fun read() = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) = Unit
        override fun seedPose(pose: Pose2d) {
            if (failReseed) throw IllegalStateException("reseed failed")
            seeds.add(pose)
        }
    }
    private class Harness(threshold: Double = 2.0,
        config: VisionFilterConfig = VisionFilterConfig(maxRotationDeviationRad = 0.1)) {
        val store = Store(RobotState(drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true),
            vision = VisionState(filterConfig = config),
            tuning = TuningState(recovery = RecoveryTuningState(stolenRobotRejectionThreshold = threshold))))
        val camera = Camera()
        val swerve = Swerve()
        val seeds get() = swerve.seeds
        val tracker = FrcVisionTracker(store, camera, swerve, false,
            estimatorTimeSecondsProvider = { 10.0 }, isDisabledProvider = { true })
        fun poll(time: Long, x: Double = 0.8, tags: Int = 2) {
            camera.frame = VisionMeasurement(timestampMs = time, frameId = time, tagId = 1, tagCount = tags,
                ambiguity = 0.01, solverType = VisionSolverType.MEGATAG2,
                targetPose = Pose3d(Translation3d(Double.NaN, 0.0, 0.0), Rotation3d()),
                hasRecoveryPose = true,
                recoveryPose = Pose3d(Translation3d(x, 0.0, 0.0), Rotation3d(0.0, 0.0, 1.0)),
                recoveryAmbiguity = 0.01, recoveryAmbiguityAvailable = true, averageTagDistanceMeters = 2.0)
            tracker.update(time)
        }
    }

    @Test fun `invalid sample thresholds cannot authorize recovery after the dwell`() {
        for (threshold in doubleArrayOf(Double.NaN, 0.0, -1.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val h = Harness(threshold)
            h.poll(100L)
            h.poll(600L)
            assertTrue(h.seeds.isEmpty(), "threshold=$threshold")
        }
    }

    @Test fun `fractional multitag requirements round up without shortening the dwell`() {
        val h = Harness(4.5)
        for (time in longArrayOf(100L, 300L, 500L, 700L)) h.poll(time)
        assertTrue(h.seeds.isEmpty())
        h.poll(900L)
        assertEquals(1, h.seeds.size)
    }

    @Test fun `single tag fractional requirements double before rounding up`() {
        val h = Harness(2.5)
        for (time in longArrayOf(100L, 300L, 500L, 700L)) h.poll(time, tags = 1)
        assertTrue(h.seeds.isEmpty())
        h.poll(900L, tags = 1)
        assertEquals(1, h.seeds.size)
    }

    @Test fun `large single tag requirements cannot wrap into immediate recovery`() {
        for (threshold in doubleArrayOf(Int.MAX_VALUE.toDouble(), Double.MAX_VALUE, Double.POSITIVE_INFINITY)) {
            val h = Harness(threshold)
            h.poll(100L, tags = 1)
            h.poll(600L, tags = 1)
            assertTrue(h.seeds.isEmpty(), "threshold=$threshold")
        }
    }

    @Test fun `finite large repeated poses produce a finite recovery mean`() {
        val h = Harness(config = VisionFilterConfig(maxDistanceMeters = Double.MAX_VALUE,
            minFieldX = -Double.MAX_VALUE, maxFieldX = Double.MAX_VALUE,
            robotLengthMeters = 0.0, robotWidthMeters = 0.0, maxRotationDeviationRad = 0.1))
        h.poll(100L, 1e308)
        h.poll(600L, 1e308)
        assertEquals(1, h.seeds.size)
        assertEquals(1e308, h.seeds.single().x, 0.0)
        assertEquals(1e308, h.store.state.drive.poseEstimator.estimatedPoseX, 0.0)
    }

    @Test fun `an inconsistent sample restarts the consensus dwell`() {
        val h = Harness()
        h.poll(100L)
        h.poll(600L, x = 1.3)
        h.poll(700L, x = 1.3)
        assertTrue(h.seeds.isEmpty())
        h.poll(1100L, x = 1.3)
        assertEquals(1, h.seeds.size)
        assertEquals(1.3, h.seeds.single().x, 1e-12)
    }

    @Test fun `invalid threshold interrupts evidence and restarts the dwell`() {
        val h = Harness(3.0)
        h.poll(100L)
        h.poll(300L)
        h.store.dispatch(RobotAction.UpdateTuningState(TuningState(recovery = RecoveryTuningState(
            stolenRobotRejectionThreshold = Double.NaN)), 350L))
        h.poll(500L)
        h.store.dispatch(RobotAction.UpdateTuningState(TuningState(recovery = RecoveryTuningState(
            stolenRobotRejectionThreshold = 2.0)), 550L))
        h.poll(700L)
        h.poll(900L)
        assertTrue(h.seeds.isEmpty())
        h.poll(1200L)
        assertEquals(1, h.seeds.size)
    }

    @Test fun `failed reseed requires fresh consensus and dwell`() {
        val h = Harness()
        h.swerve.failReseed = true
        h.poll(100L)
        assertThrows(IllegalStateException::class.java) { h.poll(600L) }
        assertTrue(h.seeds.isEmpty())
        h.swerve.failReseed = false
        h.poll(800L)
        assertTrue(h.seeds.isEmpty())
        h.poll(1300L)
        assertEquals(1, h.seeds.size)
    }
}
