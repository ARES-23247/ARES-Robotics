package com.areslib.tuning

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.Store
import com.areslib.state.TuningState
import com.areslib.telemetry.ITelemetry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
                
                // Merge into the default state to preserve Kotlin defaults for missing fields
                val defaultJson = gson.toJsonTree(store.state.tuning).asJsonObject
                for ((key, element) in loadedJson.entrySet()) {
                    defaultJson.add(key, element)
                }

                var loadedState = gson.fromJson(defaultJson, TuningState::class.java)
                if (loadedState != null) {
                    if (loadedState.driveFeedforward.kS == 0.0 && loadedState.driveFeedforward.kV == 0.0 && loadedState.driveFeedforward.kA == 0.0) {
                        loadedState = loadedState.copy(driveFeedforward = com.areslib.control.tuning.SimpleFeedforwardCoeffs(0.05, 0.638, 0.02))
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
     * Publishes all tuning constants once over NT4 without running continuous polling.
     */
    fun publishInitialState() {
        val stateJson = gson.toJsonTree(store.state.tuning).asJsonObject
        for ((key, element) in stateJson.entrySet()) {
            if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                telemetry.putNumber("Tuning/$key", element.asDouble)
            } else if (element.isJsonObject) {
                val nestedObj = element.asJsonObject
                for ((nestedKey, nestedElement) in nestedObj.entrySet()) {
                    if (nestedElement.isJsonPrimitive && nestedElement.asJsonPrimitive.isNumber) {
                        telemetry.putNumber("Tuning/$key/$nestedKey", nestedElement.asDouble)
                    }
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
        
        // Flatten state to JsonObject to push NT4 schema and poll for changes
        val stateJson = gson.toJsonTree(currentState).asJsonObject
        
        var changed = false
        val updatedJson = JsonObject()

        // We iterate through the root properties. 
        // For simplicity in NT4, we only support 1 level of nesting (e.g. Tuning/pathTranslationGains/kP).
        for ((key, element) in stateJson.entrySet()) {
            if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                val ntKey = "Tuning/$key"
                val currentValue = element.asDouble
                
                // Read from NT4 (if dashboard changed it, this will be different)
                val ntValue = telemetry.getNumber(ntKey, currentValue)
                
                if (ntValue != currentValue) {
                    changed = true
                }
                updatedJson.addProperty(key, ntValue)
                
                // Publish back to keep NT4 server schema populated
                telemetry.putNumber(ntKey, ntValue)
                
            } else if (element.isJsonObject) {
                val nestedObj = element.asJsonObject
                val newNestedObj = JsonObject()
                
                for ((nestedKey, nestedElement) in nestedObj.entrySet()) {
                    if (nestedElement.isJsonPrimitive && nestedElement.asJsonPrimitive.isNumber) {
                        val ntKey = "Tuning/$key/$nestedKey"
                        val currentValue = nestedElement.asDouble
                        
                        val ntValue = telemetry.getNumber(ntKey, currentValue)
                        
                        if (ntValue != currentValue) {
                            changed = true
                        }
                        newNestedObj.addProperty(nestedKey, ntValue)
                        telemetry.putNumber(ntKey, ntValue)
                    } else {
                        newNestedObj.add(nestedKey, nestedElement)
                    }
                }
                updatedJson.add(key, newNestedObj)
            } else {
                // Not a number or object (e.g. boolean/string), just copy it over
                updatedJson.add(key, element)
            }
        }

        // If a change occurred on NT4, write to disk and dispatch to Redux
        if (changed) {
            val newState = gson.fromJson(updatedJson, TuningState::class.java)
            store.dispatch(RobotAction.UpdateTuningState(newState))
            saveToDisk(newState)
        }
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
