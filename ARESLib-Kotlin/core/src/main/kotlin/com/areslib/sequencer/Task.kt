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
    /** Primitive ownership mask checked when parallel task trees are constructed. */
    val requiredResources: Long get() = TaskResources.NONE

    /**
     * Marks the task running and records its timeout origin atomically with respect to watchdog
     * scans. Returns no actions by default. Overrides must call this before publishing work.
     */
    fun initialize(state: RobotState): List<RobotAction> {
        TaskTimeoutManager.initializeTask(this)
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
     * normal endings complete only if no failure, cancellation or expired deadline has won.
     * The deadline is removed with the status transition before any callback runs. Callbacks run
     * outside the watchdog monitor; owners still perform virtual cleanup and inspect final status.
     */
    fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val status = TaskTimeoutManager.finishTask(this, interrupted)
        if (!interrupted) {
            when (status) {
                TaskStatus.COMPLETED -> TaskCallbacks.invokeComplete(this)
                TaskStatus.FAILED -> TaskCallbacks.invokeFail(this)
                else -> Unit
            }
        }
        return emptyList()
    }

    /**
     * Atomically removes the deadline and marks cancellation, then releases runtime metadata.
     * Does not invoke [end] or dispatch hardware cleanup actions. An overriding metadata cleanup
     * failure cannot leave this task's old watchdog deadline armed.
     */
    fun cancel() {
        TaskTimeoutManager.cancelTask(this)
        releaseRuntimeState()
    }

    /** Releases timeout and callback state while retaining the terminal status for diagnostics. */
    fun releaseRuntimeState() {
        TaskTimeoutManager.reset(this)
        TaskCallbacks.reset(this)
    }

    /**
     * Atomically removes this task's status and deadline, then invokes virtual metadata cleanup
     * (including callback removal). A cleanup exception propagates without rearming the old deadline.
     */
    fun reset() {
        TaskTimeoutManager.resetTask(this)
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
class ActionDispatchTask @kotlin.jvm.JvmOverloads constructor(
    private val action: RobotAction,
    override val requiredResources: Long = TaskResources.NONE
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
    override val requiredResources: Long = TaskResources.NONE,
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
 *
 * Path samples remain caller-owned and must stay stable during execution; initialization validates
 * all raw sample fields before alliance transformation. Markers are independently snapshotted and
 * sorted, preserving equal-distance order. Factories must return fresh tasks. Initialization stops
 * and resets the follower; successful hold-velocity completion may retain output, but invalid input,
 * timeout, clock faults, marker failure and interrupted exit stop it. Calls belong to one loop.
 * Authored feedforward velocity is retained, including zero and reverse. Virtual progress uses its
 * magnitude with a 0.1 m/s floor and a 0.4 m lead bound; it is not measured robot distance.
 * Motion integration restarts at resume; child elapsed time includes active time before pausing.
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
    override val requiredResources: Long = TaskResources.DRIVE
    private var lastTimeMs = 0L
    private lateinit var activePath: com.areslib.pathing.Path
    private var scheduledEvents = emptyArray<com.areslib.pathing.PathEvent>()
    private var nextEvent = 0
    
    private val scratchMutablePoint = com.areslib.pathing.MutablePathPoint()
    private val scratchPathPoint = com.areslib.pathing.PathPoint(Pose2d(), 0.0)
    private val actionsList = mutableListOf<RobotAction>()
    
    private var timeoutsSuspended = false
    internal fun suspendTimeouts(paused: Boolean) {
        val now = com.areslib.util.RobotClock.currentTimeMillis()
        if (!timeoutsSuspended && paused && now >= lastTimeMs) {
            val delta = now - lastTimeMs
            if (delta >= 0L) advanceEventTime(delta)
        }
        if (timeoutsSuspended && !paused) {
            // Resume from the current clock: never integrate a paused interval or shift origins
            // with potentially overflowing Long arithmetic.
            lastTimeMs = now
        }
        timeoutsSuspended = paused
        for (entry in activeEventTasks) entry.task.setTimeoutSuspended(paused)
    }
    private class ActiveEvent(val task: Task, var elapsedMs: Long = 0L)
    private val activeEventTasks = mutableListOf<ActiveEvent>()

    /** Selects alliance geometry, projects current pose onto the path, and dispatches [RobotAction.SwitchPath]. */
    override fun initialize(state: RobotState): List<RobotAction> {
        try {
            return initializePath(state)
        } catch (failure: Throwable) {
            stopAndRethrow(failure)
        }
    }

    private fun initializePath(state: RobotState): List<RobotAction> {
        follower.stop()
        check(activeEventTasks.isEmpty()) { "End active marker tasks before reinitializing the path" }
        super.initialize(state)
        validatePath()
        lastTimeMs = com.areslib.util.RobotClock.currentTimeMillis()
        val alliance = if (mirrorForAlliance) state.drive.alliance else com.areslib.state.Alliance.BLUE
        activePath = com.areslib.math.coordinate.AllianceMirroring.mirror(path, alliance, symmetry, fieldLength, fieldWidth)
        timeoutsSuspended = false
        scheduledEvents = activePath.events.toTypedArray()
        scheduledEvents.sortWith { a, b ->
            when {
                a.triggerDistanceMeters < b.triggerDistanceMeters -> -1
                a.triggerDistanceMeters > b.triggerDistanceMeters -> 1
                else -> 0
            }
        }
        nextEvent = 0
        activeEventTasks.clear()

        if (activePath.points.isEmpty()) {
            fail("path contains no trajectory points")
            return emptyList()
        }

        val currentPose = state.drive.poseEstimator
        require(currentPose.estimatedPoseX.isFinite() && currentPose.estimatedPoseY.isFinite() &&
            currentPose.estimatedPoseHeading.isFinite()) { "Path execution requires finite estimated pose" }
        val startDistance = activePath.findClosestDistance(currentPose.estimatedPoseX, currentPose.estimatedPoseY)

        return listOf(
            RobotAction.SwitchPath(activePath, isDetour = false, startDistanceMeters = startDistance, timestampMs = lastTimeMs)
        )
    }

    private fun validatePath() {
        var priorDistance = 0.0
        for (point in path.points) {
            require(point.pose.x.isFinite() && point.pose.y.isFinite() &&
                point.pose.heading.rawRadians.isFinite() && point.velocityMps.isFinite() &&
                point.curvature.isFinite() && point.tangentRadians.isFinite() &&
                point.distanceMeters.isFinite() && point.distanceMeters >= priorDistance) {
                "Path points must be finite with nonnegative nondecreasing distances"
            }
            priorDistance = point.distanceMeters
        }
        for (event in path.events) require(event.triggerDistanceMeters.isFinite() && event.triggerDistanceMeters >= 0.0) {
            "Path event distance must be finite and nonnegative"
        }
    }

    /**
     * Completes on virtual progress when holding velocity, otherwise requires final pose tolerance.
     * A path that cannot reach tolerance within 15 seconds fails so autonomous sequencing cannot
     * silently advance after a blocked drivetrain.
     */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return false
        val estimate = state.drive.poseEstimator
        if (!state.pathState.currentDistanceMeters.isFinite() || state.pathState.currentDistanceMeters < 0.0 ||
            elapsedMs < 0L || !estimate.estimatedPoseX.isFinite() || !estimate.estimatedPoseY.isFinite() ||
            !estimate.estimatedPoseHeading.isFinite()) return fail("invalid progress, pose or elapsed time")
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
        
        val currentPose = state.drive.poseEstimator
        require(currentPose.estimatedPoseX.isFinite() && currentPose.estimatedPoseY.isFinite() &&
            currentPose.estimatedPoseHeading.isFinite()) { "Path execution requires finite estimated pose" }
        val endPose = activePath.points.last().pose
        val dx = currentPose.estimatedPoseX - endPose.x
        val dy = currentPose.estimatedPoseY - endPose.y
        val distToTarget = kotlin.math.hypot(dx, dy)
        val headingError = kotlin.math.abs(com.areslib.math.wrapAngle(com.areslib.math.wrapAngle(currentPose.estimatedPoseHeading) - endPose.heading.radians))
        
        if (distToTarget < 0.08 && headingError < Math.toRadians(5.0)) {
            return true
        }
        if (elapsedMs >= MAX_PATH_DURATION_MS) {
            return fail("failed to reach the final pose within ${MAX_PATH_DURATION_MS}ms")
        }
        return false
    }

    private fun fail(reason: String): Boolean {
        follower.stop()
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

    /**
     * Advances positive path distance using speed magnitude and RobotClock. Marker setup is O(N log N);
     * each loop visits only newly crossed markers and active tasks. Sampling/projection costs are
     * separate. Immutable progress actions and moving geometry can still allocate. The returned
     * action buffer is reused on the next call; dispatch or copy it before another execution.
     * Repeated timestamps neutralize without progress; clock rollback/overflow fails the task.
     */
    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        try {
            return executeFrame(state, elapsedMs)
        } catch (failure: Throwable) {
            stopAndRethrow(failure)
        }
    }

    private fun executeFrame(state: RobotState, elapsedMs: Long): List<RobotAction> {
        actionsList.clear()
        super.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING || timeoutsSuspended) {
            follower.stop()
            return actionsList
        }
        val currentTimestamp = com.areslib.util.RobotClock.currentTimeMillis()
        if (currentTimestamp == lastTimeMs) {
            follower.stop()
            return actionsList
        }
        val deltaMs = currentTimestamp - lastTimeMs
        if (currentTimestamp < lastTimeMs || deltaMs <= 0L || elapsedMs < 0L) {
            fail("clock moved backwards or elapsed time overflowed")
            return actionsList
        }
        val dt = deltaMs / 1000.0
        lastTimeMs = currentTimestamp
        advanceEventTime(deltaMs)

        val currentDistance = state.pathState.currentDistanceMeters
        require(currentDistance.isFinite() && currentDistance >= 0.0) { "Path progress must be finite and nonnegative" }
        activePath.sampleAtDistance(currentDistance, scratchMutablePoint)
        scratchMutablePoint.copyInto(scratchPathPoint)
        
        val progressSpeed = kotlin.math.max(kotlin.math.abs(scratchPathPoint.velocityMps), 0.1)
        
        val currentPose = state.drive.poseEstimator
        require(currentPose.estimatedPoseX.isFinite() && currentPose.estimatedPoseY.isFinite() &&
            currentPose.estimatedPoseHeading.isFinite()) { "Path execution requires finite estimated pose" }
        val closestDist = activePath.findClosestDistance(
            x = currentPose.estimatedPoseX,
            y = currentPose.estimatedPoseY,
            minDistance = kotlin.math.max(0.0, currentDistance - 0.5), 
            maxDistance = currentDistance + 1.5
        )
        
        val maxLead = 0.4
        val endDistance = activePath.points.last().distanceMeters
        var nextDistance = minOf(endDistance, currentDistance + progressSpeed * dt)
        if (nextDistance > closestDist + maxLead) {
            nextDistance = closestDist + maxLead
        }
        val targetPose = scratchPathPoint.pose
        val xError = targetPose.x - currentPose.estimatedPoseX
        val yError = targetPose.y - currentPose.estimatedPoseY
        val pathTangent = com.areslib.math.wrapAngle(scratchPathPoint.tangentRadians)
        val sinTangent = kotlin.math.sin(pathTangent)
        val cosTangent = kotlin.math.cos(pathTangent)
        val crossTrack = xError * sinTangent - yError * cosTangent
        val alongTrack = xError * cosTangent + yError * sinTangent
        require(crossTrack.isFinite() && alongTrack.isFinite()) { "Path tracking error is unrepresentable" }
        var headingError = targetPose.heading.radians - com.areslib.math.wrapAngle(currentPose.estimatedPoseHeading)
        headingError = kotlin.math.atan2(kotlin.math.sin(headingError), kotlin.math.cos(headingError))
        
        follower.update(scratchPathPoint, dt)

        actionsList.add(RobotAction.UpdatePathProgress(
            distanceProgressMeters = nextDistance,
            crossTrackErrorMeters = crossTrack,
            alongTrackErrorMeters = alongTrack,
            headingErrorRadians = headingError,
            timestampMs = currentTimestamp
        ))

        while (nextEvent < scheduledEvents.size && scheduledEvents[nextEvent].triggerDistanceMeters <= nextDistance) {
            val event = scheduledEvents[nextEvent++]
            actionsList.add(RobotAction.PathEventTriggered(event.eventName, currentTimestamp))
            val cmdTask = com.areslib.pathing.NamedCommands.getCommand(event.eventName, currentTimestamp)
            if (cmdTask != null) {
                require(activeEventTasks.none { it.task === cmdTask } &&
                    TaskStateMachine.getStatus(cmdTask) == TaskStatus.PENDING) {
                    "Path marker factory must return a fresh task instance"
                }
                // Register ownership before initialization, so executor cleanup can reach a
                // partially initialized child when its initializer throws.
                activeEventTasks.add(ActiveEvent(cmdTask))
                actionsList.addAll(cmdTask.initialize(state))
                if (consumeFailedEvent(activeEventTasks.lastIndex, cmdTask, state)) return actionsList
            }
        }

        for (i in activeEventTasks.indices.reversed()) {
            val entry = activeEventTasks[i]
            val cmdTask = entry.task
            val cmdElapsed = entry.elapsedMs
            if (consumeFailedEvent(i, cmdTask, state)) break
            val completed = cmdTask.completionReady(state, cmdElapsed)
            if (consumeFailedEvent(i, cmdTask, state)) break
            if (completed) {
                // Keep ownership until normal end succeeds. Rejected/throwing endings still
                // need interrupted cleanup, including a marker's own mechanism outputs.
                actionsList.addAll(cmdTask.end(state, interrupted = false))
                if (consumeFailedEvent(i, cmdTask, state)) break
                cmdTask.releaseRuntimeState()
                activeEventTasks.removeAt(i)
            } else {
                actionsList.addAll(cmdTask.execute(state, cmdElapsed))
                if (consumeFailedEvent(i, cmdTask, state)) break
            }
        }

        return actionsList
    }

    private fun advanceEventTime(deltaMs: Long) {
        var index = 0
        while (index < activeEventTasks.size) {
            val entry = activeEventTasks[index++]
            entry.elapsedMs = if (entry.elapsedMs > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE
                else entry.elapsedMs + deltaMs
        }
    }

    private fun stopAndRethrow(failure: Throwable): Nothing {
        TaskStateMachine.markFailed(this)
        try { follower.stop() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }

    /** Ends a failed/cancelled marker command exactly once and makes the path fail closed. */
    private fun consumeFailedEvent(index: Int, eventTask: Task, state: RobotState): Boolean {
        val status = TaskStateMachine.getStatus(eventTask)
        if (status != TaskStatus.FAILED && status != TaskStatus.CANCELLED) return false

        if (status == TaskStatus.FAILED) {
            try {
                TaskCallbacks.invokeFail(eventTask)
            } catch (failure: Throwable) {
                System.err.println(
                    "FollowPathTask: Exception in failure callback for event ${eventTask.name}: ${failure.message}"
                )
            }
        }
        try {
            actionsList.addAll(eventTask.end(state, interrupted = true))
        } catch (failure: Throwable) {
            System.err.println("FollowPathTask: failed to clean event ${eventTask.name}: ${failure.message}")
        } finally {
            eventTask.releaseRuntimeState()
            activeEventTasks.removeAt(index)
        }
        fail("path event '${eventTask.name}' ${status.name.lowercase()}")
        return true
    }

    /** Stops controller output while a higher-priority task owns the drivetrain. */
    override fun pause(state: RobotState): List<RobotAction> {
        follower.stop()
        return emptyList()
    }

    /** Stops on every interrupted exit; successful velocity-hold exits may retain the final command. */
    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        var firstFailure: Throwable? = null
        if (interrupted || !holdVelocity) {
            try {
                follower.stop()
            } catch (failure: Throwable) {
                firstFailure = failure
            }
        }
        val actions = mutableListOf<RobotAction>()
        for (entry in activeEventTasks) {
            val cmdTask = entry.task
            try {
                actions.addAll(cmdTask.end(state, interrupted = true))
            } catch (failure: Throwable) {
                System.err.println("FollowPathTask: failed to stop event ${cmdTask.name}: ${failure.message}")
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            } finally {
                cmdTask.releaseRuntimeState()
            }
        }
        activeEventTasks.clear()
        try {
            actions.addAll(super.end(state, interrupted))
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
        firstFailure?.let { throw it }
        return actions
    }
}
