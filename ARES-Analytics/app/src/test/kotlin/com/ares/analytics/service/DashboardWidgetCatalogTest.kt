package com.ares.analytics.service

import com.ares.analytics.service.dashboard.DashboardWidgetType
import com.ares.analytics.service.dashboard.defaultProperties
import com.ares.analytics.service.dashboard.DashboardWidgetCatalog
import com.ares.analytics.service.dashboard.DashboardWidgetPropertyKind
import com.ares.analytics.service.dashboard.DashboardWidgetPropertySpec
import com.ares.analytics.service.dashboard.DashboardWidgetServiceGroup
import com.ares.analytics.service.dashboard.DashboardWidgetSpec
import com.ares.analytics.service.dashboard.BuiltInDashboardLayoutProfiles
import com.ares.analytics.service.dashboard.allows
import com.ares.analytics.ui.components.dashboard.DashboardWidgetRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DashboardWidgetCatalogTest {
    @Test
    fun `registry preserves the complete pre-refactor widget IDs and default sizes`() {
        val expected = linkedMapOf(
            "driver_station" to (3 to 3),
            "autonomous_selector" to (3 to 5),
            "field_viewer" to (6 to 6),
            "telemetry_chart" to (6 to 6),
            "single_signal" to (3 to 4),
            "joystick_visualizer" to (4 to 6),
            "mecanum_visualizer" to (4 to 6),
            "swerve_animator" to (4 to 6),
            "mechanism_visualizer" to (4 to 6),
            "camera_stream" to (6 to 6),
            "indicator_lights" to (3 to 4),
            "advanced_analytics" to (5 to 6),
            "statistics_panel" to (4 to 5),
            "trends_card" to (4 to 5),
            "session_summary" to (3 to 3),
            "ai_coach" to (5 to 6),
            "driver_motion_review" to (5 to 6),
            "pit_evidence_checklist" to (5 to 6),
            "vision_quality" to (3 to 4),
            "motor_health" to (3 to 4),
            "system_health" to (3 to 4),
            "alerts" to (3 to 4),
            "battery_health" to (3 to 4),
            "power_distribution" to (3 to 4),
            "brownout_protection" to (3 to 3),
            "imu_visualizer" to (4 to 5),
            "ekf_telemetry" to (4 to 5),
            "control_profiler" to (4 to 5),
            "profiling_diagnostics" to (4 to 5),
            "hardware_topology" to (3 to 3),
            "subsystem_health" to (4 to 5),
            "state_tracker" to (4 to 5),
            "runs_index" to (4 to 9),
            "pose_viewer" to (6 to 6),
            "match_schedule" to (4 to 9),
            "console_viewer" to (6 to 9),
            "tuning_card" to (3 to 3),
            "path_tuning" to (4 to 5),
        )

        assertEquals(expected.keys, DashboardWidgetRegistry.knownTypes)
        assertEquals(
            expected,
            DashboardWidgetRegistry.definitions.associate { definition ->
                definition.type.serializedName to (definition.defaultRowSpan to definition.defaultColSpan)
            },
        )
    }

    @Test
    fun `registry owns picker metadata renderer schema and add-to-layout sizing`() {
        val fieldViewer = requireNotNull(DashboardWidgetRegistry.find("field_viewer"))
        assertEquals(6, fieldViewer.defaultRowSpan)
        assertEquals(6, fieldViewer.defaultColSpan)
        assertTrue(fieldViewer.description.isNotBlank())
        assertTrue(fieldViewer.properties.map { it.key }.containsAll(listOf("rotation", "show_tracer")))
        assertEquals(null, DashboardWidgetRegistry.find("removed_widget"))

        val singleSignal = requireNotNull(DashboardWidgetRegistry.find("single_signal"))
        assertEquals("Loop time", singleSignal.defaultProperties()["label"])
        assertEquals("value", singleSignal.defaultProperties()["displayMode"])
        assertEquals("25", singleSignal.defaultProperties()["warningHigh"])
    }

    @Test
    fun `typed IDs reject unstable persisted names`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardWidgetType.of("Field Viewer")
        }
    }

    @Test
    fun `registry contract validates completely`() {
        assertEquals(emptyList(), DashboardWidgetRegistry.validationErrors())
        assertEquals(emptyList(), BuiltInDashboardLayoutProfiles.validationErrors(DashboardWidgetRegistry))
    }

    @Test
    fun `declared capabilities gate each dashboard service group`() {
        val fieldViewer = requireNotNull(DashboardWidgetRegistry.find("field_viewer"))
        assertTrue(fieldViewer.capabilities.allows(DashboardWidgetServiceGroup.LIVE))
        assertTrue(fieldViewer.capabilities.allows(DashboardWidgetServiceGroup.ANALYSIS))
        assertTrue(fieldViewer.capabilities.allows(DashboardWidgetServiceGroup.REPLAY))

        val tuning = requireNotNull(DashboardWidgetRegistry.find("tuning_card"))
        assertTrue(tuning.capabilities.allows(DashboardWidgetServiceGroup.LIVE))
        assertTrue(!tuning.capabilities.allows(DashboardWidgetServiceGroup.ANALYSIS))
        assertTrue(!tuning.capabilities.allows(DashboardWidgetServiceGroup.REPLAY))

        val console = requireNotNull(DashboardWidgetRegistry.find("console_viewer"))
        assertTrue(console.capabilities.allows(DashboardWidgetServiceGroup.LIVE))
        assertTrue(console.capabilities.allows(DashboardWidgetServiceGroup.ANALYSIS))
        assertTrue(console.capabilities.allows(DashboardWidgetServiceGroup.REPLAY))
    }

    @Test
    fun `catalog validation rejects invalid typed property defaults`() {
        val invalid = object : DashboardWidgetCatalog {
            override val specs = listOf(
                object : DashboardWidgetSpec by DashboardWidgetRegistry.definitions.first() {
                    override val type = DashboardWidgetType.of("invalid_defaults")
                    override val properties = listOf(
                        DashboardWidgetPropertySpec(
                            key = "mode",
                            label = "Mode",
                            kind = DashboardWidgetPropertyKind.CHOICE,
                            defaultValue = "missing",
                            choices = listOf("value", "bar"),
                        ),
                        DashboardWidgetPropertySpec(
                            key = "enabled",
                            label = "Enabled",
                            kind = DashboardWidgetPropertyKind.BOOLEAN,
                            defaultValue = "sometimes",
                        ),
                    )
                },
            )

            override fun find(type: DashboardWidgetType): DashboardWidgetSpec? =
                specs.singleOrNull { it.type == type }
        }

        val errors = invalid.validationErrors()
        assertTrue(errors.any { "mode" in it && "invalid choice default" in it })
        assertTrue(errors.any { "enabled" in it && "invalid boolean default" in it })
    }
}
