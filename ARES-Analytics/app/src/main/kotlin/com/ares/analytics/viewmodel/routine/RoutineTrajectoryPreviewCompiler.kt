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
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import java.util.concurrent.CancellationException

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
    val warning: String? = null,
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
        checkActive: () -> Unit = {},
    ): RoutineTrajectoryPreview {
        if (previewStart == null) return RoutineTrajectoryPreview(null, 0.0)
        return try {
            compileTimeline(steps, previewStart, hasAutonomousStart, league, checkActive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RoutineTrajectoryPreview(
                trajectory = null,
                estimatedDurationSeconds = 0.0,
                warning = "Preview unavailable: ${failure.message ?: "trajectory generation failed"}.",
            )
        }
    }

    private fun compileTimeline(
        steps: List<RoutineStep>,
        previewStart: RoutinePose,
        hasAutonomousStart: Boolean,
        league: League,
        checkActive: () -> Unit,
    ): RoutineTrajectoryPreview {
        checkActive()
        require(previewStart.isFinitePreviewPose()) { "start pose must be finite" }
        steps.firstNotNullOfOrNull(::routinePreviewStepWarning)?.let { error(it) }
        val driveModel = if (league == League.FRC) DriveModel.SWERVE else DriveModel.MECANUM
        var current = previewStart.toPose2d()
        var timeOffset = 0.0
        val previewStates = mutableListOf<TrajectoryState>()
        val actions = mutableListOf<RoutinePreviewAction>()
        var driveIndex = 0

        fun shiftedTime(duration: Double): Double {
            val next = timeOffset + duration
            require(next.isFinite() && (duration == 0.0 || next > timeOffset)) {
                "timeline duration exceeds numeric precision"
            }
            return next
        }

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
            checkActive()
            when (step.kind) {
                RoutineStepKind.ACTION -> actions += RoutinePreviewAction(
                    timeSeconds = timeOffset,
                    stepId = step.stepId,
                    actionKey = requireNotNull(step.actionKey),
                    arguments = step.arguments.toMap(),
                )
                RoutineStepKind.WAIT -> {
                    timeOffset = shiftedTime(requireNotNull(step.durationSeconds))
                    appendStationaryState()
                }
                RoutineStepKind.WAIT_UNTIL -> {
                    // A condition may finish earlier at runtime; the declared timeout is the only
                    // safe, deterministic upper bound the editor can preview.
                    timeOffset = shiftedTime(requireNotNull(step.timeoutSeconds))
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
                    val preset = TrajectoryPreset.entries.firstOrNull {
                        it.name.equals(drive.motionPresetKey, ignoreCase = true)
                    } ?: TrajectoryPreset.BALANCED
                    val result = trajectoryPlanner.generate(
                        TrajectoryRequest(
                            waypoints = listOf(current, target),
                            driveModel = driveModel,
                            preset = preset,
                            limits = ROUTINE_PREVIEW_LIMITS,
                            preferredEngine = null,
                        ),
                    )
                    checkActive()
                    check(result.isSuccess) { "drive ${driveIndex + 1} could not be generated" }
                    val generated = checkNotNull(result.trajectory)
                    val endTime = shiftedTime(generated.durationSeconds)

                    generated.states.forEachIndexed { index, sample ->
                        if (previewStates.isEmpty() || index > 0) {
                            val sampleTime = shiftedTime(sample.timeSeconds)
                            require(previewStates.lastOrNull()?.let { sampleTime > it.timeSeconds } != false) {
                                "drive ${driveIndex + 1} sample times exceed numeric precision"
                            }
                            previewStates += TrajectoryState(
                                timeSeconds = sampleTime,
                                x = sample.pose.x,
                                y = sample.pose.y,
                                headingRad = sample.pose.heading.radians,
                                velocity = kotlin.math.hypot(sample.velocityXMps, sample.velocityYMps),
                            )
                        }
                    }
                    timeOffset = endTime
                    current = target
                    driveIndex++
                }
                else -> error("Preview analyzer emitted unsupported ${step.kind} step")
            }
        }

        checkActive()
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
