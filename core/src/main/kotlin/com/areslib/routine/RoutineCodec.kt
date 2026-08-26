package com.areslib.routine

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
        validateJsonEnvelope(json)
        validateNoDuplicateKeys(json)
        val root = parseRoot(json)
        validateRootJson(root)
        validateStepJson(root.getAsJsonArray("steps"), "steps", 0, intArrayOf(0))
        val document = try {
            gson.fromJson(root, RoutineDocument::class.java)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Routine document has invalid field types: ${error.message}", error)
        }
        requireValid(document)
        return document
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

    private fun validateRootJson(root: JsonObject) {
        requireExactFields(root, ROOT_FIELDS, "routine document")
        requireString(root, "documentId", "routine document", required = true)
        requireString(root, "name", "routine document", required = true)
        requireArray(root, "steps", "routine document", required = true)
        requireInteger(root, "schemaVersion", "routine document")
        requireInteger(root, "revision", "routine document")
        requireString(root, "parentContentHash", "routine document", nullable = true)
        requireString(root, "description", "routine document", nullable = true)
    }

    private fun validateStepJson(
        array: com.google.gson.JsonArray,
        path: String,
        depth: Int,
        stepCount: IntArray
    ) {
        require(depth <= MAX_ROUTINE_JSON_NESTING) { "Routine JSON nesting exceeds $MAX_ROUTINE_JSON_NESTING" }
        array.forEachIndexed { index, element ->
            stepCount[0]++
            require(stepCount[0] <= MAX_ROUTINE_JSON_STEPS) {
                "Routine JSON contains more than $MAX_ROUTINE_JSON_STEPS source steps"
            }
            require(element.isJsonObject) { "$path[$index] must be an object" }
            val step = element.asJsonObject
            val stepPath = "$path[$index]"
            requireExactFields(step, STEP_FIELDS, stepPath)
            requireString(step, "stepId", stepPath, required = true)
            val kindElement = requireString(step, "kind", stepPath, required = true)
            runCatching { RoutineStepKind.valueOf(requireNotNull(kindElement).asString) }
                .getOrElse { throw IllegalArgumentException("Unknown step kind at $stepPath") }

            requireString(step, "actionKey", stepPath, nullable = true)
            requireString(step, "conditionKey", stepPath, nullable = true)
            requireString(step, "routineId", stepPath, nullable = true)
            requireNumber(step, "durationSeconds", stepPath, nullable = true)
            requireNumber(step, "timeoutSeconds", stepPath, nullable = true)
            requireInteger(step, "repeatCount", stepPath, nullable = true)
            validateArguments(step, stepPath)
            validateDrive(step, stepPath)

            listOf("children", "elseChildren").forEach { field ->
                step.get(field)?.let {
                    require(!it.isJsonNull && it.isJsonArray) { "$stepPath.$field must be an array" }
                    validateStepJson(it.asJsonArray, "$stepPath.$field", depth + 1, stepCount)
                }
            }
            step.get("deadline")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonObject) { "$stepPath.deadline must be an object" }
                val wrapper = JsonArray().apply { add(it) }
                validateStepJson(wrapper, "$stepPath.deadline", depth + 1, stepCount)
            }
        }
    }

    private fun validateArguments(step: JsonObject, path: String) {
        val arguments = step.get("arguments") ?: return
        require(!arguments.isJsonNull && arguments.isJsonObject) { "$path.arguments must be an object" }
        for ((key, value) in arguments.asJsonObject.entrySet()) {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "$path.arguments.$key must be a string"
            }
        }
    }

    private fun validateDrive(step: JsonObject, path: String) {
        val driveElement = step.get("drive") ?: return
        if (driveElement.isJsonNull) return
        require(driveElement.isJsonObject) { "$path.drive must be an object" }
        val drive = driveElement.asJsonObject
        val drivePath = "$path.drive"
        requireExactFields(drive, DRIVE_FIELDS, drivePath)
        requireString(drive, "motionPresetKey", drivePath)
        requireString(drive, "preferredEngineKey", drivePath, nullable = true)
        requireStringArray(drive, "duringActionKeys", drivePath)
        requireStringArray(drive, "arrivalActionKeys", drivePath)

        val targetElement = drive.get("target")
        require(targetElement != null && !targetElement.isJsonNull && targetElement.isJsonObject) {
            "$drivePath.target must be an object"
        }
        val target = targetElement.asJsonObject
        val targetPath = "$drivePath.target"
        requireExactFields(target, TARGET_FIELDS, targetPath)
        requireNumber(target, "xMeters", targetPath, required = true)
        requireNumber(target, "yMeters", targetPath, required = true)
        requireNumber(target, "headingRadians", targetPath, required = true)

        val markersElement = drive.get("markers") ?: return
        require(!markersElement.isJsonNull && markersElement.isJsonArray) { "$drivePath.markers must be an array" }
        markersElement.asJsonArray.forEachIndexed { index, markerElement ->
            val markerPath = "$drivePath.markers[$index]"
            require(markerElement.isJsonObject) { "$markerPath must be an object" }
            val marker = markerElement.asJsonObject
            requireExactFields(marker, MARKER_FIELDS, markerPath)
            requireNumber(marker, "progress", markerPath, required = true)
            requireString(marker, "actionKey", markerPath, required = true)
        }
    }

    private fun requireExactFields(value: JsonObject, allowed: Set<String>, path: String) {
        val unknown = value.entrySet().mapTo(linkedSetOf()) { it.key } - allowed
        require(unknown.isEmpty()) { "Unknown fields at $path: ${unknown.sorted().joinToString()}" }
    }

    private fun requireString(
        value: JsonObject,
        field: String,
        path: String,
        required: Boolean = false,
        nullable: Boolean = false
    ): JsonElement? {
        val element = value.get(field)
        if (element == null) {
            require(!required) { "$path.$field is required" }
            return null
        }
        if (element.isJsonNull) {
            require(nullable && !required) { "$path.$field must be a string" }
            return null
        }
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$path.$field must be a string" }
        return element
    }

    private fun requireNumber(
        value: JsonObject,
        field: String,
        path: String,
        required: Boolean = false,
        nullable: Boolean = false
    ): JsonElement? {
        val element = value.get(field)
        if (element == null) {
            require(!required) { "$path.$field is required" }
            return null
        }
        if (element.isJsonNull) {
            require(nullable && !required) { "$path.$field must be a number" }
            return null
        }
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path.$field must be a number" }
        return element
    }

    private fun requireInteger(
        value: JsonObject,
        field: String,
        path: String,
        required: Boolean = false,
        nullable: Boolean = false
    ): JsonElement? {
        val element = requireNumber(value, field, path, required, nullable) ?: return null
        try {
            element.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$path.$field must be an integer")
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("$path.$field must be an integer")
        }
        return element
    }

    private fun requireArray(
        value: JsonObject,
        field: String,
        path: String,
        required: Boolean = false
    ): JsonElement? {
        val element = value.get(field)
        if (element == null) {
            require(!required) { "$path.$field is required" }
            return null
        }
        require(!element.isJsonNull && element.isJsonArray) { "$path.$field must be an array" }
        return element
    }

    private fun requireStringArray(value: JsonObject, field: String, path: String) {
        val element = value.get(field) ?: return
        require(!element.isJsonNull && element.isJsonArray) { "$path.$field must be an array" }
        element.asJsonArray.forEachIndexed { index, item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                "$path.$field[$index] must be a string"
            }
        }
    }

    /** Gson's object model keeps only the last duplicate member, so detect duplicates while streaming. */
    @Suppress("DEPRECATION")
    private fun validateNoDuplicateKeys(json: String) {
        try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                scanJsonValue(reader, "routine document")
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Routine document contains trailing JSON" }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Routine document is not valid JSON: ${error.message}", error)
        }
    }

    private fun scanJsonValue(reader: JsonReader, path: String) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = HashSet<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.add(name)) { "Duplicate field '$name' at $path" }
                    scanJsonValue(reader, "$path.$name")
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    scanJsonValue(reader, "$path[$index]")
                    index++
                }
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("Unexpected JSON token ${reader.peek()} at $path")
        }
    }

    /** Rejects oversized or pathologically deep JSON before Gson constructs its object tree. */
    private fun validateJsonEnvelope(json: String) {
        require(json.isNotBlank()) { "Routine document must not be blank" }
        require(json.length <= MAX_ROUTINE_JSON_CHARACTERS) {
            "Routine document exceeds $MAX_ROUTINE_JSON_CHARACTERS characters"
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (character in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        require(depth <= MAX_ROUTINE_JSON_CONTAINER_DEPTH) {
                            "Routine JSON nesting exceeds the supported limit"
                        }
                    }
                    '}', ']' -> depth--
                }
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
        "kind", "stepId", "actionKey", "arguments", "drive", "durationSeconds", "timeoutSeconds",
        "conditionKey", "routineId", "repeatCount", "children", "deadline", "elseChildren"
    )
    private val DRIVE_FIELDS = setOf(
        "target", "motionPresetKey", "preferredEngineKey", "markers",
        "duringActionKeys", "arrivalActionKeys"
    )
    private val TARGET_FIELDS = setOf("xMeters", "yMeters", "headingRadians")
    private val MARKER_FIELDS = setOf("progress", "actionKey")

    internal const val MAX_ROUTINE_JSON_CHARACTERS = 4_194_304
    private const val MAX_ROUTINE_JSON_NESTING = 64
    private const val MAX_ROUTINE_JSON_CONTAINER_DEPTH = 200
    private const val MAX_ROUTINE_JSON_STEPS = 10_000
}
