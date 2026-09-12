package com.areslib.hardware.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionNoiseRejectionTest {

    @Test
    fun `test outlier rejection filters simulation outliers`() {
        val simulator = VisionSimulator()
        val filter = VisionOutlierFilter()
        val truePose = Pose2d(0.0, 0.0, Rotation2d(0.0))

        // Generate measurements with 100% outlier probability to guarantee outlier generation
        val measurements = simulator.generateMeasurements(
            truePose = truePose,
            currentTimestampMs = 1000L,
            outlierProbability = 1.0
        )

        assertTrue(measurements.isNotEmpty(), "Simulator should have produced outlier measurements within range")

        for (measurement in measurements) {
            val isValid = filter.isValid(measurement, truePose.heading.radians, truePose)
            assertFalse(isValid, "Outlier measurement tag ${measurement.tagId} should be rejected: ${measurement.targetPose}")
        }
    }

    @Test
    fun `test EKF convergence under noise and latency`() {
        val store = Store()
        val odometryOnly = Store()
        val simulator = VisionSimulator()
        val totalSteps = 100
        val dt = 0.02
        val startTimeMs = 1000L
        val latencyMs = 80L
        val trueXSpeed = 0.5
        val biasedOdometrySpeed = 0.7

        for (step in 1..totalSteps) {
            val nowMs = startTimeMs + step * 20L
            val odometry = RobotAction.DriveHardwareUpdate(
                xVelocity = biasedOdometrySpeed, yVelocity = 0.0, angularVelocity = 0.0,
                deltaX = biasedOdometrySpeed * dt, deltaY = 0.0, deltaHeading = 0.0,
                timestampMs = nowMs
            )
            store.dispatch(odometry)
            odometryOnly.dispatch(odometry)

            if (step % 5 == 0) {
                // The observed pose belongs to capture time, not to the time the frame arrives.
                val captureElapsedSeconds = (nowMs - latencyMs - startTimeMs) / 1000.0
                val capturePose = Pose2d(trueXSpeed * captureElapsedSeconds, 0.0, Rotation2d(0.0))
                val observations = simulator.generateMeasurements(
                    truePose = capturePose, currentTimestampMs = nowMs,
                    latencyMs = latencyMs, outlierProbability = 0.0
                )
                assertTrue(observations.all { it.timestampMs == nowMs - latencyMs })
                store.dispatch(RobotAction.VisionMeasurementsReceived(observations, nowMs))
            }
        }

        val estimated = store.state.drive.poseEstimator.estimatedPose
        val trueX = trueXSpeed * totalSteps * dt
        val odometryError = kotlin.math.abs(odometryOnly.state.drive.poseEstimator.estimatedPoseX - trueX)
        val fusedError = kotlin.math.abs(estimated.x - trueX)
        println("Delayed noisy vision: odometry error=$odometryError m, fused error=$fusedError m")
        assertTrue(odometryError > 0.3, "The control stream must accumulate measurable odometry bias")
        assertTrue(fusedError < 0.15 && fusedError < odometryError / 2.0,
            "Delayed vision must correct the biased odometry, not merely track perfect odometry")
        assertEquals(0.0, estimated.y, 0.10)
        assertEquals(0.0, estimated.heading.radians, 0.05)
    }
}
