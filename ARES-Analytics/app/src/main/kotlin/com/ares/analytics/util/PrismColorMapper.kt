package com.ares.analytics.util

import androidx.compose.ui.graphics.Color

/** Representative, non-animated colors for goBILDA Prism PWM programs in field and replay views. */
object PrismColorMapper {
    fun pulseWidthToColor(pulseWidthUs: Double): Color = when {
        pulseWidthUs < 500.0 || pulseWidthUs > 2500.0 -> Color.Transparent
        pulseWidthUs in 1049.0..1090.0 -> Color.Transparent // SOLID_OFF region
        pulseWidthUs < 700.0 -> Color(0xFFFF3B7A) // artboards, timer, emergency and sparkle
        pulseWidthUs < 750.0 -> Color.Red
        pulseWidthUs < 805.0 -> Color.Yellow
        pulseWidthUs < 865.0 -> Color.Green
        pulseWidthUs < 920.0 -> Color.Blue
        pulseWidthUs < 950.0 -> Color(0xFF9C5CFF)
        pulseWidthUs < 1050.0 -> Color(0xFF00D7FF) // rainbow families
        pulseWidthUs < 1160.0 -> Color.Red
        pulseWidthUs < 1285.0 -> Color(0xFFFF8A00)
        pulseWidthUs < 1415.0 -> Color.Yellow
        pulseWidthUs < 1540.0 -> Color.Green
        pulseWidthUs < 1660.0 -> Color.Cyan
        pulseWidthUs < 1785.0 -> Color.Blue
        pulseWidthUs < 1885.0 -> Color(0xFF9C5CFF)
        pulseWidthUs < 1950.0 -> Color.White
        pulseWidthUs < 2010.0 -> Color.Red
        pulseWidthUs < 2070.0 -> Color.Yellow
        pulseWidthUs < 2130.0 -> Color.Green
        pulseWidthUs < 2200.0 -> Color.Blue
        else -> Color(0xFF00D7FF) // snake programs use multiple colors
    }
}
