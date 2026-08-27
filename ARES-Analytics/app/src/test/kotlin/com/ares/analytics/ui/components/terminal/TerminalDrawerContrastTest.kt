package com.ares.analytics.ui.components.terminal

import androidx.compose.ui.graphics.Color
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalDrawerContrastTest {
    @Test
    fun `plain and reset terminal text always receive the semantic foreground`() {
        val rendered = parseAnsi("plain\u001B[30m dark\u001B[0m reset")

        assertEquals("plain dark reset", rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
        assertEquals(AresTextPrimary, rendered.spanStyles.first().item.color)
        assertEquals(AresTextPrimary, rendered.spanStyles.last().item.color)
    }

    @Test
    fun `ansi black remains readable on the terminal background in every palette`() {
        val originalColorblind = AresThemeSettings.colorblindMode
        val originalHighContrast = AresThemeSettings.highContrastMode
        try {
            for (colorblind in listOf(false, true)) {
                for (highContrast in listOf(false, true)) {
                    AresThemeSettings.colorblindMode = colorblind
                    AresThemeSettings.highContrastMode = highContrast
                    for (bright in listOf(false, true)) {
                        val ratio = contrastRatio(getAnsiColor(0, bright), AresBackground)
                        assertTrue(
                            ratio >= 4.5,
                            "ANSI black was ${"%.2f".format(ratio)}:1 for " +
                                "colorblind=$colorblind highContrast=$highContrast bright=$bright",
                        )
                    }
                }
            }
        } finally {
            AresThemeSettings.colorblindMode = originalColorblind
            AresThemeSettings.highContrastMode = originalHighContrast
        }
    }
}

private fun contrastRatio(first: Color, second: Color): Double {
    val lighter = maxOf(first.relativeLuminance(), second.relativeLuminance())
    val darker = minOf(first.relativeLuminance(), second.relativeLuminance())
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double {
    fun channel(value: Float): Double {
        val normalized = value.toDouble()
        return if (normalized <= 0.04045) normalized / 12.92
        else Math.pow((normalized + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}
