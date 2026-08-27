package com.areslib.catalog

import com.google.gson.GsonBuilder
import java.security.MessageDigest

/** Current schema written to the offline ARES action catalog. */
const val ARES_CAPABILITY_CATALOG_SCHEMA_VERSION: Int = 1

private val CAPABILITY_KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val PARAMETER_KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

/** Stable action identifier shared by generated robot code, routines, and Analytics. */
@JvmInline
value class ActionKey(val value: String) {
    init {
        require(value.matches(CAPABILITY_KEY_PATTERN)) { "Invalid action key '$value'" }
    }

    override fun toString(): String = value
}

/** Stable state-selector identifier used by wait and branch nodes. */
@JvmInline
value class ConditionKey(val value: String) {
    init {
        require(value.matches(CAPABILITY_KEY_PATTERN)) { "Invalid condition key '$value'" }
    }

    override fun toString(): String = value
}

/** Stable subsystem or actuator ownership identifier. */
@JvmInline
value class ResourceKey(val value: String) {
    init {
        require(value.matches(CAPABILITY_KEY_PATTERN)) { "Invalid resource key '$value'" }
    }

    override fun toString(): String = value
}

/** Contexts in which an action may be offered by the editor. */
enum class CapabilityContext {
    AUTONOMOUS,
    TELEOP,
    TEST
}

/** Access requested while an action or condition is active. */
enum class ResourceAccess {
    READ,
    EXCLUSIVE
}

data class ResourceClaim(
    val resourceKey: String,
    val access: ResourceAccess = ResourceAccess.EXCLUSIVE
)

enum class CapabilityParameterType {
    NUMBER,
    BOOLEAN,
    TEXT,
    ENUM
}

/**
 * Editor and generator metadata for one typed action/condition parameter.
 *
 * Only the default matching [type] may be populated. Numeric limits are inclusive and expressed
 * in [unit] when supplied. Enum options are stable serialized values rather than display labels.
 */
data class CapabilityParameterDescriptor(
    val key: String,
    val displayName: String,
    val description: String,
    val type: CapabilityParameterType,
    val required: Boolean = true,
    val unit: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val defaultNumber: Double? = null,
    val defaultBoolean: Boolean? = null,
    val defaultText: String? = null,
    val options: List<String> = emptyList()
)

data class ActionDescriptor(
    val key: String,
    val displayName: String,
    val description: String,
    val category: String = "General",
    val parameters: List<CapabilityParameterDescriptor> = emptyList(),
    val resources: List<ResourceClaim> = emptyList(),
    val allowedContexts: List<CapabilityContext> = CapabilityContext.entries
)

data class ConditionDescriptor(
    val key: String,
    val displayName: String,
    val description: String,
    val category: String = "Conditions",
    val parameters: List<CapabilityParameterDescriptor> = emptyList(),
    val resources: List<ResourceClaim> = emptyList()
)

/** Offline, generated description of every behavior capability a robot project exposes. */
data class CapabilityCatalogDocument(
    val schemaVersion: Int = ARES_CAPABILITY_CATALOG_SCHEMA_VERSION,
    val projectId: String,
    val revision: Int = 1,
    val actions: List<ActionDescriptor> = emptyList(),
    val conditions: List<ConditionDescriptor> = emptyList()
)

enum class CatalogValidationSeverity {
    WARNING,
    ERROR
}

data class CatalogValidationIssue(
    val severity: CatalogValidationSeverity,
    val path: String,
    val code: String,
    val message: String
)

/** Performs the same fail-closed catalog validation in the generator, GUI, and robot tests. */
fun validateCapabilityCatalog(document: CapabilityCatalogDocument): List<CatalogValidationIssue> {
    val issues = mutableListOf<CatalogValidationIssue>()
    if (document.schemaVersion != ARES_CAPABILITY_CATALOG_SCHEMA_VERSION) {
        issues += catalogError(
            "catalog",
            "unsupported_schema",
            "Capability catalog schema ${document.schemaVersion} is unsupported"
        )
    }
    if (!document.projectId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
        issues += catalogError("projectId", "invalid_project_id", "Project ID is not filesystem safe")
    }
    if (document.revision < 1) {
        issues += catalogError("revision", "invalid_revision", "Revision must be at least 1")
    }
    if (document.actions.isEmpty()) {
        issues += CatalogValidationIssue(
            CatalogValidationSeverity.WARNING,
            "actions",
            "empty_actions",
            "The project exposes no robot actions"
        )
    }

    val actionKeys = mutableSetOf<String>()
    document.actions.forEachIndexed { index, descriptor ->
        val path = "actions[$index]"
        validateCapabilityIdentity(descriptor.key, descriptor.displayName, descriptor.description, descriptor.category, path, issues)
        if (!actionKeys.add(descriptor.key)) {
            issues += catalogError(path, "duplicate_action", "Action '${descriptor.key}' is declared more than once")
        }
        if (descriptor.allowedContexts.isEmpty()) {
            issues += catalogError(path, "no_context", "Action '${descriptor.key}' is not allowed in any context")
        }
        validateParameters(descriptor.parameters, "$path.parameters", issues)
        validateResources(descriptor.resources, "$path.resources", issues)
    }

    val conditionKeys = mutableSetOf<String>()
    document.conditions.forEachIndexed { index, descriptor ->
        val path = "conditions[$index]"
        validateCapabilityIdentity(descriptor.key, descriptor.displayName, descriptor.description, descriptor.category, path, issues)
        if (!conditionKeys.add(descriptor.key)) {
            issues += catalogError(path, "duplicate_condition", "Condition '${descriptor.key}' is declared more than once")
        }
        validateParameters(descriptor.parameters, "$path.parameters", issues)
        validateResources(descriptor.resources, "$path.resources", issues)
    }
    return issues
}

private fun validateCapabilityIdentity(
    key: String,
    displayName: String,
    description: String,
    category: String,
    path: String,
    issues: MutableList<CatalogValidationIssue>
) {
    if (!key.matches(CAPABILITY_KEY_PATTERN)) {
        issues += catalogError(path, "invalid_key", "'$key' is not a valid capability key")
    }
    if (displayName.isBlank()) issues += catalogError(path, "missing_name", "Display name is required")
    if (description.isBlank()) issues += catalogError(path, "missing_description", "Description is required")
    if (category.isBlank()) issues += catalogError(path, "missing_category", "Category is required")
}

private fun validateParameters(
    parameters: List<CapabilityParameterDescriptor>,
    path: String,
    issues: MutableList<CatalogValidationIssue>
) {
    val keys = mutableSetOf<String>()
    parameters.forEachIndexed { index, parameter ->
        val parameterPath = "$path[$index]"
        if (!parameter.key.matches(PARAMETER_KEY_PATTERN)) {
            issues += catalogError(parameterPath, "invalid_parameter_key", "Invalid parameter key '${parameter.key}'")
        } else if (!keys.add(parameter.key)) {
            issues += catalogError(parameterPath, "duplicate_parameter", "Parameter '${parameter.key}' is duplicated")
        }
        if (parameter.displayName.isBlank()) {
            issues += catalogError(parameterPath, "missing_parameter_name", "Parameter display name is required")
        }
        if (parameter.description.isBlank()) {
            issues += catalogError(parameterPath, "missing_parameter_description", "Parameter description is required")
        }

        val numericFields = listOf(parameter.minimum, parameter.maximum, parameter.step, parameter.defaultNumber)
        if (numericFields.filterNotNull().any { !it.isFinite() }) {
            issues += catalogError(parameterPath, "non_finite_number", "Numeric settings must be finite")
        }
        if (parameter.minimum != null && parameter.maximum != null && parameter.minimum > parameter.maximum) {
            issues += catalogError(parameterPath, "invalid_range", "Minimum cannot exceed maximum")
        }
        if (parameter.step != null && parameter.step <= 0.0) {
            issues += catalogError(parameterPath, "invalid_step", "Numeric step must be positive")
        }

        when (parameter.type) {
            CapabilityParameterType.NUMBER -> {
                if (parameter.defaultBoolean != null || parameter.defaultText != null || parameter.options.isNotEmpty()) {
                    issues += catalogError(parameterPath, "conflicting_default", "Number parameter contains non-number settings")
                }
                val default = parameter.defaultNumber
                if (default != null && parameter.minimum != null && default < parameter.minimum) {
                    issues += catalogError(parameterPath, "default_below_minimum", "Default is below the minimum")
                }
                if (default != null && parameter.maximum != null && default > parameter.maximum) {
                    issues += catalogError(parameterPath, "default_above_maximum", "Default is above the maximum")
                }
            }

            CapabilityParameterType.BOOLEAN -> {
                if (parameter.defaultNumber != null || parameter.defaultText != null || parameter.options.isNotEmpty() ||
                    parameter.minimum != null || parameter.maximum != null || parameter.step != null || parameter.unit != null
                ) {
                    issues += catalogError(parameterPath, "conflicting_default", "Boolean parameter contains incompatible settings")
                }
            }

            CapabilityParameterType.TEXT -> {
                if (parameter.defaultNumber != null || parameter.defaultBoolean != null || parameter.options.isNotEmpty() ||
                    parameter.minimum != null || parameter.maximum != null || parameter.step != null || parameter.unit != null
                ) {
                    issues += catalogError(parameterPath, "conflicting_default", "Text parameter contains incompatible settings")
                }
            }

            CapabilityParameterType.ENUM -> {
                if (parameter.options.isEmpty() || parameter.options.any(String::isBlank) ||
                    parameter.options.distinct().size != parameter.options.size
                ) {
                    issues += catalogError(parameterPath, "invalid_options", "Enum options must be non-empty and unique")
                }
                if (parameter.defaultText != null && parameter.defaultText !in parameter.options) {
                    issues += catalogError(parameterPath, "invalid_enum_default", "Enum default is not one of its options")
                }
                if (parameter.defaultNumber != null || parameter.defaultBoolean != null || parameter.minimum != null ||
                    parameter.maximum != null || parameter.step != null || parameter.unit != null
                ) {
                    issues += catalogError(parameterPath, "conflicting_default", "Enum parameter contains incompatible settings")
                }
            }
        }
    }
}

private fun validateResources(
    resources: List<ResourceClaim>,
    path: String,
    issues: MutableList<CatalogValidationIssue>
) {
    val keys = mutableSetOf<String>()
    resources.forEachIndexed { index, resource ->
        val resourcePath = "$path[$index]"
        if (!resource.resourceKey.matches(CAPABILITY_KEY_PATTERN)) {
            issues += catalogError(resourcePath, "invalid_resource", "Invalid resource key '${resource.resourceKey}'")
        } else if (!keys.add(resource.resourceKey)) {
            issues += catalogError(resourcePath, "duplicate_resource", "Resource '${resource.resourceKey}' is claimed twice")
        }
    }
}

private fun catalogError(path: String, code: String, message: String) =
    CatalogValidationIssue(CatalogValidationSeverity.ERROR, path, code, message)

/** Deterministic JSON codec used by Gradle codegen and offline Analytics discovery. */
object CapabilityCatalogCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: CapabilityCatalogDocument): String {
        val canonical = document.canonicalized()
        requireNoCatalogErrors(canonical)
        return gson.toJson(canonical)
    }

    fun decode(json: String): CapabilityCatalogDocument {
        val document = try {
            gson.fromJson(json, CapabilityCatalogDocument::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Capability catalog is not valid JSON: ${error.message}", error)
        } ?: throw IllegalArgumentException("Capability catalog is empty")
        try {
            requireNoCatalogErrors(document)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Capability catalog is missing or contains invalid fields", error)
        }
        return document.canonicalized()
    }

    fun contentHash(document: CapabilityCatalogDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireNoCatalogErrors(document: CapabilityCatalogDocument) {
        val errors = validateCapabilityCatalog(document).filter { it.severity == CatalogValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
    }
}

private fun CapabilityCatalogDocument.canonicalized(): CapabilityCatalogDocument = copy(
    actions = actions.sortedBy { it.key }.map { descriptor ->
        descriptor.copy(
            parameters = descriptor.parameters.sortedBy { it.key },
            resources = descriptor.resources.sortedBy { it.resourceKey },
            allowedContexts = descriptor.allowedContexts.distinct().sortedBy { it.ordinal }
        )
    },
    conditions = conditions.sortedBy { it.key }.map { descriptor ->
        descriptor.copy(
            parameters = descriptor.parameters.sortedBy { it.key },
            resources = descriptor.resources.sortedBy { it.resourceKey }
        )
    }
)
