package com.areslib.pathing

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.areslib.sequencer.Task
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.ParallelTaskGroup
import com.areslib.sequencer.ParallelRaceGroup
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.FollowPathTask
import com.areslib.sequencer.TimeWaitTask
import com.areslib.state.Alliance
import java.io.File


/**
 * PathPlanner `.auto` JSON File Sequencer Parser.
 *
 * Deserializes PathPlanner autonomous routine structures into executable [Task] trees
 * (supporting sequential task sequences, parallel task triggers, named event commands, and path followers).
 *
 * @see AutoBuilder
 * @see DynamicPathLoader
 */
object PathPlannerAutoParser {
    private val gson = Gson()

    /**
     * Extracts the "startingPose" block (used in newer PathPlanner autos)
     */
    fun getStartingPose(jsonString: String): com.areslib.math.geometry.Pose2d? {
        val root = gson.fromJson(jsonString, JsonObject::class.java)
        val startingPose = root.getAsJsonObject("startingPose") ?: return null
        val position = startingPose.getAsJsonObject("position") ?: return null
        
        val x = position.get("x")?.asDouble ?: return null
        val y = position.get("y")?.asDouble ?: return null
        val rotation = startingPose.get("rotation")?.asDouble ?: 0.0
        
        return com.areslib.math.geometry.Pose2d(x, y, com.areslib.math.geometry.Rotation2d.fromDegrees(rotation))
    }

    /**
     * Fallback for older autos: recursively finds the first "path" command
     */
    fun getFirstPathName(jsonString: String): String? {
        val root = gson.fromJson(jsonString, JsonObject::class.java)
        val commandObj = root.getAsJsonObject("command") ?: return null
        return findFirstPathRecursively(commandObj)
    }

    private fun findFirstPathRecursively(node: JsonObject): String? {
        val type = node.get("type")?.asString ?: return null
        val data = node.getAsJsonObject("data") ?: return null

        if (type.lowercase() == "path") {
            return data.get("pathName")?.asString
        }

        if (type.lowercase() in listOf("sequential", "parallel", "race", "deadline")) {
            val cmdsArray = data.getAsJsonArray("commands") ?: JsonArray()
            for (i in 0 until cmdsArray.size()) {
                val found = findFirstPathRecursively(cmdsArray.get(i).asJsonObject)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Parses a PathPlanner .auto JSON string and compiles it into a Task.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parseAuto(
        jsonString: String,
        follower: HolonomicPathFollower,
        timestampMs: Long,
        alliance: com.areslib.state.Alliance = com.areslib.state.Alliance.BLUE
    ): Task {
        val root = gson.fromJson(jsonString, JsonObject::class.java)
        val commandObj = root.getAsJsonObject("command") ?: error("No root 'command' object in .auto file")
        // NOTE: Alliance mirroring is handled downstream by FollowPathTask via
        // AllianceMirroring.mirror(). Do NOT mirror paths here to avoid double-mirroring.
        return parseCommandNode(commandObj, follower, timestampMs)
    }

    private fun parseCommandNode(
        node: JsonObject,
        follower: HolonomicPathFollower,
        timestampMs: Long
    ): Task {
        val type = node.get("type")?.asString ?: error("Command node missing 'type'")
        val data = node.getAsJsonObject("data") ?: error("Command node '$type' missing 'data'")

        return when (type.lowercase()) {
            "sequential" -> {
                val cmdsArray = data.getAsJsonArray("commands") ?: JsonArray()
                val tasks = mutableListOf<Task>()
                for (i in 0 until cmdsArray.size()) {
                    tasks.add(parseCommandNode(cmdsArray.get(i).asJsonObject, follower, timestampMs))
                }
                SequentialTaskGroup(tasks)
            }
            "parallel" -> {
                val cmdsArray = data.getAsJsonArray("commands") ?: JsonArray()
                val tasks = mutableListOf<Task>()
                for (i in 0 until cmdsArray.size()) {
                    tasks.add(parseCommandNode(cmdsArray.get(i).asJsonObject, follower, timestampMs))
                }
                ParallelTaskGroup(tasks)
            }
            "race" -> {
                val cmdsArray = data.getAsJsonArray("commands") ?: JsonArray()
                val tasks = mutableListOf<Task>()
                for (i in 0 until cmdsArray.size()) {
                    tasks.add(parseCommandNode(cmdsArray.get(i).asJsonObject, follower, timestampMs))
                }
                ParallelRaceGroup(tasks)
            }
            "deadline" -> {
                val cmdsArray = data.getAsJsonArray("commands") ?: JsonArray()
                if (cmdsArray.size() == 0) {
                    SequentialTaskGroup(emptyList())
                } else {
                    val deadlineTask = parseCommandNode(cmdsArray.get(0).asJsonObject, follower, timestampMs)
                    val otherTasks = mutableListOf<Task>()
                    for (i in 1 until cmdsArray.size()) {
                        otherTasks.add(parseCommandNode(cmdsArray.get(i).asJsonObject, follower, timestampMs))
                    }
                    ParallelDeadlineGroup(deadlineTask, otherTasks)
                }
            }
            "path" -> {
                val pathName = data.get("pathName")?.asString ?: error("Path command missing 'pathName'")
                val path = DynamicPathLoader.loadPath(pathName)
                FollowPathTask(follower, path)
            }
            "wait" -> {
                val waitTimeSec = data.get("waitTime")?.asDouble ?: error("Wait command missing 'waitTime'")
                require(waitTimeSec.isFinite() && waitTimeSec >= 0.0 && waitTimeSec <= Long.MAX_VALUE / 1_000.0) {
                    "Wait command duration must be finite, non-negative, and representable"
                }
                val waitTimeMs = (waitTimeSec * 1000.0).toLong()
                TimeWaitTask(waitTimeMs)
            }
            "named" -> {
                val name = data.get("name")?.asString ?: error("Named command missing 'name'")
                NamedCommands.getCommand(name, timestampMs)
                    ?: error("Named command '$name' is not registered")
            }
            else -> error("Unknown command type: '$type'")
        }
    }
}
