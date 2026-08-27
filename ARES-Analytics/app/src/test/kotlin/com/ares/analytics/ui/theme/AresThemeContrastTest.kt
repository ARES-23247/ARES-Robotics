package com.ares.analytics.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class AresThemeContrastTest {
    @Test
    fun `semantic text pairs meet WCAG normal-text contrast in every palette`() {
        for (colorblind in listOf(false, true)) {
            for (highContrast in listOf(false, true)) {
                val colors = getAresColors(colorblind, highContrast)
                val pairs = linkedMapOf(
                    "primary text on background" to (colors.textPrimary to colors.background),
                    "primary text on surface" to (colors.textPrimary to colors.surface),
                    "secondary text on background" to (colors.textSecondary to colors.background),
                    "tertiary text on background" to (colors.textTertiary to colors.background),
                    "on-accent on cyan" to (colors.onAccent to colors.cyan),
                    "on-accent on cyan dark" to (colors.onAccent to colors.cyanDark),
                    "on-accent on red" to (colors.onAccent to colors.red),
                    "on-accent on green" to (colors.onAccent to colors.green),
                    "on-accent on gold" to (colors.onAccent to colors.gold),
                    "on-accent on amber" to (colors.onAccent to colors.amber),
                    "on-accent on error" to (colors.onAccent to colors.error),
                    "adaptive text on dark red" to (
                        readableForeground(colors.redDark, colors.onAccent, colors.textPrimary) to colors.redDark
                    ),
                )
                pairs.forEach { (name, pair) ->
                    val ratio = contrastRatio(pair.first, pair.second)
                    assertTrue(
                        "$name was ${"%.2f".format(ratio)}:1 for colorblind=$colorblind, highContrast=$highContrast",
                        ratio >= 4.5,
                    )
                }
            }
        }
    }

    @Test
    fun `white is intentionally rejected on bright cyan`() {
        val colors = getAresColors(colorblind = false, highContrast = false)
        assertTrue(contrastRatio(Color.White, colors.cyan) < 4.5)
        assertTrue(contrastRatio(colors.onAccent, colors.cyan) >= 4.5)
    }
}
