package com.ares.analytics.service

import com.ares.analytics.ui.components.dashboard.availableWidgetsList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardWidgetCatalogTest {
    @Test
    fun `picker exposes every canonical widget exactly once`() {
        assertEquals(
            DashboardWidgetCatalog.knownTypes,
            availableWidgetsList.map { it.type }.toSet(),
        )
        assertEquals(DashboardWidgetCatalog.knownTypes.size, availableWidgetsList.size)
    }

    @Test
    fun `catalog owns add-to-layout default sizes`() {
        val fieldViewer = DashboardWidgetCatalog.find("field_viewer")
        assertEquals(6, fieldViewer?.defaultRowSpan)
        assertEquals(6, fieldViewer?.defaultColSpan)
        assertEquals(null, DashboardWidgetCatalog.find("removed_widget"))
    }

    @Test
    fun `catalog validation identifies drift instead of silently hiding widgets`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardWidgetCatalog.requireComplete(
                actualTypes = DashboardWidgetCatalog.knownTypes - "field_viewer",
                owner = "test renderer",
            )
        }
    }
}
