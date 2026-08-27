package com.ares.analytics.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcademyLayoutTest {
    @Test
    fun `academy uses one pane on constrained desktop viewports`() {
        assertTrue(academyUsesSinglePane(widthDp = 1100f, heightDp = 768f, largeTextMode = false))
        assertTrue(academyUsesSinglePane(widthDp = 1366f, heightDp = 680f, largeTextMode = false))
    }

    @Test
    fun `large text uses one pane even on a wide display`() {
        assertTrue(academyUsesSinglePane(widthDp = 1600f, heightDp = 900f, largeTextMode = true))
    }

    @Test
    fun `wide standard text view keeps the two pane teaching layout`() {
        assertFalse(academyUsesSinglePane(widthDp = 1366f, heightDp = 768f, largeTextMode = false))
    }
}
