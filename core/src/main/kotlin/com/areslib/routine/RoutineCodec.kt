package com.areslib.routine

import com.areslib.auto.AresAutoCodec
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Result of opening either a native routine or a legacy autonomous document. */
data class DecodedRoutine(
    val document: RoutineDocument,
    val autonomousEntryPoint: AutonomousRoutineEntryPoint? = null,
    val migratedFrom: String? = null
)

/** Strict JSON codec for the native `.aresroutine` file format. */
object AresRoutineCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Encodes a structurally valid document with stable argument ordering. */
    fun encode(document: RoutineDocument): String {
        requireValid(document)
        return gson.toJson(document.withSortedArguments())
    }

    /** Decodes and validates one native `.aresroutine` document. */
    fun decode(json: String): RoutineDocument {
        val root = parseRoot(json)
        requireOnlyRootFields(root)
        require(root.has("documentId") && root.has("name") && root.has("steps")) {
            "Routine document must contain documentId, name, and steps"
        }
        require(root.get("steps").isJsonArray) { "Routine steps must be an array" }
        validateStepJson(root.getAsJsonArray("steps"), "steps")
        val document = try {
            gson.fromJson(root, RoutineDocument::class.java)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Routine document has invalid field types: ${error.message}", error)
        }
        requireValid(document)
        return document
    }

    /**
     * Opens a native routine or migrates the previous `.aresauto` schema in memory.
     *
     * Callers decide when to persist the migrated document; decoding never mutates project files.
     */
    fun decodeOrMigrateLegacyAuto(json: String): DecodedRoutine {
        val root = parseRoot(json)
        return if (root.has("startingPose")) {
            val migration = migrateAutoRoutine(AresAutoCodec.decode(json))
            DecodedRoutine(
                document = migration.document,
                autonomousEntryPoint = migration.entryPoint,
                migratedFrom = "aresauto-v1"
            )
        } else {
            DecodedRoutine(document = decode(json))
        }
    }

    /** SHA-256 of the canonical encoded document used for revision parent links. */
    fun contentHash(document: RoutineDocument): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(encode(document).toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun requireValid(document: RoutineDocument) {
        val errors = try {
            validateRoutine(document).filter { it.severity == RoutineValidationSeverity.ERROR }
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Routine document is missing required fields", error)
        }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
    }

    private fun parseRoot(json: String): JsonObject = try {
        // FTC SDK 11.1 bundles Gson 2.8.x, which predates the static parseString API.
        @Suppress("DEPRECATION")
        val parsed = JsonParser().parse(json)
        require(parsed.isJsonObject) { "Routine document root must be a JSON object" }
        parsed.asJsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("Routine document is not valid JSON: ${error.message}", error)
    }

    private fun requireOnlyRootFields(root: JsonObject) {
        val unknown = root.keySet() - ROOT_FIELDS
        require(unknown.isEmpty()) { "Unknown routine fields: ${unknown.sorted().joinToString()}" }
    }

    private fun validateStepJson(array: com.google.gson.JsonArray, path: String) {
        array.forEachIndexed { index, element ->
            require(element.isJsonObject) { "$path[$index] must be an object" }
            val step = element.asJsonObject
            val unknown = step.keySet() - STEP_FIELDS
            require(unknown.isEmpty()) { "Unknown fields at $path[$index]: ${unknown.sorted().joinToString()}" }
            require(step.has("kind") && step.get("kind").isJsonPrimitive) { "$path[$index].kind is required" }
            runCatching { RoutineStepKind.valueOf(step.get("kind").asString) }
                .getOrElse { throw IllegalArgumentException("Unknown step kind at $path[$index]") }
            listOf("children", "elseChildren").forEach { field ->
                step.get(field)?.takeUnless { it.isJsonNull }?.let {
                    require(it.isJsonArray) { "$path[$index].$field must be an array" }
                    validateStepJson(it.asJsonArray, "$path[$index].$field")
                }
            }
            step.get("deadline")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonObject) { "$path[$index].deadline must be an object" }
                val wrapper = com.google.gson.JsonArray().apply { add(it) }
                validateStepJson(wrapper, "$path[$index].deadline")
            }
        }
    }

    private fun RoutineDocument.withSortedArguments(): RoutineDocument = copy(
        steps = steps.map { it.withSortedStepArguments() }
    )

    private fun RoutineStep.withSortedStepArguments(): RoutineStep = copy(
        arguments = arguments.toSortedMap(),
        children = children.map { it.withSortedStepArguments() },
        deadline = deadline?.withSortedStepArguments(),
        elseChildren = elseChildren.map { it.withSortedStepArguments() }
    )

    private val ROOT_FIELDS = setOf(
        "schemaVersion", "documentId", "revision", "parentContentHash", "name", "description", "steps"
    )
    private val STEP_FIELDS = setOf(
        "kind", "actionKey", "arguments", "drive", "durationSeconds", "timeoutSeconds",
        "conditionKey", "routineId", "repeatCount", "children", "deadline", "elseChildren"
    )
}
