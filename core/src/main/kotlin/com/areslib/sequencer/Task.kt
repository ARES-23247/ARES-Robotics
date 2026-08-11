package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.math.geometry.Pose2d
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide callback registry keyed strictly by task object identity.
 *
 * At most one completion and one failure callback are retained for each task. Invocation is
 * synchronous on the executor thread. Terminal invocation removes both callbacks before executing
 * user code, making callbacks one-shot and releasing captured state even when the callback throws.
 */
object TaskCallbacks {
    private class TaskIdentity(val task: Task) {
        override fun hashCode(): Int = System.identityHashCode(task)
        override fun equals(other: Any?): Boolean = other is TaskIdentity && task === other.task
    }

    private val completeCallbacks = ConcurrentHashMap<TaskIdentity, () -> Unit>()
    private val failCallbacks = ConcurrentHashMap<TaskIdentity, () -> Unit>()

    /** Replaces the completion callback associated with [task]. */
    fun registerComplete(task: Task, callback: () -> Unit) {
        completeCallbacks[TaskIdentity(task)] = callback
    }

    /** Replaces the failure callback associated with [task]. */
    fun registerFail(task: Task, callback: () -> Unit) {
        failCallbacks[TaskIdentity(task)] = callback
    }

    /** Invokes [task]'s completion callback when registered. */
    fun invokeComplete(task: Task) {
        val key = TaskIdentity(task)
        val callback = completeCallbacks.remove(key)
        failCallbacks.remove(key)
        callback?.invoke()
    }

    /** Invokes [task]'s failure callback when registered. */
    fun invokeFail(task: Task) {
        val key = TaskIdentity(task)
        val callback = failCallbacks.remove(key)
        completeCallbacks.remove(key)
        callback?.invoke()
    }

    /** Removes all callbacks and retained references for [task]. */
    fun reset(task: Task) {
        val key = TaskIdentity(task)
        completeCallbacks.remove(key)
        failCallbacks.remove(key)
    }
}

/**
 * Synchronous lifecycle unit executed by [TaskExecutor].
 *
 * Lifecycle order is [initialize], repeated [isCompleted]/[execute], then [end]. Methods return
 * Redux actions for the caller to dispatch; tasks do not mutate the store directly. Overrides of
 * [initialize], [execute], and [end] must call their default implementation to preserve status,
 * timeout, and callback behavior.
 *
 * Statuses, timeouts, and callbacks live in process-wide registries. Executors call
 * [releaseRuntimeState] after true terminal transitions; [reset] additionally removes observable
 * status before an instance is configured for reuse.
 */
interface Task {
    val name: String
    val priority: Int get() = 0

    /**
     * Marks the task running, records its timeout origin, and returns no actions by default.
     */
    fun initialize(state: RobotState): List<RobotAction> {
        TaskStateMachine.transitionTo(this, TaskStatus.RUNNING)
        TaskTimeoutManager.start(this)
        return emptyList()
    }

    /**
     * Returns whether the task's domain-specific completion condition is satisfied.
     * [elapsedMs] is supplied by the executor and uses its timestamp domain.
     */
    fun isCompleted(state: RobotState, elapsedMs: Long): Boolean

    /**
     * Runs once per active update. The default detects elapsed-time timeout, marks failure, and
     * invokes the failure callback; overrides must call it before producing their own actions.
     */
    fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        if (TaskTimeoutManager.isTimedOut(this, elapsedMs)) {
            if (TaskStateMachine.markFailed(this)) {
                TaskCallbacks.invokeFail(this)
            }
        }
        return emptyList()
    }

    /**
     * Pauses this task for higher-priority work without performing terminal cleanup.
     * Override to stop transient outputs that must not remain active while preempted.
     */
    fun pause(state: RobotState): List<RobotAction> = emptyList()

    /** Restores transient outputs after a preemption; elapsed time resumes where it paused. */
    fun resume(state: RobotState): List<RobotAction> = emptyList()

    /**
     * Finalizes the task. Interrupted endings become cancelled unless the task already failed;
     * normal endings become completed and invoke the completion callback.
     */
    fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        if (interrupted) {
            if (TaskStateMachine.getStatus(this) != TaskStatus.FAILED) {
                TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
            }
        } else {
            TaskStateMachine.transitionTo(this, TaskStatus.COMPLETED)
            TaskCallbacks.invokeComplete(this)
        }
        return emptyList()
    }

    /** Marks the task cancelled without invoking [end] or dispatching cleanup actions. */
    fun cancel() {
        TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
        releaseRuntimeState()
    }

    /** Releases timeout and callback state while retaining the terminal status for diagnostics. */
    fun releaseRuntimeState() {
        TaskTimeoutManager.reset(this)
        TaskCallbacks.reset(this)
    }

    /** Removes status, timeout, and callback state so this instance can be configured again. */
    fun reset() {
        TaskStateMachine.reset(this)
        releaseRuntimeState()
    }

    /** Registers/replaces a synchronous completion callback and returns this task for chaining. */
    fun onComplete(callback: () -> Unit): Task {
        TaskCallbacks.registerComplete(this, callback)
        return this
    }

    /** Registers/replaces a synchronous timeout/failure callback and returns this task for chaining. */
    fun onFail(callback: () -> Unit): Task {
        TaskCallbacks.registerFail(this, callback)
        return this
    }

    /** Associates a strict `elapsedMs > ms` timeout and returns this task for chaining. */
    fun withTimeout(ms: Long): Task {
        TaskTimeoutManager.setTimeout(this, ms)
        return this
    }
}

/**
 * Task to wait for a specific duration of time.
 */
class TimeWaitTask(
    private val durationMs: Long
) : Task {
    init {
        require(durationMs >= 0L) { "Wait duration must be non-negative" }
    }

    override val name = "TimeWait($durationMs ms)"

    /** Completes when executor elapsed time reaches [durationMs]. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return elapsedMs >= durationMs
    }
}

/**
 * Task to wait until a specified condition is true.
 */
class WaitUntilTask(
    private val predicate: (RobotState) -> Boolean
) : Task {
    override val name = "WaitUntil"

    /** Evaluates [predicate] against the most recent immutable robot-state snapshot. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return predicate(state)
    }
}

/**
 * Task to block execution until path progress reaches a certain distance.
 */
class PathProgressWaitTask(
    private val targetDistanceMeters: Double,
    private val timeoutMs: Long = 10000L
) : Task {
    init {
        require(targetDistanceMeters.isFinite() && targetDistanceMeters >= 0.0) {
            "Target path distance must be finite and non-negative"
        }
        require(timeoutMs >= 0L) { "Fallback timeout must be non-negative" }
    }

    override val name = "PathProgressWait($targetDistanceMeters m)"

    /** Completes at the target path distance or after the built-in fallback timeout. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return state.pathState.currentDistanceMeters >= targetDistanceMeters || elapsedMs >= timeoutMs
    }
}

/**
 * Task to instantly dispatch a single Redux action and finish.
 */
class ActionDispatchTask(
    private val action: RobotAction
) : Task {
    override val name = "ActionDispatch(${action::class.simpleName})"

    private var dispatched = false

    /** Marks the task running and returns [action] exactly once for this initialization. */
    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        dispatched = true
        return listOf(action)
    }

    /** Completes immediately after [initialize] has produced the action. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return dispatched
    }
}

/**
 * Creates one Redux action from the state observed when the task starts.
 *
 * This is useful for season actions that must preserve unrelated immutable subsystem fields. The
 * factory runs once during initialization, so auto-registration never captures a stale state.
 */
class StateActionTask(
    override val name: String,
    private val actionFactory: (RobotState) -> RobotAction
) : Task {
    private var dispatched = false

    init {
        require(name.isNotBlank()) { "State action task name must not be blank" }
    }

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        dispatched = true
        return listOf(actionFactory(state))
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = dispatched
}

/**
 * Task that commands the robot to follow a specific trajectory path.
 */
class FollowPathTask @kotlin.jvm.JvmOverloads constructor(
    private val follower: com.areslib.pathing.HolonomicPathFollower,
    private val path: com.areslib.pathing.Path,
    private val symmetry: com.areslib.math.coordinate.FieldSymmetry = com.areslib.math.coordinate.FieldSymmetry.MIRRORED,
    private val fieldLength: Double = com.areslib.math.coordinate.CoordinateTransformers.FTC_FIELD_SIZE,
    private val fieldWidth: Double = com.areslib.math.coordinate.CoordinateTransformers.FTC_FIELD_SIZE,
    private val mirrorForAlliance: Boolean = true,
    private val holdVelocity: Boolean = false
) : Task {
    private companion object {
        const val MAX_PATH_DURATION_MS = 15_000L
    }

    override val name = "FollowPath(${path.points.size} points)"
    private var lastTimeMs = 0L
    private lateinit var activePath: com.areslib.pathing.Path
    private var triggeredEvents = BooleanArray(0)
    
    private val scratchMutablePoint = com.areslib.pathing.MutablePathPoint()
    private val scratchPathPoint = com.areslib.pathing.PathPoint(Pose2d(), 0.0)
    private val actionsList = mutableListOf<RobotAction>()
    
    private val activeEventTasks = mutableListOf<Task>()
    private val taskStartTimes = mutableMapOf<Task, Long>()

    /** Selects alliance geometry, projects current pose onto the path, and dispatches [RobotAction.SwitchPath]. */
    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        lastTimeMs = com.areslib.util.RobotClock.currentTimeMillis()
        val alliance = if (mirrorForAlliance) state.drive.alliance else com.areslib.state.Alliance.BLUE
        activePath = com.areslib.math.coordinate.AllianceMirroring.mirror(path, alliance, symmetry, fieldLength, fieldWidth)
        triggeredEvents = BooleanArray(activePath.events.size)
        activeEventTasks.clear()
        taskStartTimes.clear()

        if (activePath.points.isEmpty()) {
            fail("path contains no trajectory points")
            return emptyList()
        }

        val currentPose = state.drive.poseEstimator.estimatedPose
        val startDistance = activePath.findClosestDistance(currentPose.x, currentPose.y)

        return listOf(
            RobotAction.SwitchPath(activePath, isDetour = false, startDistanceMeters = startDistance, timestampMs = lastTimeMs)
        )
    }

    /**
     * Completes on virtual progress when holding velocity, otherwise requires final pose tolerance.
     * A path that cannot reach tolerance within 15 seconds fails so autonomous sequencing cannot
     * silently advance after a blocked drivetrain.
     */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (activePath.points.isEmpty()) {
            return fail("path contains no trajectory points")
        }
        val targetDistance = activePath.points.last().distanceMeters
        
        val isVirtualComplete = state.pathState.currentDistanceMeters >= targetDistance
        if (holdVelocity && isVirtualComplete) {
            return true
        }
        if (!isVirtualComplete && elapsedMs < MAX_PATH_DURATION_MS) {
            return false
        }
        
        val currentPose = state.drive.poseEstimator.estimatedPose
        val endPose = activePath.points.last().pose
        val dx = currentPose.x - endPose.x
        val dy = currentPose.y - endPose.y
        val distToTarget = kotlin.math.sqrt(dx * dx + dy * dy)
        val headingError = kotlin.math.abs(com.areslib.math.wrapAngle(currentPose.heading.radians - endPose.heading.radians))
        
        if (distToTarget < 0.08 && headingError < Math.toRadians(5.0)) {
            return true
        }
        if (elapsedMs >= MAX_PATH_DURATION_MS) {
            return fail("failed to reach the final pose within ${MAX_PATH_DURATION_MS}ms")
        }
        return false
    }

    private fun fail(reason: String): Boolean {
        if (TaskStateMachine.markFailed(this)) {
            System.err.println("FollowPathTask: $reason")
            try {
                TaskCallbacks.invokeFail(this)
            } catch (e: Exception) {
                System.err.println("FollowPathTask: Exception during failure callback: ${e.message}")
            }
        }
        return false
    }

    /** Advances path progress and event tasks using `RobotClock`; non-advancing time yields no actions. */
    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val currentTimestamp = com.areslib.util.RobotClock.currentTimeMillis()
        if (lastTimeMs != 0L && currentTimestamp <= lastTimeMs) {
            actionsList.clear()
            return actionsList
        }
        val dt = if (lastTimeMs == 0L) 0.02 else (currentTimestamp - lastTimeMs) / 1000.0
        lastTimeMs = currentTimestamp

        val currentDistance = state.pathState.currentDistanceMeters
        activePath.sampleAtDistance(currentDistance, scratchMutablePoint)
        scratchMutablePoint.copyInto(scratchPathPoint)
        
        val distanceToEnd = activePath.points.last().distanceMeters - currentDistance
        scratchPathPoint.velocityMps = kotlin.math.max(scratchPathPoint.velocityMps, if (distanceToEnd > 0.1) 0.3 else 0.0)
        
        follower.update(scratchPathPoint, dt)

        val progressSpeed = kotlin.math.max(scratchPathPoint.velocityMps, 0.1)
        
        val currentPose = state.drive.poseEstimator.estimatedPose
        val closestDist = activePath.findClosestDistance(
            x = currentPose.x, 
            y = currentPose.y, 
            minDistance = kotlin.math.max(0.0, currentDistance - 0.5), 
            maxDistance = currentDistance + 1.5
        )
        
        val maxLead = 0.4
        var nextDistance = currentDistance + progressSpeed * dt
        if (nextDistance > closestDist + maxLead) {
            nextDistance = closestDist + maxLead
        }
        val targetPose = scratchPathPoint.pose
        val xError = targetPose.x - currentPose.x
        val yError = targetPose.y - currentPose.y
        val pathTangent = scratchPathPoint.tangentRadians
        val crossTrack = xError * kotlin.math.sin(pathTangent) - yError * kotlin.math.cos(pathTangent)
        val alongTrack = xError * kotlin.math.cos(pathTangent) + yError * kotlin.math.sin(pathTangent)
        var headingError = targetPose.heading.radians - currentPose.heading.radians
        headingError = kotlin.math.atan2(kotlin.math.sin(headingError), kotlin.math.cos(headingError))
        
        actionsList.clear()
        actionsList.add(RobotAction.UpdatePathProgress(
            distanceProgressMeters = nextDistance,
            crossTrackErrorMeters = crossTrack,
            alongTrackErrorMeters = alongTrack,
            headingErrorRadians = headingError,
            timestampMs = currentTimestamp
        ))

        for (i in 0 until activePath.events.size) {
            val event = activePath.events[i]
            if (!triggeredEvents[i] && event.triggerDistanceMeters <= nextDistance) {
                triggeredEvents[i] = true
                actionsList.add(RobotAction.PathEventTriggered(event.eventName, currentTimestamp))
                
                val cmdTask = com.areslib.pathing.NamedCommands.getCommand(event.eventName, currentTimestamp)
                if (cmdTask != null) {
                    actionsList.addAll(cmdTask.initialize(state))
                    activeEventTasks.add(cmdTask)
                    taskStartTimes[cmdTask] = currentTimestamp
                }
            }
        }
        
        for (i in activeEventTasks.indices.reversed()) {
            val cmdTask = activeEventTasks[i]
            val startTime = taskStartTimes[cmdTask] ?: currentTimestamp
            val cmdElapsed = currentTimestamp - startTime
            val failed = TaskStateMachine.getStatus(cmdTask) == TaskStatus.FAILED
            if (failed || cmdTask.isCompleted(state, cmdElapsed)) {
                actionsList.addAll(cmdTask.end(state, interrupted = failed))
                cmdTask.releaseRuntimeState()
                activeEventTasks.removeAt(i)
                taskStartTimes.remove(cmdTask)
            } else {
                actionsList.addAll(cmdTask.execute(state, cmdElapsed))
            }
        }

        return actionsList
    }

    /** Stops controller output while a higher-priority task owns the drivetrain. */
    override fun pause(state: RobotState): List<RobotAction> {
        follower.stop()
        return emptyList()
    }

    /** Stops the follower unless velocity hold was requested and interrupts all active event tasks. */
    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        super.end(state, interrupted)
        if (!holdVelocity) {
            follower.stop()
        }
        val actions = mutableListOf<RobotAction>()
        for (cmdTask in activeEventTasks) {
            actions.addAll(cmdTask.end(state, interrupted = true))
            cmdTask.releaseRuntimeState()
        }
        activeEventTasks.clear()
        taskStartTimes.clear()
        return actions
    }
}
