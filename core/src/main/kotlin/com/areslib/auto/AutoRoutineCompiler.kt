package com.areslib.auto

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.CommandKey
import com.areslib.pathing.DriveModel
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.NamedCommands
import com.areslib.pathing.TimedTrajectoryEvent
import com.areslib.pathing.TrajectoryDiagnosticSeverity
import com.areslib.pathing.TrajectoryEngine
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.pathing.TrajectoryRequest
import com.areslib.sequencer.FollowPathTask
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.ParallelRaceGroup
import com.areslib.sequencer.ParallelTaskGroup
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TimeWaitTask
import kotlin.math.abs

data class AutoCompilationResult(
    val task: Task?,
    val issues: List<AutoValidationIssue>,
    val selectedEngines: List<TrajectoryEngine>
) {
    val isSuccess: Boolean
        get() = task != null && issues.none { it.severity == AutoValidationSeverity.ERROR }
}

/**
 * Compiles the GUI/DSL auto document into the existing deterministic task executor.
 *
 * The compiler is the only layer that knows about trajectory providers, followers, and named-task
 * factories. This keeps novice APIs declarative and makes validation identical for GUI-authored and
 * code-authored routines.
 */
class AutoRoutineCompiler(
    private val trajectoryPlanner: TrajectoryPlanner,
    private val follower: HolonomicPathFollower,
    private val driveModel: DriveModel,
    private val limitsForPreset: (TrajectoryPreset) -> TrajectoryLimits,
    private val strictCapabilities: Boolean = true,
    private val poseTransform: (AutoPose) -> Pose2d = { pose ->
        Pose2d(pose.xMeters, pose.yMeters, Rotation2d(pose.headingRadians))
    }
) {
    fun compile(routine: AutoRoutine): AutoCompilationResult {
        val issues = validateAutoRoutine(routine).toMutableList()
        if (issues.any { it.severity == AutoValidationSeverity.ERROR }) {
            return AutoCompilationResult(null, issues, emptyList())
        }

        val engines = mutableListOf<TrajectoryEngine>()
        val compiled = compileSequence(
            steps = routine.steps,
            startPose = poseTransform(routine.startingPose),
            path = "steps",
            issues = issues,
            engines = engines
        )
        return AutoCompilationResult(
            task = compiled?.task?.takeUnless { issues.any { issue ->
                issue.severity == AutoValidationSeverity.ERROR
            } },
            issues = issues,
            selectedEngines = engines
        )
    }

    private fun compileSequence(
        steps: List<AutoStep>,
        startPose: Pose2d,
        path: String,
        issues: MutableList<AutoValidationIssue>,
        engines: MutableList<TrajectoryEngine>
    ): CompiledStep? {
        var currentPose = startPose
        var moved = false
        val tasks = mutableListOf<Task>()
        steps.forEachIndexed { index, step ->
            val compiled = compileStep(step, currentPose, "$path[$index]", issues, engines)
                ?: return@forEachIndexed
            tasks += compiled.task
            currentPose = compiled.endPose
            moved = moved || compiled.movesRobot
        }
        if (tasks.size != steps.size) return null
        return CompiledStep(SequentialTaskGroup(tasks), currentPose, moved)
    }

    private fun compileStep(
        step: AutoStep,
        startPose: Pose2d,
        path: String,
        issues: MutableList<AutoValidationIssue>,
        engines: MutableList<TrajectoryEngine>
    ): CompiledStep? {
        return when (step.kind) {
            AutoStepKind.DRIVE -> {
                val drive = step.drive ?: return null
                compileDrive(drive, startPose, path, issues, engines)
            }
            AutoStepKind.COMMAND -> {
                val commandKey = step.commandKey ?: return null
                val task = commandTask(commandKey, path, issues) ?: return null
                CompiledStep(task, startPose, movesRobot = false)
            }
            AutoStepKind.WAIT -> {
                val durationSeconds = step.durationSeconds ?: return null
                CompiledStep(
                    TimeWaitTask((durationSeconds * 1000.0).toLong()),
                    startPose,
                    movesRobot = false
                )
            }
            AutoStepKind.TOGETHER -> compileParallel(
                children = step.children,
                startPose = startPose,
                path = path,
                race = false,
                issues = issues,
                engines = engines
            )
            AutoStepKind.FIRST_TO_FINISH -> compileParallel(
                children = step.children,
                startPose = startPose,
                path = path,
                race = true,
                issues = issues,
                engines = engines
            )
        }
    }

    private fun compileDrive(
        drive: AutoDriveStep,
        startPose: Pose2d,
        path: String,
        issues: MutableList<AutoValidationIssue>,
        engines: MutableList<TrajectoryEngine>
    ): CompiledStep? {
        val targetPose = poseTransform(drive.target)
        val generation = trajectoryPlanner.generate(
            TrajectoryRequest(
                waypoints = listOf(startPose, targetPose),
                driveModel = driveModel,
                preset = drive.preset,
                limits = limitsForPreset(drive.preset),
                preferredEngine = drive.preferredEngine
            )
        )
        generation.diagnostics.forEach { diagnostic ->
            val severity = if (diagnostic.severity == TrajectoryDiagnosticSeverity.ERROR) {
                AutoValidationSeverity.ERROR
            } else {
                AutoValidationSeverity.WARNING
            }
            issues += AutoValidationIssue(severity, path, diagnostic.code, diagnostic.message)
        }
        val generatedTrajectory = generation.trajectory ?: return null
        engines += generatedTrajectory.engine

        val markers = drive.markers.map { marker ->
            TimedTrajectoryEvent(
                command = CommandKey(marker.commandKey),
                timeSeconds = generatedTrajectory.durationSeconds * marker.progress
            )
        }
        val trajectory = generatedTrajectory.copy(events = markers)
        val followTask = FollowPathTask(
            follower = follower,
            path = trajectory.toPath(),
            mirrorForAlliance = false
        )
        val duringTasks = drive.duringCommands.mapNotNull { commandTask(it, "$path.during", issues) }
        if (duringTasks.size != drive.duringCommands.size) return null
        val driveTask: Task = if (duringTasks.isEmpty()) {
            followTask
        } else {
            ParallelDeadlineGroup(followTask, duringTasks)
        }

        val arrivalTasks = drive.arrivalCommands.mapNotNull { commandTask(it, "$path.onArrival", issues) }
        if (arrivalTasks.size != drive.arrivalCommands.size) return null
        val completeTask = when (arrivalTasks.size) {
            0 -> driveTask
            1 -> SequentialTaskGroup(listOf(driveTask, arrivalTasks.single()))
            else -> SequentialTaskGroup(listOf(driveTask, ParallelTaskGroup(arrivalTasks)))
        }
        return CompiledStep(completeTask, targetPose, movesRobot = true)
    }

    private fun compileParallel(
        children: List<AutoStep>,
        startPose: Pose2d,
        path: String,
        race: Boolean,
        issues: MutableList<AutoValidationIssue>,
        engines: MutableList<TrajectoryEngine>
    ): CompiledStep? {
        val compiledChildren = children.mapIndexedNotNull { index, child ->
            compileStep(child, startPose, "$path.children[$index]", issues, engines)
        }
        if (compiledChildren.size != children.size) return null
        val movingChildren = compiledChildren.filter { it.movesRobot }

        if (race && movingChildren.isNotEmpty()) {
            issues += AutoValidationIssue(
                AutoValidationSeverity.ERROR,
                path,
                "ambiguous_race_motion",
                "A first-to-finish group cannot contain drive goals because its final pose is unpredictable"
            )
            return null
        }
        if (movingChildren.size > 1 && movingChildren.drop(1).any {
                !samePose(it.endPose, movingChildren.first().endPose)
            }
        ) {
            issues += AutoValidationIssue(
                AutoValidationSeverity.ERROR,
                path,
                "conflicting_parallel_motion",
                "Parallel branches cannot drive to different poses"
            )
            return null
        }

        val task = if (race) {
            ParallelRaceGroup(compiledChildren.map { it.task })
        } else {
            ParallelTaskGroup(compiledChildren.map { it.task })
        }
        return CompiledStep(
            task = task,
            endPose = movingChildren.firstOrNull()?.endPose ?: startPose,
            movesRobot = movingChildren.isNotEmpty()
        )
    }

    private fun commandTask(
        rawKey: String,
        path: String,
        issues: MutableList<AutoValidationIssue>
    ): Task? {
        val key = runCatching { CommandKey(rawKey) }.getOrNull() ?: return null
        if (strictCapabilities && !NamedCommands.contains(key)) {
            issues += AutoValidationIssue(
                AutoValidationSeverity.ERROR,
                path,
                "unknown_capability",
                "Robot has not advertised the '${key.value}' capability"
            )
            return null
        }
        return NamedCommands.task(key)
    }

    private data class CompiledStep(
        val task: Task,
        val endPose: Pose2d,
        val movesRobot: Boolean
    )

    private fun samePose(first: Pose2d, second: Pose2d): Boolean =
        abs(first.x - second.x) < 1e-9 && abs(first.y - second.y) < 1e-9 &&
            abs(com.areslib.math.wrapAngle(first.heading.radians - second.heading.radians)) < 1e-9
}
