package com.areslib.math.estimation

import com.areslib.kinematics.DifferentialDriveKinematics
import com.areslib.kinematics.MecanumKinematics
import com.areslib.kinematics.SwerveKinematics
import com.areslib.math.geometry.*
import com.areslib.state.VisionMeasurement
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent physical references over operating ranges; no forward/inverse round-trip oracle. */
class RobotReadinessMathTest {
    @Test fun `wheel kinematics agree with physical basis and rigid body motion`() {
        val mecanum = MecanumKinematics(0.4, 0.6)
        val wheelSpeeds = DoubleArray(4)
        // Hand-calculated wheel speeds in FL, FR, rear-left, rear-right order, meters/second.
        val references = listOf(
            doubleArrayOf(1.2, 0.0, 0.0, 1.2, 1.2, 1.2, 1.2),
            doubleArrayOf(0.0, 0.6, 0.0, -0.6, 0.6, 0.6, -0.6),
            doubleArrayOf(0.0, 0.0, 0.8, -0.4, 0.4, -0.4, 0.4),
            doubleArrayOf(1.2, -0.4, 0.8, 1.2, 1.2, 0.4, 2.0),
        )
        for (reference in references) {
            mecanum.toWheelSpeeds(reference[0], reference[1], reference[2], wheelSpeeds)
            repeat(4) { assertEquals(reference[it + 3], wheelSpeeds[it], 1e-12) }
        }
        // XRP wheels trace concentric circles: R=1.25m, angular speed=.8rad/s, track=.155m.
        val differential = DifferentialDriveKinematics(0.155).toWheelSpeeds(ChassisSpeeds(1.0, 0.0, 0.8))
        assertEquals(0.938, differential.leftMetersPerSecond, 1e-12)
        assertEquals(1.062, differential.rightMetersPerSecond, 1e-12)

        val modules = listOf(Translation2d(0.3, 0.3), Translation2d(0.3, -0.3),
            Translation2d(-0.3, 0.3), Translation2d(-0.3, -0.3))
        for (vx in doubleArrayOf(-3.0, 0.0, 3.0)) for (vy in doubleArrayOf(-1.0, 0.0, 1.0)) {
            for (omega in doubleArrayOf(-3.0, -0.4, 0.0, 0.4, 3.0)) {
                val states = SwerveKinematics(modules).toSwerveModuleStates(ChassisSpeeds(vx, vy, omega))
                // Differentiate each module's global rigid-body position, independent of wheel conversion.
                val epsilon = 1e-5
                fun position(module: Translation2d, t: Double) = Translation2d(
                    vx * t + module.x * cos(omega * t) - module.y * sin(omega * t),
                    vy * t + module.x * sin(omega * t) + module.y * cos(omega * t))
                for (i in modules.indices) {
                    val before = position(modules[i], -epsilon); val after = position(modules[i], epsilon)
                    assertEquals((after.x - before.x) / (2 * epsilon), states[i].speedMetersPerSecond * cos(states[i].angle.radians), 1e-8)
                    assertEquals((after.y - before.y) / (2 * epsilon), states[i].speedMetersPerSecond * sin(states[i].angle.radians), 1e-8)
                }
            }
        }
    }

    @Test fun `estimation follows known straight clockwise and counterclockwise trajectories`() {
        for (omega in doubleArrayOf(-0.8, 0.0, 0.8)) {
            val state = initial()
            repeat(300) { index ->
                val step = index + 1
                PoseEstimator.addOdometryObservationDirect(state, 1000L + step * 20L, 1.2 * 0.02, 0.25 * 0.02, omega * 0.02,
                    dtSeconds = 0.02, applyGyroBiasCorrection = false)
                val expected = truth(step * 0.02, omega)
                assertEquals(expected.x, state.estimatedPoseX, 1e-9)
                assertEquals(expected.y, state.estimatedPoseY, 1e-9)
                assertEquals(atan2(sin(expected.heading), cos(expected.heading)), state.estimatedPoseHeading, 1e-9)
            }
        }
    }

    @Test fun `delayed noisy vision reduces realistic odometry drift against analytic trajectories`() {
        for (omega in doubleArrayOf(-0.8, 0.0, 0.8)) {
            val fused = initial(); val dead = initial()
            var fusedSquared = 0.0; var deadSquared = 0.0; var accepted = 0
            repeat(300) { index ->
                val step = index + 1
                for (state in listOf(fused, dead)) PoseEstimator.addOdometryObservationDirect(state,
                    1000L + step * 20L, 1.2 * 0.02 * 1.02, 0.25 * 0.02, omega * 0.02 * 1.008,
                    dtSeconds = 0.02, applyGyroBiasCorrection = false)
                if (step >= 10 && step % 5 == 0) {
                    val captured = truth((step - 5) * 0.02, omega)
                    val observation = measurement(1000L + (step - 5) * 20L,
                        captured.x + 0.01 * sin(step.toDouble()), captured.y + 0.01 * cos(step.toDouble()), captured.heading)
                    PoseEstimator.addVisionMeasurementDirect(fused, observation, 0.04, 0.04, 0.03,
                        scaleStdDevX = false, scaleStdDevY = false, scaleStdDevHeading = false)
                    if (fused.lastMeasurementAccepted) accepted++
                }
                val expected = truth(step * 0.02, omega)
                if (step > 50) {
                    fusedSquared += (fused.estimatedPoseX - expected.x).pow(2) + (fused.estimatedPoseY - expected.y).pow(2)
                    deadSquared += (dead.estimatedPoseX - expected.x).pow(2) + (dead.estimatedPoseY - expected.y).pow(2)
                }
            }
            val fusedRms = sqrt(fusedSquared / 250); val deadRms = sqrt(deadSquared / 250)
            println("Readiness trajectory omega=$omega: fusedRmsMeters=$fusedRms, deadRmsMeters=$deadRms, acceptedVision=$accepted")
            assertTrue(accepted >= 50, "Expected usable delayed vision observations")
            // Curvature partly cancels the signed wheel/yaw biases; all three paths still drift >3cm.
            assertTrue(deadRms > 0.03, "The reference workload must expose material drift")
            assertTrue(fusedRms < 0.05 && fusedRms < deadRms * 0.6, "Fused $fusedRms, dead $deadRms")
            assertTrue(fused.covarianceArray.all { it.isFinite() })
        }
    }

    @Test fun `EKF covariance and correction agree with independent scalar Bayesian posteriors`() {
        val prior = doubleArrayOf(0.04, 0.0, 0.0, 0.0, 0.09, 0.0, 0.0, 0.0, 0.01)
        val state = PoseEstimatorState(covarianceArray = prior.copyOf())
        state.history.addEntryDirect(1000L, 0.0, 0.0, 0.0, state.covariance, 1.0)
        state.lastObservationTimestampMs = 1000L
        PoseEstimator.addVisionMeasurementDirect(state, measurement(1000L, 0.2, -0.1, 0.05),
            0.1, 0.2, 0.05, scaleStdDevX = false, scaleStdDevY = false, scaleStdDevHeading = false)
        assertTrue(state.lastMeasurementAccepted, state.lastRejectionReason)
        val observations = doubleArrayOf(0.2, -0.1, 0.05)
        val variances = doubleArrayOf(0.01, 0.04, 0.0025)
        val means = doubleArrayOf(state.estimatedPoseX, state.estimatedPoseY, state.estimatedPoseHeading)
        repeat(3) { axis ->
            val posteriorVariance = 1.0 / (1.0 / prior[axis * 4] + 1.0 / variances[axis])
            assertEquals(posteriorVariance * observations[axis] / variances[axis], means[axis], 1e-12)
            assertEquals(posteriorVariance, state.covarianceArray[axis * 4], 1e-12)
        }
    }

    private fun initial() = PoseEstimatorState(estimatedPoseX = 4.0, estimatedPoseY = 3.0, estimatedPoseHeading = 0.3)
    private data class Reference(val x: Double, val y: Double, val heading: Double)
    private fun truth(t: Double, omega: Double): Reference {
        // Closed-form global trajectory from continuous rigid-body motion; no per-step integrator.
        val heading = 0.3 + omega * t
        if (omega == 0.0) return Reference(4 + (1.2 * cos(0.3) - 0.25 * sin(0.3)) * t,
            3 + (1.2 * sin(0.3) + 0.25 * cos(0.3)) * t, heading)
        return Reference(4 + (1.2 * (sin(heading) - sin(0.3)) + 0.25 * (cos(heading) - cos(0.3))) / omega,
            3 + (-1.2 * (cos(heading) - cos(0.3)) + 0.25 * (sin(heading) - sin(0.3))) / omega, heading)
    }
    private fun measurement(time: Long, x: Double, y: Double, heading: Double) = VisionMeasurement(
        timestampMs = time, targetPose = Pose3d(Translation3d(x, y, 0.0), Rotation3d(0.0, 0.0, heading)),
        tagId = -1, ambiguity = 0.01,
        robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d()))
}
