package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.pathing.CommandKey
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.NamedCommands
import com.areslib.pathing.Path
import com.areslib.sequencer.tasks.BlinkIndicatorTask
import com.areslib.sequencer.tasks.SetIndicatorColorTask
import com.areslib.state.RobotState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Prevents task-building calls from accidentally targeting an outer sequence scope. */
@DslMarker
annotation class AresSequenceDsl

/**
 * Typed builder for autonomous task trees.
 *
 * The builder is shared by FTC and FRC. It describes robot behavior only; platform lifecycle code
 * remains responsible for running the resulting [Task] through [TaskExecutor]. Durations use
 * Kotlin [Duration], named commands use [CommandKey], and nested control flow is visually explicit.
 *
 * ```kotlin
 * val routine = robotSequence {
 *     dispatch(RobotAction.SetDriveMode(DriveMode.HEADING_HOLD))
 *     parallel {
 *         task(deployIntake())
 *         waitFor(250.milliseconds)
 *     }
 *     race {
 *         namedCommand(CommandKey("collect_piece"))
 *         waitFor(2.seconds)
 *     }
 * }
 * ```
 */
@AresSequenceDsl
class RobotSequence internal constructor() {
    private val tasks = mutableListOf<Task>()

    /** Appends an already constructed task. A task instance must appear only once in a tree. */
    fun task(task: Task) {
        tasks += task
    }

    /** Dispatches one immutable Redux intent and completes immediately. */
    fun dispatch(action: RobotAction, requiredResources: Long = TaskResources.NONE) {
        task(ActionDispatchTask(action, requiredResources))
    }

    /** Waits for a finite, non-negative [duration], rounded up to the next whole millisecond. */
    fun waitFor(duration: Duration) {
        task(TimeWaitTask(ceilingMilliseconds(duration, "Wait duration")))
    }

    /** Waits until [condition] becomes true. */
    fun waitUntil(condition: (RobotState) -> Boolean) {
        task(WaitUntilTask(condition))
    }

    /**
     * Waits until [condition] becomes true and fails the task after [timeout].
     * A timeout is required at call sites where waiting forever would make an auto unsafe.
     * Whole-millisecond execution expires at the first integer tick strictly beyond [timeout];
     * rounding its strict-greater-than threshold down preserves that boundary.
     */
    fun waitUntil(timeout: Duration, condition: (RobotState) -> Boolean) {
        requireFiniteNonNegative(timeout, "Wait timeout")
        task(WaitUntilTask(condition).withTimeout(timeout.inWholeMilliseconds))
    }

    /** Waits for [meters], or the finite fallback [timeout] rounded up to whole milliseconds. */
    fun waitForDistance(meters: Double, timeout: Duration = 10_000.milliseconds) {
        require(meters.isFinite() && meters >= 0.0) {
            "Path distance must be finite and non-negative"
        }
        task(PathProgressWaitTask(meters, ceilingMilliseconds(timeout, "Path wait timeout")))
    }

    /** Follows [path] using the shared holonomic follower. */
    fun followPath(
        path: Path,
        with: HolonomicPathFollower,
        symmetry: FieldSymmetry = FieldSymmetry.ROTATIONAL
    ) {
        require(path.points.isNotEmpty()) { "Cannot follow an empty path" }
        task(FollowPathTask(with, path, symmetry))
    }

    /** Creates a fresh task from the typed named-command registry when the routine runs. */
    fun namedCommand(
        key: CommandKey,
        requiredResources: Long = NamedCommands.registeredResources(key) ?: TaskResources.NONE
    ) {
        task(NamedCommands.task(key, requiredResources))
    }

    /** Runs every child concurrently and completes when every child completes. */
    fun parallel(block: RobotSequence.() -> Unit) {
        task(ParallelTaskGroup(childTasks("parallel", block)))
    }

    /** Runs every child concurrently and completes when the first child completes. */
    fun race(block: RobotSequence.() -> Unit) {
        task(ParallelRaceGroup(childTasks("race", block)))
    }

    /** Runs [deadline] with the child tasks and interrupts the children when it completes. */
    fun deadline(deadline: Task, block: RobotSequence.() -> Unit) {
        val companions = childTasks("deadline", block)
        task(ParallelDeadlineGroup(deadline, companions))
    }

    /** Adds a nested sequential group when explicit grouping improves readability. */
    fun sequence(block: RobotSequence.() -> Unit) {
        task(SequentialTaskGroup(childTasks("sequence", block)))
    }

    /** Sets an indicator immediately. */
    fun setIndicator(name: String, color: IndicatorLightColor) {
        task(SetIndicatorColorTask(name, color))
    }

    /**
     * Blinks for the duration and full period rounded up to whole milliseconds. The rounded period
     * must be at least two milliseconds so both colors can occupy a clock tick.
     */
    fun blinkIndicator(
        name: String,
        colorA: IndicatorLightColor,
        colorB: IndicatorLightColor = IndicatorLightColor.OFF,
        duration: Duration,
        period: Duration = 500.milliseconds
    ) {
        task(
            BlinkIndicatorTask(
                lightName = name,
                colorA = colorA,
                colorB = colorB,
                durationMs = ceilingMilliseconds(duration, "Blink duration"),
                periodMs = ceilingMilliseconds(period, "Blink period")
            )
        )
    }

    internal fun build(): Task = SequentialTaskGroup(tasks)

    private fun childTasks(groupName: String, block: RobotSequence.() -> Unit): List<Task> {
        val child = RobotSequence().apply(block)
        require(child.tasks.isNotEmpty()) { "$groupName group must contain at least one task" }
        return child.tasks // Every group constructor takes its own immutable membership snapshot.
    }

    private fun requireFiniteNonNegative(duration: Duration, label: String) {
        require(duration.isFinite() && !duration.isNegative()) { "$label must be finite and non-negative" }
    }

    private fun ceilingMilliseconds(duration: Duration, label: String): Long {
        requireFiniteNonNegative(duration, label)
        val whole = duration.inWholeMilliseconds
        return if (whole.milliseconds < duration) Math.addExact(whole, 1L) else whole
    }
}

/** Builds one cross-platform autonomous task tree. */
fun robotSequence(block: RobotSequence.() -> Unit): Task = RobotSequence().apply(block).build()
