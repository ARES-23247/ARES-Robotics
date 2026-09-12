package com.areslib.ftc.vision

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
import com.areslib.state.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FtcVisionRecoveryQualityAuditTest {
    private class Camera : VisionIO {
        var frames: List<VisionMeasurement> = emptyList()
        var orientationCalls = 0
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = true
            inputs.measurements = frames
        }
        override fun setOrientation(yawDegrees: Double, yawRateDegPerSec: Double,
            pitchDegrees: Double, pitchRateDegPerSec: Double, rollDegrees: Double,
            rollRateDegPerSec: Double, linearVelocityMps: Double) { orientationCalls++ }
    }
    private class Harness(
        drive: DriveState = DriveState(measuredMotionValid = true, imuMeasurementsValid = true),
        config: VisionFilterConfig = VisionFilterConfig(maxRotationDeviationRad = 0.1),
        initialized: Boolean = false,
        recovery: RecoveryTuningState = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0)
    ) {
        val store = Store(RobotState(drive = drive, vision = VisionState(filterConfig = config),
            tuning = TuningState(recovery = recovery)), ::rootReducer)
        val camera = Camera()
        val seeds = ArrayList<Pose2d>()
        val tracker = FtcVisionTracker(store, camera, null, onOdometryReseed = { seeds.add(it) }).apply {
            hasInitializedPoseWithVision = initialized
        }
        fun frame() = VisionMeasurement(timestampMs = 100L, frameId = 1L, tagId = 1,
            targetPose = Pose3d(Translation3d(0.2, 0.0, 0.0), Rotation3d()),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d()),
            hasRecoveryPose = true,
            recoveryPose = Pose3d(Translation3d(0.8, 0.2, 0.0), Rotation3d(0.0, 0.0, 1.0)),
            solverType = VisionSolverType.MEGATAG2, tagCount = 2,
            ambiguity = 0.01, recoveryAmbiguity = 0.01, recoveryAmbiguityAvailable = true)
        fun poll(frame: VisionMeasurement, count: Int = 4) {
            repeat(count) { index ->
                val time = 100L + index * 200L
                camera.frames = listOf(frame.copy(timestampMs = time, frameId = index.toLong() + 1L))
                tracker.update(time)
            }
        }
    }

    @Test fun `invalid measured motion or IMU flags cannot initialize or seed orientation`() {
        for ((motion, imu) in listOf(false to true, true to false, false to false)) {
            val h = Harness(drive = DriveState(measuredMotionValid = motion, imuMeasurementsValid = imu))
            h.poll(h.frame(), 1)
            assertTrue(h.seeds.isEmpty())
            assertEquals(0, h.camera.orientationCalls)
        }
    }

    @Test fun `diagonal motion above the speed threshold cannot initialize`() {
        val h = Harness(drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true,
            measuredFieldXVelocityMetersPerSecond = 0.08, measuredFieldYVelocityMetersPerSecond = 0.08))
        h.poll(h.frame(), 1)
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `disallowed tags cannot bypass the filter through initial alignment`() {
        val h = Harness(config = VisionFilterConfig(allowedTagIds = setOf(2)))
        h.poll(h.frame(), 1)
        assertTrue(h.seeds.isEmpty())
        assertFalse(h.tracker.hasInitializedPoseWithVision)
    }

    @Test fun `malformed observation metadata cannot bypass initial alignment`() {
        val mutations: List<(VisionMeasurement) -> Unit> = listOf(
            { it.tagCount = 0 }, { it.ambiguity = -0.1 }, { it.latencyMs = -1.0 },
            { it.tagSpanMeters = Double.NaN }, { it.averageTagAreaPercent = Double.POSITIVE_INFINITY })
        for (mutate in mutations) {
            val h = Harness()
            val frame = h.frame().apply(mutate)
            h.poll(frame, 1)
            assertTrue(h.seeds.isEmpty(), frame.toString())
        }
    }

    @Test fun `bad independent ambiguity cannot replace a valid normal initialization pose`() {
        for (ambiguity in doubleArrayOf(-0.1, 0.9, Double.NaN, Double.POSITIVE_INFINITY)) {
            val h = Harness()
            h.poll(h.frame().copy(recoveryAmbiguity = ambiguity), 1)
            assertEquals(1, h.seeds.size)
            assertEquals(0.2, h.seeds.single().x, 1e-12)
            assertEquals(0.0, h.seeds.single().heading.radians, 1e-12)
        }
    }

    @Test fun `independent yaw divergence cannot bypass the collision gate`() {
        val h = Harness(initialized = true, drive = DriveState(measuredMotionValid = true,
            imuMeasurementsValid = true, xAccelerationG = 3.0))
        h.poll(h.frame())
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `independent yaw divergence cannot bypass a tag allowlist`() {
        val h = Harness(initialized = true, config = VisionFilterConfig(
            allowedTagIds = setOf(2), maxRotationDeviationRad = 0.1))
        h.poll(h.frame())
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `invalid independent ambiguity cannot complete recovery`() {
        for (ambiguity in doubleArrayOf(-0.1, 0.9, Double.NaN, Double.POSITIVE_INFINITY)) {
            val h = Harness(initialized = true)
            h.poll(h.frame().copy(recoveryAmbiguity = ambiguity))
            assertTrue(h.seeds.isEmpty(), "ambiguity=$ambiguity")
        }
    }

    @Test fun `zero tags cannot complete independent recovery`() {
        val h = Harness(initialized = true)
        h.poll(h.frame().copy(tagCount = 0))
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `nonfinite geometry and negative latency cannot complete recovery`() {
        val mutations: List<(VisionMeasurement) -> Unit> = listOf(
            { it.latencyMs = -1.0 }, { it.tagSpanMeters = Double.NaN },
            { it.averageTagAreaPercent = Double.POSITIVE_INFINITY })
        for (mutate in mutations) {
            val h = Harness(initialized = true)
            h.poll(h.frame().apply(mutate))
            assertTrue(h.seeds.isEmpty())
        }
    }

    @Test fun `camera orientation hints require finite cached drive components`() {
        val valid = DriveState(measuredMotionValid = true, imuMeasurementsValid = true)
        for (drive in listOf(valid.copy(pitchDegrees = Double.NaN), valid.copy(rollDegrees = Double.NaN),
            valid.copy(measuredAngularVelocityRadiansPerSecond = Double.POSITIVE_INFINITY),
            valid.copy(measuredFieldXVelocityMetersPerSecond = Double.NaN))) {
            val h = Harness(drive = drive)
            h.poll(h.frame(), 1)
            assertEquals(0, h.camera.orientationCalls)
            assertTrue(h.seeds.isEmpty())
        }
    }

    @Test fun `a valid independent solve can recover when the normal solve is invalid`() {
        val h = Harness(initialized = true)
        val frame = h.frame().apply { targetPose.translation.x = Double.NaN; ambiguity = 0.9 }
        h.poll(frame)
        assertEquals(1, h.seeds.size)
        assertEquals(0.8, h.seeds.single().x, 1e-12)
        assertEquals(1.0, h.seeds.single().heading.radians, 1e-12)
    }

    @Test fun `finite reducer fallbacks for invalid feedback do not establish stationarity`() {
        val h = Harness()
        h.store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 50L,
            xVelocityMetersPerSecond = Double.NaN, xAccelerationG = Double.NaN, isReset = true))
        assertEquals(0.0, h.store.state.drive.measuredFieldXVelocityMetersPerSecond, 0.0)
        assertFalse(h.store.state.drive.measuredMotionValid)
        assertFalse(h.store.state.drive.imuMeasurementsValid)
        h.poll(h.frame(), 1)
        assertTrue(h.seeds.isEmpty())
        assertEquals(0, h.camera.orientationCalls)
    }

    @Test fun `finite components whose derived camera hints overflow are rejected`() {
        val valid = DriveState(measuredMotionValid = true, imuMeasurementsValid = true)
        for (drive in listOf(valid.copy(measuredAngularVelocityRadiansPerSecond = Double.MAX_VALUE),
            valid.copy(measuredFieldXVelocityMetersPerSecond = Double.MAX_VALUE,
                measuredFieldYVelocityMetersPerSecond = Double.MAX_VALUE))) {
            val h = Harness(drive = drive)
            h.poll(h.frame(), 1)
            assertEquals(0, h.camera.orientationCalls)
            assertTrue(h.seeds.isEmpty())
        }
    }

    @Test fun `nonfinite stationarity limits do not authorize initial alignment`() {
        val h = Harness(recovery = RecoveryTuningState(stolenRobotVelocityThreshold = Double.POSITIVE_INFINITY))
        h.poll(h.frame(), 1)
        assertTrue(h.seeds.isEmpty())
    }
}
