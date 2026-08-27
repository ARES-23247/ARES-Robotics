package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.WidgetConfig

/** Pure layout policy shared by the Compose grid and deterministic tests. */
object DashboardLayoutEngine {
    const val COMPACT_COLUMNS = 6
    const val MEDIUM_COLUMNS = 9
    const val EXPANDED_COLUMNS = 12

    fun columnsForWidth(widthDp: Float): Int = when {
        widthDp < 900f -> COMPACT_COLUMNS
        widthDp < 1280f -> MEDIUM_COLUMNS
        else -> EXPANDED_COLUMNS
    }

    fun reflow(widgets: List<WidgetConfig>, columns: Int): List<WidgetConfig> {
        require(columns > 0)
        val placed = mutableListOf<WidgetConfig>()
        widgets.sortedWith(compareBy<WidgetConfig> { it.row }.thenBy { it.col }).forEach { source ->
            val span = source.colSpan.coerceIn(1, columns)
            val preferredCol = source.col.coerceIn(0, columns - span)
            var candidate = source.copy(col = preferredCol, colSpan = span, row = source.row.coerceAtLeast(0))
            while (overlapsAny(candidate, placed)) {
                val openColumn = (0..columns - span).firstOrNull { col ->
                    !overlapsAny(candidate.copy(col = col), placed)
                }
                candidate = if (openColumn != null) candidate.copy(col = openColumn) else candidate.copy(row = candidate.row + 1, col = 0)
            }
            placed += candidate
        }
        return widgets.map { original -> placed.first { it.id == original.id } }
    }

    fun resolveMove(widgets: List<WidgetConfig>, activeWidgetId: String, columns: Int): List<WidgetConfig> {
        val active = widgets.firstOrNull { it.id == activeWidgetId } ?: return widgets
        val normalized = active.copy(
            colSpan = active.colSpan.coerceIn(1, columns),
            col = active.col.coerceIn(0, (columns - active.colSpan.coerceAtMost(columns)).coerceAtLeast(0)),
            row = active.row.coerceAtLeast(0)
        )
        val locked = widgets.filter { it.id != activeWidgetId && it.isLocked }
        var resolvedActive = normalized
        while (overlapsAny(resolvedActive, locked)) resolvedActive = resolvedActive.copy(row = resolvedActive.row + 1)
        val others = widgets.filter { it.id != activeWidgetId && !it.isLocked }
        return reflow(locked + resolvedActive + others, columns)
    }

    private fun overlapsAny(widget: WidgetConfig, placed: List<WidgetConfig>): Boolean = placed.any { other ->
        widget.col < other.col + other.colSpan && widget.col + widget.colSpan > other.col &&
            widget.row < other.row + other.rowSpan && widget.row + widget.rowSpan > other.row
    }
}
