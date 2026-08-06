package com.areslib.hardware.vision

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VisionNoiseRejectionTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
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
        var state = RobotState()
        val simulator = VisionSimulator()

        val totalSteps = 100
        val dt = 0.02 // 20ms steps
        var currentTimeMs = 1000L

        // Ground-truth robot moves along x-axis from x=0.0 to x=2.0
        val trueXSpeed = 1.0 // 1.0 m/s

        for (step in 1..totalSteps) {
            currentTimeMs += (dt * 1000).toLong()
            val simPose = Pose2d(trueXSpeed * (step * dt), 0.0, Rotation2d(0.0))

            // 1. Dispatch Odometry observation
            state = rootReducer(
                state,
                RobotAction.DriveHardwareUpdate(
                    xVelocity = trueXSpeed,
                    yVelocity = 0.0,
                    angularVelocity = 0.0,
                    deltaX = trueXSpeed * dt,
                    deltaY = 0.0,
                    deltaHeading = 0.0,
                    timestampMs = currentTimeMs
                )
            )

            // 2. Periodically inject simulated visual measurements (every 100ms) with 80ms latency
            if (step % 5 == 0) {
                val visionMeasurements = simulator.generateMeasurements(
                    truePose = simPose,
                    currentTimestampMs = currentTimeMs,
                    latencyMs = 80L,
                    outlierProbability = 0.0 // Verify pure noise convergence first
                )

                state = rootReducer(
                    state,
                    RobotAction.VisionMeasurementsReceived(visionMeasurements, currentTimeMs)
                )
            }
        }

        val estimatedPose = state.drive.poseEstimator.estimatedPose
        val finalTruePose = Pose2d(trueXSpeed * (totalSteps * dt), 0.0, Rotation2d(0.0))

        println("Final true pose: $finalTruePose")
        println("Final estimated EKF pose: $estimatedPose")

        // EKF must successfully track the true pose within 15cm accuracy under standard Gaussian noise & 80ms latency
        assertEquals(finalTruePose.x, estimatedPose.x, 0.15, "EKF X coordinate should converge to true pose")
        assertEquals(finalTruePose.y, estimatedPose.y, 0.10, "EKF Y coordinate should converge to true pose")
        assertEquals(finalTruePose.heading.radians, estimatedPose.heading.radians, 0.05, "EKF Heading should converge to true pose")
    }
}

