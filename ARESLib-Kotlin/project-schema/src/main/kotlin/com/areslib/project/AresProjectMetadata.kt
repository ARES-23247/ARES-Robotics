package com.areslib.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.security.MessageDigest

const val ARES_PROJECT_METADATA_SCHEMA_VERSION: Int = 4

enum class AresLeague { FTC, FRC }

/** Which source is authoritative when Studio and project-owned Kotlin coexist. */
enum class AresProjectAuthoringModel {
    /** Canonical `.ares` documents own the robot; Kotlin runtime and tests are generated. */
    GUI_OWNED,

    /** Team-owned Kotlin owns robot behavior; Studio consumes explicit registration metadata. */
    CODE_FIRST,

    /** `.ares` owns drivetrain/routines while registered team Kotlin owns selected mechanisms. */
    HYBRID,
}

/** Human and competition identity stored with the stable project ID in `.ares/project.json`. */
data class AresProjectIdentityDocument(
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val displayName: String,
)

/** FTC hub-command transport selected by the canonical project document. */
enum class AresFtcHubCommandTransport {
    /** Use only the supported FTC SDK command path. Recommended for new teams and first bring-up. */
    STANDARD_SDK,

    /** Use ARES' experimental direct REV Hub motor-write path, with SDK fallback on every failure. */
    ARES_PHOTON,
}

/** FTC-only runtime choices that materially change robot-process behavior. */
data class AresFtcRuntimeOptionsDocument(
    val hubCommandTransport: AresFtcHubCommandTransport = AresFtcHubCommandTransport.STANDARD_SDK,
    /** Start the bounded Control-Hub-to-Limelight HTTP proxy while the robot facade is alive. */
    val limelightProxyEnabled: Boolean = false,
)

/** Platform-specific runtime choices. Unsupported platform sections are rejected, not ignored. */
data class AresRuntimeOptionsDocument(
    val ftc: AresFtcRuntimeOptionsDocument? = null,
)

/** Coordinate frame used by authored routine poses. Headings are CCW-positive in every frame. */
enum class AresCoordinateConvention {
    /** Origin at field center; FTC alliance transforms rotate 180 degrees around that origin. */
    CENTER_ORIGIN_CCW,

    /** Origin at the blue-alliance field corner; FRC alliance transforms reflect field X. */
    BLUE_CORNER_ORIGIN_CCW,
}

/**
 * Canonical project geometry shared by the editor, generator, simulator, and robot preflight.
 *
 * Keeping these values in `.ares/project.json` prevents two laptops from validating the same
 * routine with different machine-local workspace settings.
 */
data class AresProjectMetadataDocument(
    val schemaVersion: Int = ARES_PROJECT_METADATA_SCHEMA_VERSION,
    val projectId: String,
    val identity: AresProjectIdentityDocument,
    val league: AresLeague,
    val coordinateConvention: AresCoordinateConvention,
    val robotLengthMeters: Double,
    val robotWidthMeters: Double,
    val fieldLengthMeters: Double,
    val fieldWidthMeters: Double,
    val authoringModel: AresProjectAuthoringModel = AresProjectAuthoringModel.GUI_OWNED,
    val runtimeOptions: AresRuntimeOptionsDocument = AresRuntimeOptionsDocument(),
)

/** Resolves the explicit FTC policy, defaulting legacy/in-memory documents to the safe SDK path. */
fun AresProjectMetadataDocument.resolvedFtcRuntimeOptions(): AresFtcRuntimeOptionsDocument =
    runtimeOptions.ftc ?: AresFtcRuntimeOptionsDocument()

fun validateAresProjectMetadata(document: AresProjectMetadataDocument): List<String> = buildList {
    if (document.schemaVersion != ARES_PROJECT_METADATA_SCHEMA_VERSION) {
        add("Unsupported project metadata schema ${document.schemaVersion}")
    }
    if (!document.projectId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        add("Project ID must be a stable key")
    }
    if (!document.identity.teamId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
        add("Team ID must be a stable team key")
    }
    if (!document.identity.seasonId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
        add("Season ID must be a stable season key")
    }
    if (!document.identity.robotId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        add("Robot ID must be a stable key")
    }
    if (document.identity.displayName.isBlank() || document.identity.displayName.length > 80) {
        add("Robot display name must contain 1 to 80 characters")
    }
    val dimensions = listOf(
        "robotLengthMeters" to document.robotLengthMeters,
        "robotWidthMeters" to document.robotWidthMeters,
        "fieldLengthMeters" to document.fieldLengthMeters,
        "fieldWidthMeters" to document.fieldWidthMeters,
    )
    dimensions.forEach { (name, value) ->
        if (!value.isFinite() || value <= 0.0) add("$name must be finite and positive")
    }
    if (document.robotLengthMeters > document.fieldLengthMeters ||
        document.robotWidthMeters > document.fieldWidthMeters
    ) {
        add("Robot footprint must fit inside the field")
    }
    when (document.league) {
        AresLeague.FTC -> if (document.coordinateConvention != AresCoordinateConvention.CENTER_ORIGIN_CCW) {
            add("FTC projects must use CENTER_ORIGIN_CCW")
        }
        AresLeague.FRC -> if (document.coordinateConvention != AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW) {
            add("FRC projects must use BLUE_CORNER_ORIGIN_CCW")
        }
    }
    if (document.league == AresLeague.FRC && document.runtimeOptions.ftc != null) {
        add("FRC projects cannot declare FTC runtime options")
    }
}

object AresProjectMetadataCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: AresProjectMetadataDocument): String {
        val normalized = normalize(document)
        requireValid(normalized)
        return gson.toJson(normalized)
    }

    fun decode(json: String): AresProjectMetadataDocument {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Project metadata is not valid JSON: ${error.message}", error)
        }
        val schemaVersion = root.requiredInt("schemaVersion")
        require(schemaVersion == ARES_PROJECT_METADATA_SCHEMA_VERSION) {
            "Unsupported project metadata schema $schemaVersion; create or export a current ARES project"
        }
        val document = decodeCurrent(root)
        requireValid(document)
        return document
    }

    fun contentHash(document: AresProjectMetadataDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireValid(document: AresProjectMetadataDocument) {
        val issues = validateAresProjectMetadata(document)
        require(issues.isEmpty()) { issues.joinToString("; ") }
    }

    private fun normalize(document: AresProjectMetadataDocument): AresProjectMetadataDocument = when (document.league) {
        AresLeague.FTC -> document.copy(
            schemaVersion = ARES_PROJECT_METADATA_SCHEMA_VERSION,
            runtimeOptions = AresRuntimeOptionsDocument(ftc = document.resolvedFtcRuntimeOptions()),
        )
        AresLeague.FRC -> document.copy(schemaVersion = ARES_PROJECT_METADATA_SCHEMA_VERSION)
    }

    private fun decodeCurrent(root: JsonObject): AresProjectMetadataDocument {
        val league = root.requiredEnum<AresLeague>("league")
        val identity = root.requiredObject("identity").let { value ->
            AresProjectIdentityDocument(
                teamId = value.requiredString("teamId"),
                seasonId = value.requiredString("seasonId"),
                robotId = value.requiredString("robotId"),
                displayName = value.requiredString("displayName"),
            )
        }
        val runtime = root.requiredObject("runtimeOptions")
        val ftc = runtime.get("ftc")?.takeUnless { it.isJsonNull }?.let { element ->
            require(element.isJsonObject) { "Project metadata field 'runtimeOptions.ftc' must be an object or null" }
            val value = element.asJsonObject
            AresFtcRuntimeOptionsDocument(
                hubCommandTransport = value.requiredEnum("hubCommandTransport"),
                limelightProxyEnabled = value.requiredBoolean("limelightProxyEnabled"),
            )
        }
        require(league != AresLeague.FTC || ftc != null) {
            "FTC project metadata must declare 'runtimeOptions.ftc'"
        }
        val runtimeOptions = AresRuntimeOptionsDocument(ftc = ftc)
        return normalize(
            AresProjectMetadataDocument(
                schemaVersion = ARES_PROJECT_METADATA_SCHEMA_VERSION,
                projectId = root.requiredString("projectId"),
                identity = identity,
                league = league,
                coordinateConvention = root.requiredEnum("coordinateConvention"),
                robotLengthMeters = root.requiredDouble("robotLengthMeters"),
                robotWidthMeters = root.requiredDouble("robotWidthMeters"),
                fieldLengthMeters = root.requiredDouble("fieldLengthMeters"),
                fieldWidthMeters = root.requiredDouble("fieldWidthMeters"),
                authoringModel = root.requiredEnum("authoringModel"),
                runtimeOptions = runtimeOptions,
            ),
        )
    }
}

private fun JsonObject.requiredElement(name: String) = get(name)?.takeUnless { it.isJsonNull }
    ?: throw IllegalArgumentException("Project metadata is missing required field '$name'")

private fun JsonObject.requiredObject(name: String): JsonObject = requiredElement(name).let { element ->
    require(element.isJsonObject) { "Project metadata field '$name' must be an object" }
    element.asJsonObject
}

private fun JsonObject.requiredPrimitive(name: String): JsonPrimitive = requiredElement(name).let { element ->
    require(element.isJsonPrimitive) { "Project metadata field '$name' must be a primitive value" }
    element.asJsonPrimitive
}

private fun JsonObject.requiredString(name: String): String = requiredPrimitive(name).let { value ->
    require(value.isString) { "Project metadata field '$name' must be a string" }
    value.asString
}

private fun JsonObject.requiredBoolean(name: String): Boolean = requiredPrimitive(name).let { value ->
    require(value.isBoolean) { "Project metadata field '$name' must be a boolean" }
    value.asBoolean
}

private fun JsonObject.requiredInt(name: String): Int = requiredPrimitive(name).let { value ->
    require(value.isNumber) { "Project metadata field '$name' must be an integer" }
    runCatching { value.asBigDecimal.intValueExact() }
        .getOrElse { throw IllegalArgumentException("Project metadata field '$name' must be an integer", it) }
}

private fun JsonObject.requiredDouble(name: String): Double = requiredPrimitive(name).let { value ->
    require(value.isNumber) { "Project metadata field '$name' must be a number" }
    val result = value.asDouble
    require(result.isFinite()) { "Project metadata field '$name' must be a finite number" }
    result
}

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
    val raw = requiredString(name)
    return enumValues<T>().firstOrNull { it.name == raw }
        ?: throw IllegalArgumentException("Project metadata field '$name' has unsupported value '$raw'")
}
