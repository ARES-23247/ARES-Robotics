package com.areslib.math.estimation

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Vector3
import com.areslib.state.VisionMeasurement
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Deterministic replay comparison used to prevent estimator changes from hiding drift. */
class PoseEstimatorReplayComparisonTest {

    @Test
    fun `latency compensated vision beats biased dead reckoning on a curved replay`() {
        val fused = PoseEstimatorState()
        val deadReckoning = PoseEstimatorState()
        val truth = ArrayList<TruthPose>(301)
        truth.add(TruthPose(0.0, 0.0, 0.0))

        var truePose = truth[0]
        val dtSeconds = 0.02
        for (step in 1..300) {
            val timestampMs = step * 20L
            val trueDx = 0.035
            val trueDy = 0.002 * sin(step * 0.07)
            val trueDTheta = 0.006
            truePose = integrate(truePose, trueDx, trueDy, trueDTheta)
            truth.add(truePose)

            // Repeatable scale and steering biases create realistic accumulating drift.
            val measuredDx = trueDx * 1.018
            val measuredDy = trueDy + 0.00035
            val measuredDTheta = trueDTheta * 1.012
            PoseEstimator.addOdometryObservationDirect(
                fused, timestampMs, measuredDx, measuredDy, measuredDTheta,
                dtSeconds = dtSeconds
            )
            PoseEstimator.addOdometryObservationDirect(
                deadReckoning, timestampMs, measuredDx, measuredDy, measuredDTheta,
                dtSeconds = dtSeconds
            )

            // Camera observations arrive 100 ms late. The measurement timestamp points
            // to the historical pose so the correction must be replayed through motion
            // that occurred after image capture.
            if (step >= 10 && step % 10 == 0) {
                val observedStep = step - 5
                val observed = truth[observedStep]
                PoseEstimator.addVisionMeasurement(
                    state = fused,
                    measurement = VisionMeasurement(
                        timestampMs = observedStep * 20L,
                        targetPose = Pose3d(
                            Translation3d(observed.x, observed.y, 0.0),
                            Rotation3d(0.0, 0.0, observed.heading)
                        ),
                        tagId = -1,
                        ambiguity = 0.01,
                        robotPoseTargetSpace = Pose3d(
                            Translation3d(0.0, 0.0, 1.0),
                            Rotation3d()
                        )
                    ),
                    visionStdDevs = Vector3(0.04, 0.04, 0.03),
                    numTags = 1,
                    useMahalanobisRejection = false
                )
            }
        }

        val deadReckoningError = hypot(
            deadReckoning.estimatedPoseX - truePose.x,
            deadReckoning.estimatedPoseY - truePose.y
        )
        val fusedError = hypot(
            fused.estimatedPoseX - truePose.x,
            fused.estimatedPoseY - truePose.y
        )

        assertTrue(deadReckoningError > 0.05, "replay must contain meaningful drift")
        assertTrue(
            fusedError < deadReckoningError * 0.5,
            "latency-compensated fusion error $fusedError should beat dead reckoning $deadReckoningError"
        )
        assertTrue(fused.covarianceArray.all(Double::isFinite))
    }

    private fun integrate(pose: TruthPose, dx: Double, dy: Double, dTheta: Double): TruthPose {
        val s = if (kotlin.math.abs(dTheta) < 1e-9) 1.0 else sin(dTheta) / dTheta
        val c = if (kotlin.math.abs(dTheta) < 1e-9) dTheta / 2.0 else (1.0 - cos(dTheta)) / dTheta
        val arcX = s * dx - c * dy
        val arcY = c * dx + s * dy
        val cosHeading = cos(pose.heading)
        val sinHeading = sin(pose.heading)
        return TruthPose(
            x = pose.x + arcX * cosHeading - arcY * sinHeading,
            y = pose.y + arcX * sinHeading + arcY * cosHeading,
            heading = com.areslib.math.wrapAngle(pose.heading + dTheta)
        )
    }

    private data class TruthPose(val x: Double, val y: Double, val heading: Double)
}
