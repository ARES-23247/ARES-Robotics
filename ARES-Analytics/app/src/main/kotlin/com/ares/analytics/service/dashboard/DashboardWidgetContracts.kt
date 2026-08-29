package com.ares.analytics.service.dashboard

/** Stable serialized identity for a dashboard widget type. */
@JvmInline
value class DashboardWidgetType private constructor(val serializedName: String) {
    companion object {
        private val validName = Regex("[a-z][a-z0-9_]{1,63}")

        fun of(serializedName: String): DashboardWidgetType {
            require(validName.matches(serializedName)) {
                "Dashboard widget type must be a stable lower_snake_case identifier: $serializedName"
            }
            return DashboardWidgetType(serializedName)
        }
    }

    override fun toString(): String = serializedName
}
enum class DashboardWidgetCategory(val displayName: String) {
    RECOMMENDED("Recommended"),
    LIVE("Live control"),
    ANALYSIS("Analysis"),
    DIAGNOSTICS("Diagnostics"),
    REPLAY("Replay & review"),
    DEVELOPER("Developer tools"),
}

/** Runtime capabilities a widget consumes. Used for validation and future availability guidance. */
enum class DashboardWidgetCapability {
    LIVE_TELEMETRY,
    DATABASE,
    REPLAY,
    ROBOT_CONTROL,
    CAMERA,
    CLOUD,
}

/** Coarse dependency boundary enforced when a widget renderer accesses dashboard services. */
enum class DashboardWidgetServiceGroup {
    LIVE,
    ANALYSIS,
    REPLAY,
}

fun Set<DashboardWidgetCapability>.allows(group: DashboardWidgetServiceGroup): Boolean = when (group) {
    DashboardWidgetServiceGroup.LIVE -> any {
        it == DashboardWidgetCapability.LIVE_TELEMETRY ||
            it == DashboardWidgetCapability.ROBOT_CONTROL ||
            it == DashboardWidgetCapability.CAMERA
    }
    DashboardWidgetServiceGroup.ANALYSIS -> any {
        it == DashboardWidgetCapability.DATABASE || it == DashboardWidgetCapability.CLOUD
    }
    DashboardWidgetServiceGroup.REPLAY -> DashboardWidgetCapability.REPLAY in this
}

enum class DashboardWidgetPropertyKind {
    TEXT,
    TOPIC,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    CHOICE,
}

data class DashboardWidgetPropertySpec(
    val key: String,
    val label: String,
    val kind: DashboardWidgetPropertyKind,
    val defaultValue: String? = null,
    val unit: String? = null,
    val choices: List<String> = emptyList(),
) {
    init {
        require(key.matches(Regex("[a-z][A-Za-z0-9_]{0,63}"))) {
            "Dashboard widget property keys must be stable identifiers: $key"
        }
        require(kind == DashboardWidgetPropertyKind.CHOICE || choices.isEmpty()) {
            "Only choice properties may declare choices"
        }
        require(kind != DashboardWidgetPropertyKind.CHOICE || choices.isNotEmpty()) {
            "Choice properties must declare at least one choice"
        }
    }
}

/** UI-neutral widget metadata consumed by layout logic and the picker. */
interface DashboardWidgetSpec {
    val type: DashboardWidgetType
    val displayName: String
    val description: String
    val category: DashboardWidgetCategory
    val recommended: Boolean
    val defaultRowSpan: Int
    val defaultColSpan: Int
    val minimumRowSpan: Int
    val minimumColSpan: Int
    val capabilities: Set<DashboardWidgetCapability>
    val properties: List<DashboardWidgetPropertySpec>
}

/** Persistable defaults for a newly added widget. Null defaults remain intentionally absent. */
fun DashboardWidgetSpec.defaultProperties(): Map<String, String> =
    properties.mapNotNull { property ->
        property.defaultValue?.let { defaultValue -> property.key to defaultValue }
    }.toMap(linkedMapOf())

/** Narrow contract used by non-Compose layout and ViewModel code. */
interface DashboardWidgetCatalog {
    val specs: List<DashboardWidgetSpec>

    fun find(type: DashboardWidgetType): DashboardWidgetSpec?

    fun find(serializedName: String): DashboardWidgetSpec? =
        runCatching { DashboardWidgetType.of(serializedName) }.getOrNull()?.let(::find)

    val knownTypes: Set<String>
        get() = specs.mapTo(linkedSetOf()) { it.type.serializedName }

    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        val duplicateTypes = specs.groupBy { it.type }.filterValues { it.size > 1 }.keys
        if (duplicateTypes.isNotEmpty()) errors += "Duplicate dashboard widget types: $duplicateTypes"
        specs.forEach { spec ->
            if (spec.displayName.isBlank()) errors += "${spec.type} has a blank display name"
            if (spec.description.isBlank()) errors += "${spec.type} has a blank description"
            if (spec.defaultRowSpan < spec.minimumRowSpan || spec.defaultColSpan < spec.minimumColSpan) {
                errors += "${spec.type} default size is smaller than its minimum size"
            }
            val duplicateProperties = spec.properties.groupBy { it.key }.filterValues { it.size > 1 }.keys
            if (duplicateProperties.isNotEmpty()) {
                errors += "${spec.type} has duplicate property keys: $duplicateProperties"
            }
            spec.properties.forEach { property ->
                val defaultValue = property.defaultValue ?: return@forEach
                val validDefault = when (property.kind) {
                    DashboardWidgetPropertyKind.TEXT,
                    DashboardWidgetPropertyKind.TOPIC -> true
                    DashboardWidgetPropertyKind.BOOLEAN -> defaultValue.toBooleanStrictOrNull() != null
                    DashboardWidgetPropertyKind.INTEGER -> defaultValue.toIntOrNull() != null
                    DashboardWidgetPropertyKind.DECIMAL -> defaultValue.toDoubleOrNull() != null
                    DashboardWidgetPropertyKind.CHOICE -> defaultValue in property.choices
                }
                if (!validDefault) {
                    errors += "${spec.type}/${property.key} has an invalid ${property.kind.name.lowercase()} default: $defaultValue"
                }
            }
        }
        return errors
    }

    fun requireValid() {
        val errors = validationErrors()
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}
