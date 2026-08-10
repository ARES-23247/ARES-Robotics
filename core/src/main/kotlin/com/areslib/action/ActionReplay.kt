package com.areslib.action

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline deterministic replay tool.
 * Reconstructs exact sequence of RobotState states by re-playing logged RobotActions through rootReducer.
 */
object ActionReplay {
    private val gson = Gson()

    private val actionRegistry = ConcurrentHashMap<String, Class<out RobotAction>>().apply {
        putAll(mapOf(
            "DriveHardwareUpdate" to RobotAction.DriveHardwareUpdate::class.java,
            "VisionMeasurementsReceived" to RobotAction.VisionMeasurementsReceived::class.java,
            "PoseUpdate" to RobotAction.PoseUpdate::class.java,
            "JoystickDriveIntent" to RobotAction.JoystickDriveIntent::class.java,
            "PathEventTriggered" to RobotAction.PathEventTriggered::class.java,
            "UpdateSubsystemState" to RobotAction.UpdateSubsystemState::class.java,
            "ChainPaths" to RobotAction.ChainPaths::class.java,
            "SwitchPath" to RobotAction.SwitchPath::class.java,
            "UpdatePathProgress" to RobotAction.UpdatePathProgress::class.java
        ))
    }

    /**
     * Registers a custom action type for deserialization during replay.
     */
    fun registerAction(type: String, clazz: Class<out RobotAction>) {
        actionRegistry[type] = clazz
    }

    /**
     * Parses a JSONL log file into a list of [RobotAction] instances without replaying them.
     *
     * This is useful for cursor-based replay sessions (e.g. [ActionReplaySession]) that need
     * random-access to the raw action list without eagerly computing every intermediate state.
     *
     * @param logFile The `.jsonl` file recorded by [ActionLogger].
     * @return An ordered list of successfully deserialized actions. Malformed or unknown
     *         action lines are silently skipped (with a stderr warning).
     */
    fun parseActions(logFile: File): List<RobotAction> {
        if (!logFile.exists()) return emptyList()

        val actions = mutableListOf<RobotAction>()
        BufferedReader(FileReader(logFile)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                line.takeIf { it.trim().isNotEmpty() }
                    ?.let { deserializeAction(it) }
                    ?.also { actions.add(it) }
                line = reader.readLine()
            }
        }
        return actions
    }

    /**
     * Replays a JSONL log file back through the pure rootReducer (or a custom reducer), returning a sequence of RobotState states.
     */
    @JvmOverloads
    fun replayLog(logFile: File, reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer): List<RobotState> {
        val actions = parseActions(logFile)
        val states = mutableListOf<RobotState>()
        var currentState = RobotState()
        states.add(currentState)

        for (action in actions) {
            currentState = reducer(currentState, action)
            states.add(currentState)
        }

        return states
    }

    private fun deserializeAction(jsonLine: String): RobotAction? {
        return try {
            val envelope = gson.fromJson(jsonLine, JsonObject::class.java)
            val type = envelope.get("type").asString
            val payload = envelope.getAsJsonObject("payload")

            val actionClass = actionRegistry[type]

            if (actionClass != null) {
                gson.fromJson(payload, actionClass as java.lang.reflect.Type)
            } else {
                System.err.println("ActionReplay: Unknown action type '$type'")
                null
            }
        } catch (e: Exception) {
            System.err.println("ActionReplay: Failed to deserialize action line: ${e.message}")
            null
        }
    }
}
