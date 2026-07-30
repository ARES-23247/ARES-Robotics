package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.math.geometry.Pose2d
import java.util.concurrent.ConcurrentHashMap

/**
 * Object implementation for Task Callbacks.
 *
 * Asynchronous superstructure task sequence execution unit.
 */
object TaskCallbacks {
    private val completeCallbacks = ConcurrentHashMap<Task, () -> Unit>()
    private val failCallbacks = ConcurrentHashMap<Task, () -> Unit>()

    /**
     * registerComplete declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun registerComplete(task: Task, callback: () -> Unit) {
        completeCallbacks[task] = callback
    }

    /**
     * registerFail declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun registerFail(task: Task, callback: () -> Unit) {
        failCallbacks[task] = callback
    }

    /**
     * invokeComplete declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun invokeComplete(task: Task) {
        completeCallbacks[task]?.invoke()
    }

    /**
     * invokeFail declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun invokeFail(task: Task) {
        failCallbacks[task]?.invoke()
    }

    /**
     * reset declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun reset(task: Task) {
        completeCallbacks.remove(task)
        failCallbacks.remove(task)
    }
}

/**
 * Interface representing a discrete superstructure or autonomous execution task.
 * Acts as a Facade delegating to internal single-responsibility controllers.
 */
interface Task {
    val name: String
    val priority: Int get() = 0

    /**
     * Called once when the task becomes active.
     */
    fun initialize(state: RobotState): List<RobotAction> {
        TaskStateMachine.transitionTo(this, TaskStatus.RUNNING)
        TaskTimeoutManager.start(this)
        return emptyList()
    }

    /**
     * Checked periodically. Returns true when the wait conditions/guards are satisfied.
     */
    fun isCompleted(state: RobotState, elapsedMs: Long): Boolean

    /**
     * Called periodically while the task is running.
     */
    fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        if (TaskTimeoutManager.isTimedOut(this, elapsedMs)) {
            TaskStateMachine.transitionTo(this, TaskStatus.FAILED)
            TaskCallbacks.invokeFail(this)
        }
        return emptyList()
    }

    /**
     * Called once when isCompleted returns true.
     */
    fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        if (interrupted) {
            TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
        } else {
            TaskStateMachine.transitionTo(this, TaskStatus.COMPLETED)
            TaskCallbacks.invokeComplete(this)
        }
        return emptyList()
    }

    /**
     * cancel declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun cancel() {
        TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
    }

    /**
     * reset declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun reset() {
        TaskStateMachine.reset(this)
        TaskTimeoutManager.reset(this)
        TaskCallbacks.reset(this)
    }

    /**
     * onComplete declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun onComplete(callback: () -> Unit): Task {
        TaskCallbacks.registerComplete(this, callback)
        return this
    }

    /**
     * onFail declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun onFail(callback: () -> Unit): Task {
        TaskCallbacks.registerFail(this, callback)
        return this
    }

    /**
     * withTimeout declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
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
    override val name = "TimeWait($durationMs ms)"

    /**
     * isCompleted declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return elapsedMs >= durationMs
    }
}

/**
 * Task to block execution until path progress reaches a certain distance.
 */
class PathProgressWaitTask(
    private val targetDistanceMeters: Double,
    private val timeoutMs: Long = 10000L
) : Task {
    override val name = "PathProgressWait($targetDistanceMeters m)"

    /**
     * isCompleted declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
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

    /**
     * initialize declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        dispatched = true
        return listOf(action)
    }

    /**
     * isCompleted declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return dispatched
    }
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
    private val mirrorForAlliance: Boolean = true
) : Task {
    override val name = "FollowPath(${path.points.size} points)"
    private var lastTimeMs = 0L
    private lateinit var activePath: com.areslib.pathing.Path
    private val triggeredEvents = mutableSetOf<String>()
    
    private val scratchMutablePoint = com.areslib.pathing.MutablePathPoint()
    private val scratchPathPoint = com.areslib.pathing.PathPoint(Pose2d(), 0.0)
    private val actionsList = mutableListOf<RobotAction>()
    
    private val activeEventTasks = mutableListOf<Task>()
    private val taskStartTimes = mutableMapOf<Task, Long>()

    /**
     * initialize declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        lastTimeMs = com.areslib.util.RobotClock.currentTimeMillis()
        val alliance = if (mirrorForAlliance) state.drive.alliance else com.areslib.state.Alliance.BLUE
        activePath = com.areslib.math.coordinate.AllianceMirroring.mirror(path, alliance, symmetry, fieldLength, fieldWidth)
        triggeredEvents.clear()
        activeEventTasks.clear()
        taskStartTimes.clear()

        val currentPose = state.drive.poseEstimator.estimatedPose
        val startDistance = activePath.findClosestDistance(currentPose.x, currentPose.y)

        return listOf(
            RobotAction.SwitchPath(activePath, isDetour = false, startDistanceMeters = startDistance, timestampMs = lastTimeMs)
        )
    }

    /**
     * isCompleted declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (activePath.points.isEmpty()) return true
        val targetDistance = activePath.points.last().distanceMeters
        
        val isVirtualComplete = state.pathState.currentDistanceMeters >= targetDistance
        if (!isVirtualComplete && elapsedMs < 15000L) {
            return false
        }
        
        val currentPose = state.drive.poseEstimator.estimatedPose
        val endPose = activePath.points.last().pose
        val dx = currentPose.x - endPose.x
        val dy = currentPose.y - endPose.y
        val distToTarget = kotlin.math.sqrt(dx * dx + dy * dy)
        val headingError = kotlin.math.abs(com.areslib.math.wrapAngle(currentPose.heading.radians - endPose.heading.radians))
        
        return (distToTarget < 0.08 && headingError < Math.toRadians(5.0)) || elapsedMs >= 15000L
    }

    /**
     * execute declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
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
            if (event.eventName !in triggeredEvents && event.triggerDistanceMeters <= nextDistance) {
                triggeredEvents.add(event.eventName)
                actionsList.add(RobotAction.PathEventTriggered(event.eventName, currentTimestamp))
                
                val cmdTask = com.areslib.pathing.NamedCommands.getCommand(event.eventName, currentTimestamp)
                if (cmdTask != null) {
                    actionsList.addAll(cmdTask.initialize(state))
                    activeEventTasks.add(cmdTask)
                    taskStartTimes[cmdTask] = currentTimestamp
                }
            }
        }
        
        val it = activeEventTasks.iterator()
        while (it.hasNext()) {
            val cmdTask = it.next()
            val startTime = taskStartTimes[cmdTask] ?: currentTimestamp
            val cmdElapsed = currentTimestamp - startTime
            if (cmdTask.isCompleted(state, cmdElapsed)) {
                actionsList.addAll(cmdTask.end(state, false))
                it.remove()
                taskStartTimes.remove(cmdTask)
            } else {
                actionsList.addAll(cmdTask.execute(state, cmdElapsed))
            }
        }

        return actionsList
    }

    /**
     * end declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        super.end(state, interrupted)
        follower.stop()
        val actions = mutableListOf<RobotAction>()
        for (cmdTask in activeEventTasks) {
            actions.addAll(cmdTask.end(state, interrupted = true))
        }
        activeEventTasks.clear()
        taskStartTimes.clear()
        return actions
    }
}
