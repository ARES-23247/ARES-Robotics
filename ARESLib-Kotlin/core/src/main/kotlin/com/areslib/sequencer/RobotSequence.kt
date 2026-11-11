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
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Prevents task-building calls from accidentally targeting an outer sequence scope. */
@DslMarker
annotation class AresSequenceDsl

/**
 * Typed builder for cross-platform autonomous and teleop-macro task trees.
 *
 * The builder is shared by FTC and FRC. It describes robot behavior only; platform lifecycle code
 * remains responsible for running the resulting [Task] through [TaskExecutor].
 *
 * To keep text-first authoring consistent across runtime sequences and declarative `.aresroutine`
 * files, [RobotSequence] supports both runtime sequencer vocabulary (`parallel`, `race`,
 * `waitFor(Duration)`, `namedCommand`) and [com.areslib.routine.routine] vocabulary (`together`,
 * `firstToFinish`, `waitSeconds(Double)`, `step`, `deadline(main = { ... }, companions = { ... })`).
 *
 * ### Example
 * ```kotlin
 * val scorePreload = robotSequence {
 *     dispatch(RobotAction.SetDriveMode(DriveMode.HEADING_HOLD))
 *
 *     // Run all children concurrently until all complete (alias: together { ... })
 *     parallel {
 *         namedCommand("arm.scoreHeight")
 *         waitFor(250.milliseconds)
 *     }
 *
 *     // Wait for a sensor or state condition with a mandatory safety timeout
 *     waitUntil(timeout = 1500.milliseconds) { state ->
 *         state.arm.isAtTarget
 *     }
 *
 *     // Run companions until the main deadline task finishes
 *     deadline(
 *         main = { waitSeconds(0.6) },
 *         companions = { namedCommand("outtake.release") },
 *     )
 *
 *     // Complete as soon as the first branch finishes (alias: firstToFinish { ... })
 *     race {
 *         namedCommand("arm.stow")
 *         waitSeconds(1.5)
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

    /**
     * Dispatches one immutable Redux intent computed from the [RobotState] observed when this step starts,
     * completing immediately.
     */
    fun dispatch(
        name: String = "StateAction",
        requiredResources: Long = TaskResources.NONE,
        actionFactory: (RobotState) -> RobotAction,
    ) {
        task(StateActionTask(name, requiredResources, actionFactory))
    }

    /** Waits for a finite, non-negative [duration], rounded up to the next whole millisecond. */
    fun waitFor(duration: Duration) {
        task(TimeWaitTask(ceilingMilliseconds(duration, "Wait duration")))
    }

    /**
     * Waits for a finite, non-negative number of [seconds], rounded up to the next whole millisecond.
     * Matches [com.areslib.routine.RoutineBuilder.waitSeconds] for consistent text-first authoring.
     */
    fun waitSeconds(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0.0) { "Wait duration must be finite and non-negative" }
        val ms = ceil(seconds * 1_000.0)
        require(ms <= Long.MAX_VALUE.toDouble()) { "Wait duration overflows millisecond representation" }
        task(TimeWaitTask(ms.toLong()))
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

    /**
     * Convenience overload that resolves a registered command by its string [key] without requiring
     * an explicit [CommandKey] wrapper at the call site.
     */
    fun namedCommand(
        key: String,
        requiredResources: Long = NamedCommands.registeredResources(CommandKey(key)) ?: TaskResources.NONE,
    ) {
        namedCommand(CommandKey(key), requiredResources)
    }

    /**
     * Alias for [namedCommand] that matches declarative [com.areslib.routine.RoutineBuilder] step syntax.
     */
    fun step(
        key: String,
        requiredResources: Long = NamedCommands.registeredResources(CommandKey(key)) ?: TaskResources.NONE,
    ) {
        namedCommand(CommandKey(key), requiredResources)
    }

    /** Runs every child concurrently and completes when every child completes. */
    fun parallel(block: RobotSequence.() -> Unit) {
        task(ParallelTaskGroup(childTasks("parallel", block)))
    }

    /**
     * Runs every child concurrently and completes when every child completes.
     * Alias for [parallel] that matches [com.areslib.routine.RoutineBuilder.together].
     */
    fun together(block: RobotSequence.() -> Unit) {
        parallel(block)
    }

    /** Runs every child concurrently and completes when the first child completes. */
    fun race(block: RobotSequence.() -> Unit) {
        task(ParallelRaceGroup(childTasks("race", block)))
    }

    /**
     * Runs every child concurrently and completes when the first child completes.
     * Alias for [race] that matches [com.areslib.routine.RoutineBuilder.firstToFinish].
     */
    fun firstToFinish(block: RobotSequence.() -> Unit) {
        race(block)
    }

    /** Runs [deadline] with the child tasks and interrupts the children when it completes. */
    fun deadline(deadline: Task, block: RobotSequence.() -> Unit) {
        val companions = childTasks("deadline", block)
        task(ParallelDeadlineGroup(deadline, companions))
    }

    /**
     * Block-based deadline group matching [com.areslib.routine.RoutineBuilder.deadline].
     *
     * Runs [main] alongside [companions] and interrupts [companions] when [main] completes.
     * If [main] contains multiple steps, they execute sequentially as the deadline task.
     */
    fun deadline(main: RobotSequence.() -> Unit, companions: RobotSequence.() -> Unit) {
        val mainTasks = childTasks("deadline main", main)
        val deadlineTask = mainTasks.singleOrNull() ?: SequentialTaskGroup(mainTasks)
        val companionTasks = childTasks("deadline", companions)
        task(ParallelDeadlineGroup(deadlineTask, companionTasks))
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
