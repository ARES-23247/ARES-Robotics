package com.areslib.hardware.vision

import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.*
import com.areslib.state.DriveState
import com.areslib.state.VisionMeasurement
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

class VisionRecoveryPhysicalAuditTest {
    private val config = VisionFilterConfig(maxDistanceMeters = 0.5, maxRotationDeviationRad = 0.1)
    private fun frame() = VisionMeasurement(tagId = 1, ambiguity = 0.01,
        targetPose = Pose3d(Translation3d(0.8, 0.2, 0.0), Rotation3d(0.0, 0.0, 1.0)),
        recoveryPose = Pose3d(Translation3d(0.7, 0.2, 0.0), Rotation3d(0.0, 0.0, 0.8)),
        hasRecoveryPose = true, recoveryAmbiguity = 0.01, recoveryAmbiguityAvailable = true)

    @Test fun `recovery permits a displaced pose while preserving physical limits`() {
        val frame = frame()
        assertFalse(VisionOutlierFilter.isValid(config, frame, 0.0, 0.0, 0.0))
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame))
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame, true))
        frame.averageTagDistanceMeters = 0.6
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame))
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame, true))
    }

    @Test fun `independent solve uses its own pose and ambiguity`() {
        val frame = frame().apply { targetPose.translation.x = Double.NaN; ambiguity = 0.9 }
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame, true))
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame))
        for (bad in doubleArrayOf(-0.1, 0.9, Double.NaN, Double.POSITIVE_INFINITY)) {
            frame.recoveryAmbiguity = bad
            assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame, true))
        }
    }

    @Test fun `unavailable ambiguity and finite unknown geometry remain supported`() {
        val frame = frame().apply {
            ambiguityAvailable = false; ambiguity = Double.NaN
            recoveryAmbiguityAvailable = false; recoveryAmbiguity = Double.NaN
            tagSpanMeters = -1.0; averageTagAreaPercent = -1.0; averageTagDistanceMeters = -1.0
        }
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame))
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame, true))
        frame.hasRecoveryPose = false
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame, true))
    }

    @Test fun `shared metadata and tag policy apply to both solves`() {
        val mutations: List<(VisionMeasurement) -> Unit> = listOf(
            { it.tagCount = 0 }, { it.latencyMs = -1.0 }, { it.latencyMs = Double.NaN },
            { it.tagSpanMeters = Double.NaN }, { it.averageTagDistanceMeters = Double.NaN },
            { it.averageTagAreaPercent = Double.POSITIVE_INFINITY }, { it.tagId = 2 })
        val allowed = config.copy(allowedTagIds = setOf(1))
        for (mutate in mutations) {
            val frame = frame().apply(mutate)
            assertFalse(VisionOutlierFilter.isValidForRecovery(allowed, frame))
            assertFalse(VisionOutlierFilter.isValidForRecovery(allowed, frame, true))
        }
    }

    @Test fun `independent poses must satisfy orientation height and footprint bounds`() {
        val poses = listOf(
            Pose3d(Translation3d(Double.NaN, 0.0, 0.0), Rotation3d()),
            Pose3d(Translation3d(0.0, 0.0, 5.0), Rotation3d()),
            Pose3d(Translation3d(0.0, 0.0, 0.0), Rotation3d(1.0, 0.0, 0.0)),
            Pose3d(Translation3d(0.0, 0.0, 0.0), Rotation3d(Quaternion(0.0, 0.0, 0.0, 0.0))),
            Pose3d(Translation3d(0.0, 0.0, 0.0), Rotation3d(Quaternion(2.0, 0.0, 0.0, 0.0))),
            Pose3d(Translation3d(1.8, 0.0, 0.0), Rotation3d()))
        for (pose in poses) {
            assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame().copy(recoveryPose = pose), true))
        }
    }

    @Test fun `motion and shock gates apply without innovation gating`() {
        val frame = frame()
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame, true, angularVelocityRadPerSec = 3.0))
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame, true, linearAccelXG = 3.0))
        assertFalse(VisionOutlierFilter.isValidForRecovery(config, frame, true, linearAccelYG = Double.NaN))
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame, true, linearAccelZG = 0.0))
        assertTrue(VisionOutlierFilter.isValidForRecovery(config, frame, true, linearAccelZG = 1.0))
        assertFalse(VisionOutlierFilter.isValidForRecovery(config.copy(maxAccelerationG = Double.MIN_VALUE),
            frame, true, linearAccelXG = 2.0 * Double.MIN_VALUE, linearAccelZG = 0.0))
        assertTrue(VisionOutlierFilter.isValidForRecovery(config.copy(maxAccelerationG = 1e201),
            frame, true, linearAccelXG = 1e200, linearAccelZG = 0.0))
    }

    @Test fun `invalid scalar configuration rejects both solves`() {
        for (invalid in listOf(config.copy(maxAmbiguity = Double.NaN), config.copy(maxDistanceMeters = -1.0),
            config.copy(maxAccelerationG = -1.0), config.copy(robotLengthMeters = Double.NaN))) {
            assertFalse(VisionOutlierFilter.isValidForRecovery(invalid, frame()))
            assertFalse(VisionOutlierFilter.isValidForRecovery(invalid, frame(), true))
        }
    }

    @Test fun `cached feedback validity requires flags and finite values`() {
        val valid = DriveState(measuredMotionValid = true, imuMeasurementsValid = true)
        assertTrue(VisionOutlierFilter.isDriveObservationValid(valid))
        val invalid = listOf(valid.copy(measuredMotionValid = false), valid.copy(imuMeasurementsValid = false),
            valid.copy(measuredFieldXVelocityMetersPerSecond = Double.NaN),
            valid.copy(measuredFieldYVelocityMetersPerSecond = Double.NaN),
            valid.copy(measuredAngularVelocityRadiansPerSecond = Double.NaN),
            valid.copy(pitchDegrees = Double.NaN), valid.copy(rollDegrees = Double.NaN),
            valid.copy(xAccelerationG = Double.NaN), valid.copy(yAccelerationG = Double.NaN),
            valid.copy(zAccelerationG = Double.NaN),
            valid.copy(poseEstimator = PoseEstimatorSnapshot(estimatedPoseX = Double.NaN)),
            valid.copy(poseEstimator = PoseEstimatorSnapshot(estimatedPoseY = Double.NaN)),
            valid.copy(poseEstimator = PoseEstimatorSnapshot(estimatedPoseHeading = Double.NaN)))
        invalid.forEach { assertFalse(VisionOutlierFilter.isDriveObservationValid(it)) }
    }

    @Test fun `physical recovery and cached drive validation allocate no per-call storage`() {
        val frame = frame()
        val drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        var valid = 0
        fun loop(count: Int) {
            repeat(count) {
                if (VisionOutlierFilter.isDriveObservationValid(drive) &&
                    VisionOutlierFilter.isValidForRecovery(config, frame, true)) valid++
            }
        }
        loop(100_000)
        val before = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        loop(10_000)
        val elapsed = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("Vision recovery gates: $allocated bytes / 10,000 checks, ${elapsed / 10_000.0} ns/check (desktop JVM)")
        assertTrue(allocated <= 4096L, "Physical recovery checks allocated $allocated bytes")
        assertEquals(110_000, valid)
    }
}
