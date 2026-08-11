package com.areslib.subsystem

import com.google.gson.GsonBuilder
import java.security.MessageDigest

const val ARES_SUBSYSTEM_SCHEMA_VERSION: Int = 3

enum class SubsystemPlatform { FTC, FRC }

/** Hardware categories supported by the generated, cached IO boundary. */
enum class SubsystemHardwareKind {
    MOTOR,
    POSITIONAL_SERVO,
    CONTINUOUS_SERVO,
    DIGITAL_INPUT,
    ANALOG_INPUT,
    COLOR_SENSOR,
}

/** Explicit cached signal read from one hardware device. */
enum class SubsystemMeasurementSource {
    MOTOR_POSITION_NATIVE,
    MOTOR_VELOCITY_NATIVE_PER_SECOND,
    MOTOR_CURRENT_AMPS,
    DIGITAL_STATE,
    ANALOG_VOLTAGE,
    COLOR_ARGB,
}

enum class SubsystemValueType { DOUBLE, BOOLEAN, INT, STRING }

enum class SubsystemFieldRole { TARGET, MEASUREMENT, STATUS, CONFIGURATION }

enum class SubsystemControlStrategy {
    /** Target value is applied directly after clamping. */
    DIRECT,

    POSITION_PID,
    VELOCITY_PID,
    BANG_BANG,
    SERVO_POSITION,
}

/** Platform connection data. Only the fields required by the selected platform are populated. */
data class SubsystemHardwareConnection(
    val hardwareMapName: String? = null,
    val canId: Int? = null,
    val canBus: String = "rio",
    val channel: Int? = null,
)

data class SubsystemHardwareDocument(
    val hardwareId: String,
    val displayName: String,
    val kind: SubsystemHardwareKind,
    val connection: SubsystemHardwareConnection = SubsystemHardwareConnection(),
    val required: Boolean = true,
    val inverted: Boolean = false,
    /** State field populated by this device during the cached sensor-read phase. */
    val measurementFieldId: String? = null,
    val measurementSource: SubsystemMeasurementSource? = null,
    /** `stateValue = rawHardwareValue * measurementScale + measurementOffset`. */
    val measurementScale: Double = 1.0,
    val measurementOffset: Double = 0.0,
    val currentLimitAmps: Double? = null,
)

/** A typed state value. Raw Kotlin expressions are deliberately not accepted. */
data class SubsystemStateFieldDocument(
    val fieldId: String,
    val displayName: String,
    val type: SubsystemValueType,
    val role: SubsystemFieldRole,
    val unit: String? = null,
    val defaultNumber: Double? = null,
    val defaultBoolean: Boolean? = null,
    val defaultInt: Int? = null,
    val defaultText: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
)

data class SubsystemControlLoopDocument(
    val loopId: String,
    val displayName: String,
    val strategy: SubsystemControlStrategy,
    val actuatorId: String,
    val targetFieldId: String,
    val measurementFieldId: String? = null,
    val kP: Double = 0.0,
    val kI: Double = 0.0,
    val kD: Double = 0.0,
    val kS: Double = 0.0,
    val kV: Double = 0.0,
    /** First-order derivative filter time constant; zero disables filtering. */
    val derivativeFilterTimeConstantSeconds: Double = 0.02,
    val tolerance: Double = 0.0,
    val minimumOutput: Double = -12.0,
    val maximumOutput: Double = 12.0,
)

/**
 * Canonical subsystem authoring document stored in `.ares/subsystems`.
 *
 * The document describes hardware ownership, immutable Redux state, and output control. Generated
 * Kotlin remains deterministic and platform-specific while this model stays independent of the
 * FTC SDK, WPILib, and vendor libraries.
 */
data class SubsystemDocument(
    val schemaVersion: Int = ARES_SUBSYSTEM_SCHEMA_VERSION,
    val documentId: String,
    val name: String,
    val description: String = "",
    val platform: SubsystemPlatform,
    val revision: Int = 1,
    val parentContentHash: String? = null,
    val hardware: List<SubsystemHardwareDocument> = emptyList(),
    val stateFields: List<SubsystemStateFieldDocument> = emptyList(),
    val controlLoops: List<SubsystemControlLoopDocument> = emptyList(),
    /** Required failures abort robot initialization; optional failures are reported and skipped. */
    val requiredAtStartup: Boolean = true,
    val generateMockIo: Boolean = true,
    val generateTest: Boolean = true,
)

data class SubsystemValidationIssue(val path: String, val message: String)

fun validateSubsystemDocument(document: SubsystemDocument): List<SubsystemValidationIssue> = buildList {
    fun issue(path: String, message: String) {
        add(SubsystemValidationIssue(path, message))
    }

    if (document.schemaVersion != ARES_SUBSYSTEM_SCHEMA_VERSION) {
        issue("schemaVersion", "Unsupported subsystem schema ${document.schemaVersion}")
    }
    if (!document.documentId.matches(STABLE_ID)) {
        issue("documentId", "Document ID must be a stable lowercase key")
    } else if (!document.documentId.replace('-', '_').isUsableKotlinIdentifier()) {
        issue("documentId", "Document ID would create a Kotlin keyword package")
    }
    if (!document.name.matches(PASCAL_CASE)) {
        issue("name", "Subsystem class name must use PascalCase")
    }
    if (document.revision < 1) issue("revision", "Revision must be positive")
    if (document.parentContentHash != null && !document.parentContentHash.matches(SHA_256)) {
        issue("parentContentHash", "Parent content hash must be SHA-256")
    }
    if (document.hardware.isEmpty()) issue("hardware", "Add at least one hardware device")
    if (document.stateFields.isEmpty()) issue("stateFields", "Add at least one state field")
    if (document.generateTest && !document.generateMockIo) {
        issue("generateTest", "Generated starter tests require mock IO")
    }

    duplicateIds(document.hardware.map { it.hardwareId }).forEach {
        issue("hardware", "Hardware ID '$it' is duplicated")
    }
    duplicateIds(document.stateFields.map { it.fieldId }).forEach {
        issue("stateFields", "State field ID '$it' is duplicated")
    }
    duplicateIds(document.controlLoops.map { it.loopId }).forEach {
        issue("controlLoops", "Control loop ID '$it' is duplicated")
    }

    val hardwareById = document.hardware.associateBy { it.hardwareId }
    val fieldsById = document.stateFields.associateBy { it.fieldId }

    document.hardware.forEachIndexed { index, device ->
        val path = "hardware[$index]"
        if (!device.hardwareId.isUsableKotlinIdentifier()) issue("$path.hardwareId", "Hardware ID must be a Kotlin identifier, not a keyword")
        if (device.displayName.isBlank()) issue("$path.displayName", "Hardware display name is required")
        when (document.platform) {
            SubsystemPlatform.FTC -> {
                if (device.connection.hardwareMapName.isNullOrBlank()) {
                    issue("$path.connection.hardwareMapName", "FTC hardware requires a hardware-map name")
                }
                if (device.connection.canId != null || device.connection.channel != null) {
                    issue("$path.connection", "FTC hardware must not use FRC CAN/channel addressing")
                }
                if (device.currentLimitAmps != null) {
                    issue("$path.currentLimitAmps", "FTC DcMotorEx cannot enforce a controller current limit; use a current safety rule instead")
                }
            }
            SubsystemPlatform.FRC -> when (device.kind) {
                SubsystemHardwareKind.MOTOR -> if (device.connection.canId == null || device.connection.canId !in 0..62) {
                    issue("$path.connection.canId", "FRC motors require a CAN ID from 0 to 62")
                }
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.CONTINUOUS_SERVO,
                SubsystemHardwareKind.DIGITAL_INPUT,
                SubsystemHardwareKind.ANALOG_INPUT -> if (device.connection.channel == null || device.connection.channel !in 0..31) {
                    issue("$path.connection.channel", "FRC channel must be from 0 to 31")
                }
                SubsystemHardwareKind.COLOR_SENSOR ->
                    issue("$path.kind", "Generated FRC color-sensor wiring is not supported yet")
            }
        }
        device.currentLimitAmps?.let { limit ->
            if (!limit.isFinite() || limit <= 0.0) issue("$path.currentLimitAmps", "Current limit must be finite and positive")
            if (device.kind != SubsystemHardwareKind.MOTOR) issue("$path.currentLimitAmps", "Only motors use a current limit")
        }
        if (!device.measurementScale.isFinite() || !device.measurementOffset.isFinite()) {
            issue("$path.measurementScale", "Measurement conversion must be finite")
        }
        if (device.measurementFieldId == null && device.measurementSource != null) {
            issue("$path.measurementSource", "A measurement source requires a cached state field")
        }
        if (device.measurementFieldId == null &&
            (device.measurementScale != 1.0 || device.measurementOffset != 0.0)
        ) {
            issue("$path.measurementScale", "Measurement conversion requires a cached state field")
        }
        device.measurementFieldId?.let { fieldId ->
            val field = fieldsById[fieldId]
            if (field == null) {
                issue("$path.measurementFieldId", "Unknown measurement field '$fieldId'")
            } else if (device.measurementSource == null) {
                issue("$path.measurementSource", "Choose the exact hardware signal stored in '$fieldId'")
            } else if (field.role != SubsystemFieldRole.MEASUREMENT && field.role != SubsystemFieldRole.STATUS) {
                issue("$path.measurementFieldId", "Hardware measurements must write a measurement or status field")
            } else {
                val source = requireNotNull(device.measurementSource)
                if (source !in device.kind.compatibleMeasurementSources()) {
                    issue("$path.measurementSource", "$source cannot be read from ${device.kind}")
                }
                val requiredType = source.valueType()
                if (field.type != requiredType) {
                    issue("$path.measurementFieldId", "$source measurements require a ${requiredType.name} field")
                }
                if (requiredType != SubsystemValueType.DOUBLE &&
                    (device.measurementScale != 1.0 || device.measurementOffset != 0.0)
                ) {
                    issue("$path.measurementScale", "Only numeric double measurements use scale and offset")
                }
            }
        }
    }

    document.stateFields.forEachIndexed { index, field ->
        val path = "stateFields[$index]"
        if (!field.fieldId.isUsableKotlinIdentifier()) issue("$path.fieldId", "State field ID must be a Kotlin identifier, not a keyword")
        if (field.displayName.isBlank()) issue("$path.displayName", "State field display name is required")
        if (field.unit?.isBlank() == true) issue("$path.unit", "Unit must be omitted or non-blank")
        field.minimum?.let { if (!it.isFinite()) issue("$path.minimum", "Minimum must be finite") }
        field.maximum?.let { if (!it.isFinite()) issue("$path.maximum", "Maximum must be finite") }
        if (field.minimum != null && field.maximum != null && field.minimum > field.maximum) {
            issue(path, "Minimum cannot exceed maximum")
        }
        when (field.type) {
            SubsystemValueType.DOUBLE -> {
                val value = field.defaultNumber
                if (value == null || !value.isFinite()) issue("$path.defaultNumber", "Double fields require a finite default")
                if (field.defaultBoolean != null || field.defaultInt != null || field.defaultText != null) {
                    issue(path, "Double field contains a default for another type")
                }
                if (value != null && field.minimum != null && value < field.minimum) issue(path, "Default is below the minimum")
                if (value != null && field.maximum != null && value > field.maximum) issue(path, "Default is above the maximum")
            }
            SubsystemValueType.BOOLEAN -> {
                if (field.defaultBoolean == null) issue("$path.defaultBoolean", "Boolean fields require a default")
                if (field.defaultNumber != null || field.defaultInt != null || field.defaultText != null) issue(path, "Boolean field contains a default for another type")
                if (field.minimum != null || field.maximum != null) issue(path, "Boolean fields cannot have numeric limits")
            }
            SubsystemValueType.INT -> {
                if (field.defaultInt == null) issue("$path.defaultInt", "Int fields require a default")
                if (field.defaultNumber != null || field.defaultBoolean != null || field.defaultText != null) issue(path, "Int field contains a default for another type")
            }
            SubsystemValueType.STRING -> {
                if (field.defaultText == null) issue("$path.defaultText", "String fields require a default")
                if (field.defaultNumber != null || field.defaultBoolean != null || field.defaultInt != null) issue(path, "String field contains a default for another type")
                if (field.minimum != null || field.maximum != null) issue(path, "String fields cannot have numeric limits")
            }
        }
    }

    document.controlLoops.forEachIndexed { index, loop ->
        val path = "controlLoops[$index]"
        if (!loop.loopId.isUsableKotlinIdentifier()) issue("$path.loopId", "Control loop ID must be a Kotlin identifier, not a keyword")
        if (loop.displayName.isBlank()) issue("$path.displayName", "Control loop display name is required")
        val actuator = hardwareById[loop.actuatorId]
        if (actuator == null) {
            issue("$path.actuatorId", "Unknown actuator '${loop.actuatorId}'")
        } else if (actuator.kind !in ACTUATOR_KINDS) {
            issue("$path.actuatorId", "Selected hardware is a sensor, not an actuator")
        }
        val target = fieldsById[loop.targetFieldId]
        if (target == null) {
            issue("$path.targetFieldId", "Unknown target field '${loop.targetFieldId}'")
        } else {
            if (target.role != SubsystemFieldRole.TARGET && target.role != SubsystemFieldRole.CONFIGURATION) {
                issue("$path.targetFieldId", "Control targets must use a target or configuration field")
            }
            if (target.type !in NUMERIC_TYPES) issue("$path.targetFieldId", "Control targets must be numeric")
        }
        val needsMeasurement = loop.strategy in CLOSED_LOOP_STRATEGIES
        val measurement = loop.measurementFieldId?.let(fieldsById::get)
        if (needsMeasurement && measurement == null) issue("$path.measurementFieldId", "This strategy requires a measurement field")
        if (measurement != null && measurement.type !in NUMERIC_TYPES) issue("$path.measurementFieldId", "Control measurements must be numeric")
        if (loop.strategy == SubsystemControlStrategy.SERVO_POSITION && actuator?.kind != SubsystemHardwareKind.POSITIONAL_SERVO) {
            issue("$path.strategy", "Servo-position control requires a positional servo")
        }
        if (loop.strategy != SubsystemControlStrategy.SERVO_POSITION && actuator?.kind == SubsystemHardwareKind.POSITIONAL_SERVO) {
            issue("$path.strategy", "Positional servos require servo-position control")
        }
        listOf(
            loop.kP,
            loop.kI,
            loop.kD,
            loop.kS,
            loop.kV,
            loop.derivativeFilterTimeConstantSeconds,
            loop.tolerance,
            loop.minimumOutput,
            loop.maximumOutput,
        )
            .forEach { value -> if (!value.isFinite()) issue(path, "Controller values must be finite") }
        if (loop.derivativeFilterTimeConstantSeconds < 0.0) {
            issue("$path.derivativeFilterTimeConstantSeconds", "Derivative filter time cannot be negative")
        }
        if (loop.tolerance < 0.0) issue("$path.tolerance", "Tolerance cannot be negative")
        if (loop.minimumOutput >= loop.maximumOutput) issue(path, "Minimum output must be below maximum output")
    }

    document.hardware.filter { it.kind in ACTUATOR_KINDS }.forEach { actuator ->
        if (document.controlLoops.none { it.actuatorId == actuator.hardwareId }) {
            issue("hardware.${actuator.hardwareId}", "Actuator '${actuator.displayName}' is not controlled by any loop")
        }
    }
}

object SubsystemDocumentCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: SubsystemDocument): String {
        requireValid(document)
        return gson.toJson(document)
    }

    fun decode(json: String): SubsystemDocument {
        val document = try {
            gson.fromJson(json, SubsystemDocument::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Subsystem document is not valid JSON: ${error.message}", error)
        } ?: throw IllegalArgumentException("Subsystem document is empty")
        requireValid(document)
        return document
    }

    fun contentHash(document: SubsystemDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireValid(document: SubsystemDocument) {
        val issues = validateSubsystemDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
    }
}

private val STABLE_ID = Regex("[a-z][a-z0-9-]{0,63}")
private val PASCAL_CASE = Regex("[A-Z][A-Za-z0-9]{0,63}")
private val KOTLIN_IDENTIFIER = Regex("[a-z][A-Za-z0-9_]{0,63}")
private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
    "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
    "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
    "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
    "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public",
    "reified", "sealed", "suspend", "tailrec", "vararg",
)
private val SHA_256 = Regex("[a-f0-9]{64}")
private val ACTUATOR_KINDS = setOf(
    SubsystemHardwareKind.MOTOR,
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
)
private val NUMERIC_TYPES = setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)
private val CLOSED_LOOP_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.VELOCITY_PID,
    SubsystemControlStrategy.BANG_BANG,
)

fun SubsystemHardwareKind.compatibleMeasurementSources(): List<SubsystemMeasurementSource> = when (this) {
    SubsystemHardwareKind.MOTOR -> listOf(
        SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
        SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
        SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
    )
    SubsystemHardwareKind.DIGITAL_INPUT -> listOf(SubsystemMeasurementSource.DIGITAL_STATE)
    SubsystemHardwareKind.ANALOG_INPUT -> listOf(SubsystemMeasurementSource.ANALOG_VOLTAGE)
    SubsystemHardwareKind.COLOR_SENSOR -> listOf(SubsystemMeasurementSource.COLOR_ARGB)
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO -> emptyList()
}

fun SubsystemMeasurementSource.valueType(): SubsystemValueType = when (this) {
    SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
    SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
    SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
    SubsystemMeasurementSource.ANALOG_VOLTAGE -> SubsystemValueType.DOUBLE
    SubsystemMeasurementSource.DIGITAL_STATE -> SubsystemValueType.BOOLEAN
    SubsystemMeasurementSource.COLOR_ARGB -> SubsystemValueType.INT
}

private fun duplicateIds(ids: List<String>): Set<String> {
    val seen = hashSetOf<String>()
    return ids.filterNot(seen::add).toSet()
}

private fun String.isUsableKotlinIdentifier(): Boolean = matches(KOTLIN_IDENTIFIER) && this !in KOTLIN_KEYWORDS
