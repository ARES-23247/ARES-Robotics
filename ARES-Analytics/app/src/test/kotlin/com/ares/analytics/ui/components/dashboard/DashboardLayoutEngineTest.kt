package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.WidgetConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardLayoutEngineTest {
    @Test
    fun `column policy adapts to viewport width`() {
        assertEquals(6, DashboardLayoutEngine.columnsForWidth(640f))
        assertEquals(9, DashboardLayoutEngine.columnsForWidth(1000f))
        assertEquals(12, DashboardLayoutEngine.columnsForWidth(1440f))
    }

    @Test
    fun `only canonical twelve column projection can be persisted through editing`() {
        assertTrue(!isDashboardLayoutEditingSupported(DashboardLayoutEngine.COMPACT_COLUMNS))
        assertTrue(!isDashboardLayoutEditingSupported(DashboardLayoutEngine.MEDIUM_COLUMNS))
        assertTrue(isDashboardLayoutEditingSupported(DashboardLayoutEngine.EXPANDED_COLUMNS))
    }

    @Test
    fun `reflow clamps spans and removes collisions`() {
        val widgets = listOf(
            WidgetConfig("a", "field_viewer", 0, 0, 3, 8),
            WidgetConfig("b", "alerts", 0, 4, 3, 5),
            WidgetConfig("c", "system_health", 0, 10, 2, 4)
        )

        val reflowed = DashboardLayoutEngine.reflow(widgets, 6)

        assertTrue(reflowed.all { it.col >= 0 && it.col + it.colSpan <= 6 })
        reflowed.forEachIndexed { index, widget ->
            reflowed.drop(index + 1).forEach { other -> assertTrue(!overlaps(widget, other)) }
        }
    }

    private fun overlaps(left: WidgetConfig, right: WidgetConfig): Boolean =
        left.col < right.col + right.colSpan && left.col + left.colSpan > right.col &&
            left.row < right.row + right.rowSpan && left.row + left.rowSpan > right.row
}
