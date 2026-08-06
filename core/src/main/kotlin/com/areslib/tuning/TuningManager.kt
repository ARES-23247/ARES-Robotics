package com.areslib.tuning

import com.areslib.action.RobotAction
import com.areslib.Store
import com.areslib.state.TuningState
import com.areslib.telemetry.ITelemetry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File

/**
 * Manages dynamically tunable variables for the robot.
 * Synchronizes the Redux [TuningState] with an external dashboard over NetworkTables,
 * and automatically persists changes to a local JSON file (with backup/rollbacks).
 */
class TuningManager(
    private val store: Store,
    private val telemetry: ITelemetry,
    private val saveFile: File
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        // Try to load initial configuration from file
        if (saveFile.exists()) {
            try {
                val jsonStr = saveFile.readText()
                val loadedJson = gson.fromJson(jsonStr, JsonObject::class.java)

                // Merge into default state to preserve Kotlin defaults for missing fields
                val defaultJson = gson.toJsonTree(store.state.tuning).asJsonObject
                mergeJsonObjects(defaultJson, loadedJson)

                var loadedState = gson.fromJson(defaultJson, TuningState::class.java)
                if (loadedState != null) {
                    if (loadedState.driveFeedforward.kS == 0.0 && loadedState.driveFeedforward.kV == 0.0 && loadedState.driveFeedforward.kA == 0.0) {
                        loadedState = loadedState.copy(
                            drive = loadedState.drive.copy(
                                driveFeedforward = com.areslib.control.tuning.SimpleFeedforwardCoeffs(0.05, 0.638, 0.02)
                            )
                        )
                    }
                    store.dispatch(RobotAction.UpdateTuningState(loadedState))
                }
            } catch (e: Exception) {
                System.err.println("TuningManager: Failed to load tuning config from ${saveFile.absolutePath}: ${e.message}")
            }
        }

        // Publish current constants once on startup so dashboard populates immediately
        publishInitialState()
    }

    /**
     * Deeply merges incoming JSON properties into the default target JSON object.
     */
    private fun mergeJsonObjects(target: JsonObject, source: JsonObject) {
        for ((key, element) in source.entrySet()) {
            if (element.isJsonObject && target.has(key) && target.get(key).isJsonObject) {
                mergeJsonObjects(target.getAsJsonObject(key), element.asJsonObject)
            } else {
                target.add(key, element)
            }
        }
    }

    /**
     * Publishes all tuning constants recursively over NT4.
     */
    fun publishInitialState() {
        val stateJson = gson.toJsonTree(store.state.tuning).asJsonObject
        publishJsonObject("", stateJson)
    }

    private fun publishJsonObject(prefix: String, obj: JsonObject) {
        for ((key, element) in obj.entrySet()) {
            val currentPrefix = if (prefix.isEmpty()) key else "$prefix/$key"
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                    telemetry.putNumber("Tuning/$currentPrefix", element.asDouble)
                }
                element.isJsonObject -> {
                    publishJsonObject(currentPrefix, element.asJsonObject)
                }
            }
        }
    }

    private var lastUpdateTimestamp = 0L

    /**
     * Call this in the periodic robot update loop.
     * Polls NT4 for any tuning changes and dispatches them to the store.
     */
    fun update(timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()) {
        if (timestampMs - lastUpdateTimestamp < 500L) return
        lastUpdateTimestamp = timestampMs

        val currentState = store.state.tuning
        val stateJson = gson.toJsonTree(currentState).asJsonObject

        val (updatedJson, changed) = pollJsonObject("", stateJson)

        if (changed) {
            val newState = gson.fromJson(updatedJson, TuningState::class.java)
            if (newState != null) {
                store.dispatch(RobotAction.UpdateTuningState(newState))
                saveToDisk(newState)
            }
        }
    }

    private fun pollJsonObject(prefix: String, obj: JsonObject): Pair<JsonObject, Boolean> {
        val result = JsonObject()
        var changed = false

        val domainPrefixes = listOf(
            "drive/ftc/", "localization/ftcPinpoint/", "localization/ekfNoise/", "subsystem/ftc/",
            "drive/", "vision/", "visionAlign/", "localization/", "driver/", "recovery/", "telemetry/", "subsystem/"
        )

        for ((key, element) in obj.entrySet()) {
            val currentPrefix = if (prefix.isEmpty()) key else "$prefix/$key"
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                    val ntKey = "Tuning/$currentPrefix"
                    val currentValue = element.asDouble

                    // Check primary domain-scoped key (e.g. Tuning/drive/pathTranslationGains/kP)
                    // and fall back to legacy flat key (e.g. Tuning/pathTranslationGains/kP)
                    val pathWithoutDomain = domainPrefixes.fold(currentPrefix) { acc, p ->
                        if (acc.startsWith(p)) acc.substring(p.length) else acc
                    }
                    val legacyKey = "Tuning/$pathWithoutDomain"

                    var ntValue = telemetry.getNumber(ntKey, currentValue)
                    if (ntValue == currentValue && legacyKey != ntKey) {
                        val legacyValue = telemetry.getNumber(legacyKey, currentValue)
                        if (legacyValue != currentValue) {
                            ntValue = legacyValue
                        }
                    }

                    if (ntValue != currentValue) {
                        changed = true
                    }
                    result.addProperty(key, ntValue)
                    telemetry.putNumber(ntKey, ntValue)
                }
                element.isJsonObject -> {
                    val (nestedResult, nestedChanged) = pollJsonObject(currentPrefix, element.asJsonObject)
                    if (nestedChanged) changed = true
                    result.add(key, nestedResult)
                }
                else -> {
                    result.add(key, element)
                }
            }
        }
        return Pair(result, changed)
    }

    private fun saveToDisk(state: TuningState) {
        try {
            // Create backup if file exists for 1-step rollback
            if (saveFile.exists()) {
                val backupFile = File(saveFile.parentFile, saveFile.nameWithoutExtension + ".backup.json")
                saveFile.copyTo(backupFile, overwrite = true)
            }
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(state))
        } catch (e: Exception) {
            System.err.println("TuningManager: Failed to save tuning config: ${e.message}")
        }
    }
}
