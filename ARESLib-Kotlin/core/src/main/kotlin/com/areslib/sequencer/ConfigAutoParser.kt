package com.areslib.sequencer

import com.google.gson.Gson
import com.areslib.action.RobotAction
import com.areslib.pathing.DynamicPathLoader
import com.areslib.pathing.HolonomicPathFollower

/**
 * Data structures representing the JSON schema for configuration-driven autonomous routines.
 */
data class AutoStepJson(
    val type: String,
    val pathName: String? = null,
    val meters: Double? = null,
    val durationMs: Long? = null,
    val rpm: Double? = null,
    val count: Int? = null,
    val eventName: String? = null
)

/**
 * Class implementation for Auto Script Json.
 *
 * Asynchronous superstructure task sequence execution unit.
 */
data class AutoScriptJson(
    val name: String,
    val steps: List<AutoStepJson>
)

/**
 * A parser that compiles a JSON-formatted script string into an executable [Task] routine.
 * Allows teams to define autonomous sequences dynamically without recompiling code.
 */
object ConfigAutoParser {
    private val gson = Gson()

    /**
     * Parses a JSON script string and constructs a sequential task sequence.
     *
     * @param jsonString The raw JSON string of the autonomous script.
     * @param follower The robot's holonomic path follower facade.
     * @param baseTimestampMs Reference timestamp for action creator execution.
     * @return A compiled [Task] (typically a [SequentialTaskGroup]) representing the routine.
     */
    fun parse(jsonString: String, follower: HolonomicPathFollower, baseTimestampMs: Long): Task {
        val script = gson.fromJson(jsonString, AutoScriptJson::class.java)
        val tasks = mutableListOf<Task>()
        
        for (step in script.steps) {
            val task = when (step.type.lowercase()) {
                "followpath" -> {
                    val name = step.pathName ?: error("followpath step requires 'pathName'")
                    val path = DynamicPathLoader.loadPath(name)
                    FollowPathTask(follower, path)
                }
                "waitdistance" -> {
                    val meters = step.meters ?: error("waitdistance step requires 'meters'")
                    PathProgressWaitTask(meters)
                }
                "waittime" -> {
                    val durationMs = step.durationMs ?: error("waittime step requires 'durationMs'")
                    TimeWaitTask(durationMs)
                }

                "dispatchpathevent" -> {
                    val eventName = step.eventName ?: error("dispatchpathevent step requires 'eventName'")
                    ActionDispatchTask(RobotAction.PathEventTriggered(eventName, baseTimestampMs))
                }
                else -> error("Unknown step type: '${step.type}'")
            }
            tasks.add(task)
        }
        
        return SequentialTaskGroup(tasks)
    }
}
