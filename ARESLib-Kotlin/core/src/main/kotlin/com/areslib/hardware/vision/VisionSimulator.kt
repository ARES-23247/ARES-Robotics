package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import java.util.Random

/**
 * High-fidelity virtual AprilTag vision tracking simulation engine.
 * emulates real-world camera detection with latency, Gaussian noise, and occasional outliers.
 */
class VisionSimulator(
    val tags: Map<Int, Pose3d> = mapOf(
        1 to Pose3d(Translation3d(1.8, 1.8, 0.5), Rotation3d(0.0, 0.0, Math.PI)),
        2 to Pose3d(Translation3d(1.8, -1.8, 0.5), Rotation3d(0.0, 0.0, Math.PI)),
        3 to Pose3d(Translation3d(-1.8, 1.8, 0.5), Rotation3d(0.0, 0.0, 0.0)),
        4 to Pose3d(Translation3d(-1.8, -1.8, 0.5), Rotation3d(0.0, 0.0, 0.0))
    )
) {
    private val random = Random()

    /**
     * Generates a list of simulated measurements relative to the true robot pose.
     * Applies noise, delay, and potential outlier conditions to challenge EKF fusion robustness.
     */
    fun generateMeasurements(
        truePose: Pose2d,
        currentTimestampMs: Long,
        latencyMs: Long = 80L,
        maxRangeMeters: Double = 6.0,
        noiseTranslationStdDev: Double = 0.03,
        noiseRotationStdDev: Double = 0.01,
        outlierProbability: Double = 0.05
    ): List<VisionMeasurement> {
        val measurements = mutableListOf<VisionMeasurement>()

        for ((id, tagPose) in tags) {
            val dx = tagPose.x - truePose.x
            val dy = tagPose.y - truePose.y
            val distance = Math.hypot(dx, dy)

            // Camera cannot detect tags out of range
            if (distance > maxRangeMeters) continue

            // 1. Simulate Outlier Generation
            val isOutlier = random.nextDouble() < outlierProbability
            val measurementPose = if (isOutlier) {
                if (random.nextBoolean()) {
                    // Outlier option A: Extreme out-of-bounds translation (creates high distance deviation)
                    Pose3d(
                        Translation3d(truePose.x + 8.5, truePose.y - 4.0, 0.0),
                        Rotation3d(0.0, 0.0, truePose.heading.radians)
                    )
                } else {
                    // Outlier option B: Extreme rotation mismatch
                    Pose3d(
                        Translation3d(truePose.x + dx, truePose.y + dy, 0.0),
                        Rotation3d(0.0, 0.0, truePose.heading.radians + Math.PI / 2.0)
                    )
                }
            } else {
                // 2. Normal Measurement with Gaussian Noise
                val noisyX = truePose.x + random.nextGaussian() * noiseTranslationStdDev
                val noisyY = truePose.y + random.nextGaussian() * noiseTranslationStdDev
                val noisyHeading = truePose.heading.radians + random.nextGaussian() * noiseRotationStdDev
                Pose3d(
                    Translation3d(noisyX, noisyY, 0.0),
                    Rotation3d(0.0, 0.0, noisyHeading)
                )
            }

            val ambiguity = if (isOutlier && random.nextBoolean()) {
                0.45 // High ambiguity outlier
            } else {
                // Closer tags have lower ambiguity
                (0.02 * (distance / maxRangeMeters)).coerceAtMost(0.19)
            }

            measurements.add(
                VisionMeasurement(
                    timestampMs = currentTimestampMs - latencyMs,
                    targetPose = measurementPose,
                    tagId = id,
                    ambiguity = ambiguity
                )
            )
        }

        return measurements
    }
}
