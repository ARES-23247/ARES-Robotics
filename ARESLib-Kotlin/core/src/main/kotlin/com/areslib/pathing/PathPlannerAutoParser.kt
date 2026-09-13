package com.areslib.pathing

import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sequencer.*
import com.areslib.state.Alliance
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Compiles PathPlanner auto command trees at setup time. Coordinates are meters and JSON
 * rotations are CCW-positive degrees. The explicit alliance is applied once at construction;
 * changing robot state later does not change this compiled auto's geometry.
 */
object PathPlannerAutoParser {
    private val gson = Gson()
    private const val MAX_JSON_CHARACTERS = 4_194_304
    private const val MAX_COMMAND_DEPTH = 64
    private const val MAX_COMMAND_NODES = 4096
    private val groupTypes = setOf("sequential", "parallel", "race", "deadline")

    /** Reads an optional legacy explicit starting pose. A present pose must be complete and finite. */
    fun getStartingPose(jsonString: String): Pose2d? {
        val root = root(jsonString)
        val poseValue = root.get("startingPose") ?: return null
        if (poseValue.isJsonNull) return null
        val pose = objectValue(poseValue, "startingPose")
        val position = objectValue(pose.get("position"), "startingPose.position")
        val x = number(position, "x")
        val y = number(position, "y")
        val rotation = if (pose.has("rotation")) number(pose, "rotation") else 0.0
        return Pose2d(x, y, Rotation2d.fromDegrees(rotation))
    }

    /** Finds the first authored path in depth-first command order without loading path files. */
    fun getFirstPathName(jsonString: String): String? {
        val command = root(jsonString).get("command") ?: return null
        val budget = CommandBudget()
        fun visit(value: JsonElement, depth: Int): String? {
            budget.visit(depth)
            val node = objectValue(value, "command")
            val type = string(node, "type").lowercase()
            val data = objectValue(node.get("data"), "command.data")
            if (type == "path") return string(data, "pathName")
            if (type in groupTypes) for (child in children(data)) {
                visit(child, depth + 1)?.let { return it }
            }
            return null
        }
        return visit(command, 0)
    }

    /** Builds a fresh task tree; factories are called once per named-command occurrence. */
    fun parseAuto(
        jsonString: String,
        follower: HolonomicPathFollower,
        timestampMs: Long,
        alliance: Alliance = Alliance.BLUE
    ): Task {
        val command = objectValue(root(jsonString).get("command"), "command")
        return parseCommandNode(command, follower, timestampMs, alliance, CommandBudget(), 0)
    }

    private fun parseCommandNode(
        node: JsonObject,
        follower: HolonomicPathFollower,
        timestampMs: Long,
        alliance: Alliance,
        budget: CommandBudget,
        depth: Int
    ): Task {
        budget.visit(depth)
        val type = string(node, "type").lowercase()
        val data = objectValue(node.get("data"), "command.data")
        if (type in groupTypes) {
            val commands = children(data)
            require(type != "deadline" || commands.size() > 0) { "Deadline command requires a deadline child" }
            val tasks = ArrayList<Task>(commands.size())
            for (child in commands) {
                tasks.add(parseCommandNode(objectValue(child, "child command"), follower, timestampMs, alliance, budget, depth + 1))
            }
            return when (type) {
                "sequential" -> SequentialTaskGroup(tasks)
                "parallel" -> ParallelTaskGroup(tasks)
                "race" -> ParallelRaceGroup(tasks)
                else -> ParallelDeadlineGroup(tasks.first(), tasks.subList(1, tasks.size))
            }
        }
        return when (type) {
            "path" -> {
                val path = DynamicPathLoader.loadPath(string(data, "pathName"))
                val mirrored = AllianceMirroring.mirror(path, alliance, FieldSymmetry.MIRRORED)
                FollowPathTask(follower, mirrored, mirrorForAlliance = false)
            }
            "wait" -> {
                val seconds = number(data, "waitTime")
                require(seconds >= 0.0 && seconds * 1000.0 < Long.MAX_VALUE.toDouble()) {
                    "Wait command duration must be non-negative and representable in milliseconds"
                }
                TimeWaitTask((seconds * 1000.0).toLong())
            }
            "named" -> {
                val name = string(data, "name")
                NamedCommands.getCommand(name, timestampMs) ?: error("Named command '$name' is not registered")
            }
            else -> error("Unknown command type: '$type'")
        }
    }

    private fun root(json: String): JsonObject {
        require(json.isNotBlank() && json.length <= MAX_JSON_CHARACTERS) { "Auto JSON is empty or too large" }
        return try {
            objectValue(gson.fromJson(json, JsonElement::class.java), "auto")
        } catch (failure: com.google.gson.JsonParseException) {
            throw IllegalArgumentException("Invalid auto JSON", failure)
        }
    }

    private fun objectValue(value: JsonElement?, label: String): JsonObject {
        require(value != null && value.isJsonObject) { "$label must be an object" }
        return value.asJsonObject
    }

    private fun string(node: JsonObject, key: String): String {
        val value = node.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
            "$key must be a nonblank string"
        }
        return value.asString
    }

    private fun number(node: JsonObject, key: String): Double {
        val value = node.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be a number" }
        return value.asDouble.also { require(it.isFinite()) { "$key must be finite" } }
    }

    private fun children(data: JsonObject): JsonArray {
        val value = data.get("commands")
        require(value != null && value.isJsonArray) { "Group commands must be an array" }
        require(value.asJsonArray.size() <= MAX_COMMAND_NODES) { "Too many auto commands" }
        return value.asJsonArray
    }

    private class CommandBudget {
        private var count = 0
        fun visit(depth: Int) {
            require(depth <= MAX_COMMAND_DEPTH && ++count <= MAX_COMMAND_NODES) { "Auto command tree is too deep or too large" }
        }
    }
}
