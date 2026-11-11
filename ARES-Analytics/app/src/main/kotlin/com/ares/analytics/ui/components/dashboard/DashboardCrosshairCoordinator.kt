package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable pointer-hover timestamp broadcast across synchronized dashboard charts and field maps. */
data class CrosshairHoverState(
    val timestampMs: Long,
    val sourceWidgetId: String,
)

/**
 * Lightweight, zero-grid-recomposition coordinator for time-synchronized crosshair inspection.
 *
 * Scoped inside [DashboardWidgetHost] rather than [com.ares.analytics.viewmodel.DashboardState]
 * so 60 Hz pointer movements only recompose leaf chart/field overlays that explicitly subscribe.
 */
@Stable
class DashboardCrosshairCoordinator {
    private val _hover = MutableStateFlow<CrosshairHoverState?>(null)
    val hover: StateFlow<CrosshairHoverState?> = _hover.asStateFlow()

    fun updateHover(widgetId: String, timestampMs: Long) {
        if (widgetId.isBlank() || timestampMs < 0L) return
        val current = _hover.value
        if (current?.sourceWidgetId == widgetId && current.timestampMs == timestampMs) return
        _hover.value = CrosshairHoverState(timestampMs = timestampMs, sourceWidgetId = widgetId)
    }

    fun clearHover(widgetId: String? = null) {
        val current = _hover.value ?: return
        if (widgetId == null || current.sourceWidgetId == widgetId) {
            _hover.value = null
        }
    }
}
