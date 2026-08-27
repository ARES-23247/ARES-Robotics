package com.ares.analytics.service

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutPreferenceServiceTest {

    @Test
    fun `unsafe blank and reserved profile names are rejected inside the service boundary`() = runTest {
        val container = Files.createTempDirectory("ares-layout-name-safety").toFile()
        val root = container.resolve("layouts").apply { mkdirs() }
        val service = LayoutPreferenceService(root.absolutePath)
        val layout = DashboardLayoutConfig(emptyList())
        try {
            listOf(
                "../auth",
                "..\\auth",
                "team/layout",
                "team\\layout",
                "",
                "   ",
                ".",
                "..",
                "CON",
                "nul",
                "COM1",
                "LPT9"
            ).forEach { unsafeName ->
                assertFailsWith<IllegalArgumentException>("expected rejection for '$unsafeName'") {
                    service.saveLayout(unsafeName, layout)
                }
                assertTrue(layoutProfileNameError(unsafeName) != null)
            }

            assertFalse(container.resolve("auth.json").exists())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            container.deleteRecursively()
        }
    }

    @Test
    fun `failed atomic replacement preserves prior layout and removes temporary file`() = runTest {
        val root = Files.createTempDirectory("ares-layout-atomic").toFile()
        val original = DashboardLayoutConfig(
            listOf(WidgetConfig("original", "telemetry_chart", 0, 0, 2, 2))
        )
        val replacement = DashboardLayoutConfig(
            listOf(WidgetConfig("replacement", "field_viewer", 1, 1, 3, 3))
        )
        val service = LayoutPreferenceService(root.absolutePath)
        try {
            service.saveLayout("Match Review Copy", original)
            val failingService = LayoutPreferenceService(root.absolutePath) { temporary, destination ->
                assertEquals(destination.parent, temporary.parent)
                throw IOException("injected atomic replace failure")
            }

            assertFailsWith<IOException> {
                failingService.saveLayout("Match Review Copy", replacement)
            }

            assertEquals(original, service.loadLayout("Match Review Copy"))
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testDefaultLayouts() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ares_layout_test_default")
        val service = LayoutPreferenceService(tempDir.absolutePath)
        val studentLayout = service.getDefaultLayout("student")
        assertTrue(studentLayout.widgets.any { it.type == "field_viewer" })
        assertTrue(studentLayout.widgets.any { it.type == "system_health" })
        assertTrue(studentLayout.widgets.any { it.type == "subsystem_health" })
        assertTrue(studentLayout.widgets.any { it.type == "autonomous_selector" })

        val driverLayout = service.getDefaultLayout("driver")
        assertTrue(driverLayout.widgets.any { it.type == "joystick_visualizer" })

        val builderLayout = service.getDefaultLayout("builder")
        assertTrue(builderLayout.widgets.any { it.type == "hardware_topology" })
        assertTrue(builderLayout.widgets.any { it.type == "power_distribution" })

        val autonomousLayout = service.getDefaultLayout("autonomous")
        assertTrue(autonomousLayout.widgets.any { it.type == "path_tuning" })
        assertTrue(autonomousLayout.widgets.any { it.type == "ekf_telemetry" })

        val analystLayout = service.getDefaultLayout("analyst")
        assertTrue(analystLayout.widgets.any { it.type == "runs_index" })
        assertTrue(analystLayout.widgets.any { it.type == "trends_card" })

        val mentorLayout = service.getDefaultLayout("mentor")
        assertTrue(mentorLayout.widgets.any { it.type == "pit_evidence_checklist" })
        assertTrue(mentorLayout.widgets.any { it.type == "control_profiler" })

        val programmerLayout = service.getDefaultLayout("programmer")
        assertTrue(programmerLayout.widgets.isNotEmpty())
        val chart = programmerLayout.widgets.first { it.type == "telemetry_chart" }
        assertEquals(0, chart.row)
        assertEquals(0, chart.col)
        assertEquals(6, chart.rowSpan)
        assertEquals(8, chart.colSpan)
        val driverCoachLayout = service.getDefaultLayout("driver_coach")
        assertTrue(driverCoachLayout.widgets.any { it.type == "autonomous_selector" })
        val alerts = driverCoachLayout.widgets.first { it.type == "alerts" }
        assertEquals(5, alerts.row)
        assertEquals(8, alerts.col)
        assertEquals(5, alerts.rowSpan)
        assertEquals(4, alerts.colSpan)
        val pitCrewLayout = service.getDefaultLayout("pit_crew")
        assertTrue(pitCrewLayout.widgets.any { it.type == "ai_coach" })
        assertTrue(pitCrewLayout.widgets.any { it.type == "advanced_analytics" })
        assertTrue(service.getDefaultLayout("match_review").widgets.any { it.type == "advanced_analytics" })
        assertTrue(service.getDefaultLayout("pit_diagnostics").widgets.any { it.type == "system_health" })
        assertTrue(service.getDefaultLayout("driver_practice").widgets.any { it.type == "field_viewer" })

        service.getAvailableLayouts().forEach { profile ->
            val widgets = service.getDefaultLayout(profile).widgets
            assertTrue(widgets.all { it.col >= 0 && it.colSpan > 0 && it.col + it.colSpan <= 12 }, "$profile must fit the 12-column grid")
            widgets.forEachIndexed { index, widget ->
                widgets.drop(index + 1).forEach { other ->
                    val overlaps = widget.col < other.col + other.colSpan && widget.col + widget.colSpan > other.col &&
                        widget.row < other.row + other.rowSpan && widget.row + widget.rowSpan > other.row
                    assertTrue(!overlaps, "$profile contains overlapping widgets ${widget.id} and ${other.id}")
                }
            }
        }
    }

    @Test
    fun testSaveAndLoadLayout() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ares_layout_test_save")
        tempDir.mkdirs()
        val service = LayoutPreferenceService(tempDir.absolutePath)
        val customWidgets = listOf(
            WidgetConfig("chart_1", "telemetry_chart", 0, 0, 2, 2)
        )
        val config = DashboardLayoutConfig(customWidgets)

        service.saveLayout("custom_profile", config)
        val loaded = service.loadLayout("custom_profile")
        assertEquals(1, loaded.widgets.size)
        assertEquals("chart_1", loaded.widgets.first().id)
        assertEquals("telemetry_chart", loaded.widgets.first().type)

        // Cleanup
        File(tempDir, "custom_profile.json").delete()
        tempDir.delete()
    }

    @Test
    /**
     * testGetAvailableLayouts fun.
     */
    fun testGetAvailableLayouts() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ares_layout_test_list")
        tempDir.mkdirs()
        val service = LayoutPreferenceService(tempDir.absolutePath)
        val config = DashboardLayoutConfig(emptyList())
        service.saveLayout("Custom Team Layout", config)
        val available = service.getAvailableLayouts()
        assertTrue(available.contains("Standard"))
        assertTrue(available.contains("Custom Team Layout"))

        // Cleanup
        File(tempDir, "custom_team_layout.json").delete()
        tempDir.delete()
    }

    @Test
    /**
     * testDeleteLayout fun.
     */
    fun testDeleteLayout() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ares_layout_test_delete")
        tempDir.mkdirs()
        val service = LayoutPreferenceService(tempDir.absolutePath)
        val config = DashboardLayoutConfig(emptyList())
        service.saveLayout("Temp Delete Profile", config)
        assertTrue(service.getSavedLayouts().contains("Temp Delete Profile"))
        val deleted = service.deleteLayout("Temp Delete Profile")
        assertTrue(deleted)
        assertTrue(!service.getSavedLayouts().contains("Temp Delete Profile"))

        tempDir.delete()
    }
}
