package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.reducer.rootReducer
import com.areslib.state.DriveState
import com.areslib.state.RecoveryTuningState
import com.areslib.state.RobotState
import com.areslib.state.TuningState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import com.areslib.frc.TestSwerveHardwareIO

class FrcVisionFreshnessAuditTest {
    private class Camera : VisionIO {
        var frames: List<VisionMeasurement> = emptyList()
        var connected = true
        var writeInputs = true
        var failure: Throwable? = null
        var duringRead: (() -> Unit)? = null
        override fun updateInputs(inputs: VisionIOInputs) {
            duringRead?.invoke()
            failure?.let { throw it }
            if (writeInputs) {
                inputs.isConnected = connected
                inputs.measurements = frames
            }
        }
    }
    private class Swerve : TestSwerveHardwareIO() {
        var seeds = 0
        var fusions = 0
        override fun read() = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun seedPose(pose: Pose2d) { seeds++ }
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) { fusions++ }
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double,
            stdDevXMeters: Double, stdDevYMeters: Double, stdDevHeadingRadians: Double) { fusions++ }
    }
    private class Harness(recovery: Boolean = false) {
        val camera = Camera()
        var batches = 0
        val store = Store(RobotState(
            drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true),
            vision = VisionState(filterConfig = VisionFilterConfig(
                minFieldX = -20.0, maxFieldX = 20.0, minFieldY = -20.0, maxFieldY = 20.0,
                maxDistanceMeters = 10.0)),
            tuning = TuningState(recovery = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0))
        ), { state, action ->
            if (action is RobotAction.VisionMeasurementsReceived) batches++
            rootReducer(state, action)
        })
        var disabled = true
        val swerve = Swerve()
        val seeds get() = swerve.seeds
        val tracker = FrcVisionTracker(store, camera, swerve, isSimulation = false,
            estimatorTimeSecondsProvider = { 10.0 }, fpgaToEstimatorTimeSeconds = { it },
            isDisabledProvider = { disabled })
        fun frame(time: Long, recovery: Boolean = false, source: String = "camera") = VisionMeasurement(
            timestampMs = time, frameId = time, sourceId = source, tagId = 1, tagCount = 2,
            targetPose = Pose3d(Translation3d(0.2, 0.0, 0.0), Rotation3d()),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d()),
            recoveryPose = Pose3d(Translation3d(3.0, 3.0, 0.0), Rotation3d()),
            hasRecoveryPose = recovery,
            ambiguity = 0.01, recoveryAmbiguity = 0.01, recoveryAmbiguityAvailable = true)
        fun poll(time: Long, recovery: Boolean = false) {
            camera.frames = listOf(frame(time, recovery))
            tracker.update(time)
        }
    }

    @Test fun `disconnected observations never reach fusion or reseeding`() {
        val h = Harness()
        h.camera.connected = false
        h.poll(100L)
        assertEquals(0, h.batches)
        assertEquals(0, h.seeds)
        assertFalse(h.tracker.isConnected)
        assertEquals("OFFLINE", h.tracker.lastVisionStatus)
    }

    @Test fun `a partial poll cannot reuse the previous connected snapshot`() {
        val h = Harness()
        h.poll(100L)
        h.camera.writeInputs = false
        h.tracker.update(120L)
        assertFalse(h.tracker.isConnected)
        assertEquals("OFFLINE", h.tracker.lastVisionStatus)
    }

    @Test fun `a polling failure invalidates published connection state`() {
        val h = Harness()
        h.poll(100L)
        val failure = AssertionError("camera read failed")
        h.camera.failure = failure
        assertSame(failure, assertThrows<AssertionError> { h.tracker.update(120L) })
        assertFalse(h.tracker.isConnected)
        assertEquals("IO_ERROR", h.tracker.lastVisionStatus)
        h.camera.failure = null
        h.poll(140L)
        assertTrue(h.tracker.isConnected)
    }

    @Test fun `a current frame near maximum timestamp remains fresh`() {
        val h = Harness()
        h.poll(Long.MAX_VALUE)
        assertEquals(1, h.batches)
        assertTrue(h.tracker.lastVisionStatus != "STALE_FRAME")
    }

    @Test fun `more than eight camera sources retain duplicate protection`() {
        val h = Harness()
        h.camera.frames = List(12) { h.frame(100L, source = "camera-$it") }
        h.tracker.update(100L)
        val firstBatches = h.batches
        h.tracker.update(120L)
        assertEquals("STALE_FRAME", h.tracker.lastVisionStatus)
        assertEquals(firstBatches, h.batches)
    }

    @Test fun `an empty poll breaks recovery consensus`() {
        val h = Harness(recovery = true)
        h.poll(100L, recovery = true)
        h.camera.frames = emptyList()
        h.tracker.update(200L)
        h.poll(700L, recovery = true)
        assertEquals(0, h.seeds)
    }

    @Test fun `a long update gap cannot complete an old recovery consensus`() {
        val h = Harness(recovery = true)
        h.poll(100L, recovery = true)
        h.poll(2_000L, recovery = true)
        assertEquals(0, h.seeds)
    }

    @Test fun `a polling failure breaks recovery consensus`() {
        val h = Harness(recovery = true)
        h.poll(100L, recovery = true)
        h.camera.failure = IllegalStateException("read failed")
        assertThrows<IllegalStateException> { h.tracker.update(200L) }
        h.camera.failure = null
        h.poll(700L, recovery = true)
        assertEquals(0, h.seeds)
    }

    @Test fun `clock rewind starts a new frame epoch`() {
        val h = Harness()
        h.poll(1_000L)
        val firstBatches = h.batches
        h.poll(100L)
        assertEquals(firstBatches + 1, h.batches)
        assertTrue(h.tracker.lastVisionStatus != "STALE_FRAME")
    }

    @Test fun `cached observations do not repeatedly dispatch diagnostics`() {
        val h = Harness()
        h.poll(100L)
        val firstBatches = h.batches
        h.tracker.update(120L)
        assertEquals(firstBatches, h.batches)
    }

    @Test fun `disabling fusion between polls discards recovery consensus`() {
        val h = Harness(recovery = true)
        h.poll(100L, recovery = true)
        h.tracker.fusionEnabled = false
        h.tracker.fusionEnabled = true
        h.poll(700L, recovery = true)
        assertEquals(0, h.seeds)
    }

    @Test fun `recursive polling is rejected and clears the outer snapshot`() {
        val h = Harness()
        h.poll(100L)
        h.camera.duringRead = { h.tracker.update(120L) }
        assertThrows<IllegalStateException> { h.tracker.update(120L) }
        assertFalse(h.tracker.isConnected)
        assertEquals("IO_ERROR", h.tracker.lastVisionStatus)
        h.camera.duringRead = null
        h.poll(140L)
        assertTrue(h.tracker.isConnected)
    }

    @Test fun `fresh frames after camera loss must build a new recovery consensus`() {
        val h = Harness(recovery = true)
        h.poll(100L, recovery = true)
        h.camera.connected = false
        h.tracker.update(200L)
        h.camera.connected = true
        h.poll(700L, recovery = true)
        assertEquals(0, h.seeds)
    }

    @Test fun `continuous stale polling cannot preserve expired recovery evidence`() {
        val h = Harness(recovery = true)
        h.poll(100L, recovery = true)
        for (time in 200L..1_900L step 100L) h.tracker.update(time)
        h.poll(2_000L, recovery = true)
        assertEquals(0, h.seeds)
    }

    @Test fun `motion during an empty poll restarts enabled recovery dwell`() {
        val h = Harness(recovery = true)
        h.disabled = false
        h.poll(100L, recovery = true)
        h.poll(700L, recovery = true)
        h.store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 800L,
            xVelocityMetersPerSecond = 1.0, isReset = true))
        h.camera.frames = emptyList()
        h.tracker.update(800L)
        h.store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 900L,
            xVelocityMetersPerSecond = 0.0, isReset = true))
        h.poll(900L, recovery = true)
        h.poll(1_300L, recovery = true)
        assertEquals(0, h.seeds)
        h.poll(1_400L, recovery = true)
        assertEquals(0, h.seeds)
        h.poll(1_900L, recovery = true)
        assertEquals(1, h.seeds)
    }
}
