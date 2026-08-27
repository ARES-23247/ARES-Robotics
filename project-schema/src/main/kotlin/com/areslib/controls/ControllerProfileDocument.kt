package com.areslib.controls

import com.google.gson.GsonBuilder
import java.security.MessageDigest

const val ARES_CONTROLLER_PROFILE_SCHEMA_VERSION: Int = 1

enum class ControllerSurfaceDocument { FRONT, REAR }

enum class ControllerControlTypeDocument { BUTTON, AXIS }

/** Input-frame adapter whose indexes a mapping targets. */
enum class ControllerInputPlatform {
    DESKTOP_GLFW,
    FTC,
    FRC
}

data class ControllerAnchorDocument(
    val x: Double,
    val y: Double
)

/** Optional USB/HID identity fields used for offline profile matching. */
data class ControllerDeviceMatcherDocument(
    val nameContains: String? = null,
    val guid: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val minimumAxisCount: Int? = null,
    val minimumButtonCount: Int? = null
)

/** One platform-specific zero-based [com.areslib.input.InputFrame] mapping. */
data class ControllerInputMappingDocument(
    val platform: ControllerInputPlatform,
    val buttonIndex: Int? = null,
    val axisIndex: Int? = null
)

/**
 * One friendly physical control and its platform-specific input-frame mappings.
 *
 * An empty mapping list is valid in an editor template. Code generation rejects a profile when an
 * enabled binding references a control that has not been learned for the target platform. Keeping
 * mappings separate is essential: GLFW, WPILib Driver Station, and FTC Android do not guarantee
 * that a vendor-specific button has the same HID index.
 */
data class ControllerControlDocument(
    val controlId: String,
    val displayName: String,
    val type: ControllerControlTypeDocument,
    val surface: ControllerSurfaceDocument = ControllerSurfaceDocument.FRONT,
    val anchor: ControllerAnchorDocument,
    val mappings: List<ControllerInputMappingDocument> = emptyList(),
    val aliases: List<String> = emptyList()
)

/** Serializable `.arescontroller` project profile, including vendor-specific extra buttons. */
data class ControllerProfileDocument(
    val schemaVersion: Int = ARES_CONTROLLER_PROFILE_SCHEMA_VERSION,
    val documentId: String,
    val revision: Int = 1,
    val parentContentHash: String? = null,
    val displayName: String,
    val deviceMatchers: List<ControllerDeviceMatcherDocument> = emptyList(),
    val controls: List<ControllerControlDocument>
)

fun validateControllerProfile(document: ControllerProfileDocument): List<ControlValidationIssue> {
    val issues = mutableListOf<ControlValidationIssue>()
    val keyPattern = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    if (document.schemaVersion != ARES_CONTROLLER_PROFILE_SCHEMA_VERSION) {
        issues += profileError("document", "unsupported_schema", "Unsupported controller profile schema ${document.schemaVersion}")
    }
    if (!document.documentId.matches(keyPattern)) {
        issues += profileError("documentId", "invalid_key", "Controller profile ID is not stable")
    }
    if (document.revision < 1) issues += profileError("revision", "invalid_revision", "Revision must be at least 1")
    if (document.displayName.isBlank()) issues += profileError("displayName", "missing_name", "Profile name is required")
    if (document.controls.isEmpty()) issues += profileError("controls", "missing_controls", "A profile requires controls")

    document.deviceMatchers.forEachIndexed { index, matcher ->
        val path = "deviceMatchers[$index]"
        if (matcher.nameContains?.isBlank() == true || matcher.guid?.isBlank() == true) {
            issues += profileError(path, "blank_matcher", "Matcher text cannot be blank")
        }
        if (matcher.vendorId != null && matcher.vendorId !in 0..0xffff ||
            matcher.productId != null && matcher.productId !in 0..0xffff ||
            matcher.minimumAxisCount != null && matcher.minimumAxisCount < 0 ||
            matcher.minimumButtonCount != null && matcher.minimumButtonCount < 0
        ) {
            issues += profileError(path, "invalid_matcher", "USB IDs and input counts are outside their valid ranges")
        }
        if (matcher.nameContains == null && matcher.guid == null && matcher.vendorId == null && matcher.productId == null &&
            matcher.minimumAxisCount == null && matcher.minimumButtonCount == null
        ) {
            issues += profileError(path, "empty_matcher", "A device matcher must declare at least one constraint")
        }
    }

    val ids = linkedSetOf<String>()
    val buttonIndexes = linkedSetOf<Pair<ControllerInputPlatform, Int>>()
    val axisIndexes = linkedSetOf<Pair<ControllerInputPlatform, Int>>()
    document.controls.forEachIndexed { index, control ->
        val path = "controls[$index]"
        if (!control.controlId.matches(keyPattern)) {
            issues += profileError(path, "invalid_control_id", "Control ID '${control.controlId}' is not stable")
        } else if (!ids.add(control.controlId)) {
            issues += profileError(path, "duplicate_control", "Control '${control.controlId}' is duplicated")
        }
        if (control.displayName.isBlank()) issues += profileError(path, "missing_control_name", "Control name is required")
        if (!control.anchor.x.isFinite() || control.anchor.x !in 0.0..1.0 ||
            !control.anchor.y.isFinite() || control.anchor.y !in 0.0..1.0
        ) {
            issues += profileError(path, "invalid_anchor", "Diagram anchors must be inside [0, 1]")
        }
        val mappedPlatforms = linkedSetOf<ControllerInputPlatform>()
        control.mappings.forEachIndexed { mappingIndex, mapping ->
            val mappingPath = "$path.mappings[$mappingIndex]"
            if (!mappedPlatforms.add(mapping.platform)) {
                issues += profileError(mappingPath, "duplicate_platform_mapping", "A control may map once per platform")
            }
            val button = mapping.buttonIndex
            val axis = mapping.axisIndex
            if (button != null && button < 0 || axis != null && axis < 0) {
                issues += profileError(mappingPath, "negative_index", "Input-frame indexes cannot be negative")
            }
            if ((button == null) == (axis == null)) {
                issues += profileError(mappingPath, "invalid_mapping", "A mapping must contain exactly one button or axis index")
            }
            when (control.type) {
                ControllerControlTypeDocument.BUTTON -> if (axis != null) {
                    issues += profileError(mappingPath, "wrong_index_type", "A button cannot contain an axis index")
                }
                ControllerControlTypeDocument.AXIS -> if (button != null) {
                    issues += profileError(mappingPath, "wrong_index_type", "An axis cannot contain a button index")
                }
            }
            if (button != null && !buttonIndexes.add(mapping.platform to button)) {
                issues += ControlValidationIssue(
                    ControlValidationSeverity.WARNING,
                    mappingPath,
                    "shared_button_index",
                    "Multiple controls use ${mapping.platform} button $button; confirm the device aliases them"
                )
            }
            if (axis != null && !axisIndexes.add(mapping.platform to axis)) {
                issues += ControlValidationIssue(
                    ControlValidationSeverity.WARNING,
                    mappingPath,
                    "shared_axis_index",
                    "Multiple controls use ${mapping.platform} axis $axis; confirm the device aliases them"
                )
            }
        }
        if (control.aliases.any(String::isBlank) || control.aliases.distinct().size != control.aliases.size) {
            issues += profileError(path, "invalid_aliases", "Aliases must be non-blank and unique")
        }
    }
    return issues
}

/** Returns control IDs that are safe for generated enabled bindings. */
fun ControllerProfileDocument.learnedControlIds(platform: ControllerInputPlatform): Set<String> =
    controls.mapNotNullTo(linkedSetOf()) { control ->
        control.controlId.takeIf { control.mappings.any { it.platform == platform } }
    }

private fun profileError(path: String, code: String, message: String) =
    ControlValidationIssue(ControlValidationSeverity.ERROR, path, code, message)

object ControllerProfileCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: ControllerProfileDocument): String {
        val canonical = document.canonicalized()
        requireValid(canonical)
        return gson.toJson(canonical)
    }

    fun decode(json: String): ControllerProfileDocument {
        val document = try {
            gson.fromJson(json, ControllerProfileDocument::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Controller profile is not valid JSON: ${error.message}", error)
        } ?: throw IllegalArgumentException("Controller profile is empty")
        try {
            requireValid(document)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Controller profile is missing or contains invalid fields", error)
        }
        return document.canonicalized()
    }

    fun contentHash(document: ControllerProfileDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireValid(document: ControllerProfileDocument) {
        val errors = validateControllerProfile(document).filter { it.severity == ControlValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
    }
}

private fun ControllerProfileDocument.canonicalized(): ControllerProfileDocument = copy(
    deviceMatchers = deviceMatchers.sortedWith(
        compareBy<ControllerDeviceMatcherDocument> { it.vendorId ?: -1 }
            .thenBy { it.productId ?: -1 }
            .thenBy { it.guid.orEmpty() }
            .thenBy { it.nameContains.orEmpty() }
    ),
    controls = controls.sortedBy { it.controlId }.map {
        it.copy(
            mappings = it.mappings.sortedBy { mapping -> mapping.platform.ordinal },
            aliases = it.aliases.distinct().sorted()
        )
    }
)
