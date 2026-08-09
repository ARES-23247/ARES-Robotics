package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.pathing.Path
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.sequencer.tasks.BlinkIndicatorTask
import com.areslib.sequencer.tasks.SetIndicatorColorTask

/**
 * A fluid builder API to construct sequential and parallel autonomous sequences of [Task] objects.
 * Hides task class instantiation boilerplate behind clean, chainable method calls.
 */
class RobotSequence {
    private val tasks = mutableListOf<Task>()

    /**
     * Commands the robot to follow a trajectory path.
     */
    fun followPath(
        path: Path,
        follower: HolonomicPathFollower,
        symmetry: FieldSymmetry = FieldSymmetry.ROTATIONAL
    ): RobotSequence {
        tasks.add(FollowPathTask(follower, path, symmetry))
        return this
    }

    /**
     * Blocks execution progress until the active path tracking covers the specified distance.
     */
    fun waitDistance(meters: Double): RobotSequence {
        tasks.add(PathProgressWaitTask(meters))
        return this
    }

    /**
     * Blocks execution for a specified duration of time in milliseconds.
     */
    fun waitTime(ms: Long): RobotSequence {
        tasks.add(TimeWaitTask(ms))
        return this
    }

    /**
     * Blocks execution until a dynamic condition is met.
     */
    fun waitUntil(predicate: (com.areslib.state.RobotState) -> Boolean): RobotSequence {
        tasks.add(WaitUntilTask(predicate))
        return this
    }

    /**
     * Dispatches a single Redux [RobotAction] instantly.
     */
    fun dispatch(action: RobotAction): RobotSequence {
        tasks.add(ActionDispatchTask(action))
        return this
    }

    /**
     * Appends a generic [Task] to the sequence.
     */
    fun addTask(task: Task): RobotSequence {
        tasks.add(task)
        return this
    }

    /**
     * Appends a group of tasks that will execute concurrently in parallel.
     */
    fun addParallel(vararg tasks: Task): RobotSequence {
        this.tasks.add(ParallelTaskGroup(tasks.toList()))
        return this
    }

    /**
     * Appends a group of tasks that will execute sequentially.
     */
    fun addSequential(vararg tasks: Task): RobotSequence {
        this.tasks.add(SequentialTaskGroup(tasks.toList()))
        return this
    }

    /**
     * Sets a named indicator light to a predefined color.
     * This is an instant task — it dispatches the action and completes immediately.
     *
     * @param name Hardware map name of the indicator light (e.g. "indicator").
     * @param color The target [IndicatorLightColor].
     */
    fun setIndicator(name: String, color: com.areslib.hardware.actuator.IndicatorLightColor): RobotSequence {
        tasks.add(SetIndicatorColorTask(name, color))
        return this
    }

    /**
     * Blinks a named indicator light between two colors for a duration.
     *
     * @param name Hardware map name of the indicator light.
     * @param colorA First blink color (shown initially and on completion).
     * @param colorB Second blink color (alternates with colorA).
     * @param durationMs Total blink duration in milliseconds.
     * @param periodMs Full blink cycle period in ms (default 500ms = 1Hz blink).
     */
    fun blinkIndicator(
        name: String,
        colorA: com.areslib.hardware.actuator.IndicatorLightColor,
        colorB: com.areslib.hardware.actuator.IndicatorLightColor = com.areslib.hardware.actuator.IndicatorLightColor.OFF,
        durationMs: Long,
        periodMs: Long = 500L
    ): RobotSequence {
        tasks.add(BlinkIndicatorTask(name, colorA, colorB, durationMs, periodMs))
        return this
    }

    /**
     * Compiles the chained methods into a single composite [Task] (a [SequentialTaskGroup]).
     */
    fun build(): Task {
        return SequentialTaskGroup(tasks)
    }
}
