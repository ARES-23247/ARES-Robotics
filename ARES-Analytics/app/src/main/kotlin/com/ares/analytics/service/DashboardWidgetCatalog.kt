package com.ares.analytics.service

data class DashboardWidgetSpec(
    val type: String,
    val defaultRowSpan: Int,
    val defaultColSpan: Int,
)

/** Canonical set of dashboard widget types and their add-to-layout sizes. */
object DashboardWidgetCatalog {
    private val specs = listOf(
        DashboardWidgetSpec("driver_station", 3, 3),
        DashboardWidgetSpec("autonomous_selector", 3, 5),
        DashboardWidgetSpec("field_viewer", 6, 6),
        DashboardWidgetSpec("telemetry_chart", 6, 6),
        DashboardWidgetSpec("joystick_visualizer", 4, 6),
        DashboardWidgetSpec("mecanum_visualizer", 4, 6),
        DashboardWidgetSpec("swerve_animator", 4, 6),
        DashboardWidgetSpec("mechanism_visualizer", 4, 6),
        DashboardWidgetSpec("camera_stream", 6, 6),
        DashboardWidgetSpec("indicator_lights", 3, 4),
        DashboardWidgetSpec("advanced_analytics", 5, 6),
        DashboardWidgetSpec("statistics_panel", 4, 5),
        DashboardWidgetSpec("trends_card", 4, 5),
        DashboardWidgetSpec("session_summary", 3, 3),
        DashboardWidgetSpec("ai_coach", 5, 6),
        DashboardWidgetSpec("driver_motion_review", 5, 6),
        DashboardWidgetSpec("pit_evidence_checklist", 5, 6),
        DashboardWidgetSpec("vision_quality", 3, 4),
        DashboardWidgetSpec("motor_health", 3, 4),
        DashboardWidgetSpec("system_health", 3, 4),
        DashboardWidgetSpec("alerts", 3, 4),
        DashboardWidgetSpec("battery_health", 3, 4),
        DashboardWidgetSpec("power_distribution", 3, 4),
        DashboardWidgetSpec("brownout_protection", 3, 3),
        DashboardWidgetSpec("imu_visualizer", 4, 5),
        DashboardWidgetSpec("ekf_telemetry", 4, 5),
        DashboardWidgetSpec("control_profiler", 4, 5),
        DashboardWidgetSpec("profiling_diagnostics", 4, 5),
        DashboardWidgetSpec("hardware_topology", 3, 3),
        DashboardWidgetSpec("subsystem_health", 4, 5),
        DashboardWidgetSpec("state_tracker", 4, 5),
        DashboardWidgetSpec("runs_index", 4, 9),
        DashboardWidgetSpec("pose_viewer", 6, 6),
        DashboardWidgetSpec("match_schedule", 4, 9),
        DashboardWidgetSpec("console_viewer", 6, 9),
        DashboardWidgetSpec("tuning_card", 3, 3),
        DashboardWidgetSpec("path_tuning", 4, 5),
    )

    private val byType = specs.associateBy(DashboardWidgetSpec::type).also { indexed ->
        require(indexed.size == specs.size) { "Dashboard widget catalog contains duplicate types" }
    }

    val knownTypes: Set<String> = byType.keys

    fun find(type: String): DashboardWidgetSpec? = byType[type]

    fun completenessError(actualTypes: Collection<String>, owner: String): String? {
        val actual = actualTypes.toSet()
        val missing = knownTypes - actual
        val unexpected = actual - knownTypes
        return when {
            missing.isNotEmpty() || unexpected.isNotEmpty() ->
                "$owner is out of sync with DashboardWidgetCatalog; missing=$missing, unexpected=$unexpected"
            actual.size != actualTypes.size -> "$owner contains duplicate widget types"
            else -> null
        }
    }

    fun requireComplete(actualTypes: Collection<String>, owner: String) {
        require(completenessError(actualTypes, owner) == null) {
            completenessError(actualTypes, owner) ?: "Unknown dashboard widget catalog mismatch"
        }
    }
}
