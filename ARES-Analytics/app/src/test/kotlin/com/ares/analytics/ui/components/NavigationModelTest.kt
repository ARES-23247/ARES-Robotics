package com.ares.analytics.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationModelTest {
    @Test
    fun `primary navigation has six task sections`() {
        assertEquals(
            listOf("Dashboard", "Robot", "Autonomous", "Analysis", "Data", "Settings"),
            primaryNavigationSections.map { it.label }
        )
    }

    @Test
    fun `daily routes map to one contextual section`() {
        val grouped = primaryNavigationSections.flatMap { section -> section.targets().map { it to section } }.toMap()
        assertEquals(NavigationSection.ROBOT, grouped[NavigationTarget.CONTROLS])
        assertEquals(NavigationSection.ROBOT, grouped[NavigationTarget.TUNING])
        assertEquals(NavigationSection.ROBOT, grouped[NavigationTarget.HARDWARE_STUDIO])
        assertEquals(NavigationSection.ROBOT, grouped[NavigationTarget.ROBOT_STUDIO])
        assertEquals(NavigationSection.ROBOT, grouped[NavigationTarget.SUPERSTRUCTURE_STUDIO])
        assertEquals(NavigationSection.ROBOT, grouped[NavigationTarget.PIT_DIAGNOSTICS])
        assertEquals(NavigationTarget.ROBOT_STUDIO, NavigationSection.ROBOT.defaultTarget())
        assertEquals(NavigationSection.AUTONOMOUS, grouped[NavigationTarget.FIELD_EDITOR])
        assertEquals(NavigationSection.ANALYSIS, grouped[NavigationTarget.GUIDED_RUN_ANALYSIS])
        assertEquals(NavigationTarget.GUIDED_RUN_ANALYSIS, NavigationSection.ANALYSIS.defaultTarget())
        assertFalse(grouped.containsKey(NavigationTarget.MATCH_STRATEGY))
        assertEquals(NavigationSection.DATA, grouped[NavigationTarget.CLOUD])
        assertEquals(NavigationSection.SETTINGS, grouped[NavigationTarget.ADMIN])
        assertEquals(grouped.size, grouped.keys.distinct().size)
    }

    @Test
    fun `developer utilities are progressively disclosed`() {
        val standard = availablePaletteTargets(developerMode = false)
        val developer = availablePaletteTargets(developerMode = true)
        assertTrue(developer.containsAll(developerToolTargets))
        assertFalse(standard.any { it in developerToolTargets })
        assertTrue(standard.contains(NavigationTarget.ACADEMY))
    }

    @Test
    fun `command search matches destination and group`() {
        assertEquals(listOf(NavigationTarget.TUNING), filterNavigationTargets("tuning", false))
        assertTrue(filterNavigationTargets("analysis", false).contains(NavigationTarget.RUN_HISTORY))
        assertTrue(filterNavigationTargets("possible cause", false).contains(NavigationTarget.GUIDED_RUN_ANALYSIS))
        assertTrue(filterNavigationTargets("database", false).isEmpty())
        assertEquals(listOf(NavigationTarget.DATABASE_VIEWER), filterNavigationTargets("database", true))
        assertEquals(listOf(NavigationTarget.ACADEMY), filterNavigationTargets("start here", false))
        assertEquals(listOf(NavigationTarget.ACADEMY), filterNavigationTargets("Robot Academy", false))
        assertEquals(listOf(NavigationTarget.ACADEMY), filterNavigationTargets("classroom", false))
        assertTrue(filterNavigationTargets("disconnected", false).contains(NavigationTarget.DASHBOARD))
        assertTrue(filterNavigationTargets("gamepad", false).contains(NavigationTarget.CONTROLS))
        assertEquals(listOf(NavigationTarget.DRIVEBASE_BUILDER), filterNavigationTargets("mecanum", false))
        assertEquals(listOf(NavigationTarget.ROBOT_STUDIO), filterNavigationTargets("build robot", false))
        assertEquals(listOf(NavigationTarget.PROJECT_IDENTITY), filterNavigationTargets("robot dimensions", false))
    }

    @Test
    fun `command search matches subsystem builder and field editor`() {
        assertTrue(filterNavigationTargets("subsystem", false).contains(NavigationTarget.SUBSYSTEM_GEN))
        assertTrue(filterNavigationTargets("field", false).contains(NavigationTarget.FIELD_EDITOR))
    }

    @Test
    fun `dense section navigation becomes an explicit menu before tabs are clipped`() {
        assertEquals(SectionNavigationPresentation.MENU, sectionNavigationPresentation(900f, 9))
        assertEquals(SectionNavigationPresentation.TABS, sectionNavigationPresentation(1_300f, 9))
        assertEquals(SectionNavigationPresentation.TABS, sectionNavigationPresentation(420f, 2))
        assertEquals(SectionNavigationPresentation.TABS, sectionNavigationPresentation(120f, 1))
    }

    @Test
    fun `global quick menu keeps the former dashboard shortcuts available everywhere`() {
        assertEquals(
            listOf(
                NavigationTarget.ROBOT_STUDIO,
                NavigationTarget.ACADEMY,
                NavigationTarget.PATH_PLANNER,
                NavigationTarget.RUN_HISTORY,
                NavigationTarget.IMPORT_CENTER,
                NavigationTarget.TUNING,
            ),
            shellQuickDestinations,
        )
        assertEquals(shellQuickDestinations.size, shellQuickDestinations.distinct().size)
    }
}
