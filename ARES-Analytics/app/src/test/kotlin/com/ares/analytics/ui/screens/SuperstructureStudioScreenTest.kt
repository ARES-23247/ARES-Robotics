package com.ares.analytics.ui.screens

import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperstructureStudioScreenTest {
    @Test
    fun `clean retained editor refreshes catalogs when it re-enters composition`() {
        assertTrue(
            shouldRefreshSuperstructureCatalogsOnEntry(
                SuperstructureStudioState(projectPath = "project", loading = false, dirty = false),
            ),
        )
    }

    @Test
    fun `entry refresh never replaces an unfinished draft`() {
        assertFalse(
            shouldRefreshSuperstructureCatalogsOnEntry(
                SuperstructureStudioState(projectPath = "project", loading = false, dirty = true),
            ),
        )
    }

    @Test
    fun `initial load is not duplicated`() {
        assertFalse(
            shouldRefreshSuperstructureCatalogsOnEntry(
                SuperstructureStudioState(projectPath = "project", loading = true, dirty = false),
            ),
        )
    }
}
