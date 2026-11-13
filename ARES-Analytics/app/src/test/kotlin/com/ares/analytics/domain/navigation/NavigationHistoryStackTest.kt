package com.ares.analytics.domain.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationHistoryStackTest {

    @Test
    fun `tracks forward and backward history and prunes forward branch on new navigation`() {
        var stack = NavigationHistoryStack()
        assertFalse(stack.canGoBack)
        assertFalse(stack.canGoForward)
        assertEquals(NavigationTarget.DASHBOARD, stack.current.target)

        stack = stack.navigateTo(NavigationTarget.ROBOT_STUDIO)
        stack = stack.navigateTo(NavigationTarget.SUBSYSTEM_GEN, "Intake")
        stack = stack.navigateTo(NavigationTarget.PIT_DIAGNOSTICS, "Battery Fleet")

        assertTrue(stack.canGoBack)
        assertFalse(stack.canGoForward)
        assertEquals(NavigationTarget.PIT_DIAGNOSTICS, stack.current.target)
        assertEquals("Battery Fleet", stack.current.subLabel)
        assertEquals(
            listOf(NavigationTarget.SUBSYSTEM_GEN, NavigationTarget.ROBOT_STUDIO, NavigationTarget.DASHBOARD),
            stack.recentTargets,
        )

        stack = stack.goBack()
        assertEquals(NavigationTarget.SUBSYSTEM_GEN, stack.current.target)
        assertEquals("Intake", stack.current.subLabel)
        assertTrue(stack.canGoBack)
        assertTrue(stack.canGoForward)

        stack = stack.goForward()
        assertEquals(NavigationTarget.PIT_DIAGNOSTICS, stack.current.target)

        // Back twice, then navigate to Match Strategy -> forward branch is truncated.
        stack = stack.goBack().goBack()
        assertEquals(NavigationTarget.ROBOT_STUDIO, stack.current.target)
        stack = stack.navigateTo(NavigationTarget.MATCH_STRATEGY, "Match #12")
        assertFalse(stack.canGoForward)
        assertEquals(NavigationTarget.MATCH_STRATEGY, stack.current.target)
        assertEquals("Match #12", stack.current.subLabel)
    }

    @Test
    fun `updating subLabel mutates current entry without pushing duplicate history frames`() {
        var stack = NavigationHistoryStack()
            .navigateTo(NavigationTarget.PIT_DIAGNOSTICS, "Readiness & Sign-Off")
        assertEquals(2, stack.entries.size)

        stack = stack.updateCurrentSubLabel("Match Queue")
        assertEquals(2, stack.entries.size)
        assertEquals("Match Queue", stack.current.subLabel)

        stack = stack.navigateTo(NavigationTarget.PIT_DIAGNOSTICS, "Alert Thresholds")
        assertEquals(2, stack.entries.size)
        assertEquals("Alert Thresholds", stack.current.subLabel)

        val reset = stack.reset(NavigationTarget.DASHBOARD)
        assertFalse(reset.canGoBack)
        assertNull(reset.current.subLabel)
    }

    @Test
    fun `respects maxEntries bound cleanly`() {
        var stack = NavigationHistoryStack(maxEntries = 4)
        stack = stack.navigateTo(NavigationTarget.ROBOT_STUDIO)
        stack = stack.navigateTo(NavigationTarget.SUBSYSTEM_GEN)
        stack = stack.navigateTo(NavigationTarget.PATH_PLANNER)
        stack = stack.navigateTo(NavigationTarget.RUN_HISTORY)
        stack = stack.navigateTo(NavigationTarget.MATCH_STRATEGY)

        assertEquals(4, stack.entries.size)
        assertEquals(NavigationTarget.MATCH_STRATEGY, stack.current.target)
    }

    @Test
    fun `buildContextualBreadcrumbs builds clickable Robot Studio child and Pit sub-tab hierarchy`() {
        val subsystemCrumbs = buildContextualBreadcrumbs(
            sectionLabel = "Robot",
            parentTarget = NavigationTarget.ROBOT_STUDIO,
            activeTarget = NavigationTarget.SUBSYSTEM_GEN,
            subContextLabel = "ArmPivot",
        )
        assertEquals(
            listOf(
                BreadcrumbSegment("Robot Studio", NavigationTarget.ROBOT_STUDIO, isCurrent = false),
                BreadcrumbSegment(NavigationTarget.SUBSYSTEM_GEN.label, NavigationTarget.SUBSYSTEM_GEN, isCurrent = false),
                BreadcrumbSegment("ArmPivot", null, isCurrent = true),
            ),
            subsystemCrumbs,
        )

        val pitCrumbs = buildContextualBreadcrumbs(
            sectionLabel = "Robot",
            parentTarget = NavigationTarget.ROBOT_STUDIO,
            activeTarget = NavigationTarget.PIT_DIAGNOSTICS,
            subContextLabel = PitOperationsSubTab.BATTERY_FLEET.label,
        )
        assertEquals(
            listOf(
                BreadcrumbSegment("Robot", NavigationTarget.ROBOT_STUDIO, isCurrent = false),
                BreadcrumbSegment("Pit Operations", NavigationTarget.PIT_DIAGNOSTICS, isCurrent = false),
                BreadcrumbSegment("Battery Fleet", null, isCurrent = true),
            ),
            pitCrumbs,
        )
    }
}
