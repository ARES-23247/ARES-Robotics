package com.ares.analytics.viewmodel.routine

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.shared.models.TrajectoryState
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.DriveModel
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.pathing.TrajectoryRequest
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose

private val ROUTINE_PREVIEW_LIMITS = TrajectoryLimits(
    maxVelocityMps = 3.0,
    maxAccelerationMps2 = 3.0,
    maxJerkMps3 = 12.0,
    maxCentripetalAccelerationMps2 = 3.0,
    maxAngularVelocityRps = Math.toRadians(540.0),
    maxAngularAccelerationRps2 = Math.toRadians(720.0),
)

internal data class RoutineTrajectoryPreview(
    val trajectory: Trajectory?,
    val estimatedDurationSeconds: Double,
)

/** Compiles deterministic routine drive requests into an educational kinematic preview. */
internal class RoutineTrajectoryPreviewCompiler(
    private val trajectoryPlanner: TrajectoryPlanner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider)),
) {
    fun compile(
        drives: List<RoutineDriveStep>,
        previewStart: RoutinePose?,
        hasAutonomousStart: Boolean,
        league: League,
    ): RoutineTrajectoryPreview {
        if (previewStart == null || drives.isEmpty()) return RoutineTrajectoryPreview(null, 0.0)

        val driveModel = if (league == League.FRC) DriveModel.SWERVE else DriveModel.MECANUM
        var current = previewStart.toPose2d()
        var timeOffset = 0.0
        val previewStates = mutableListOf<TrajectoryState>()

        drives.forEachIndexed { driveIndex, drive ->
            // A neutral routine has no start pose. Treat its first drive target as the preview
            // anchor, rather than inventing match metadata that would change runtime behavior.
            if (!hasAutonomousStart && driveIndex == 0) return@forEachIndexed

            val target = drive.target.toPose2d()
            val preset = runCatching {
                TrajectoryPreset.valueOf(drive.motionPresetKey.uppercase())
            }.getOrDefault(TrajectoryPreset.BALANCED)
            val generated = trajectoryPlanner.generate(
                TrajectoryRequest(
                    waypoints = listOf(current, target),
                    driveModel = driveModel,
                    preset = preset,
                    limits = ROUTINE_PREVIEW_LIMITS,
                    preferredEngine = null,
                ),
            ).trajectory ?: return@forEachIndexed

            generated.states.forEachIndexed { index, sample ->
                if (previewStates.isEmpty() || index > 0) {
                    previewStates += TrajectoryState(
                        timeSeconds = sample.timeSeconds + timeOffset,
                        x = sample.pose.x,
                        y = sample.pose.y,
                        headingRad = sample.pose.heading.radians,
                        velocity = kotlin.math.hypot(sample.velocityXMps, sample.velocityYMps),
                    )
                }
            }
            timeOffset += generated.durationSeconds
            current = target
        }

        return RoutineTrajectoryPreview(
            trajectory = previewStates.takeIf { it.isNotEmpty() }
                ?.let { Trajectory(timeOffset, it) },
            estimatedDurationSeconds = timeOffset,
        )
    }
}

private fun RoutinePose.toPose2d(): Pose2d =
    Pose2d(xMeters, yMeters, Rotation2d(headingRadians))
