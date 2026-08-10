package com.areslib.auto

import com.areslib.pathing.CommandKey
import com.areslib.pathing.TrajectoryEngine
import com.areslib.pathing.TrajectoryPreset
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlin.time.Duration

const val ARES_AUTO_SCHEMA_VERSION: Int = 1

/** Distance value used by the code DSL to prevent accidental inches/meters confusion. */
@JvmInline
value class Distance private constructor(val meters: Double) {
    init {
        require(meters.isFinite()) { "Distance must be finite" }
    }

    companion object {
        fun meters(value: Number): Distance = Distance(value.toDouble())
    }
}

/** CCW-positive angle value used by the code DSL. */
@JvmInline
value class Angle private constructor(val radians: Double) {
    init {
        require(radians.isFinite()) { "Angle must be finite" }
    }

    companion object {
        fun radians(value: Number): Angle = Angle(value.toDouble())
        fun degrees(value: Number): Angle = Angle(Math.toRadians(value.toDouble()))
    }
}

val Number.meters: Distance
    get() = Distance.meters(this)

val Number.radians: Angle
    get() = Angle.radians(this)

val Number.degrees: Angle
    get() = Angle.degrees(this)

/** Validated progress along a drive step. */
@JvmInline
value class PathProgress private constructor(val fraction: Double) {
    init {
        require(fraction.isFinite() && fraction in 0.0..1.0) {
            "Path progress must be between 0% and 100%"
        }
    }

    companion object {
        fun percent(value: Number): PathProgress = PathProgress(value.toDouble() / 100.0)
        fun fraction(value: Number): PathProgress = PathProgress(value.toDouble())
    }
}

val Number.percent: PathProgress
    get() = PathProgress.percent(this)

/** Serializable field pose used by both the GUI and Kotlin DSL. */
data class AutoPose(
    val xMeters: Double,
    val yMeters: Double,
    val headingRadians: Double
) {
    init {
        require(xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()) {
            "Auto pose must contain only finite values"
        }
    }
}

enum class AutoStepKind {
    DRIVE,
    COMMAND,
    WAIT,
    TOGETHER,
    FIRST_TO_FINISH
}

data class AutoMarker(
    val progress: Double,
    val commandKey: String
)

data class AutoDriveStep(
    val target: AutoPose,
    val preset: TrajectoryPreset = TrajectoryPreset.BALANCED,
    val preferredEngine: TrajectoryEngine? = null,
    val markers: List<AutoMarker> = emptyList(),
    val duringCommands: List<String> = emptyList(),
    val arrivalCommands: List<String> = emptyList()
)

/**
 * One serializable node in the ARES auto document.
 *
 * Nullable payloads keep the JSON schema simple and portable. [validateAutoRoutine] enforces the
 * exact payload required by each [kind] before deployment or execution.
 */
data class AutoStep(
    val kind: AutoStepKind,
    val drive: AutoDriveStep? = null,
    val commandKey: String? = null,
    val durationSeconds: Double? = null,
    val children: List<AutoStep> = emptyList()
) {
    companion object {
        fun drive(step: AutoDriveStep): AutoStep = AutoStep(AutoStepKind.DRIVE, drive = step)
        fun command(key: CommandKey): AutoStep = AutoStep(AutoStepKind.COMMAND, commandKey = key.value)
        fun wait(duration: Duration): AutoStep = AutoStep(
            AutoStepKind.WAIT,
            durationSeconds = duration.inWholeMilliseconds / 1000.0
        )
        fun together(children: List<AutoStep>): AutoStep = AutoStep(AutoStepKind.TOGETHER, children = children)
        fun firstToFinish(children: List<AutoStep>): AutoStep =
            AutoStep(AutoStepKind.FIRST_TO_FINISH, children = children)
    }
}

/** The canonical, versioned document edited by Analytics and executed by both robot leagues. */
data class AutoRoutine(
    val schemaVersion: Int = ARES_AUTO_SCHEMA_VERSION,
    val documentId: String,
    val revision: Int = 1,
    val parentContentHash: String? = null,
    val name: String,
    val startingPose: AutoPose,
    val steps: List<AutoStep>
)

@DslMarker
annotation class AresAutoDsl

/** Builds timeline steps without exposing document-level settings inside nested groups. */
@AresAutoDsl
open class AutoTimelineBuilder internal constructor() {
    private val steps = mutableListOf<AutoStep>()

    /** Drives from the preceding goal to a new field pose. */
    fun driveTo(
        x: Distance,
        y: Distance,
        heading: Angle,
        preset: TrajectoryPreset = TrajectoryPreset.BALANCED,
        block: AutoDriveStepBuilder.() -> Unit = {}
    ) {
        val drive = AutoDriveStepBuilder(
            target = AutoPose(x.meters, y.meters, heading.radians),
            preset = preset
        ).apply(block).build()
        steps += AutoStep.drive(drive)
    }

    /** Runs a capability advertised by the current robot. */
    fun run(command: CommandKey) {
        steps += AutoStep.command(command)
    }

    /** Waits without exposing millisecond primitives. */
    fun waitFor(duration: Duration) {
        require(duration.isFinite() && !duration.isNegative()) {
            "Wait duration must be finite and non-negative"
        }
        steps += AutoStep.wait(duration)
    }

    /** Runs every child and continues after all children finish. */
    fun together(block: AutoTimelineBuilder.() -> Unit) {
        steps += AutoStep.together(childSteps("together", block))
    }

    /** Runs every child and continues when the first child finishes. */
    fun firstToFinish(block: AutoTimelineBuilder.() -> Unit) {
        steps += AutoStep.firstToFinish(childSteps("firstToFinish", block))
    }

    internal fun snapshot(): List<AutoStep> = steps.toList()

    private fun childSteps(groupName: String, block: AutoTimelineBuilder.() -> Unit): List<AutoStep> {
        val child = AutoTimelineBuilder().apply(block)
        val childSteps = child.snapshot()
        require(childSteps.isNotEmpty()) { "$groupName must contain at least one step" }
        return childSteps
    }
}

/** Code representation of the same model created by the visual auto editor. */
@AresAutoDsl
class AutoRoutineBuilder internal constructor(private val name: String) : AutoTimelineBuilder() {
    private var startingPose: AutoPose? = null

    /** Declares the physical field pose used to seed localization before autonomous starts. */
    fun startAt(x: Distance, y: Distance, heading: Angle = 0.degrees) {
        check(startingPose == null) { "An auto may declare only one starting pose" }
        startingPose = AutoPose(x.meters, y.meters, heading.radians)
    }

    internal fun build(): AutoRoutine {
        val routine = AutoRoutine(
            documentId = autoDocumentId(name),
            name = name,
            startingPose = requireNotNull(startingPose) { "Auto '$name' must declare startAt(...)" },
            steps = snapshot()
        )
        val errors = validateAutoRoutine(routine).filter { it.severity == AutoValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        return routine
    }

}

@AresAutoDsl
class AutoDriveStepBuilder internal constructor(
    private val target: AutoPose,
    private val preset: TrajectoryPreset
) {
    private var preferredEngine: TrajectoryEngine? = null
    private val markers = mutableListOf<AutoMarker>()
    private val duringCommands = mutableListOf<String>()
    private val arrivalCommands = mutableListOf<String>()

    /** Pins this step to a specific engine. Omit this for automatic provider selection. */
    fun use(engine: TrajectoryEngine) {
        preferredEngine = engine
    }

    /** Starts [command] with the drive step and interrupts it when the drive completes. */
    fun during(command: CommandKey) {
        duringCommands += command.value
    }

    /** Triggers [command] once when the robot crosses [progress]. */
    fun at(progress: PathProgress, command: CommandKey) {
        markers += AutoMarker(progress.fraction, command.value)
    }

    /** Runs [command] after the robot reaches the target pose. */
    fun onArrival(command: CommandKey) {
        arrivalCommands += command.value
    }

    internal fun build(): AutoDriveStep = AutoDriveStep(
        target = target,
        preset = preset,
        preferredEngine = preferredEngine,
        markers = markers.toList(),
        duringCommands = duringCommands.toList(),
        arrivalCommands = arrivalCommands.toList()
    )
}

/** Builds an auto without exposing tasks, followers, Redux actions, or file schemas. */
fun autonomous(name: String, block: AutoRoutineBuilder.() -> Unit): AutoRoutine {
    require(name.isNotBlank()) { "Auto name must not be blank" }
    return AutoRoutineBuilder(name.trim()).apply(block).build()
}

enum class AutoValidationSeverity {
    WARNING,
    ERROR
}

data class AutoValidationIssue(
    val severity: AutoValidationSeverity,
    val path: String,
    val code: String,
    val message: String
)

/** Validates documents loaded from disk before they can reach a robot. */
fun validateAutoRoutine(routine: AutoRoutine): List<AutoValidationIssue> {
    val issues = mutableListOf<AutoValidationIssue>()
    if (routine.schemaVersion != ARES_AUTO_SCHEMA_VERSION) {
        issues += autoError(
            "auto",
            "unsupported_schema",
            "Auto schema ${routine.schemaVersion} is not supported; expected $ARES_AUTO_SCHEMA_VERSION"
        )
    }
    if (routine.name.isBlank()) {
        issues += autoError("auto", "missing_name", "Auto name must not be blank")
    }
    if (!routine.documentId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
        issues += autoError(
            "documentId",
            "invalid_document_id",
            "Document ID must be a filesystem-safe lowercase identifier"
        )
    }
    if (routine.revision < 1) {
        issues += autoError("revision", "invalid_revision", "Revision must be at least 1")
    }
    if (routine.parentContentHash != null && !routine.parentContentHash.matches(Regex("[a-f0-9]{64}"))) {
        issues += autoError(
            "parentContentHash",
            "invalid_parent_hash",
            "Parent content hash must be a lowercase SHA-256 value"
        )
    }
    if (!routine.startingPose.isFinite()) {
        issues += autoError("startingPose", "invalid_start_pose", "Starting pose must contain finite values")
    }
    if (routine.steps.isEmpty()) {
        issues += autoError("steps", "empty_auto", "Add at least one action or drive goal")
    }
    validateSteps(routine.steps, "steps", issues)
    return issues
}

private fun validateSteps(
    steps: List<AutoStep>,
    parentPath: String,
    issues: MutableList<AutoValidationIssue>
) {
    steps.forEachIndexed { index, step ->
        val path = "$parentPath[$index]"
        when (step.kind) {
            AutoStepKind.DRIVE -> {
                validatePayload(
                    path,
                    step.commandKey == null && step.durationSeconds == null && step.children.isEmpty(),
                    issues
                )
                val drive = step.drive
                if (drive == null) {
                    issues += autoError(path, "missing_drive", "Drive step is missing its target")
                } else {
                    if (!drive.target.isFinite()) {
                        issues += autoError(path, "invalid_target", "Drive target must contain finite values")
                    }
                    validateCommandKeys(drive.duringCommands, "$path.during", issues)
                    validateCommandKeys(drive.arrivalCommands, "$path.onArrival", issues)
                    drive.markers.forEachIndexed { markerIndex, marker ->
                        if (!marker.progress.isFinite() || marker.progress !in 0.0..1.0) {
                            issues += autoError(
                                "$path.markers[$markerIndex]",
                                "invalid_progress",
                                "Marker progress must be between 0% and 100%"
                            )
                        }
                        validateCommandKeys(
                            listOf(marker.commandKey),
                            "$path.markers[$markerIndex]",
                            issues
                        )
                    }
                }
            }

            AutoStepKind.COMMAND -> {
                validatePayload(
                    path,
                    step.drive == null && step.durationSeconds == null && step.children.isEmpty(),
                    issues
                )
                validateCommandKeys(listOfNotNull(step.commandKey), path, issues)
                if (step.commandKey == null) {
                    issues += autoError(path, "missing_command", "Action step must select a robot capability")
                }
            }

            AutoStepKind.WAIT -> {
                validatePayload(
                    path,
                    step.drive == null && step.commandKey == null && step.children.isEmpty(),
                    issues
                )
                if (step.durationSeconds == null || !step.durationSeconds.isFinite() || step.durationSeconds < 0.0) {
                    issues += autoError(path, "invalid_wait", "Wait duration must be finite and non-negative")
                }
            }

            AutoStepKind.TOGETHER,
            AutoStepKind.FIRST_TO_FINISH -> {
                validatePayload(
                    path,
                    step.drive == null && step.commandKey == null && step.durationSeconds == null,
                    issues
                )
                if (step.children.isEmpty()) {
                    issues += autoError(path, "empty_group", "${step.kind.name} must contain at least one step")
                }
                validateSteps(step.children, "$path.children", issues)
            }
        }
    }
}

private fun validatePayload(
    path: String,
    isValid: Boolean,
    issues: MutableList<AutoValidationIssue>
) {
    if (!isValid) {
        issues += autoError(
            path,
            "conflicting_payload",
            "${path.substringAfterLast('.')} contains fields that do not belong to its step type"
        )
    }
}

private fun validateCommandKeys(
    keys: List<String>,
    path: String,
    issues: MutableList<AutoValidationIssue>
) {
    keys.forEach { key ->
        if (runCatching { CommandKey(key) }.isFailure) {
            issues += autoError(path, "invalid_command_key", "'$key' is not a valid robot capability key")
        }
    }
}

private fun autoError(path: String, code: String, message: String): AutoValidationIssue =
    AutoValidationIssue(AutoValidationSeverity.ERROR, path, code, message)

private fun AutoPose.isFinite(): Boolean =
    xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()

/** JSON codec for the native `.aresauto` format. External formats are handled by adapters. */
object AresAutoCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(routine: AutoRoutine): String {
        val errors = validateAutoRoutine(routine).filter { it.severity == AutoValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        return gson.toJson(routine)
    }

    fun decode(json: String): AutoRoutine {
        val routine = try {
            // FTC SDK currently supplies Gson 2.8.x at runtime. The instance API is available
            // there and in newer desktop Gson versions; the static parseString API is not.
            parseRoutine(JsonParser().parse(json).asJsonObject)
        } catch (error: Exception) {
            throw IllegalArgumentException("Auto document is not valid JSON: ${error.message}", error)
        }
        val errors = try {
            validateAutoRoutine(routine).filter { it.severity == AutoValidationSeverity.ERROR }
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Auto document is missing required fields", error)
        }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        return routine
    }

    private fun parseRoutine(root: JsonObject): AutoRoutine {
        root.requireOnly(
            "schemaVersion", "documentId", "revision", "parentContentHash", "name", "startingPose", "steps"
        )
        return AutoRoutine(
            schemaVersion = root.intOrDefault("schemaVersion", ARES_AUTO_SCHEMA_VERSION),
            documentId = root.requiredString("documentId"),
            revision = root.intOrDefault("revision", 1),
            parentContentHash = root.optionalString("parentContentHash"),
            name = root.requiredString("name"),
            startingPose = parsePose(root.requiredObject("startingPose")),
            steps = root.requiredArray("steps").mapIndexed { index, element ->
                require(element.isJsonObject) { "steps[$index] must be an object" }
                parseStep(element.asJsonObject, "steps[$index]")
            }
        )
    }

    private fun parseStep(json: JsonObject, path: String): AutoStep {
        val kind = enumValue<AutoStepKind>(json.requiredString("kind"), "$path.kind")
        return when (kind) {
            AutoStepKind.DRIVE -> {
                json.requireOnly("kind", "drive", "children")
                json.requireEmptyArrayIfPresent("children")
                AutoStep.drive(parseDrive(json.requiredObject("drive"), "$path.drive"))
            }
            AutoStepKind.COMMAND -> {
                json.requireOnly("kind", "commandKey", "children")
                json.requireEmptyArrayIfPresent("children")
                AutoStep.command(CommandKey(json.requiredString("commandKey")))
            }
            AutoStepKind.WAIT -> {
                json.requireOnly("kind", "durationSeconds", "children")
                json.requireEmptyArrayIfPresent("children")
                AutoStep(kind = kind, durationSeconds = json.requiredDouble("durationSeconds"))
            }
            AutoStepKind.TOGETHER,
            AutoStepKind.FIRST_TO_FINISH -> {
                json.requireOnly("kind", "children")
                val children = json.requiredArray("children").mapIndexed { index, element ->
                    require(element.isJsonObject) { "$path.children[$index] must be an object" }
                    parseStep(element.asJsonObject, "$path.children[$index]")
                }
                AutoStep(kind = kind, children = children)
            }
        }
    }

    private fun parseDrive(json: JsonObject, path: String): AutoDriveStep {
        json.requireOnly("target", "preset", "preferredEngine", "markers", "duringCommands", "arrivalCommands")
        return AutoDriveStep(
            target = parsePose(json.requiredObject("target")),
            preset = json.optionalString("preset")?.let {
                enumValue<TrajectoryPreset>(it, "$path.preset")
            } ?: TrajectoryPreset.BALANCED,
            preferredEngine = json.optionalString("preferredEngine")?.let {
                enumValue<TrajectoryEngine>(it, "$path.preferredEngine")
            },
            markers = json.optionalArray("markers")?.mapIndexed { index, element ->
                require(element.isJsonObject) { "$path.markers[$index] must be an object" }
                val marker = element.asJsonObject
                marker.requireOnly("progress", "commandKey")
                AutoMarker(marker.requiredDouble("progress"), marker.requiredString("commandKey"))
            } ?: emptyList(),
            duringCommands = json.stringArrayOrEmpty("duringCommands"),
            arrivalCommands = json.stringArrayOrEmpty("arrivalCommands")
        )
    }

    private fun parsePose(json: JsonObject): AutoPose {
        json.requireOnly("xMeters", "yMeters", "headingRadians")
        return AutoPose(
            xMeters = json.requiredDouble("xMeters"),
            yMeters = json.requiredDouble("yMeters"),
            headingRadians = json.requiredDouble("headingRadians")
        )
    }

    private fun JsonObject.requireOnly(vararg allowed: String) {
        // JsonObject.keySet() is absent from the Gson version bundled by the FTC SDK.
        val unexpected = entrySet().mapTo(mutableSetOf()) { it.key } - allowed.toSet()
        require(unexpected.isEmpty()) { "Unexpected field(s): ${unexpected.joinToString()}" }
    }

    private fun JsonObject.requireEmptyArrayIfPresent(name: String) {
        val value = get(name) ?: return
        require(value.isJsonArray && value.asJsonArray.size() == 0) {
            "'$name' does not belong to this step type"
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        requireNotNull(get(name)) { "Missing '$name'" }.asString

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.requiredDouble(name: String): Double =
        requireNotNull(get(name)) { "Missing '$name'" }.asDouble

    private fun JsonObject.requiredObject(name: String): JsonObject {
        val value = requireNotNull(get(name)) { "Missing '$name'" }
        require(value.isJsonObject) { "'$name' must be an object" }
        return value.asJsonObject
    }

    private fun JsonObject.requiredArray(name: String) =
        requireNotNull(optionalArray(name)) { "Missing '$name'" }

    private fun JsonObject.optionalArray(name: String) = get(name)?.takeUnless { it.isJsonNull }?.let { value ->
        require(value.isJsonArray) { "'$name' must be an array" }
        value.asJsonArray
    }

    private fun JsonObject.stringArrayOrEmpty(name: String): List<String> =
        optionalArray(name)?.map { element -> element.asString } ?: emptyList()

    private fun JsonObject.intOrDefault(name: String, default: Int): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: default

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown $path value '$value'")

    /** Stable lowercase SHA-256 used to link local revisions and detect external edits. */
    fun contentHash(routine: AutoRoutine): String {
        val bytes = encode(routine).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** Carries identity forward and links a modified document to its previous saved content. */
    fun nextRevision(previous: AutoRoutine, modified: AutoRoutine): AutoRoutine = modified.copy(
        schemaVersion = ARES_AUTO_SCHEMA_VERSION,
        documentId = previous.documentId,
        revision = previous.revision + 1,
        parentContentHash = contentHash(previous)
    )
}

private fun autoDocumentId(name: String): String {
    val normalized = name.trim().lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(64)
    return normalized.ifEmpty { "auto" }
}
