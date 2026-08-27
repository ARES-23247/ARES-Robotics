package com.ares.analytics.ui

import com.ares.analytics.ui.screens.shouldShowDashboardOfflineGuide
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardOfflineGuideTest {
    @Test
    fun `guide appears only when no live or replay data source exists`() {
        assertTrue(shouldShowDashboardOfflineGuide(isConnected = false, primarySessionId = null, dismissed = false))
        assertFalse(shouldShowDashboardOfflineGuide(isConnected = true, primarySessionId = null, dismissed = false))
        assertFalse(shouldShowDashboardOfflineGuide(isConnected = false, primarySessionId = "run-42", dismissed = false))
        assertFalse(shouldShowDashboardOfflineGuide(isConnected = false, primarySessionId = null, dismissed = true))
    }
}
