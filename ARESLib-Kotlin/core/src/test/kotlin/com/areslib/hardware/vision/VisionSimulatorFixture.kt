package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import java.util.Random

/** Deterministic test fixture; simulation products own their real camera models. */
internal class VisionSimulator(
    private val random: Random = Random(0L),
    private val tags: Map<Int, Pose3d> = mapOf(
        1 to Pose3d(Translation3d(1.8, 1.8, 0.5), Rotation3d(0.0, 0.0, Math.PI)),
        2 to Pose3d(Translation3d(1.8, -1.8, 0.5), Rotation3d(0.0, 0.0, Math.PI)),
        3 to Pose3d(Translation3d(-1.8, 1.8, 0.5), Rotation3d(0.0, 0.0, 0.0)),
        4 to Pose3d(Translation3d(-1.8, -1.8, 0.5), Rotation3d(0.0, 0.0, 0.0)),
    ),
) {
    fun generateMeasurements(
        truePose: Pose2d,
        currentTimestampMs: Long,
        latencyMs: Long = 80L,
        maxRangeMeters: Double = 6.0,
        noiseTranslationStdDev: Double = 0.03,
        noiseRotationStdDev: Double = 0.01,
        outlierProbability: Double = 0.05,
    ): List<VisionMeasurement> = buildList {
        for ((id, tagPose) in tags) {
            val dx = tagPose.x - truePose.x
            val dy = tagPose.y - truePose.y
            val distance = Math.hypot(dx, dy)
            if (distance > maxRangeMeters) continue

            val isOutlier = random.nextDouble() < outlierProbability
            val measurementPose = if (isOutlier) {
                if (random.nextBoolean()) {
                    Pose3d(
                        Translation3d(truePose.x + 8.5, truePose.y - 4.0, 0.0),
                        Rotation3d(0.0, 0.0, truePose.heading.radians),
                    )
                } else {
                    Pose3d(
                        Translation3d(truePose.x + dx, truePose.y + dy, 0.0),
                        Rotation3d(0.0, 0.0, truePose.heading.radians + Math.PI / 2.0),
                    )
                }
            } else {
                Pose3d(
                    Translation3d(
                        truePose.x + random.nextGaussian() * noiseTranslationStdDev,
                        truePose.y + random.nextGaussian() * noiseTranslationStdDev,
                        0.0,
                    ),
                    Rotation3d(
                        0.0,
                        0.0,
                        truePose.heading.radians + random.nextGaussian() * noiseRotationStdDev,
                    ),
                )
            }
            val ambiguity = if (isOutlier && random.nextBoolean()) {
                0.45
            } else {
                (0.02 * (distance / maxRangeMeters)).coerceAtMost(0.19)
            }
            add(
                VisionMeasurement(
                    timestampMs = currentTimestampMs - latencyMs,
                    targetPose = measurementPose,
                    tagId = id,
                    ambiguity = ambiguity,
                ),
            )
        }
    }
}
