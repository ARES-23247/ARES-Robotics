package com.ares.analytics.service.dashboard

import com.ares.analytics.service.DashboardLayoutConfig
import com.ares.analytics.service.WidgetConfig
import java.util.Locale

data class DashboardLayoutProfile(
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val layout: DashboardLayoutConfig,
)

interface DashboardLayoutProfileCatalog {
    val profiles: List<DashboardLayoutProfile>

    fun getDefaultLayout(profileName: String): DashboardLayoutConfig

    fun availableNames(): List<String>

    fun validationErrors(widgetCatalog: DashboardWidgetCatalog): List<String>
}
fun dashboardLayoutValidationErrors(
    owner: String,
    layout: DashboardLayoutConfig,
    widgetCatalog: DashboardWidgetCatalog,
    gridColumns: Int = 12,
): List<String> {
    val errors = mutableListOf<String>()
    val widgets = layout.widgets
    val duplicateIds = widgets.groupBy { it.id }.filterValues { it.size > 1 }.keys
    if (duplicateIds.isNotEmpty()) errors += "$owner has duplicate widget IDs: $duplicateIds"
    widgets.forEachIndexed { index, widget ->
        val spec = widgetCatalog.find(widget.type)
        if (spec == null) {
            errors += "$owner references unknown widget type ${widget.type}"
        } else {
            if (widget.rowSpan < spec.minimumRowSpan || widget.colSpan < spec.minimumColSpan) {
                errors += "$owner/${widget.id} is smaller than the registered minimum"
            }
            val unknownProperties = widget.properties.keys - spec.properties.mapTo(mutableSetOf()) { it.key }
            if (unknownProperties.isNotEmpty()) {
                errors += "$owner/${widget.id} has undeclared properties: $unknownProperties"
            }
        }
        if (widget.row < 0 || widget.col < 0 || widget.rowSpan <= 0 || widget.colSpan <= 0 || widget.col + widget.colSpan > gridColumns) {
            errors += "$owner/${widget.id} is outside the $gridColumns-column grid"
        }
        widgets.drop(index + 1).forEach { other ->
            val overlaps = widget.col < other.col + other.colSpan && widget.col + widget.colSpan > other.col &&
                widget.row < other.row + other.rowSpan && widget.row + widget.rowSpan > other.row
            if (overlaps) errors += "$owner overlaps ${widget.id} and ${other.id}"
        }
    }
    return errors
}

/** Declarative, validated built-in dashboard layouts. */
object BuiltInDashboardLayoutProfiles : DashboardLayoutProfileCatalog {
    private const val GRID_COLUMNS = 12
    private val selectableOrder = listOf(
        "Student", "Driver", "Builder", "Autonomous", "Analyst", "Mentor", "Standard",
        "Driver Coach", "Programmer", "Pit Crew", "Match Review", "Pit Diagnostics",
        "Driver Practice", "Replay",
    )

    override val profiles = listOf(
        profile("Student",
            widget("field_viewer", 0, 0, 5, 7),
            widget("system_health", 0, 7, 3, 5),
            widget("autonomous_selector", 3, 7, 2, 5),
            widget("telemetry_chart", 5, 0, 5, 7),
            widget("alerts", 5, 7, 5, 5),
            widget("subsystem_health", 10, 0, 4, 7),
        ),
        profile("Driver",
            widget("field_viewer", 0, 0, 6, 8),
            widget("joystick_visualizer", 0, 8, 3, 4),
            widget("system_health", 3, 8, 3, 4),
            widget("telemetry_chart", 6, 0, 4, 8),
            widget("alerts", 6, 8, 4, 4),
        ),
        profile("Builder",
            widget("hardware_topology", 0, 0, 5, 7),
            widget("system_health", 0, 7, 3, 5),
            widget("motor_health", 3, 7, 4, 5),
            widget("power_distribution", 5, 0, 4, 4),
            widget("battery_health", 5, 4, 4, 3),
            widget("alerts", 7, 7, 2, 5),
            widget("subsystem_health", 9, 0, 4, 7),
        ),
        profile("Autonomous",
            widget("field_viewer", 0, 0, 6, 7),
            widget("autonomous_selector", 0, 7, 3, 5),
            widget("pose_viewer", 3, 7, 3, 5),
            widget("path_tuning", 6, 0, 5, 6),
            widget("ekf_telemetry", 6, 6, 5, 6),
        ),
        profile("Analyst",
            widget("runs_index", 0, 0, 3, 12),
            widget("telemetry_chart", 3, 0, 5, 8),
            widget("session_summary", 3, 8, 5, 4),
            widget("advanced_analytics", 8, 0, 5, 6),
            widget("trends_card", 8, 6, 5, 6),
        ),
        profile("Mentor",
            widget("system_health", 0, 0, 3, 6),
            widget("alerts", 0, 6, 3, 6),
            widget("pit_evidence_checklist", 3, 0, 5, 6),
            widget("ai_coach", 3, 6, 5, 6),
            widget("runs_index", 8, 0, 4, 6),
            widget("control_profiler", 8, 6, 4, 6),
        ),
        profile("Driver Coach",
            widget("field_viewer", 0, 0, 5, 7),
            widget("autonomous_selector", 0, 7, 3, 5),
            widget("system_health", 3, 7, 2, 5),
            widget("telemetry_chart", 5, 0, 5, 8),
            widget("alerts", 5, 8, 5, 4),
        ),
        profile("Programmer",
            widget("telemetry_chart", 0, 0, 6, 8),
            widget("console_viewer", 0, 8, 6, 4, id = "console_viewer_0"),
            widget("system_health", 6, 0, 3, 4),
            widget("profiling_diagnostics", 6, 4, 3, 4),
            widget("ekf_telemetry", 6, 8, 3, 4),
        ),
        profile("Pit Crew",
            widget("runs_index", 0, 0, 3, 8),
            widget("system_health", 0, 8, 3, 4),
            widget("motor_health", 3, 0, 4, 4),
            widget("vision_quality", 3, 4, 4, 4),
            widget("alerts", 3, 8, 4, 4),
            widget("advanced_analytics", 7, 0, 4, 6),
            widget("ai_coach", 7, 6, 4, 6),
        ),
        profile("Replay",
            widget("runs_index", 0, 0, 3, 12),
            widget("telemetry_chart", 3, 0, 5, 8),
            widget("field_viewer", 3, 8, 5, 4),
            widget("advanced_analytics", 8, 0, 5, 6),
            widget("alerts", 8, 6, 5, 3),
            widget("system_health", 8, 9, 5, 3),
            aliases = setOf("Match Review"),
        ),
        profile("Pit Diagnostics",
            widget("system_health", 0, 0, 3, 6),
            widget("alerts", 0, 6, 3, 6),
            widget("motor_health", 3, 0, 4, 4),
            widget("battery_health", 3, 4, 4, 4),
            widget("vision_quality", 3, 8, 4, 4),
            widget("ai_coach", 7, 0, 5, 6),
            widget("advanced_analytics", 7, 6, 5, 6),
        ),
        profile("Driver Practice",
            widget("field_viewer", 0, 0, 6, 8),
            widget("joystick_visualizer", 0, 8, 3, 4),
            widget("system_health", 3, 8, 3, 4),
            widget("telemetry_chart", 6, 0, 4, 8),
            widget("alerts", 6, 8, 4, 4),
        ),
        profile("Standard",
            widget("runs_index", 0, 0, 3, 7),
            widget("system_health", 0, 7, 3, 5),
            widget("field_viewer", 3, 0, 5, 7),
            widget("telemetry_chart", 3, 7, 5, 5),
            widget("advanced_analytics", 8, 0, 5, 6),
            widget("alerts", 8, 6, 5, 3),
            widget("joystick_visualizer", 8, 9, 5, 3),
            widget("subsystem_health", 13, 0, 4, 6),
        ),
    )

    private val byKey: Map<String, DashboardLayoutProfile> = buildMap {
        profiles.forEach { profile ->
            (profile.aliases + profile.displayName).forEach { name ->
                require(put(profileKey(name), profile) == null) { "Duplicate dashboard profile name or alias: $name" }
            }
        }
    }

    override fun getDefaultLayout(profileName: String): DashboardLayoutConfig =
        byKey[profileKey(profileName)]?.layout ?: requireNotNull(byKey[profileKey("Standard")]).layout

    override fun availableNames(): List<String> = selectableOrder

    override fun validationErrors(widgetCatalog: DashboardWidgetCatalog): List<String> {
        val errors = profiles.flatMap { profile ->
            dashboardLayoutValidationErrors(profile.displayName, profile.layout, widgetCatalog, GRID_COLUMNS)
        }.toMutableList()
        selectableOrder.filter { profileKey(it) !in byKey }.forEach { unresolved ->
            errors += "Selectable dashboard profile does not resolve: $unresolved"
        }
        return errors
    }

    fun requireValid(widgetCatalog: DashboardWidgetCatalog) {
        val errors = validationErrors(widgetCatalog)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private fun profile(
        displayName: String,
        vararg widgets: WidgetConfig,
        aliases: Set<String> = emptySet(),
    ) = DashboardLayoutProfile(displayName, aliases, DashboardLayoutConfig(widgets.toList()))

    private fun widget(
        type: String,
        row: Int,
        col: Int,
        rowSpan: Int,
        colSpan: Int,
        id: String = type,
    ) = WidgetConfig(id, type, row, col, rowSpan, colSpan)

    private fun profileKey(name: String): String =
        name.trim().lowercase(Locale.ROOT).replace(' ', '_')
}
