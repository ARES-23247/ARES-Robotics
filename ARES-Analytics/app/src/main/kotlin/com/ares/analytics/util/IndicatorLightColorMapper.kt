package com.ares.analytics.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Maps a GoBilda RGB Indicator Light servo position (0.0 to 1.0)
 * to an approximate display color for the dashboard robot visualization.
 * Uses piecewise linear interpolation between known color stops.
 *
 * The color stops are approximate values for the GoBilda Product Insight #4 chart.
 * Teams should tune these values on real hardware for exact color matching.
 */
object IndicatorLightColorMapper {

    private data class ColorStop(val position: Double, val color: Color)

    private val stops = listOf(
        ColorStop(0.000, Color(0xFF202020)),  // OFF (dark)
        ColorStop(0.2525, Color(0xFFFF007F)), // RAINBOW (magenta/rainbow accent)
        ColorStop(0.279, Color(0xFFFF0000)),  // RED
        ColorStop(0.333, Color(0xFFFF8000)),  // ORANGE
        ColorStop(0.388, Color(0xFFFFFF00)),  // YELLOW
        ColorStop(0.472, Color(0xFF00FF00)),  // GREEN
        ColorStop(0.511, Color(0xFF00FFFF)),  // CYAN
        ColorStop(0.611, Color(0xFF0000FF)),  // BLUE
        ColorStop(0.722, Color(0xFF8000FF)),  // PURPLE
        ColorStop(0.833, Color(0xFFFFFFFF)),  // WHITE
    )

    /**
     * Returns the approximate display color for a given servo position.
     * Interpolates linearly between adjacent color stops.
     *
     * @param position Servo position from 0.0 to 1.0
     * @return The interpolated [Color] for display
     */
    fun positionToColor(position: Double): Color {
        if (position < 0.0) return Color(0xFF202020)
        val pos = position.coerceIn(0.0, 1.0)
        if (pos in 0.240..0.265) return Color(0xFFFF007F)
        for (i in 0 until stops.size - 1) {
            val lo = stops[i]
            val hi = stops[i + 1]
            if (pos <= hi.position) {
                val range = hi.position - lo.position
                val t = if (range > 0.001) ((pos - lo.position) / range).toFloat() else 0f
                return lerp(lo.color, hi.color, t.coerceIn(0f, 1f))
            }
        }
        return stops.last().color
    }

    /**
     * Returns the closest named color for a given position, for display labels.
     *
     * @param position Servo position from 0.0 to 1.0
     * @return Human-readable color name
     */
    fun positionToName(position: Double): String {
        if (position < 0.0) return "Off"
        val pos = position.coerceIn(0.0, 1.0)
        return when {
            pos < 0.140 -> "Off"
            pos in 0.240..0.265 -> "Rainbow"
            pos < 0.306 -> "Red"
            pos < 0.361 -> "Orange"
            pos < 0.430 -> "Yellow"
            pos < 0.492 -> "Green"
            pos < 0.561 -> "Cyan"
            pos < 0.667 -> "Blue"
            pos < 0.778 -> "Purple"
            else -> "White"
        }
    }
}
