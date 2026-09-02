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
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind

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
    val actions: List<RoutinePreviewAction> = emptyList(),
)

/** One instant action on the deterministic structural preview timeline. */
data class RoutinePreviewAction(
    val timeSeconds: Double,
    val stepId: String,
    val actionKey: String,
    val arguments: Map<String, String>,
)

/** Compiles deterministic routine drive requests into an educational kinematic preview. */
internal class RoutineTrajectoryPreviewCompiler(
    private val trajectoryPlanner: TrajectoryPlanner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider)),
) {
    fun compile(
        steps: List<RoutineStep>,
        previewStart: RoutinePose?,
        hasAutonomousStart: Boolean,
        league: League,
    ): RoutineTrajectoryPreview {
        if (previewStart == null) return RoutineTrajectoryPreview(null, 0.0)

        val driveModel = if (league == League.FRC) DriveModel.SWERVE else DriveModel.MECANUM
        var current = previewStart.toPose2d()
        var timeOffset = 0.0
        val previewStates = mutableListOf<TrajectoryState>()
        val actions = mutableListOf<RoutinePreviewAction>()
        var driveIndex = 0

        fun appendStationaryState() {
            val prior = previewStates.lastOrNull()
            if (prior == null || prior.timeSeconds != timeOffset) {
                previewStates += TrajectoryState(
                    timeSeconds = timeOffset,
                    x = current.x,
                    y = current.y,
                    headingRad = current.heading.radians,
                    velocity = 0.0,
                )
            }
        }

        appendStationaryState()

        steps.forEach { step ->
            when (step.kind) {
                RoutineStepKind.ACTION -> actions += RoutinePreviewAction(
                    timeSeconds = timeOffset,
                    stepId = step.stepId,
                    actionKey = requireNotNull(step.actionKey),
                    arguments = step.arguments,
                )
                RoutineStepKind.WAIT -> {
                    timeOffset += requireNotNull(step.durationSeconds)
                    appendStationaryState()
                }
                RoutineStepKind.WAIT_UNTIL -> {
                    // A condition may finish earlier at runtime; the declared timeout is the only
                    // safe, deterministic upper bound the editor can preview.
                    timeOffset += requireNotNull(step.timeoutSeconds)
                    appendStationaryState()
                }
                RoutineStepKind.DRIVE_TO -> {
                    val drive = requireNotNull(step.drive)
                    // A neutral routine has no start pose. Treat its first drive target as the
                    // preview anchor rather than inventing entry-point metadata.
                    if (!hasAutonomousStart && driveIndex == 0) {
                        current = drive.target.toPose2d()
                        if (previewStates.size == 1 && timeOffset == 0.0) previewStates.clear()
                        appendStationaryState()
                        driveIndex++
                        return@forEach
                    }

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
                    ).trajectory ?: return@forEach

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
                    driveIndex++
                }
                else -> error("Preview analyzer emitted unsupported ${step.kind} step")
            }
        }

        return RoutineTrajectoryPreview(
            trajectory = previewStates.takeIf { timeOffset > 0.0 && it.size >= 2 }
                ?.let { Trajectory(timeOffset, it) },
            estimatedDurationSeconds = timeOffset,
            actions = actions,
        )
    }
}

private fun RoutinePose.toPose2d(): Pose2d =
    Pose2d(xMeters, yMeters, Rotation2d(headingRadians))
