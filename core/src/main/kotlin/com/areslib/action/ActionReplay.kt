package com.areslib.action

import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.state.SubsystemState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** A malformed, unsupported, or incompletely registered action log cannot be replayed safely. */
class ActionReplayException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Offline deterministic replay tool.
 *
 * The JSONL codec is deliberately strict: every record declares [SCHEMA_VERSION], every core
 * action has an explicit registry entry, and an unknown type or malformed line aborts parsing with
 * [ActionReplayException]. Returning a partial replay would make the resulting robot state look
 * authoritative even though one or more transitions were omitted.
 *
 * Season-defined top-level actions can be registered with [registerAction]. The two core actions
 * that carry the polymorphic [SubsystemState] marker also record the concrete state type, but that
 * type must be registered with [registerSubsystemState] before replay. This is intentional: core
 * cannot safely infer or instantiate an FTC/FRC season class that may not be on the replay
 * classpath.
 */
object ActionReplay {
    const val SCHEMA_VERSION: Int = 1

    private const val SUBSYSTEM_STATE_TYPE_FIELD = "_ares_subsystem_state_type"
    private val gson = Gson()

    private val builtInByName: Map<String, Class<out RobotAction>> = linkedMapOf(
        "DriveHardwareUpdate" to RobotAction.DriveHardwareUpdate::class.java,
        "VisionMeasurementsReceived" to RobotAction.VisionMeasurementsReceived::class.java,
        "PoseUpdate" to RobotAction.PoseUpdate::class.java,
        "SetAlliance" to RobotAction.SetAlliance::class.java,
        "SetDriveMode" to RobotAction.SetDriveMode::class.java,
        "SetHeadingLockTarget" to RobotAction.SetHeadingLockTarget::class.java,
        "CalibrateSwerveOffsets" to RobotAction.CalibrateSwerveOffsets::class.java,
        "SetPositionLockTarget" to RobotAction.SetPositionLockTarget::class.java,
        "JoystickDriveIntent" to RobotAction.JoystickDriveIntent::class.java,
        "PathEventTriggered" to RobotAction.PathEventTriggered::class.java,
        "RoutineRequested" to RobotAction.RoutineRequested::class.java,
        "RoutineStarted" to RobotAction.RoutineStarted::class.java,
        "RoutineStepEntered" to RobotAction.RoutineStepEntered::class.java,
        "RoutineCompleted" to RobotAction.RoutineCompleted::class.java,
        "RoutineFailed" to RobotAction.RoutineFailed::class.java,
        "RoutineCancelled" to RobotAction.RoutineCancelled::class.java,
        "UpdateSubsystemState" to RobotAction.UpdateSubsystemState::class.java,
        "UpdateNamedSubsystemState" to RobotAction.UpdateNamedSubsystemState::class.java,
        "SetIndicatorLight" to RobotAction.SetIndicatorLight::class.java,
        "SetPrismDriver" to RobotAction.SetPrismDriver::class.java,
        "ChainPaths" to RobotAction.ChainPaths::class.java,
        "SwitchPath" to RobotAction.SwitchPath::class.java,
        "UpdatePathProgress" to RobotAction.UpdatePathProgress::class.java,
        "UpdateTuningState" to RobotAction.UpdateTuningState::class.java,
        "StartCalibrationSweep" to StartCalibrationSweep::class.java,
        "CalibrationFrameLogged" to CalibrationFrameLogged::class.java
    )
    private val builtInByClass: Map<Class<out RobotAction>, String> =
        builtInByName.entries.associate { (name, clazz) -> clazz to name }

    private val customActionsByName = ConcurrentHashMap<String, Class<out RobotAction>>()
    private val customActionNamesByClass = ConcurrentHashMap<Class<out RobotAction>, String>()
    private val subsystemStatesByName = ConcurrentHashMap<String, Class<out SubsystemState>>()
    private val subsystemStateNamesByClass = ConcurrentHashMap<Class<out SubsystemState>, String>()
    private val registryLock = Any()

    /** Immutable, detached payload handed from [ActionLogger] to its writer thread. */
    internal data class EncodedAction(val type: String, val payload: JsonObject)

    /** Current core registry surface, exposed internally for an exhaustiveness regression. */
    internal fun builtInActionClasses(): Set<Class<out RobotAction>> = builtInByClass.keys

    /**
     * Registers a season/application action codec under [type].
     *
     * Registration is explicit and collision-safe; core action names/classes cannot be shadowed.
     */
    fun registerAction(type: String, clazz: Class<out RobotAction>) {
        require(type.isNotBlank()) { "Action type must not be blank" }
        synchronized(registryLock) {
            val builtInClass = builtInByName[type]
            require(builtInClass == null || builtInClass == clazz) {
                "Action type '$type' is reserved for ${builtInClass?.name}"
            }
            val builtInName = builtInByClass[clazz]
            require(builtInName == null || builtInName == type) {
                "Core action ${clazz.name} is already registered as '$builtInName'"
            }
            if (builtInClass == clazz) return

            val existingClass = customActionsByName[type]
            require(existingClass == null || existingClass == clazz) {
                "Action type '$type' is already registered for ${existingClass?.name}"
            }
            val existingName = customActionNamesByClass[clazz]
            require(existingName == null || existingName == type) {
                "Action class ${clazz.name} is already registered as '$existingName'"
            }
            customActionsByName[type] = clazz
            customActionNamesByClass[clazz] = type
        }
    }

    /** Registers a season/application action under its fully qualified class name. */
    fun registerAction(clazz: Class<out RobotAction>) = registerAction(clazz.name, clazz)

    /**
     * Registers a concrete season state used inside [RobotAction.UpdateSubsystemState] or
     * [RobotAction.UpdateNamedSubsystemState]. Call this before parsing a log containing that
     * state. The encoded payload remains lossless even when the class is unavailable, but replay
     * fails visibly until its codec is supplied.
     */
    fun registerSubsystemState(type: String, clazz: Class<out SubsystemState>) {
        require(type.isNotBlank()) { "Subsystem state type must not be blank" }
        synchronized(registryLock) {
            val existingClass = subsystemStatesByName[type]
            require(existingClass == null || existingClass == clazz) {
                "Subsystem state type '$type' is already registered for ${existingClass?.name}"
            }
            val existingName = subsystemStateNamesByClass[clazz]
            require(existingName == null || existingName == type) {
                "Subsystem state class ${clazz.name} is already registered as '$existingName'"
            }
            subsystemStatesByName[type] = clazz
            subsystemStateNamesByClass[clazz] = type
        }
    }

    /** Registers a subsystem state under its fully qualified class name. */
    fun registerSubsystemState(clazz: Class<out SubsystemState>) =
        registerSubsystemState(clazz.name, clazz)

    /**
     * Serializes [action] immediately into a detached JSON tree.
     *
     * This is the ownership boundary for asynchronous logging. Gson traverses mutable lists,
     * arrays, pooled vision measurements/poses, path points, and custom states before the value is
     * placed on the writer queue; producer mutations after this method returns cannot alter it.
     */
    internal fun encodeForLog(action: RobotAction): EncodedAction {
        @Suppress("UNCHECKED_CAST")
        val actionClass = action.javaClass as Class<out RobotAction>
        val type = builtInByClass[actionClass]
            ?: customActionNamesByClass[actionClass]
            ?: actionClass.name
        val element = try {
            gson.toJsonTree(action, actionClass)
        } catch (e: Exception) {
            throw ActionReplayException("Could not snapshot action ${actionClass.name}: ${e.message}", e)
        }
        if (!element.isJsonObject) {
            throw ActionReplayException("Action ${actionClass.name} encoded as a non-object payload")
        }
        val payload = element.asJsonObject
        when (action) {
            is RobotAction.UpdateSubsystemState -> payload.addProperty(
                SUBSYSTEM_STATE_TYPE_FIELD,
                subsystemStateType(action.state.javaClass)
            )
            is RobotAction.UpdateNamedSubsystemState -> payload.addProperty(
                SUBSYSTEM_STATE_TYPE_FIELD,
                subsystemStateType(action.state.javaClass)
            )
            else -> Unit
        }
        return EncodedAction(type, payload)
    }

    /**
     * Parses every non-empty record in [logFile], preserving file order exactly.
     *
     * Missing files, malformed envelopes, unsupported schema versions, unknown action types, and
     * unavailable season state codecs all throw [ActionReplayException]. No record is skipped.
     */
    fun parseActions(logFile: File): List<RobotAction> {
        if (!logFile.isFile) {
            throw ActionReplayException("Action log does not exist or is not a file: ${logFile.absolutePath}")
        }

        val actions = mutableListOf<RobotAction>()
        try {
            Files.newBufferedReader(logFile.toPath(), StandardCharsets.UTF_8).use { reader ->
                var lineNumber = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (line.isBlank()) {
                        throw ActionReplayException(
                            "${logFile.absolutePath}:$lineNumber: blank records are not valid JSONL actions"
                        )
                    }
                    try {
                        actions.add(deserializeAction(line))
                    } catch (e: ActionReplayException) {
                        throw ActionReplayException(
                            "${logFile.absolutePath}:$lineNumber: ${e.message}",
                            e
                        )
                    }
                }
            }
        } catch (e: ActionReplayException) {
            throw e
        } catch (e: Exception) {
            throw ActionReplayException("Failed reading action log ${logFile.absolutePath}: ${e.message}", e)
        }
        return actions
    }

    /** Replays the complete log through [rootReducer] (or [reducer]) in recorded order. */
    @JvmOverloads
    fun replayLog(
        logFile: File,
        reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
    ): List<RobotState> {
        val actions = parseActions(logFile)
        val states = ArrayList<RobotState>(actions.size + 1)
        var currentState = RobotState()
        states.add(currentState)
        for (action in actions) {
            currentState = reducer(currentState, action)
            states.add(currentState)
        }
        return states
    }

    private fun deserializeAction(jsonLine: String): RobotAction {
        val parsed = try {
            JsonParser.parseString(jsonLine)
        } catch (e: Exception) {
            throw ActionReplayException("Malformed JSON: ${e.message}", e)
        }
        if (!parsed.isJsonObject) {
            throw ActionReplayException("Action record must be a JSON object")
        }
        val envelope = parsed.asJsonObject

        val schemaElement = envelope.get("schema_version")
            ?: throw ActionReplayException("Action record is missing schema_version")
        if (!schemaElement.isJsonPrimitive || !schemaElement.asJsonPrimitive.isNumber ||
            schemaElement.toString() != SCHEMA_VERSION.toString()
        ) {
            throw ActionReplayException(
                "Unsupported action-log schema_version $schemaElement; supported version is $SCHEMA_VERSION"
            )
        }

        val typeElement = envelope.get("type")
            ?: throw ActionReplayException("Action record is missing type")
        if (!typeElement.isJsonPrimitive || !typeElement.asJsonPrimitive.isString) {
            throw ActionReplayException("Action record type must be a string")
        }
        val type = typeElement.asString
        val payloadElement = envelope.get("payload")
            ?: throw ActionReplayException("Action '$type' is missing payload")
        if (!payloadElement.isJsonObject) {
            throw ActionReplayException("Action '$type' payload must be a JSON object")
        }
        val payload = payloadElement.asJsonObject
        val actionClass = builtInByName[type] ?: customActionsByName[type]
            ?: throw ActionReplayException(
                "Unknown action type '$type'; register season actions with ActionReplay.registerAction"
            )

        return try {
            when (actionClass) {
                RobotAction.UpdateSubsystemState::class.java -> decodeSubsystemUpdate(payload)
                RobotAction.UpdateNamedSubsystemState::class.java -> decodeNamedSubsystemUpdate(payload)
                else -> gson.fromJson(payload, actionClass)
                    ?: throw ActionReplayException("Action '$type' decoded to null")
            }
        } catch (e: ActionReplayException) {
            throw e
        } catch (e: Exception) {
            throw ActionReplayException("Failed to decode action '$type': ${e.message}", e)
        }
    }

    private fun decodeSubsystemUpdate(payload: JsonObject): RobotAction.UpdateSubsystemState {
        val state = decodeSubsystemState(payload)
        return RobotAction.UpdateSubsystemState(state, payload.requiredLong("timestampMs"))
    }

    private fun decodeNamedSubsystemUpdate(payload: JsonObject): RobotAction.UpdateNamedSubsystemState {
        val subsystemId = payload.requiredString("subsystemId")
        val state = decodeSubsystemState(payload)
        return RobotAction.UpdateNamedSubsystemState(
            subsystemId,
            state,
            payload.requiredLong("timestampMs")
        )
    }

    private fun decodeSubsystemState(payload: JsonObject): SubsystemState {
        val type = payload.requiredString(SUBSYSTEM_STATE_TYPE_FIELD)
        val stateClass = subsystemStatesByName[type]
            ?: throw ActionReplayException(
                "SubsystemState type '$type' is not registered; season replay must call " +
                    "ActionReplay.registerSubsystemState before parsing"
            )
        val stateElement = payload.get("state")
            ?: throw ActionReplayException("Subsystem action is missing state")
        if (!stateElement.isJsonObject) {
            throw ActionReplayException("Subsystem action state must be a JSON object")
        }
        return gson.fromJson(stateElement, stateClass)
            ?: throw ActionReplayException("SubsystemState '$type' decoded to null")
    }

    private fun subsystemStateType(clazz: Class<out SubsystemState>): String =
        subsystemStateNamesByClass[clazz] ?: clazz.name

    private fun JsonObject.requiredLong(name: String): Long {
        val element = get(name) ?: throw ActionReplayException("Action payload is missing $name")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw ActionReplayException("Action payload field $name must be a number")
        }
        return try {
            element.asLong
        } catch (e: Exception) {
            throw ActionReplayException("Action payload field $name is not a valid Long", e)
        }
    }

    private fun JsonObject.requiredString(name: String): String {
        val element = get(name) ?: throw ActionReplayException("Action payload is missing $name")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw ActionReplayException("Action payload field $name must be a string")
        }
        return element.asString
    }
}
