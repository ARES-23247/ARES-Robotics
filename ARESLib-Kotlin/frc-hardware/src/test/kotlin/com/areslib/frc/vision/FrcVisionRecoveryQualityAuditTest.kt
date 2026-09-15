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
import com.areslib.state.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.areslib.frc.TestSwerveHardwareIO

class FrcVisionRecoveryQualityAuditTest {
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
    private class Swerve : TestSwerveHardwareIO() {
        val seeds = ArrayList<Pose2d>()
        var fusions = 0
        override fun read() = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) { fusions++ }
        override fun seedPose(pose: Pose2d) { seeds.add(pose) }
    }
    private class Harness(
        drive: DriveState = DriveState(measuredMotionValid = true, imuMeasurementsValid = true),
        config: VisionFilterConfig = VisionFilterConfig(maxRotationDeviationRad = 0.1),
        recovery: RecoveryTuningState = RecoveryTuningState(stolenRobotRejectionThreshold = 2.0)
    ) {
        val store = Store(RobotState(drive = drive, vision = VisionState(filterConfig = config),
            tuning = TuningState(recovery = recovery)), ::rootReducer)
        val camera = Camera()
        var disabled = true
        val swerve = Swerve()
        val seeds get() = swerve.seeds
        val tracker = FrcVisionTracker(store, camera, swerve, isSimulation = false,
            estimatorTimeSecondsProvider = { 10.0 }, isDisabledProvider = { disabled })
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

    @Test fun `invalid independent ambiguity cannot complete recovery`() {
        for (ambiguity in doubleArrayOf(-0.1, 0.9, Double.NaN, Double.POSITIVE_INFINITY)) {
            val h = Harness()
            h.poll(h.frame().copy(recoveryAmbiguity = ambiguity))
            assertTrue(h.seeds.isEmpty(), "ambiguity=$ambiguity")
        }
    }

    @Test fun `zero tags cannot complete independent recovery`() {
        val h = Harness()
        h.poll(h.frame().copy(tagCount = 0))
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `nonfinite geometry and negative latency cannot complete recovery`() {
        val mutations: List<(VisionMeasurement) -> Unit> = listOf(
            { it.latencyMs = -1.0 }, { it.tagSpanMeters = Double.NaN },
            { it.averageTagAreaPercent = Double.POSITIVE_INFINITY })
        for (mutate in mutations) {
            val h = Harness()
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
        val h = Harness()
        val frame = h.frame().apply { targetPose.translation.x = Double.NaN; ambiguity = 0.9 }
        h.poll(frame)
        assertEquals(1, h.seeds.size)
        assertEquals(0.8, h.seeds.single().x, 1e-12)
        assertEquals(1.0, h.seeds.single().heading.radians, 1e-12)
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

    @Test fun `normal fusion uses reported range or the complete target-space norm`() {
        for ((target, reported, expected) in listOf(
            Triple(Translation3d(100.0, 0.0, 100.0), 1.0, 1),
            Triple(Translation3d(5.0, 0.0, 1.0), -1.0, 1),
            Triple(Translation3d(5.0, 0.0, 4.0), -1.0, 0),
            Triple(Translation3d(0.0, 0.0, 6.0), -1.0, 0),
            Triple(Translation3d(0.0, 0.0, 0.01), -1.0, 0))) {
            val h = Harness()
            h.poll(h.frame().copy(hasRecoveryPose = false, averageTagDistanceMeters = reported,
                robotPoseTargetSpace = Pose3d(target, Rotation3d())), 1)
            assertEquals(expected, h.swerve.fusions, "target=$target reported=$reported")
        }
    }

    @Test fun `reported range beyond configured physical distance cannot recover`() {
        val h = Harness(config = VisionFilterConfig(maxDistanceMeters = 0.5))
        h.poll(h.frame().copy(averageTagDistanceMeters = 2.0))
        assertTrue(h.seeds.isEmpty())
    }

    @Test fun `nonfinite enabled stationarity limits cannot authorize recovery`() {
        val valid = DriveState(measuredMotionValid = true, imuMeasurementsValid = true)
        for ((drive, recovery) in listOf(
            valid.copy(measuredFieldXVelocityMetersPerSecond = 1.0) to RecoveryTuningState(
                stolenRobotRejectionThreshold = 2.0, stolenRobotVelocityThreshold = Double.POSITIVE_INFINITY),
            valid.copy(measuredAngularVelocityRadiansPerSecond = 1.0) to RecoveryTuningState(
                stolenRobotRejectionThreshold = 2.0, stolenRobotAngularVelocityThreshold = Double.POSITIVE_INFINITY))) {
            val h = Harness(drive = drive, recovery = recovery)
            h.disabled = false
            h.poll(h.frame(), 7)
            assertTrue(h.seeds.isEmpty())
        }
    }
}
