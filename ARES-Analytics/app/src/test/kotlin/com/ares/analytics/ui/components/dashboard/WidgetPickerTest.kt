package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetPickerTest {
    @Test
    fun `recommended category only returns curated widgets`() {
        val widgets = filterWidgets("", WidgetCategory.RECOMMENDED)
        assertTrue(widgets.isNotEmpty())
        assertTrue(widgets.all { it.recommended })
        assertTrue(widgets.any { it.type.serializedName == "advanced_analytics" })
        assertTrue(widgets.any { it.type.serializedName == "system_health" })
    }

    @Test
    fun `search matches descriptions and respects category`() {
        val result = filterWidgets("covariance", WidgetCategory.DIAGNOSTICS)
        assertEquals(listOf("ekf_telemetry"), result.map { it.type.serializedName })
        assertTrue(filterWidgets("covariance", WidgetCategory.LIVE).isEmpty())
    }

    @Test
    fun `evidence based review tools are discoverable by plain language`() {
        assertTrue(filterWidgets("driver score", WidgetCategory.ANALYSIS).any { it.type.serializedName == "driver_motion_review" })
        assertTrue(filterWidgets("possible causes", WidgetCategory.DIAGNOSTICS).any { it.type.serializedName == "pit_evidence_checklist" })
    }
}
