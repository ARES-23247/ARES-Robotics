package com.ares.analytics.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AresThemeTest {
    @Test
    fun `large text preserves and multiplies the system font scale`() {
        assertEquals(1.25f, effectiveAresFontScale(1.25f, false))
        assertEquals(1.475f, effectiveAresFontScale(1.25f, true), 0.0001f)
    }

    @Test
    fun `accent foreground meets WCAG AA in every supported palette`() {
        for (colorblind in listOf(false, true)) {
            for (highContrast in listOf(false, true)) {
                val palette = getAresColors(colorblind, highContrast)
                val fills = listOf(
                    "cyan" to palette.cyan,
                    "cyan dark" to palette.cyanDark,
                    "green or colorblind blue" to palette.green,
                    "gold" to palette.gold,
                    "amber" to palette.amber,
                    "red or colorblind orange" to palette.red,
                    "error" to palette.error,
                )
                fills.forEach { (name, fill) ->
                    assertTrue(
                        contrastRatio(palette.onAccent, fill) >= 4.5,
                        "$name must have at least 4.5:1 contrast in colorblind=$colorblind highContrast=$highContrast"
                    )
                }
            }
        }
    }

    @Test
    fun `white is intentionally rejected on the bright primary accent`() {
        val palette = getAresColors(colorblind = false, highContrast = false)
        assertTrue(contrastRatio(Color.White, palette.cyan) < 4.5)
        assertTrue(contrastRatio(palette.onAccent, palette.cyan) >= 4.5)
    }

    @Test
    fun `typography inherits semantic foreground from its component`() {
        val styles = listOf(
            AresTypography.displayLarge,
            AresTypography.displayMedium,
            AresTypography.displaySmall,
            AresTypography.headlineLarge,
            AresTypography.headlineMedium,
            AresTypography.headlineSmall,
            AresTypography.titleLarge,
            AresTypography.titleMedium,
            AresTypography.titleSmall,
            AresTypography.bodyLarge,
            AresTypography.bodyMedium,
            AresTypography.bodySmall,
            AresTypography.labelLarge,
            AresTypography.labelMedium,
            AresTypography.labelSmall,
        )

        styles.forEach { style ->
            assertEquals(
                Color.Unspecified,
                style.color,
                "Typography must not override a button, dialog, card, or disabled-state content color",
            )
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
