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
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val backupFile = backupFile()
        val loadedState = loadTuningState(saveFile) ?: loadTuningState(backupFile)
        if (loadedState != null) {
            store.dispatch(RobotAction.UpdateTuningState(withDefaultFeedforward(loadedState)))
        } else if (saveFile.exists() || backupFile.exists()) {
            System.err.println("TuningManager: No valid tuning config found at ${saveFile.absolutePath} or its backup")
        }

        // Publish current constants once on startup so dashboard populates immediately
        publishInitialState()
    }

    /**
     * Deeply merges incoming JSON properties into the default target JSON object.
     */
    private fun mergeJsonObjects(target: JsonObject, source: JsonObject) {
        for ((key, element) in source.entrySet()) {
            val existing = target.get(key) ?: continue
            when {
                element.isJsonObject && existing.isJsonObject ->
                    mergeJsonObjects(existing.asJsonObject, element.asJsonObject)
                existing.isJsonNull && isSafeJsonValue(element) -> target.add(key, element)
                element.isJsonNull -> target.add(key, element)
                element.isJsonPrimitive && existing.isJsonPrimitive &&
                    element.asJsonPrimitive.isNumber && existing.asJsonPrimitive.isNumber -> {
                    val number = element.asDouble
                    if (number.isFinite()) target.addProperty(key, number)
                }
            }
        }
    }

    private fun isSafeJsonValue(element: JsonElement): Boolean = when {
        element.isJsonNull -> true
        element.isJsonPrimitive -> element.asJsonPrimitive.let { !it.isNumber || it.asDouble.isFinite() }
        element.isJsonObject -> element.asJsonObject.entrySet().all { isSafeJsonValue(it.value) }
        else -> false
    }

    /**
     * Publishes all tuning constants recursively over NT4.
     */
    fun publishInitialState() {
        telemetry.putNumber(TuningTopics.SCHEMA_VERSION_TOPIC, TuningTopics.SCHEMA_VERSION.toDouble())
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

        for ((key, element) in obj.entrySet()) {
            val currentPrefix = if (prefix.isEmpty()) key else "$prefix/$key"
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                    val ntKey = "Tuning/$currentPrefix"
                    val currentValue = element.asDouble.let { if (it.isFinite()) it else 0.0 }

                    var ntValue = telemetry.getNumber(ntKey, currentValue)
                    if (!ntValue.isFinite()) ntValue = currentValue

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
        var tempFile: File? = null
        try {
            val parent = saveFile.absoluteFile.parentFile
            parent?.mkdirs()

            if (saveFile.exists()) {
                val backup = backupFile()
                val backupTemp = File.createTempFile("${saveFile.nameWithoutExtension}-backup-", ".tmp", parent)
                try {
                    saveFile.inputStream().use { input ->
                        FileOutputStream(backupTemp).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    atomicReplace(backupTemp, backup)
                } finally {
                    backupTemp.delete()
                }
            }

            tempFile = File.createTempFile("${saveFile.nameWithoutExtension}-", ".tmp", parent)
            FileOutputStream(tempFile).use { output ->
                val persisted = gson.toJsonTree(state).asJsonObject
                persisted.addProperty("_schemaVersion", TuningTopics.SCHEMA_VERSION)
                output.write(gson.toJson(persisted).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicReplace(tempFile, saveFile)
        } catch (e: Exception) {
            System.err.println("TuningManager: Failed to save tuning config: ${e.message}")
        } finally {
            tempFile?.delete()
        }
    }

    private fun loadTuningState(file: File): TuningState? {
        if (!file.isFile) return null
        return try {
            val loadedJson = gson.fromJson(file.readText(), JsonObject::class.java) ?: return null
            val schemaVersion = loadedJson.get("_schemaVersion")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
            if (schemaVersion != TuningTopics.SCHEMA_VERSION) return null
            val merged = gson.toJsonTree(store.state.tuning).asJsonObject
            mergeCanonicalNumbers(merged, loadedJson)
            gson.fromJson(merged, TuningState::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /** Flattens schema-v2 input and merges only known finite numeric leaves. */
    private fun mergeCanonicalNumbers(target: JsonObject, source: JsonObject) {
        val flattened = LinkedHashMap<String, Double>()
        flattenNumbers(source, "", flattened)
        for ((path, value) in flattened) {
            if (!value.isFinite() || path == "_schemaVersion") continue
            setCanonicalNumber(target, TuningTopics.statePath("Tuning/$path"), value)
        }
    }

    private fun flattenNumbers(source: JsonObject, prefix: String, out: MutableMap<String, Double>) {
        for ((key, element) in source.entrySet()) {
            val path = if (prefix.isEmpty()) key else "$prefix/$key"
            when {
                element.isJsonObject -> flattenNumbers(element.asJsonObject, path, out)
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                    val value = element.asDouble
                    if (value.isFinite()) out[path] = value
                }
            }
        }
    }

    private fun setCanonicalNumber(target: JsonObject, path: String, value: Double) {
        val parts = path.split('/')
        var cursor = target
        var materializedOptionalObject = false
        for (i in 0 until parts.lastIndex) {
            val key = parts[i]
            val existing = cursor.get(key) ?: return
            cursor = when {
                existing.isJsonObject -> existing.asJsonObject
                existing.isJsonNull -> JsonObject().also {
                    cursor.add(key, it)
                    materializedOptionalObject = true
                }
                else -> return
            }
        }
        val leaf = parts.lastOrNull() ?: return
        val existingLeaf = cursor.get(leaf)
        val optionalPidLeaf = path.startsWith("drive/ftc/motorGains/") && leaf in setOf("kP", "kI", "kD", "kF")
        if (existingLeaf == null && (materializedOptionalObject || optionalPidLeaf) && optionalPidLeaf) {
            cursor.addProperty(leaf, value)
            return
        }
        existingLeaf ?: return
        if (existingLeaf.isJsonPrimitive && existingLeaf.asJsonPrimitive.isNumber) {
            cursor.addProperty(leaf, value)
        }
    }

    private fun withDefaultFeedforward(state: TuningState): TuningState {
        val feedforward = state.driveFeedforward
        if (feedforward.kS != 0.0 || feedforward.kV != 0.0 || feedforward.kA != 0.0) return state
        return state.copy(
            drive = state.drive.copy(
                driveFeedforward = com.areslib.control.tuning.SimpleFeedforwardCoeffs(0.05, 0.638, 0.02)
            )
        )
    }

    private fun backupFile(): File =
        File(saveFile.absoluteFile.parentFile, saveFile.nameWithoutExtension + ".backup.json")

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
