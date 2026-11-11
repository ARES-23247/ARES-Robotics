package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.dashboard.BuiltInDashboardLayoutProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardProfileMetadataTest {
    @Test
    fun `every selectable built-in dashboard profile provides novice summary and widget count`() {
        val availableNames = BuiltInDashboardLayoutProfiles.availableNames()
        assertTrue(availableNames.isNotEmpty())

        availableNames.forEach { name ->
            assertTrue(isBuiltInDashboardProfile(name), "Expected $name to be recognized as built-in")
            val metadata = assertNotNull(dashboardProfileMetadata(name), "Missing metadata for $name")
            assertTrue(metadata.summary.isNotBlank(), "Expected non-blank summary for $name")
            assertTrue(metadata.roleTag.isNotBlank(), "Expected non-blank roleTag for $name")
            assertTrue(metadata.widgetCount > 0, "Expected positive widgetCount for $name")
        }

        assertFalse(isBuiltInDashboardProfile("My Custom Layout"))
    }

    @Test
    fun `orderDashboardProfilesForPicker promotes beginner-recommended profiles to the top`() {
        val input = listOf("Programmer", "Student", "Pit Crew", "Standard", "My Custom Layout")
        val ordered = orderDashboardProfilesForPicker(input)

        assertEquals(listOf("Student", "Standard", "Programmer", "Pit Crew", "My Custom Layout"), ordered)
        assertTrue(dashboardProfileMetadata("Student")?.beginnerRecommended == true)
        assertTrue(dashboardProfileMetadata("Standard")?.beginnerRecommended == true)
    }
}
