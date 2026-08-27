package com.ares.analytics.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

private fun aresColorScheme(colors: AresColorPalette) = darkColorScheme(
    primary = colors.cyan,
    onPrimary = colors.onAccent,
    primaryContainer = colors.cyanDark,
    onPrimaryContainer = colors.onAccent,

    // Interactive selection is cyan; red remains reserved for explicit warning/error surfaces.
    secondary = colors.cyan,
    onSecondary = colors.onAccent,
    secondaryContainer = colors.cyanDark,
    onSecondaryContainer = colors.onAccent,

    tertiary = colors.gold,
    onTertiary = colors.onAccent,

    background = colors.background,
    onBackground = colors.textPrimary,

    surface = colors.surface,
    onSurface = colors.textPrimary,
    surfaceVariant = colors.surfaceElevated,
    onSurfaceVariant = colors.textSecondary,

    error = colors.error,
    onError = colors.onAccent,

    outline = colors.border,
    outlineVariant = colors.borderFocused
)

private val AresShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun AresTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = aresColorScheme(AresThemeSettings.currentColors)
    val parentDensity = LocalDensity.current
    val effectiveDensity = remember(parentDensity.density, parentDensity.fontScale, AresThemeSettings.largeTextMode) {
        Density(
            density = parentDensity.density,
            fontScale = effectiveAresFontScale(parentDensity.fontScale, AresThemeSettings.largeTextMode)
        )
    }
    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AresTypography,
            shapes = AresShapes,
            content = content
        )
    }
}

internal fun effectiveAresFontScale(systemScale: Float, largeTextMode: Boolean): Float =
    systemScale * if (largeTextMode) 1.18f else 1.0f
